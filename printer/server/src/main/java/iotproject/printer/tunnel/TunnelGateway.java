package iotproject.printer.tunnel;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static iotproject.printer.tunnel.TunnelFrame.*;

/** TLS sessions and explicit, loopback-only target mappings. No print semantics. */
public final class TunnelGateway implements AutoCloseable {
    private static final Logger log=LoggerFactory.getLogger(TunnelGateway.class);
    private static final int MAX_STREAMS_PER_SESSION=3;
    private static final int PRINT_ACK_TIMEOUT_SECONDS=12;
    private static final String[] TLS12_CIPHER_SUITES={
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
    };
    public record Mapping(String deviceId,int localPort,String targetIp,int targetPort) {
        public Mapping {
            if(deviceId==null || !deviceId.matches("[A-Za-z0-9_.-]{1,64}") || localPort<0 || localPort>65535
                    || targetPort<1 || targetPort>65535) throw new IllegalArgumentException("Invalid mapping");
            String[] octets=targetIp.split("\\.",-1);
            if(octets.length!=4) throw new IllegalArgumentException("IPv4 required");
            int[] values=new int[4];
            for(int i=0;i<4;i++) {
                if(!octets[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException("IPv4 required");
                values[i]=Integer.parseInt(octets[i]);
                if(values[i]>255) throw new IllegalArgumentException("IPv4 required");
            }
            if(values[0]==0 || values[0]==127 || values[0]>=224 || values[3]==255)
                throw new IllegalArgumentException("Forbidden target address");
        }
    }
    private final SSLContext tls;
    private final String[] enabledProtocols;
    private final InetSocketAddress address;
    private final InetSocketAddress legacyAddress;
    private final BiPredicate<String,String> authenticate;
    private final List<Mapping> mappings;
    private final ConcurrentMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final List<ServerSocket> listeners=new CopyOnWriteArrayList<>();
    private final List<Integer> mappingPorts=new CopyOnWriteArrayList<>();
    private final ExecutorService workers=Executors.newCachedThreadPool(r -> {Thread t=new Thread(r,"tunnel-io");t.setDaemon(true);return t;});
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"tunnel-watchdog");t.setDaemon(true);return t;});
    private final Semaphore capacity;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile int port;
    private volatile int legacyPort;

    public TunnelGateway(SSLContext tls,InetSocketAddress address,BiPredicate<String,String> authenticate,
                         List<Mapping> mappings,int maxConnections) {
        this(tls,address,authenticate,mappings,maxConnections,new String[]{"TLSv1.2"});
    }
    public TunnelGateway(SSLContext tls,InetSocketAddress address,BiPredicate<String,String> authenticate,
                         List<Mapping> mappings,int maxConnections,String[] enabledProtocols) {
        this(tls,address,authenticate,mappings,maxConnections,enabledProtocols,null);
    }
    public TunnelGateway(SSLContext tls,InetSocketAddress address,BiPredicate<String,String> authenticate,
                         List<Mapping> mappings,int maxConnections,String[] enabledProtocols,
                         InetSocketAddress legacyAddress) {
        this.tls=tls; this.address=address; this.authenticate=authenticate; this.mappings=List.copyOf(mappings);
        Set<Integer> configuredPorts=new HashSet<>();
        for(Mapping mapping:this.mappings) {
            if(mapping.localPort()!=0 && !configuredPorts.add(mapping.localPort()))
                throw new IllegalArgumentException("Duplicate local tunnel mapping port: "+mapping.localPort());
        }
        if(enabledProtocols==null || enabledProtocols.length!=1
                || !(enabledProtocols[0].equals("TLSv1.2") || enabledProtocols[0].equals("TLSv1")))
            throw new IllegalArgumentException("Only isolated TLSv1.2 or legacy TLSv1 listeners are supported");
        this.enabledProtocols=enabledProtocols.clone();
        this.legacyAddress=legacyAddress;
        if(maxConnections<1 || maxConnections>1024) throw new IllegalArgumentException("Invalid connection limit");
        capacity=new Semaphore(maxConnections);
    }
    public void start() throws IOException {
        if(!running.compareAndSet(false,true)) return;
        try {
            SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
            listeners.add(server); server.setEnabledProtocols(enabledProtocols);
            if(enabledProtocols[0].equals("TLSv1.2")) server.setEnabledCipherSuites(TLS12_CIPHER_SUITES);
            server.bind(address);
            port=server.getLocalPort();
            log.info("TLS tunnel listening on {}:{}",address.getHostString(),port);
            workers.execute(() -> accept(server,null));
            if(legacyAddress!=null) {
                SSLServerSocket legacy=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
                listeners.add(legacy); legacy.setEnabledProtocols(new String[]{"TLSv1"});
                legacy.setEnabledCipherSuites(new String[]{"TLS_RSA_WITH_AES_128_CBC_SHA"});
                legacy.bind(legacyAddress);
                legacyPort=legacy.getLocalPort();
                log.warn("Legacy TLS 1.0 tunnel enabled on {}:{}",legacyAddress.getHostString(),legacy.getLocalPort());
                workers.execute(() -> accept(legacy,null));
            }
            for(Mapping mapping:mappings) {
                ServerSocket local=new ServerSocket(); listeners.add(local);
                local.bind(new InetSocketAddress("127.0.0.1",mapping.localPort())); mappingPorts.add(local.getLocalPort());
                log.info("TCP mapping listening on 127.0.0.1:{} -> device {} -> {}:{}",
                        local.getLocalPort(),mapping.deviceId(),mapping.targetIp(),mapping.targetPort());
                workers.execute(() -> accept(local,mapping));
            }
            timer.scheduleAtFixedRate(() -> sessions.values().forEach(Session::tick),1,1,TimeUnit.SECONDS);
        } catch(IOException | RuntimeException e) { close(); throw e; }
    }
    public int port() {return port;}
    public int legacyPort() {return legacyPort;}
    public int mappingPort(int index) {return mappingPorts.get(index);}
    public boolean online(String device) {return sessions.containsKey(device);}
    public boolean available(String device) {
        Session session=sessions.get(device);
        return session!=null && session.streams.size()<MAX_STREAMS_PER_SESSION && !session.socket.isClosed();
    }
    public boolean hasMapping(String device) {
        return mappings.stream().anyMatch(mapping -> mapping.deviceId().equals(device));
    }
    public boolean hasSingleMapping(String device) {
        return mappings.stream().filter(mapping -> mapping.deviceId().equals(device)).count()==1;
    }
    public void sendRaw(String device,byte[] data) throws IOException {
        Mapping mapping=mappings.stream().filter(item -> item.deviceId().equals(device)).findFirst()
                .orElseThrow(() -> new IOException("No authorized printer mapping for device"));
        if(!hasSingleMapping(device))
            throw new IOException("Exactly one printer mapping is required for device");
        sendRaw(device,mapping.targetIp(),mapping.targetPort(),data);
    }
    private void accept(ServerSocket server,Mapping mapping) {
        while(running.get()) {
            try {
                Socket socket=server.accept();
                if(!capacity.tryAcquire()) {socket.close();continue;}
                sockets.add(socket); socket.setTcpNoDelay(true);
                workers.execute(() -> {
                    try {
                        if(mapping==null) new Session((SSLSocket)socket).run();
                        else bridge(socket,mapping);
                    } catch(Exception failure) {
                        // Log only the exception class; messages can contain untrusted payloads.
                        log.debug("Tunnel connection ended: {}",failure.getClass().getSimpleName());
                        if(failure.getClass()==IOException.class) log.debug("Tunnel protocol state: {}",failure.getMessage());
                    } finally { closeSocket(socket); sockets.remove(socket);capacity.release(); }
                });
            } catch(IOException e) { if(running.get()) close(); return; }
        }
    }
    private void bridge(Socket local,Mapping mapping) throws Exception {
        bridge(local,mapping,null,null,false);
    }
    private void bridge(Socket local,Mapping mapping,CompletableFuture<Void> opened) throws Exception {
        bridge(local,mapping,opened,null,false);
    }
    private void bridge(Socket local,Mapping mapping,CompletableFuture<Void> opened,
                        CompletableFuture<Void> acknowledged,boolean discardTargetResponse) throws Exception {
        Session session=sessions.get(mapping.deviceId());
        if(session==null) {
            IOException failure=new IOException("Device offline");
            if(opened!=null) opened.completeExceptionally(failure);
            if(acknowledged!=null) acknowledged.completeExceptionally(failure);
            return;
        }
        Stream stream=session.open(local,mapping);
        if(stream==null) {
            IOException failure=new IOException("Device busy");
            if(opened!=null) opened.completeExceptionally(failure);
            if(acknowledged!=null) acknowledged.completeExceptionally(failure);
            return;
        }
        try {
            stream.opened.get(7,TimeUnit.SECONDS);
            if(opened!=null) opened.complete(null);
            workers.execute(() -> stream.deliver(discardTargetResponse));
            byte[] buffer=new byte[CHUNK];
            while(true) {
                int limit=stream.awaitCredit();
                int count=local.getInputStream().read(buffer,0,Math.min(limit,CHUNK));
                if(count<0) {
                    synchronized(stream) {
                        if(stream.reset) throw new IOException("Stream reset");
                        stream.localFin=true;stream.lastActivity=System.nanoTime();
                        session.send(new TunnelFrame(FIN,stream.id,new byte[0]));
                    }
                    break;
                }
                synchronized(stream) {
                    if(stream.reset) throw new IOException("Stream reset");
                    stream.sendCredit-=count;stream.lastActivity=System.nanoTime();
                    session.send(new TunnelFrame(DATA,stream.id,Arrays.copyOf(buffer,count)));
                }
            }
            if(acknowledged!=null) {
                stream.awaitAllDataAcknowledged(PRINT_ACK_TIMEOUT_SECONDS,TimeUnit.SECONDS);
                acknowledged.complete(null);
            }
            stream.done.get();
        } catch(Exception e) {
            if(opened!=null) opened.completeExceptionally(e);
            if(acknowledged!=null) acknowledged.completeExceptionally(e);
            log.debug("Local tunnel sender stopped: {}",e.getClass().getSimpleName());
            if(e instanceof SocketException) log.debug("Local socket state: {}",e.getMessage());
            session.reset(stream,"TARGET_IO");
        }
    }
    public boolean probe(String device,String targetIp,int targetPort) throws IOException {
        exchange(device,targetIp,targetPort,new byte[0],500);
        return true;
    }
    public void sendRaw(String device,String targetIp,int targetPort,byte[] data) throws IOException {
        exchange(device,targetIp,targetPort,data,true);
    }
    public byte[] exchangeRaw(String device,String targetIp,int targetPort,byte[] data,int responseTimeoutMs) throws IOException {
        if(data==null || data.length==0 || data.length>64 || responseTimeoutMs<100 || responseTimeoutMs>5000)
            throw new IllegalArgumentException("Invalid raw exchange bounds");
        return exchange(device,targetIp,targetPort,data,responseTimeoutMs);
    }
    private byte[] exchange(String device,String targetIp,int targetPort,byte[] data,int responseTimeoutMs) throws IOException {
        return exchangeInternal(device,targetIp,targetPort,data,responseTimeoutMs,false);
    }
    private byte[] exchange(String device,String targetIp,int targetPort,byte[] data,
                            boolean waitForDataAcknowledgement) throws IOException {
        return exchangeInternal(device,targetIp,targetPort,data,0,waitForDataAcknowledgement);
    }
    private byte[] exchangeInternal(String device,String targetIp,int targetPort,byte[] data,int responseTimeoutMs,
                                    boolean waitForDataAcknowledgement) throws IOException {
        Mapping target=new Mapping(device,0,targetIp,targetPort);
        if(!available(device)) throw new IOException("Device offline or tunnel busy");
        CompletableFuture<Void> opened=new CompletableFuture<>();
        CompletableFuture<Void> acknowledged=waitForDataAcknowledgement?new CompletableFuture<>():null;
        try(ServerSocket listener=new ServerSocket()) {
            listener.bind(new InetSocketAddress("127.0.0.1",0));
            Future<?> bridge=workers.submit(()->{
                try(Socket local=listener.accept()) {bridge(local,target,opened,acknowledged,waitForDataAcknowledgement);}
                catch(Exception e) {
                    opened.completeExceptionally(e);
                    if(acknowledged!=null) acknowledged.completeExceptionally(e);
                }
            });
            try(Socket client=new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1",listener.getLocalPort()),1000);
                try {opened.get(8,TimeUnit.SECONDS);}
                catch(Exception e) {throw new IOException("Tunnel target connection failed",e);}
                if(data.length>0) {client.getOutputStream().write(data);client.getOutputStream().flush();}
                client.shutdownOutput();
                if(waitForDataAcknowledgement) {
                    try {acknowledged.get(PRINT_ACK_TIMEOUT_SECONDS+1L,TimeUnit.SECONDS);}
                    catch(TimeoutException e) {throw new IOException("Printer data was not acknowledged by the target TCP stack",e);}
                    catch(ExecutionException e) {throw new IOException("Printer data transfer failed",e.getCause());}
                    catch(InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for printer TCP acknowledgements",e);
                    }
                    return new byte[0];
                }
                client.setSoTimeout(responseTimeoutMs);
                ByteArrayOutputStream response=new ByteArrayOutputStream();
                byte[] buffer=new byte[512];
                try {int count;while((count=client.getInputStream().read(buffer))!=-1) response.write(buffer,0,count);}
                catch(SocketTimeoutException ignored) { }
                return response.toByteArray();
            } finally {
                if(!waitForDataAcknowledgement || acknowledged==null || !acknowledged.isDone()) {
                    try {bridge.get(1,TimeUnit.SECONDS);} catch(Exception ignored) {bridge.cancel(true);}
                }
            }
        }
    }
    private final class Session {
        final SSLSocket socket;
        final Object writeLock=new Object();
        volatile long writingSince;
        volatile long lastPing=System.nanoTime();
        final ConcurrentMap<Integer,Stream> streams=new ConcurrentHashMap<>();
        String device;
        int nextId=1;
        Session(SSLSocket socket) {this.socket=socket;}
        void run() throws Exception {
            String stage="TLS handshake";
            try {
                socket.setSoTimeout(10_000); socket.startHandshake();
                stage="device authentication";
                TunnelFrame auth=WireProtocol.read(socket.getInputStream(),socket,null);
                if(auth.type()!=AUTH) throw new IOException("AUTH required");
                var json=auth.json(); device=text(json,"device_id",64);
                if(!device.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid device");
                String secret=text(json,"credential",128); text(json,"firmware",64);
                text(json,"lan_ip",15);text(json,"netmask",15);text(json,"gateway",15);
                if(!authenticate.test(device,secret)) {
                    log.warn("Tunnel device authentication rejected: device={}, remote={}",
                            device,socket.getRemoteSocketAddress());
                    send(TunnelFrame.json(AUTH_ERROR,0,Map.of("code","DENIED")));return;
                }
                send(TunnelFrame.json(AUTH_OK,0,Map.of("session_id",UUID.randomUUID().toString(),
                        "heartbeat_sec",30,"timeout_sec",90,"initial_window",WINDOW_SIZE)));
                Session old=sessions.put(device,this); if(old!=null) old.shutdown();
                log.info("Tunnel device online: {}",device);
                stage="tunnel session";
                socket.setSoTimeout(90_000);
                while(!socket.isClosed()) receive(WireProtocol.read(socket.getInputStream(),socket,device));
            } catch(Exception failure) {
                if(stage.equals("TLS handshake") || stage.equals("device authentication")) {
                    log.warn("Tunnel connection from {} failed during {}: {}",socket.getRemoteSocketAddress(),
                            stage,failure.getClass().getSimpleName());
                }
                throw failure;
            } finally { shutdown(); }
        }
        void receive(TunnelFrame frame) throws Exception {
            if(frame.type()==PING) {
                var node=frame.json().get("nonce");
                if(node==null || !node.isIntegralNumber() || !node.canConvertToLong()
                        || node.longValue()<0 || node.longValue()>0xffffffffL) throw new IOException("Invalid nonce");
                lastPing=System.nanoTime();send(TunnelFrame.json(PONG,0,Map.of("nonce",node.longValue())));return;
            }
            Stream s=streams.get(frame.streamId());
            if(s==null) throw new IOException("Unknown stream");
            synchronized(s) {
                if(s.reset && frame.type()!=CLOSED) return; // drain in-flight bytes after RESET
                switch(frame.type()) {
                    case OPEN_OK -> {
                        if(s.opened.isDone()) throw new IOException("Duplicate OPEN_OK");
                        s.sendCredit=number(frame.json(),"initial_window",512,WINDOW_SIZE);
                        s.opened.complete(null); s.notifyAll();
                    }
                    case OPEN_ERROR -> {
                        if(s.opened.isDone()) throw new IOException("Late OPEN_ERROR");
                        String code=text(frame.json(),"code",64);
                        log.warn("Tunnel target connection rejected: device={}, target={}:{}, stream={}, code={}",
                                device,s.mapping.targetIp(),s.mapping.targetPort(),s.id,code);
                        streams.remove(s.id,s);s.fail();
                    }
                    case DATA -> {
                        if(!s.opened.isDone() || s.remoteFin || frame.payload().length>s.receiveCredit)
                            throw new IOException("Invalid DATA state/credit");
                        s.receiveCredit-=frame.payload().length;s.lastActivity=System.nanoTime();
                        if(!s.incoming.offer(frame)) throw new IOException("Receive queue overflow");
                    }
                    case WINDOW -> {
                        if(!s.opened.isDone()) throw new IOException("Early WINDOW_UPDATE");
                        int credit=number(frame.json(),"credit",1,WINDOW_SIZE);
                        if(s.sendCredit+credit>WINDOW_SIZE) throw new IOException("Credit overflow");
                        s.sendCredit+=credit;s.notifyAll();
                    }
                    case FIN -> {
                        log.debug("Device FIN stream {}",s.id);
                        if(!s.opened.isDone() || s.remoteFin) throw new IOException("Invalid FIN");
                        s.remoteFin=true;s.lastActivity=System.nanoTime();
                        if(!s.incoming.offer(frame)) throw new IOException("Receive queue overflow");
                    }
                    case RESET -> {
                        text(frame.json(),"code",64);
                        log.debug("Device reset target stream {}",s.id);
                        s.reset=true;s.resetAt=System.nanoTime();s.fail();
                    }
                    case CLOSED -> {
                        log.debug("Device CLOSED stream {}, queued {}",s.id,s.incoming.size());
                        if(!s.reset && (!s.localFin || !s.remoteFin)) throw new IOException("Early CLOSED");
                        streams.remove(s.id,s);
                        // Deliver queued data and FIN before completing the local connection.
                        if(s.reset) s.fail(); else s.peerClosed=true;
                        s.notifyAll();
                    }
                    default -> throw new IOException("Unexpected device message");
                }
            }
        }
        synchronized Stream open(Socket local,Mapping mapping) throws IOException {
            if(streams.size()>=MAX_STREAMS_PER_SESSION || socket.isClosed()) return null;
            if(nextId==0) {shutdown();return null;}
            Stream s=new Stream(this,nextId++,local,mapping);streams.put(s.id,s);
            send(TunnelFrame.json(OPEN,s.id,Map.of("target_ip",mapping.targetIp(),"target_port",mapping.targetPort(),
                    "connect_timeout_ms",1500,"idle_timeout_sec",120,"initial_window",WINDOW_SIZE)));
            return s;
        }
        void send(TunnelFrame frame) throws IOException {
            synchronized(writeLock) {
                writingSince=System.nanoTime();
                try {WireProtocol.write(frame,socket.getOutputStream(),socket);}
                finally {writingSince=0;}
            }
        }
        void reset(Stream s,String code) {
            log.debug("Resetting target stream {}: {}",s.id,code);
            synchronized(s) {if(!streams.remove(s.id,s) || s.reset) return;s.reset=true;s.resetAt=System.nanoTime();s.fail();}
            try {send(TunnelFrame.json(RESET,s.id,Map.of("code",code)));} catch(IOException e) {shutdown();}
        }
        void tick() {
            long now=System.nanoTime();
            if(now-lastPing>TimeUnit.SECONDS.toNanos(90)
                    || (writingSince!=0 && now-writingSince>TimeUnit.SECONDS.toNanos(10))) {shutdown();return;}
            for(Stream s:streams.values()) {
                if(s.reset && now-s.resetAt>TimeUnit.SECONDS.toNanos(10)) {shutdown();return;}
                if(!s.opened.isDone() && now-s.lastActivity>TimeUnit.SECONDS.toNanos(7)) {shutdown();return;}
                if(!s.reset && now-s.lastActivity>TimeUnit.SECONDS.toNanos(s.localFin||s.remoteFin ? 10 : 120))
                    workers.execute(() -> reset(s,"IDLE_TIMEOUT"));
            }
        }
        void shutdown() {
            closeSocket(socket);
            if(device!=null && sessions.remove(device,this)) log.info("Tunnel device offline: {}",device);
            streams.values().forEach(Stream::fail);streams.clear();
        }
    }
    private final class Stream {
        final Session session;final int id;final Socket local;final Mapping mapping;
        final CompletableFuture<Void> opened=new CompletableFuture<>(),done=new CompletableFuture<>();
        final BlockingQueue<TunnelFrame> incoming=new ArrayBlockingQueue<>(WINDOW_SIZE+2);
        int sendCredit,receiveCredit=WINDOW_SIZE;
        boolean localFin,remoteFin,peerClosed,reset;
        volatile long lastActivity=System.nanoTime(),resetAt;
        Stream(Session session,int id,Socket local,Mapping mapping) {
            this.session=session;this.id=id;this.local=local;this.mapping=mapping;
        }
        synchronized int awaitCredit() throws IOException,InterruptedException {
            while(sendCredit==0 && !reset && !done.isDone()) wait();
            if(reset || done.isDone()) throw new IOException("Stream closed");return sendCredit;
        }
        synchronized void awaitAllDataAcknowledged(long timeout,TimeUnit unit) throws IOException,InterruptedException {
            long deadline=System.nanoTime()+unit.toNanos(timeout),remaining=unit.toNanos(timeout);
            while(sendCredit<WINDOW_SIZE && !peerClosed && !reset && remaining>0) {
                TimeUnit.NANOSECONDS.timedWait(this,remaining);
                remaining=deadline-System.nanoTime();
            }
            if(sendCredit==WINDOW_SIZE || (peerClosed && localFin && !reset)) return;
            throw new IOException(reset
                    ? "Target stream reset before all printer data was acknowledged"
                    : "Timed out waiting for printer TCP acknowledgements or clean stream close");
        }
        void deliver(boolean discardTargetResponse) {
            try {
                while(!reset) {
                    TunnelFrame frame=incoming.poll(200,TimeUnit.MILLISECONDS);
                    if(frame!=null) {
                        if(frame.type()==FIN) local.shutdownOutput();
                        else {
                            if(!discardTargetResponse) local.getOutputStream().write(frame.payload());
                            synchronized(this) {receiveCredit+=frame.payload().length;}
                            // CLOSED can already be queued by the device; no window is needed after it.
                            synchronized(this) {
                                if(!peerClosed && !reset) session.send(TunnelFrame.json(WINDOW,id,Map.of("credit",frame.payload().length)));
                            }
                        }
                    }
                    synchronized(this) {if(peerClosed && incoming.isEmpty()) {
                        log.debug("Delivered stream {} EOF",id);
                        done.complete(null);notifyAll();return;
                    }}
                }
            } catch(Exception e) {
                log.debug("Local tunnel receiver stopped: {}",e.getClass().getSimpleName());
                if(e instanceof SocketException) log.debug("Local receiver state: {}",e.getMessage());
                session.reset(this,"TARGET_IO");
            }
        }
        synchronized void fail() {
            reset=true;closeSocket(local);incoming.clear();
            opened.completeExceptionally(new IOException("Stream unavailable"));
            done.completeExceptionally(new IOException("Stream unavailable"));notifyAll();
        }
    }
    private static void closeSocket(Socket socket) {try {socket.close();} catch(IOException ignored) {}}
    @Override public void close() {
        running.set(false);listeners.forEach(s -> {try{s.close();}catch(IOException ignored){}});
        sockets.forEach(TunnelGateway::closeSocket);sessions.values().forEach(Session::shutdown);
        timer.shutdownNow();workers.shutdownNow();
    }
}
