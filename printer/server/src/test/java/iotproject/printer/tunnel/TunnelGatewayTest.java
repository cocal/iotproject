package iotproject.printer.tunnel;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static iotproject.printer.tunnel.TunnelFrame.*;
import static org.junit.jupiter.api.Assertions.*;

class TunnelGatewayTest {
    @TempDir static Path temp;
    static SSLContext serverTls,clientTls;
    static Path certificate;
    static Path keyStore;
    @BeforeAll static void tls() throws Exception {
        Path key=temp.resolve("test.p12");keyStore=key;certificate=temp.resolve("ca.pem");
        run("keytool","-genkeypair","-alias","test","-keyalg","RSA","-storetype","PKCS12","-keystore",key.toString(),
                "-storepass","test-password","-keypass","test-password","-dname","CN=localhost",
                "-ext","SAN=dns:localhost,ip:127.0.0.1","-validity","1","-noprompt");
        run("keytool","-exportcert","-rfc","-alias","test","-keystore",key.toString(),"-storepass","test-password","-file",certificate.toString());
        KeyStore store=KeyStore.getInstance("PKCS12");
        try(var input=Files.newInputStream(key)){store.load(input,"test-password".toCharArray());}
        var keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());keys.init(store,"test-password".toCharArray());
        var trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());trust.init(store);
        serverTls=SSLContext.getInstance("TLS");serverTls.init(keys.getKeyManagers(),null,null);
        clientTls=SSLContext.getInstance("TLS");clientTls.init(null,trust.getTrustManagers(),null);
    }
    static void run(String... command) throws Exception {
        Process p=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(p.getInputStream().readAllBytes());assertEquals(0,p.waitFor(),output);
    }
    static TunnelGateway gateway(List<TunnelGateway.Mapping> mappings) throws Exception {
        var g=new TunnelGateway(serverTls,new InetSocketAddress("127.0.0.1",0),
                (id,key)->id.equals("test-device") && key.equals("test-key"),mappings,16);
        g.start();return g;
    }
    static SSLSocket device(TunnelGateway g,String secret) throws Exception {
        var socket=(SSLSocket)clientTls.getSocketFactory().createSocket("localhost",g.port());
        var params=socket.getSSLParameters();params.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(params);
        socket.setSoTimeout(5000);socket.startHandshake();
        TunnelFrame.json(AUTH,0,Map.of("device_id","test-device","credential",secret,"firmware","test",
                "lan_ip","192.168.1.2","netmask","255.255.255.0","gateway","192.168.1.1")).write(socket.getOutputStream());return socket;
    }
    static void await(Callable<Boolean> check) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!check.call()) {if(System.nanoTime()>end) fail("Timed out");Thread.sleep(10);}
    }
    @Test void frameBoundaries() throws Exception {
        var out=new ByteArrayOutputStream();new TunnelFrame(DATA,7,new byte[]{0,1,(byte)255}).write(out);
        new TunnelFrame(FIN,7,new byte[0]).write(out);
        InputStream input=new FilterInputStream(new ByteArrayInputStream(out.toByteArray())) {
            @Override public int read(byte[] b,int off,int len) throws IOException {return super.read(b,off,Math.min(1,len));}
        };
        assertArrayEquals(new byte[]{0,1,(byte)255},TunnelFrame.read(input).payload());assertEquals(FIN,TunnelFrame.read(input).type());
        byte[] bad=out.toByteArray();bad[8]=0x7f;
        assertThrows(IOException.class,()->TunnelFrame.read(new ByteArrayInputStream(bad)));
        assertThrows(IOException.class,()->new TunnelFrame(PING,0,"{\"nonce\":1,\"nonce\":2}".getBytes()).json());
        assertThrows(IOException.class,()->new TunnelFrame(PING,0,"{} {}".getBytes()).json());
        assertThrows(IllegalArgumentException.class,()->new TunnelGateway.Mapping("test",0,"127.0.0.1",9100));
    }
    @Test void springConfigurationIsOptInAndBindsMappings() {
        var runner=new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(TunnelConfiguration.class)
                .withBean(iotproject.printer.device.DeviceAuthService.class,
                        ()->org.mockito.Mockito.mock(iotproject.printer.device.DeviceAuthService.class));
        runner.run(context->assertFalse(context.containsBean("tunnelGateway")));
        runner.withPropertyValues("printer.tunnel.enabled=true","printer.tunnel.bind=127.0.0.1",
                "printer.tunnel.port=0","printer.tunnel.key-store="+keyStore,"printer.tunnel.key-store-password=test-password",
                "printer.tunnel.mappings[0].device-id=test-device","printer.tunnel.mappings[0].local-port=0",
                "printer.tunnel.mappings[0].target-ip=192.168.1.50","printer.tunnel.mappings[0].target-port=9100")
                .run(context->{assertNull(context.getStartupFailure());
                    assertTrue(context.getBean(TunnelGateway.class).mappingPort(0)>0);});
    }
    @Test void authHeartbeatReplacement() throws Exception {
        try(var g=gateway(List.of())) {
            try(var bad=device(g,"wrong")) {assertEquals(AUTH_ERROR,TunnelFrame.read(bad.getInputStream()).type());assertEquals(-1,bad.getInputStream().read());}
            try(var first=device(g,"test-key")) {
                assertEquals(AUTH_OK,TunnelFrame.read(first.getInputStream()).type());await(()->g.online("test-device"));
                TunnelFrame.json(PING,0,Map.of("nonce",4294967295L)).write(first.getOutputStream());
                assertEquals(4294967295L,TunnelFrame.read(first.getInputStream()).json().get("nonce").longValue());
                try(var next=device(g,"test-key")) {
                    assertEquals(AUTH_OK,TunnelFrame.read(next.getInputStream()).type());assertEquals(-1,first.getInputStream().read());
                    TunnelFrame.json(PING,0,Map.of("nonce",2)).write(next.getOutputStream());
                    assertEquals(PONG,TunnelFrame.read(next.getInputStream()).type());assertTrue(g.online("test-device"));
                }
            }
            await(()->!g.online("test-device"));
        }
    }
    @Test void busyOpenFailureAndInvalidWindow() throws Exception {
        try(var g=gateway(List.of(new TunnelGateway.Mapping("test-device",0,"192.168.1.50",9100)));var d=device(g,"test-key")) {
            assertEquals(AUTH_OK,TunnelFrame.read(d.getInputStream()).type());await(()->g.online("test-device"));
            try(var local=new Socket("127.0.0.1",g.mappingPort(0))) {
                var open=TunnelFrame.read(d.getInputStream());assertEquals(OPEN,open.type());
                try(var busy=new Socket("127.0.0.1",g.mappingPort(0))) {busy.setSoTimeout(2000);assertEquals(-1,busy.getInputStream().read());}
                TunnelFrame.json(OPEN_ERROR,open.streamId(),Map.of("code","REFUSED")).write(d.getOutputStream());
                local.setSoTimeout(2000);assertEquals(-1,local.getInputStream().read());
            }
            try(var local=new Socket("127.0.0.1",g.mappingPort(0))) {
                var open=TunnelFrame.read(d.getInputStream());
                TunnelFrame.json(OPEN_OK,open.streamId(),Map.of("initial_window",4096)).write(d.getOutputStream());
                TunnelFrame.json(WINDOW,open.streamId(),Map.of("credit",1)).write(d.getOutputStream());
                assertEquals(-1,d.getInputStream().read());local.setSoTimeout(2000);assertEquals(-1,local.getInputStream().read());
            }
        }
    }
    @Test void exhaustedWindowStillRespondsToHeartbeat() throws Exception {
        try(var g=gateway(List.of(new TunnelGateway.Mapping("test-device",0,"192.168.1.50",9100)));var d=device(g,"test-key")) {
            assertEquals(AUTH_OK,TunnelFrame.read(d.getInputStream()).type());await(()->g.online("test-device"));
            try(var local=new Socket("127.0.0.1",g.mappingPort(0))) {
                var open=TunnelFrame.read(d.getInputStream());
                TunnelFrame.json(OPEN_OK,open.streamId(),Map.of("initial_window",4096)).write(d.getOutputStream());
                local.getOutputStream().write(new byte[8192]);
                int bytes=0;
                while(bytes<4096) {var f=TunnelFrame.read(d.getInputStream());assertEquals(DATA,f.type());bytes+=f.payload().length;}
                assertEquals(4096,bytes);
                TunnelFrame.json(PING,0,Map.of("nonce",42)).write(d.getOutputStream());
                assertEquals(PONG,TunnelFrame.read(d.getInputStream()).type());
                TunnelFrame.json(RESET,open.streamId(),Map.of("code","TARGET_IO")).write(d.getOutputStream());
                new TunnelFrame(CLOSED,open.streamId(),new byte[0]).write(d.getOutputStream());
            }
        }
    }
    @Test void untrustedTlsIsRejected() throws Exception {
        try(var g=gateway(List.of());var d=(SSLSocket)SSLContext.getDefault().getSocketFactory().createSocket("localhost",g.port())) {
            d.setSoTimeout(3000);assertThrows(SSLHandshakeException.class,d::startHandshake);
        }
    }
    @Test void pythonLargeDuplexAndHalfClose() {
        assertTimeoutPreemptively(Duration.ofSeconds(40),()->{
            InetAddress lan=Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
                    .flatMap(n->Collections.list(n.getInetAddresses()).stream())
                    .filter(a->a instanceof Inet4Address && !a.isLoopbackAddress()).findFirst().orElseThrow();
            try(var echo=new ServerSocket(0,10,lan)) {
                ExecutorService target=Executors.newSingleThreadExecutor();
                try(var g=gateway(List.of(new TunnelGateway.Mapping("test-device",0,lan.getHostAddress(),echo.getLocalPort())))) {
                    var builder=new ProcessBuilder("python3",Path.of("../device/simulator/client.py").toAbsolutePath().toString(),
                            "--host","localhost","--port",String.valueOf(g.port()),"--ca",certificate.toString(),"--device-id","test-device",
                            "--allow-cidr",lan.getHostAddress()+"/32","--allow-port",String.valueOf(echo.getLocalPort()),
                            "--lan-ip",lan.getHostAddress(),"--gateway",lan.getHostAddress(),"--once");
                    builder.environment().put("TUNNEL_DEVICE_KEY","test-key");Path log=temp.resolve("simulator.log");
                    builder.redirectErrorStream(true).redirectOutput(log.toFile());Process agent=builder.start();
                    try {
                        await(()->g.online("test-device"));
                        for(int round=0;round<12;round++) {
                            await(()->g.available("test-device"));
                            int mode=round%2;
                            Future<?> server=target.submit(()->{
                                try(Socket accepted=echo.accept()) {
                                    accepted.setSoTimeout(15000);byte[] buffer=new byte[333];int count;var saved=new ByteArrayOutputStream();
                                    while((count=accepted.getInputStream().read(buffer))!=-1) {
                                        if(mode==0) accepted.getOutputStream().write(buffer,0,count);else saved.write(buffer,0,count);
                                    }
                                    if(mode==1) accepted.getOutputStream().write(saved.toByteArray());accepted.shutdownOutput();
                                }catch(IOException e){throw new UncheckedIOException(e);}
                            });
                            byte[] bytes=new byte[256*1024+17];new Random(round).nextBytes(bytes);
                            try(Socket local=new Socket("127.0.0.1",g.mappingPort(0))) {
                                local.setSoTimeout(15000);
                                var sender=CompletableFuture.runAsync(()->{
                                    try{local.getOutputStream().write(bytes);local.shutdownOutput();}catch(IOException e){throw new UncheckedIOException(e);}
                                });
                                assertArrayEquals(bytes,local.getInputStream().readAllBytes(),"round="+round+" "+Files.readString(log));
                                sender.get(5,TimeUnit.SECONDS);server.get(5,TimeUnit.SECONDS);
                            }
                        }
                    }finally {agent.destroy();assertTrue(agent.waitFor(5,TimeUnit.SECONDS));}
                }finally {target.shutdownNow();}
            }
        });
    }
}
