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
    private final InetSocketAddress address;
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

    public TunnelGateway(SSLContext tls,InetSocketAddress address,BiPredicate<String,String> authenticate,
                         List<Mapping> mappings,int maxConnections) {
        this.tls=tls; this.address=address; this.authenticate=authenticate; this.mappings=List.copyOf(mappings);
        if(maxConnections<1 || maxConnections>1024) throw new IllegalArgumentException("Invalid connection limit");
        capacity=new Semaphore(maxConnections);
    }
    public void start() throws IOException {
        if(!running.compareAndSet(false,true)) return;
        try {
            SSLServerSocket server=(SSLServerSocket)tls.getServerSocketFactory().createServerSocket();
            listeners.add(server); server.setEnabledProtocols(new String[]{"TLSv1.2"}); server.bind(address);
            port=server.getLocalPort();
            log.info("TLS tunnel listening on {}:{}",address.getHostString(),port);
            workers.execute(() -> accept(server,null));
            for(Mapping mapping:mappings) {
                ServerSocket local=new ServerSocket(); listeners.add(local);
                local.bind(new InetSocketAddress("127.0.0.1",mapping.localPort())); mappingPorts.add(local.getLocalPort());
                workers.execute(() -> accept(local,mapping));
            }
            timer.scheduleAtFixedRate(() -> sessions.values().forEach(Session::tick),1,1,TimeUnit.SECONDS);
        } catch(IOException | RuntimeException e) { close(); throw e; }
    }
    public int port() {return port;}
    public int mappingPort(int index) {return mappingPorts.get(index);}
    public boolean online(String device) {return sessions.containsKey(device);}
    public boolean available(String device) {
        Session session=sessions.get(device);
        return session!=null && session.stream==null && !session.socket.isClosed();
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
        Session session=sessions.get(mapping.deviceId());
        if(session==null) return;
        Stream stream=session.open(local,mapping);
        if(stream==null) return;
        try {
            stream.opened.get(7,TimeUnit.SECONDS);
            workers.execute(() -> stream.deliver());
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
            stream.done.get();
        } catch(Exception e) {
            log.debug("Local tunnel sender stopped: {}",e.getClass().getSimpleName());
            if(e instanceof SocketException) log.debug("Local socket state: {}",e.getMessage());
            session.reset(stream,"TARGET_IO");
        }
    }
    private final class Session {
        final SSLSocket socket;
        final Object writeLock=new Object();
        volatile long writingSince;
        volatile long lastPing=System.nanoTime();
        volatile Stream stream;
        String device;
        int nextId=1;
        Session(SSLSocket socket) {this.socket=socket;}
        void run() throws Exception {
            try {
                socket.setSoTimeout(10_000); socket.startHandshake();
                TunnelFrame auth=TunnelFrame.read(socket.getInputStream());
                if(auth.type()!=AUTH) throw new IOException("AUTH required");
                var json=auth.json(); device=text(json,"device_id",64);
                if(!device.matches("[A-Za-z0-9_.-]+")) throw new IOException("Invalid device");
                String secret=text(json,"credential",128); text(json,"firmware",64);
                text(json,"lan_ip",15);text(json,"netmask",15);text(json,"gateway",15);
                if(!authenticate.test(device,secret)) {send(TunnelFrame.json(AUTH_ERROR,0,Map.of("code","DENIED")));return;}
                send(TunnelFrame.json(AUTH_OK,0,Map.of("session_id",UUID.randomUUID().toString(),
                        "heartbeat_sec",30,"timeout_sec",90,"initial_window",WINDOW_SIZE)));
                Session old=sessions.put(device,this); if(old!=null) old.shutdown();
                log.info("Tunnel device online: {}",device);
                socket.setSoTimeout(90_000);
                while(!socket.isClosed()) receive(TunnelFrame.read(socket.getInputStream()));
            } finally { shutdown(); }
        }
        void receive(TunnelFrame frame) throws Exception {
            if(frame.type()==PING) {
                var node=frame.json().get("nonce");
                if(node==null || !node.isIntegralNumber() || !node.canConvertToLong()
                        || node.longValue()<0 || node.longValue()>0xffffffffL) throw new IOException("Invalid nonce");
                lastPing=System.nanoTime();send(TunnelFrame.json(PONG,0,Map.of("nonce",node.longValue())));return;
            }
            Stream s=stream;
            if(s==null || s.id!=frame.streamId()) throw new IOException("Unknown stream");
            synchronized(s) {
                if(s.reset && frame.type()!=CLOSED) return; // drain in-flight bytes after RESET
                switch(frame.type()) {
                    case OPEN_OK -> {
                        if(s.opened.isDone()) throw new IOException("Duplicate OPEN_OK");
                        s.sendCredit=number(frame.json(),"initial_window",WINDOW_SIZE,WINDOW_SIZE);
                        s.opened.complete(null); s.notifyAll();
                    }
                    case OPEN_ERROR -> {
                        if(s.opened.isDone()) throw new IOException("Late OPEN_ERROR");
                        text(frame.json(),"code",64); stream=null;s.fail();
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
                        stream=null;
                        // Deliver queued data and FIN before completing the local connection.
                        if(s.reset) s.fail(); else s.peerClosed=true;
                        s.notifyAll();
                    }
                    default -> throw new IOException("Unexpected device message");
                }
            }
        }
        synchronized Stream open(Socket local,Mapping mapping) throws IOException {
            if(stream!=null || socket.isClosed()) return null;
            if(nextId==0) {shutdown();return null;}
            Stream s=new Stream(this,nextId++,local);stream=s;
            send(TunnelFrame.json(OPEN,s.id,Map.of("target_ip",mapping.targetIp(),"target_port",mapping.targetPort(),
                    "connect_timeout_ms",1500,"idle_timeout_sec",120,"initial_window",WINDOW_SIZE)));
            return s;
        }
        void send(TunnelFrame frame) throws IOException {
            synchronized(writeLock) {
                writingSince=System.nanoTime();
                try {frame.write(socket.getOutputStream());}
                finally {writingSince=0;}
            }
        }
        void reset(Stream s,String code) {
            log.debug("Resetting target stream {}: {}",s.id,code);
            synchronized(s) {if(stream!=s || s.reset) return;s.reset=true;s.resetAt=System.nanoTime();s.fail();}
            try {send(TunnelFrame.json(RESET,s.id,Map.of("code",code)));} catch(IOException e) {shutdown();}
        }
        void tick() {
            long now=System.nanoTime();
            if(now-lastPing>TimeUnit.SECONDS.toNanos(90)
                    || (writingSince!=0 && now-writingSince>TimeUnit.SECONDS.toNanos(10))) {shutdown();return;}
            Stream s=stream; if(s==null) return;
            if(s.reset && now-s.resetAt>TimeUnit.SECONDS.toNanos(10)) {shutdown();return;}
            if(!s.opened.isDone() && now-s.lastActivity>TimeUnit.SECONDS.toNanos(7)) {shutdown();return;}
            if(!s.reset && now-s.lastActivity>TimeUnit.SECONDS.toNanos(s.localFin||s.remoteFin ? 10 : 120))
                workers.execute(() -> reset(s,"IDLE_TIMEOUT"));
        }
        void shutdown() {
            closeSocket(socket);
            if(device!=null && sessions.remove(device,this)) log.info("Tunnel device offline: {}",device);
            Stream s=stream;if(s!=null) s.fail();
        }
    }
    private final class Stream {
        final Session session;final int id;final Socket local;
        final CompletableFuture<Void> opened=new CompletableFuture<>(),done=new CompletableFuture<>();
        final BlockingQueue<TunnelFrame> incoming=new ArrayBlockingQueue<>(WINDOW_SIZE+2);
        int sendCredit,receiveCredit=WINDOW_SIZE;
        boolean localFin,remoteFin,peerClosed,reset;
        volatile long lastActivity=System.nanoTime(),resetAt;
        Stream(Session session,int id,Socket local) {this.session=session;this.id=id;this.local=local;}
        synchronized int awaitCredit() throws IOException,InterruptedException {
            while(sendCredit==0 && !reset && !done.isDone()) wait();
            if(reset || done.isDone()) throw new IOException("Stream closed");return sendCredit;
        }
        void deliver() {
            try {
                while(!reset) {
                    TunnelFrame frame=incoming.poll(200,TimeUnit.MILLISECONDS);
                    if(frame!=null) {
                        if(frame.type()==FIN) local.shutdownOutput();
                        else {
                            local.getOutputStream().write(frame.payload());
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
