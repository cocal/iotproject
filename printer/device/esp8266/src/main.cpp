#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <WiFiClientSecureBearSSL.h>
#include <StackThunk.h>
#include <ArduinoJson.h>
#include <ESP8266WebServer.h>
#include <LittleFS.h>
#include <time.h>
extern "C" {
#include <lwip/tcp.h>
}
#include "config.h"

// Protocol v1: bounded frames, a single LAN socket, no print/scanner semantics.
constexpr uint8_t AUTH=1, AUTH_OK=2, AUTH_ERROR=3, OPEN=16, OPEN_OK=17, OPEN_ERROR=18,
    DATA=32, WINDOW=33, FIN=34, STREAM_CLOSED=35, RESET=36, PING=48, PONG=49;
constexpr size_t CHUNK=1024, CREDIT=4096;
BearSSL::WiFiClientSecure tunnel;
BearSSL::X509List trust(TRUSTED_CERT);
ESP8266WebServer setupWeb(80);
bool setupMode=false;
String configuredSsid,configuredPassword;
tcp_pcb *target=nullptr;
uint32_t sid=0,lastSid=0,credit=0,toTargetCredit=CREDIT,unacked=0,returnedCredit=0;
uint32_t lastPong=0,lastPing=0,nonce=0,pingNonces[4]={},pingCount=0;
uint32_t connectAt=0,connectTimeout=1500,activity=0,closeAt=0,idleMs=120000;
uint32_t onlineAt=0,nextAttempt=0,backoff=1000;
bool notified=false;
bool online=false,connecting=false,opened=false,remoteFin=false,localFin=false,writeClosed=false;
bool targetError=false,targetEof=false,protocolBad=false;
// LAN -> TLS ring; bytes are acknowledged with tcp_recved only after TLS write.
uint8_t ring[CREDIT];size_t ringHead=0,ringSize=0;
uint8_t outbound[CREDIT];size_t outHead=0,outSize=0;
uint8_t header[16],payload[CHUNK];size_t headerUsed=0,payloadUsed=0,payloadLength=0;
uint8_t incomingType=0;uint32_t incomingSid=0;
uint32_t memoryLowHeap=UINT32_MAX,memoryLowBlock=UINT32_MAX,memoryLowStack=UINT32_MAX;
uint32_t memorySampleAt=0,lastMemoryReport=0,lastPressureReport=0;
uint32_t pressureBytes=0,pressureRingSize=0;
volatile bool memoryPressurePending=false;

uint32_t read32(const uint8_t *p) {return uint32_t(p[0])<<24|uint32_t(p[1])<<16|uint32_t(p[2])<<8|p[3];}
void put32(uint8_t *p,uint32_t n){p[0]=n>>24;p[1]=n>>16;p[2]=n>>8;p[3]=n;}
bool elapsed(uint32_t since,uint32_t duration){return uint32_t(millis()-since)>=duration;}
bool known(uint8_t t){return t==AUTH||t==AUTH_OK||t==AUTH_ERROR||t==OPEN||t==OPEN_OK||t==OPEN_ERROR||t==DATA||t==WINDOW||t==FIN||t==STREAM_CLOSED||t==RESET||t==PING||t==PONG;}

