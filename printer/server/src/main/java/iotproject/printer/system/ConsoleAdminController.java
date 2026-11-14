package iotproject.printer.system;

import iotproject.printer.jobs.PrintJobRepository;
import iotproject.printer.render.EscPosReceiptRenderer;
import iotproject.printer.tunnel.TunnelGateway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@ConditionalOnProperty(name="printer.tunnel.enabled",havingValue="true")
@RequestMapping("/internal/console")
public class ConsoleAdminController {
    private static final List<Integer> PRINTER_PORTS = List.of(9100, 631, 515);
    private final TunnelGateway tunnel;
    private final PrintJobRepository jobs;
    private final EscPosReceiptRenderer renderer;
    private final JdbcTemplate jdbc;
    private final String apiKey;
    private final List<String> allowedCidrs;
    private final AtomicBoolean scanActive=new AtomicBoolean();
    private final ExecutorService scanner=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"printer-scanner");t.setDaemon(true);return t;});
    private final ConcurrentMap<UUID,ScanState> scans=new ConcurrentHashMap<>();

    public ConsoleAdminController(TunnelGateway tunnel, PrintJobRepository jobs, EscPosReceiptRenderer renderer,
                                  JdbcTemplate jdbc,
                                  @Value("${printer.console-admin.api-key:}") String apiKey,
                                  @Value("${printer.console-admin.allowed-cidrs:}") String cidrs) {
        this.tunnel=tunnel;this.jobs=jobs;this.renderer=renderer;this.jdbc=jdbc;this.apiKey=apiKey;
        this.allowedCidrs=Arrays.stream(cidrs.split(",")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }

    @PostMapping("/scan")
    public ResponseEntity<?> scan(@RequestHeader("X-Console-Key") String key,@RequestBody Scan request) {
        if(!authorized(key)) return ResponseEntity.status(401).build();
        try {
            String cidr=canonicalCidr(request.cidr());
            if(!allowed(cidr) || prefix(cidr)<24) return ResponseEntity.badRequest().body(Map.of("error","scan_range_not_allowed"));
            Inet4Address network=(Inet4Address)InetAddress.getByName(cidr.substring(0,cidr.indexOf('/')));
            int bits=prefix(cidr);
            long base=Integer.toUnsignedLong(network.getAddress()[0]<<24 | (network.getAddress()[1]&255)<<16 | (network.getAddress()[2]&255)<<8 | network.getAddress()[3]&255);
            long first=base+1,last=(base | (0xffffffffL >>> bits))-1;
            if(last-first+1>254) return ResponseEntity.badRequest().body(Map.of("error","scan_range_too_large"));
            if(!scanActive.compareAndSet(false,true))return ResponseEntity.status(409).body(Map.of("error","scan_already_running"));
            scans.entrySet().removeIf(entry->!entry.getValue().status.equals("running"));
            UUID id=UUID.randomUUID();ScanState state=new ScanState(id,request.deviceId(),request.operator(),first,last);
            scans.put(id,state);scanner.submit(()->runScan(state,first,last));
            return ResponseEntity.accepted().body(scanJson(state));
        } catch(Exception e) { return ResponseEntity.badRequest().body(Map.of("error","invalid_scan_range")); }
    }

    @GetMapping("/scan/{id}")
    public ResponseEntity<?> scanStatus(@RequestHeader("X-Console-Key") String key,@PathVariable UUID id){
        if(!authorized(key))return ResponseEntity.status(401).build();ScanState state=scans.get(id);
        return state==null?ResponseEntity.notFound().build():ResponseEntity.ok(scanJson(state));
    }

    private void runScan(ScanState state,long first,long last){
        try{
            for(long host=first;host<=last;host++){
                if(!tunnel.online(state.deviceId)){state.status="failed";state.error="gateway_offline";break;}
                String ip=String.format("%d.%d.%d.%d",(host>>>24)&255,(host>>>16)&255,(host>>>8)&255,host&255);
                state.current=ip;
                for(int port:PRINTER_PORTS){
                    if(!tunnel.available(state.deviceId)){state.status="failed";state.error="gateway_busy_or_offline";return;}
                    try{tunnel.probe(state.deviceId,ip,port);state.targets.add(Map.of("ip",ip,"port",port));}
                    catch(Exception ignored){}
                }
                state.checked++;
            }
            if(state.status.equals("running"))state.status="completed";
            audit(state.operator,"scan",null,null,state.status+":"+state.targets.size());
        }catch(Exception e){state.status="failed";state.error="scan_failed";}
        finally{scanActive.set(false);}
    }
    private Map<String,Object> scanJson(ScanState s){return Map.of("scanId",s.id,"status",s.status,"checked",s.checked,"total",s.total,"current",s.current==null?"":s.current,"targets",List.copyOf(s.targets),"error",s.error==null?"":s.error);}
    private static final class ScanState{
        final UUID id;final String deviceId,operator;final long total;final List<Map<String,Object>> targets=new CopyOnWriteArrayList<>();
        volatile String status="running",current,error;volatile int checked;
        ScanState(UUID id,String deviceId,String operator,long first,long last){this.id=id;this.deviceId=deviceId;this.operator=operator;this.total=last-first+1;}
    }

    @PostMapping("/test-print")
    public ResponseEntity<?> testPrint(@RequestHeader("X-Console-Key") String key,@RequestBody PrintTarget request) {
        if(!authorized(key)) return ResponseEntity.status(401).build();
        if(!validTarget(request.ip(),request.port())) return ResponseEntity.badRequest().body(Map.of("error","target_not_allowed"));
        try {
            byte[] data=renderer.render(Map.of("customer","打印链路测试","time",Instant.now().toString(),
                    "invoiceNo","TEST-"+UUID.randomUUID().toString().substring(0,8),"items",List.of("测试打印 1 件 0.00"),
                    "total","0.00","note","测试单据，不是业务订单"));
            tunnel.sendRaw(request.deviceId(),request.ip(),request.port(),data);
            audit(request.operator(),"test_print",request.ip(),request.port(),"sent_unknown");
            return ResponseEntity.ok(Map.of("result","sent_unknown","bytes",data.length));
        } catch(Exception e) {audit(request.operator(),"test_print",request.ip(),request.port(),"delivery_unknown");return ResponseEntity.status(502).body(Map.of("error","delivery_unknown"));}
    }

    @PostMapping("/reprint")
    public ResponseEntity<?> reprint(@RequestHeader("X-Console-Key") String key,@RequestBody Reprint request) {
        if(!authorized(key)) return ResponseEntity.status(401).build();
        if(!validTarget(request.deviceId(),request.ip(),request.port())) return ResponseEntity.badRequest().body(Map.of("error","target_not_allowed"));
        var artifact=jobs.findArtifact(request.jobId());
        if(artifact.isEmpty()) return ResponseEntity.notFound().build();
        try {
            tunnel.sendRaw(request.deviceId(),request.ip(),request.port(),artifact.get());
            audit(request.operator(),"reprint",request.ip(),request.port(),request.jobId()+":sent_unknown");
            return ResponseEntity.ok(Map.of("result","sent_unknown","bytes",artifact.get().length));
        } catch(Exception e) {audit(request.operator(),"reprint",request.ip(),request.port(),request.jobId()+":delivery_unknown");return ResponseEntity.status(502).body(Map.of("error","delivery_unknown"));}
    }

    private boolean validTarget(String device,String ip,int port) {return allowedIp(ip)&&port==9100&&tunnel.online(device);}
    private boolean validTarget(String ip,int port) {return allowedIp(ip)&&port==9100;}
    private boolean allowedIp(String value) {return value!=null&&value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")&&allowedCidrs.stream().anyMatch(cidr->contains(cidr,value));}
    private boolean contains(String cidr,String ip) {
        try {String canonical=canonicalCidr(cidr);InetAddress target=InetAddress.getByName(ip);if(!(target instanceof Inet4Address))return false;
            byte[] n=InetAddress.getByName(canonical.substring(0,canonical.indexOf('/'))).getAddress(),a=target.getAddress();int bits=prefix(canonical);
            for(int i=0;i<4;i++){int take=Math.min(bits,8);int mask=take==0?0:0xff<<(8-take);if((n[i]&mask)!=(a[i]&mask))return false;bits-=take;}return true;
        }catch(Exception e){return false;}
    }
    private int prefix(String cidr){return Integer.parseInt(cidr.substring(cidr.indexOf('/')+1));}
    private String canonicalCidr(String value) throws Exception {
        if(value==null||!value.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}/[0-9]{1,2}"))return "";
        String[] p=value.split("/",-1);InetAddress ip=InetAddress.getByName(p[0]);
        if(!(ip instanceof Inet4Address)||p.length!=2)return "";
        int bits=Integer.parseInt(p[1]);if(bits<16||bits>30)return "";
        byte[] b=ip.getAddress();int m=(int)(0xffffffffL<<(32-bits));int n=(b[0]&255)<<24|(b[1]&255)<<16|(b[2]&255)<<8|b[3]&255;
        return String.format("%d.%d.%d.%d/%d",(n&m)>>>24,((n&m)>>>16)&255,((n&m)>>>8)&255,(n&m)&255,bits);
    }
    private boolean allowed(String requested){return allowedCidrs.stream().anyMatch(c->contains(c,requested.substring(0,requested.indexOf('/')))&&prefix(requested)>=prefix(c));}
    private boolean authorized(String supplied){return !apiKey.isBlank()&&supplied!=null&&MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8));}
    private void audit(String operator,String action,String ip,Integer port,String result){jdbc.update("INSERT INTO printer_admin_actions(operator,action,target_ip,target_port,result) VALUES (?,?,?::inet,?,?)",operator,action,ip,port,result);}
    public record Scan(String deviceId,String cidr,String operator){}
    public record PrintTarget(String deviceId,String ip,int port,String operator){}
    public record Reprint(String deviceId,String ip,int port,UUID jobId,String operator){}
}