void sampleMemory(){
    if(memorySampleAt&& !elapsed(memorySampleAt,5000))return;
    memorySampleAt=millis();
    uint32_t freeHeap=ESP.getFreeHeap();
    if(freeHeap<memoryLowHeap)memoryLowHeap=freeHeap;
    uint32_t freeStack=ESP.getFreeContStack();
    if(freeStack<memoryLowStack)memoryLowStack=freeStack;
#if defined(UMM_INFO)
    uint32_t maxBlock=ESP.getMaxFreeBlockSize();
    if(maxBlock<memoryLowBlock)memoryLowBlock=maxBlock;
#endif
}
void memorySnapshot(const char *event){
    uint32_t freeHeap=ESP.getFreeHeap();
    if(freeHeap<memoryLowHeap)memoryLowHeap=freeHeap;
    uint32_t freeStack=ESP.getFreeContStack();
    if(freeStack<memoryLowStack)memoryLowStack=freeStack;
    int wifiStatus=WiFi.status(),wifiRssi=WiFi.RSSI();
    sampleMemory();
#if defined(UMM_INFO)
    uint32_t maxBlock=ESP.getMaxFreeBlockSize();
    uint8_t fragmentation=ESP.getHeapFragmentation();
    if(maxBlock<memoryLowBlock)memoryLowBlock=maxBlock;
    Serial.printf("[mem] ms=%lu event=%s heap=%lu max=%lu frag=%u%% low_heap=%lu low_max=%lu stack=%lu low_stack=%lu tls_stack=%lu wifi=%d rssi=%d sid=%lu type=%u len=%u ring=%u out=%u\n",
        (unsigned long)millis(),event,(unsigned long)freeHeap,(unsigned long)maxBlock,fragmentation,
        (unsigned long)memoryLowHeap,(unsigned long)memoryLowBlock,(unsigned long)freeStack,
        (unsigned long)memoryLowStack,(unsigned long)stack_thunk_get_max_usage(),wifiStatus,wifiRssi,
        (unsigned long)sid,incomingType,(unsigned)payloadLength,(unsigned)ringSize,(unsigned)outSize);
#else
    Serial.printf("[mem] ms=%lu event=%s heap=%lu low_heap=%lu stack=%lu low_stack=%lu tls_stack=%lu wifi=%d rssi=%d sid=%lu type=%u len=%u ring=%u out=%u\n",
        (unsigned long)millis(),event,(unsigned long)freeHeap,(unsigned long)memoryLowHeap,
        (unsigned long)freeStack,(unsigned long)memoryLowStack,
        (unsigned long)stack_thunk_get_max_usage(),wifiStatus,wifiRssi,(unsigned long)sid,
        incomingType,(unsigned)payloadLength,(unsigned)ringSize,(unsigned)outSize);
#endif
}

bool sendFrame(uint8_t type,uint32_t stream,const uint8_t *bytes=nullptr,size_t length=0){
    // One loop owns TLS. BearSSL handles partial TCP/TLS writes internally.
    uint8_t out[16+CHUNK];out[0]='I';out[1]='T';out[2]=1;out[3]=type;
    put32(out+4,stream);put32(out+8,length);put32(out+12,0);
    if(length)memcpy(out+16,bytes,length);
    size_t offset=0;uint32_t started=millis();
    while(offset<16+length){
        if(!tunnel.connected()||elapsed(started,5000))return false;
        size_t n=tunnel.write(out+offset,16+length-offset);
        if(!n)return false;
        offset+=n;yield();
    }
    return true;
}
bool sendJson(uint8_t type,uint32_t stream,const JsonDocument &doc){
    uint8_t bytes[512];size_t n=measureJson(doc);
    if(n>sizeof(bytes))return false;
    serializeJson(doc,bytes,sizeof(bytes));return sendFrame(type,stream,bytes,n);
}
bool codeFrame(uint8_t type,uint32_t stream,const char *code){
    StaticJsonDocument<128> doc;doc["code"]=code;return sendJson(type,stream,doc);
}
void clearTarget(){
    if(target){tcp_arg(target,nullptr);tcp_recv(target,nullptr);tcp_sent(target,nullptr);tcp_err(target,nullptr);tcp_abort(target);target=nullptr;}
    sid=0;opened=connecting=notified=false;remoteFin=localFin=writeClosed=false;
    targetError=targetEof=false;ringHead=ringSize=outHead=outSize=0;unacked=returnedCredit=0;toTargetCredit=CREDIT;closeAt=0;
}
void disconnectTunnel(const char *reason="unspecified"){
    memorySnapshot(reason);
    clearTarget();tunnel.stop();online=false;headerUsed=payloadUsed=0;protocolBad=false;
    lastSid=0;pingCount=0;
    nextAttempt=millis()+backoff+random(0,251);backoff=min(backoff*2,uint32_t(30000));
    Serial.println(F("Tunnel offline; reconnect scheduled"));
}
void resetFlow(const char *code){
    uint32_t id=sid;if(!id)return;
    clearTarget();
    if(!codeFrame(RESET,id,code)||!sendFrame(STREAM_CLOSED,id))protocolBad=true;
}

err_t targetReceive(void *,tcp_pcb *,pbuf *p,err_t error){
    if(error!=ERR_OK){targetError=true;return error;}
    if(!p){targetEof=true;return ERR_OK;}
    if(p->tot_len>CREDIT-ringSize){
        if(!lastPressureReport||elapsed(lastPressureReport,5000)){
            lastPressureReport=millis();pressureBytes=p->tot_len;pressureRingSize=ringSize;memoryPressurePending=true;
        }
        return ERR_MEM; // lwIP retains pbuf and retries
    }
    size_t tail=(ringHead+ringSize)%CREDIT;
    for(pbuf *q=p;q;q=q->next){
        const uint8_t *data=static_cast<const uint8_t*>(q->payload);
        for(size_t i=0;i<q->len;i++){ring[tail]=data[i];tail=(tail+1)%CREDIT;}
    }
    ringSize+=p->tot_len;pbuf_free(p);activity=millis();return ERR_OK;
}
err_t targetSent(void *,tcp_pcb *,u16_t count){
    if(count>unacked){targetError=true;return ERR_OK;}
    unacked-=count;returnedCredit+=count;activity=millis();return ERR_OK;
}
void targetFailed(void *,err_t){target=nullptr;targetError=true;}
err_t targetConnected(void *,tcp_pcb *,err_t error){
    connecting=false;if(error!=ERR_OK){targetError=true;return error;}
    opened=true;activity=millis();return ERR_OK;
}

bool allowed(IPAddress ip,uint16_t port){
    IPAddress net,mask;
    if(strcmp(ALLOW_NETWORK,"auto")==0){net=WiFi.localIP();mask=WiFi.subnetMask();}
    else if(!net.fromString(ALLOW_NETWORK)||!mask.fromString(ALLOW_NETMASK))return false;
    uint32_t v=uint32_t(ip),m=uint32_t(mask),n=uint32_t(net);
    return ip[0]!=0&&ip[0]!=127&&ip[0]<224&&(v&m)==(n&m)
        &&(v&~m)!=0&&(v&~m)!=~m && (ALLOW_PORT==0||port==ALLOW_PORT);
}
bool integer(JsonVariantConst v,uint32_t low,uint32_t high){return v.is<uint32_t>()&&v.as<uint32_t>()>=low&&v.as<uint32_t>()<=high;}

bool parseWindowCredit(uint32_t &creditValue){
    size_t offset=0;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    if(offset>=payloadLength||payload[offset++]!='{')return false;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    const char key[]="\"credit\"";
    for(size_t i=0;i<sizeof(key)-1;i++)if(offset>=payloadLength||payload[offset++]!=key[i])return false;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    if(offset>=payloadLength||payload[offset++]!=':')return false;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    if(offset>=payloadLength||payload[offset]<'0'||payload[offset]>'9')return false;
    if(payload[offset]=='0'&&offset+1<payloadLength&&payload[offset+1]>='0'&&payload[offset+1]<='9')return false;
    uint32_t parsed=0;
    do{
        uint32_t digit=payload[offset++]-'0';
        if(parsed>(CREDIT-digit)/10)return false;
        parsed=parsed*10+digit;
    }while(offset<payloadLength&&payload[offset]>='0'&&payload[offset]<='9');
    if(parsed==0)return false;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    if(offset>=payloadLength||payload[offset++]!='}')return false;
    while(offset<payloadLength&&(payload[offset]==' '||payload[offset]=='\t'||payload[offset]=='\r'||payload[offset]=='\n'))offset++;
    if(offset!=payloadLength)return false;
    creditValue=parsed;return true;
}

void dispatch(){
    StaticJsonDocument<1536> doc;
    if(incomingType!=DATA && incomingType!=WINDOW && incomingType!=FIN && incomingType!=STREAM_CLOSED){
        DeserializationError parseError=deserializeJson(doc,payload,payloadLength);
        if(parseError||!doc.is<JsonObject>()){
            Serial.printf("[protocol] json_invalid type=%u len=%u error=%s\n",incomingType,(unsigned)payloadLength,
                parseError?parseError.c_str():"not_object");
            memorySnapshot("json_invalid");protocolBad=true;return;
        }
    }
    if(!online){
        if(incomingType!=AUTH_OK||!integer(doc["heartbeat_sec"],30,30)||!integer(doc["timeout_sec"],90,90)
            ||!integer(doc["initial_window"],CREDIT,CREDIT)||!doc["session_id"].is<const char*>()){
            protocolBad=true;return;
        }
        online=true;onlineAt=lastPong=millis();lastPing=millis()-30000;
        memorySnapshot("tunnel_online");return;
    }
    if(incomingType==PONG){
        if(!integer(doc["nonce"],0,UINT32_MAX)){protocolBad=true;return;}
        uint32_t value=doc["nonce"];
        bool found=false;
        for(uint32_t i=0;i<pingCount;i++)if(pingNonces[i]==value){
            for(uint32_t j=i+1;j<pingCount;j++){pingNonces[j-1]=pingNonces[j];}
            --pingCount;found=true;break;
        }
        if(!found){protocolBad=true;return;}lastPong=millis();return;
    }
    if(incomingType==OPEN){
        if(incomingSid<=lastSid){protocolBad=true;return;}lastSid=incomingSid;
        if(sid){if(!codeFrame(OPEN_ERROR,incomingSid,"BUSY"))protocolBad=true;return;}
        IPAddress ip;
        if(!doc["target_ip"].is<const char*>()||!ip.fromString(doc["target_ip"].as<const char*>())
            ||!integer(doc["target_port"],1,65535)||!integer(doc["initial_window"],CREDIT,CREDIT)
            ||!integer(doc["connect_timeout_ms"],200,5000)||!integer(doc["idle_timeout_sec"],5,3600)){
            protocolBad=true;return;
        }
        uint16_t port=doc["target_port"];
        if(!allowed(ip,port)){if(!codeFrame(OPEN_ERROR,incomingSid,"DENIED"))protocolBad=true;return;}
        target=tcp_new();
        if(!target){if(!codeFrame(OPEN_ERROR,incomingSid,"UNREACHABLE"))protocolBad=true;return;}
        sid=incomingSid;credit=CREDIT;toTargetCredit=CREDIT;
        connectTimeout=doc["connect_timeout_ms"];idleMs=doc["idle_timeout_sec"].as<uint32_t>()*1000;
        connecting=true;connectAt=activity=millis();tcp_nagle_disable(target);
        tcp_arg(target,nullptr);tcp_recv(target,targetReceive);tcp_sent(target,targetSent);tcp_err(target,targetFailed);
        ip_addr_t address;IP_ADDR4(&address,ip[0],ip[1],ip[2],ip[3]);
        if(tcp_connect(target,&address,port,targetConnected)!=ERR_OK)targetError=true;
        return;
    }
    if(!sid || incomingSid!=sid){
        // Already in flight before STREAM_CLOSED reached the peer.
        if(incomingSid==lastSid&&(incomingType==WINDOW||incomingType==RESET))return;
        protocolBad=true;return;
    }
    if(incomingType==RESET){uint32_t id=sid;clearTarget();if(!sendFrame(STREAM_CLOSED,id))protocolBad=true;return;}
    if(!opened){protocolBad=true;return;}
    switch(incomingType){
    case DATA:{
        if(remoteFin||payloadLength>toTargetCredit){protocolBad=true;return;}
        if(payloadLength>CREDIT-outSize){protocolBad=true;return;}
        for(size_t i=0;i<payloadLength;i++)outbound[(outHead+outSize+i)%CREDIT]=payload[i];
        outSize+=payloadLength;toTargetCredit-=payloadLength;activity=millis();break;
    }
    case WINDOW:{
        uint32_t returnedCreditValue;
        if(!parseWindowCredit(returnedCreditValue)||credit+returnedCreditValue>CREDIT){
            Serial.printf("[protocol] window_invalid len=%u credit=%u\n",(unsigned)payloadLength,(unsigned)credit);
            memorySnapshot("window_invalid");protocolBad=true;return;
        }
        credit+=returnedCreditValue;break;
    }
    case FIN:
        if(remoteFin){protocolBad=true;return;}remoteFin=true;if(!closeAt)closeAt=millis();break;
    default:protocolBad=true;
    }
}
void readTunnel(){
    // Bounded work per loop leaves time for LAN callbacks and heartbeats.
    for(int frames=0;frames<8 && tunnel.available();){
        if(headerUsed<16){
            int n=tunnel.read(header+headerUsed,16-headerUsed);if(n<=0)return;headerUsed+=n;
            if(headerUsed<16)return;
            incomingType=header[3];incomingSid=read32(header+4);payloadLength=read32(header+8);
            bool control=incomingType<=3||incomingType>=48;
            if(header[0]!='I'||header[1]!='T'||header[2]!=1||read32(header+12)!=0||!known(incomingType)
                ||(control!=(incomingSid==0))||payloadLength>(incomingType==DATA?CHUNK:512)
                ||(incomingType==DATA&&payloadLength==0)||((incomingType==FIN||incomingType==STREAM_CLOSED)&&payloadLength)){
                protocolBad=true;return;
            }
        }
        if(payloadUsed<payloadLength){int n=tunnel.read(payload+payloadUsed,payloadLength-payloadUsed);if(n<=0)return;payloadUsed+=n;}
        if(payloadUsed<payloadLength)return;
        dispatch();headerUsed=payloadUsed=0;++frames;if(protocolBad)return;
    }
}
void serviceTarget(){
    if(!sid)return;
    if(targetError|| (connecting&&elapsed(connectAt,connectTimeout))){
        if(!opened){uint32_t id=sid;bool timed=connecting&&!targetError;clearTarget();if(!codeFrame(OPEN_ERROR,id,timed?"CONNECT_TIMEOUT":"REFUSED"))protocolBad=true;}
        else {resetFlow("TARGET_IO");}
        return;
    }
    if(!opened)return;
    // OPEN_OK must precede any data. Callback only sets flags, never touches TLS.
    if(!notified){StaticJsonDocument<96> doc;doc["initial_window"]=CREDIT;
        if(!sendJson(OPEN_OK,sid,doc)){protocolBad=true;return;}notified=true;}
    if(!target||targetError)return;
    if(returnedCredit){uint32_t n=returnedCredit;returnedCredit=0;toTargetCredit+=n;
        StaticJsonDocument<96> doc;doc["credit"]=n;if(!sendJson(WINDOW,sid,doc)){protocolBad=true;return;}}
    if(ringSize&&credit){
        size_t n=min(min(ringSize,size_t(credit)),min(CHUNK,CREDIT-ringHead));
        if(!sendFrame(DATA,sid,ring+ringHead,n)){protocolBad=true;return;}
        ringHead=(ringHead+n)%CREDIT;ringSize-=n;credit-=n;
        if(target)tcp_recved(target,n);
    }
    if(outSize&&target){
        size_t n=min(min(outSize,CREDIT-outHead),size_t(tcp_sndbuf(target)));
        if(n){
            err_t e=tcp_write(target,outbound+outHead,n,TCP_WRITE_FLAG_COPY);
            if(e==ERR_OK){outHead=(outHead+n)%CREDIT;outSize-=n;unacked+=n;tcp_output(target);}
            else if(e!=ERR_MEM){resetFlow("TARGET_IO");return;}
        }
    }
    if(remoteFin&&!writeClosed&&unacked==0&&outSize==0){
        err_t e=tcp_shutdown(target,0,1);if(e==ERR_OK)writeClosed=true;else if(e!=ERR_MEM){resetFlow("TARGET_IO");return;}
    }
    if(targetEof&&!ringSize&&!localFin){
        if(!sendFrame(FIN,sid)){protocolBad=true;return;}localFin=true;if(!closeAt)closeAt=millis();
    }
    if(localFin&&writeClosed&&unacked==0&&target){
        // Do not let callbacks reference a PCB that tcp_close frees.
        tcp_recv(target,nullptr);tcp_sent(target,nullptr);tcp_err(target,nullptr);
        err_t e=tcp_close(target);
        if(e==ERR_OK){target=nullptr;uint32_t id=sid;clearTarget();if(!sendFrame(STREAM_CLOSED,id))protocolBad=true;}
        else {tcp_recv(target,targetReceive);tcp_sent(target,targetSent);tcp_err(target,targetFailed);}
    }
    if(sid && closeAt&&elapsed(closeAt,10000))resetFlow("CLOSE_TIMEOUT");
    else if(sid&&elapsed(activity,idleMs))resetFlow("IDLE_TIMEOUT");
}
void connectTunnel(){
    Serial.println(F("Connecting TLS tunnel"));
    memorySnapshot("tls_connect_start");
    tunnel.setTrustAnchors(&trust);tunnel.setSSLVersion(BR_TLS12,BR_TLS12);
    // Keep the default receive buffer: a 1 KiB app frame does NOT cap TLS records.
    tunnel.setTimeout(5000);
    IPAddress address;
    // BearSSL validates dNSName, not iPAddress SAN. For an IP endpoint use ONLY
    // this server's exact self-signed cert as trust anchor (not a broad CA).
    bool connected=address.fromString(TUNNEL_HOST)
        ? tunnel.connect(address,TUNNEL_PORT) : tunnel.connect(TUNNEL_HOST,TUNNEL_PORT);
    if(!connected){Serial.printf("TLS failure code=%d\n",tunnel.getLastSSLError());disconnectTunnel("tls_failure");return;}
    memorySnapshot("tls_connected");
    StaticJsonDocument<768> doc;
    doc["device_id"]=DEVICE_ID;doc["credential"]=DEVICE_KEY;doc["firmware"]="esp8266-tunnel-v1";
    doc["lan_ip"]=WiFi.localIP().toString();doc["netmask"]=WiFi.subnetMask().toString();doc["gateway"]=WiFi.gatewayIP().toString();
    if(!sendJson(AUTH,0,doc)){disconnectTunnel("auth_send_failure");return;}connectAt=millis();
}
void startSetup(){
    setupMode=true;WiFi.mode(WIFI_AP_STA);
    String name=String("Gateway-")+String(ESP.getChipId(),HEX);
    WiFi.softAP(name.c_str(),"setup8266");
    setupWeb.on("/",HTTP_GET,[]{
        if(setupWeb.client().localIP()!=WiFi.softAPIP()){
            setupWeb.send(403,"text/plain","Connect to setup hotspot");return;
        }
        setupWeb.sendHeader("Cache-Control","no-store");
        setupWeb.send(200,"text/html; charset=utf-8",
            "<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<h2>ESP8266 Wi-Fi 配置</h2><form action='/save' method='post'>"
            "<p>Wi-Fi 名称 <input name='ssid' maxlength='32' required></p>"
            "<p>Wi-Fi 密码 <input name='password' type='password' maxlength='64'></p>"
            "<button>保存并重启</button></form>");
    });
    setupWeb.on("/save",HTTP_POST,[]{
        if(setupWeb.client().localIP()!=WiFi.softAPIP()){
            setupWeb.send(403,"text/plain","Connect to setup hotspot");return;
        }
        if(setupWeb.header("Origin").length() && setupWeb.header("Origin")!="http://192.168.4.1"){
            setupWeb.send(403,"text/plain","Invalid origin");return;
        }
        String ssid=setupWeb.arg("ssid"),password=setupWeb.arg("password");
        if(ssid.length()==0||ssid.length()>32||password.length()>64){
            setupWeb.send(400,"text/plain","Invalid Wi-Fi settings");return;
        }
        StaticJsonDocument<256> doc;doc["ssid"]=ssid;doc["password"]=password;
        File file=LittleFS.open("/wifi.json","w");
        if(!file){setupWeb.send(500,"text/plain","Cannot save configuration");return;}
        serializeJson(doc,file);file.close();
        setupWeb.send(200,"text/html; charset=utf-8","已保存，设备正在重启。请切回原 Wi-Fi。");
        delay(300);ESP.restart();
    });
    setupWeb.collectHeaders("Origin");
    setupWeb.begin();
    Serial.printf("Setup AP: %s; open http://192.168.4.1; hold FLASH 5 seconds to reconfigure\n",name.c_str());
}
void setup(){
    Serial.begin(115200);pinMode(0,INPUT_PULLUP);ESP.resetFreeContStack();memorySnapshot("boot");
    WiFi.persistent(false);WiFi.mode(WIFI_STA);WiFi.setAutoReconnect(true);
    if(!LittleFS.begin()){startSetup();return;}
    File saved=LittleFS.open("/wifi.json","r");
    if(saved){
        StaticJsonDocument<256> doc;
        if(!deserializeJson(doc,saved)){
            configuredSsid=doc["ssid"].as<String>();configuredPassword=doc["password"].as<String>();
        }
        saved.close();
    }
    if(configuredSsid.length()==0 && strcmp(WIFI_SSID,"YOUR_WIFI")!=0){
        configuredSsid=WIFI_SSID;configuredPassword=WIFI_PASSWORD;
    }
    if(configuredSsid.length()==0){startSetup();return;}
    WiFi.begin(configuredSsid.c_str(),configuredPassword.c_str());randomSeed(ESP.getCycleCount());
    // Real time is required for X.509 validity checking; no insecure fallback.
    configTime(0,0,"pool.ntp.org","time.cloudflare.com");
}

void loop(){
    sampleMemory();
    if(memoryPressurePending){
        memoryPressurePending=false;
        Serial.printf("[buffer] rx_backpressure incoming=%u ring=%u\n",(unsigned)pressureBytes,(unsigned)pressureRingSize);
        memorySnapshot("rx_backpressure");
    }
    static uint32_t pressedAt=0;
    if(digitalRead(0)==LOW){
        if(!pressedAt)pressedAt=millis();
        if(!setupMode&&elapsed(pressedAt,5000)){disconnectTunnel("setup_button");startSetup();}
    }else pressedAt=0;
    if(setupMode){setupWeb.handleClient();delay(1);return;}
    if(WiFi.status()!=WL_CONNECTED){if(tunnel.connected()||sid)disconnectTunnel("wifi_disconnected");delay(10);return;}
    if(time(nullptr)<1700000000){delay(10);return;}
    if(!tunnel.connected()){
        if(online||sid)disconnectTunnel("tunnel_socket_closed");
        if(int32_t(millis()-nextAttempt)>=0){connectTunnel();}
        delay(1);return;
    }
    readTunnel();serviceTarget();
    if(online){
        if(elapsed(lastMemoryReport,60000)){lastMemoryReport=millis();memorySnapshot("periodic");}
        if(elapsed(onlineAt,60000))backoff=1000;
        if(elapsed(lastPong,90000)){disconnectTunnel("pong_timeout");return;}
        if(elapsed(lastPing,30000)){
            if(pingCount>=4){disconnectTunnel("ping_queue_full");return;}
            StaticJsonDocument<96> doc;doc["nonce"]=++nonce;
            pingNonces[pingCount++]=nonce;lastPing=millis();if(!sendJson(PING,0,doc))protocolBad=true;
        }
    }else if(elapsed(connectAt,10000))protocolBad=true;
    if(protocolBad){disconnectTunnel("protocol_error");}
    delay(1);
}
