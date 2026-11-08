# Remote Server

Java 17 / Spring Boot 服务端，负责外包旧 TCP 协议接收、任务持久化、渲染队列、设备通信和管理 API。

## 当前协议

- 兼容接收端口：`9100`
- 编码：GBK
- 报文：按行文本；连接关闭或读到包含 `地址` 的行时形成单据
- 任务原文和解析结果都写入 PostgreSQL 的 `print_jobs` 表

## 本地构建

```bash
mvn test
```

数据库迁移位于 `src/main/resources/db/migration/`。数据库连接和 9100 端口配置在 `application.yml` 中，可通过环境变量覆盖。

启动前需要一个 PostgreSQL 数据库（默认 `printer` 用户/密码、数据库名 `printer`）：

```bash
mvn spring-boot:run
```

HTTP 提交接口要求 `PRINT_API_KEY`，请求使用 `X-API-Key` 和 `Idempotency-Key` 请求头。`docker compose up --build` 启动时必须先设置 `PRINT_API_KEY`。

```json
POST /api/v1/print-commands
{
  "idempotencyKey": "order-10001",
  "templateId": "receipt-xprinter-v1",
  "targetDeviceId": "printer-room-1",
  "data": {
    "customer": "客户",
    "invoiceNo": "A10001",
    "items": ["商品 2 件 10.00 20.00"],
    "total": "20.00"
  }
}
```

9100 收到单据后会保存原文、解析字段并生成 ESC/POS 产物。可通过 `GET /api/v1/print-jobs/{id}` 查询任务，或通过 `GET /api/v1/print-jobs/{id}/artifact` 下载字节流。生产环境应将查询和产物接口置于可信网络或反向代理认证之后。

设备必须先写入 devices 表，secret_sha256 为独立设备密钥 UTF-8 字节的 SHA-256 小写十六进制值。当前已实现 X-Device-Key 轮询路径及默认 120 秒租约，租约每 30 秒回收、最多投递 3 次；这是旧设备路径。

新方案为通用反向 TLS TCP 网关（计划 9443），协议见 ../PROTOCOL.md。VPS 通过 OPEN 指定目标，再传输原始 DATA；设备不接收打印任务或执行协议探测。VPS 负责打印调度、扫描和结果判定，首版每设备一条活动目标流。网关和 Linux 参考客户端已实现首版，业务集成待完成；新路径不能沿用“流关闭即打印完成”或旧轮询的自动重试语义，不部署 MQ。

## 通用 TCP 隧道：第一版已实现

网关使用 JDK JSSE TLS 1.2，与 ESP8266 BearSSL 预期版本兼容；复用数据库的设备独立凭据认证。默认 `printer.tunnel.enabled=false`，不会自动监听新端口。`TunnelGateway` 维护内存在线会话和单流状态，支持 16 字节帧头、1 KiB DATA、4 KiB 双向信用窗口、PING/PONG、FIN 半关闭和 RESET。新连接会替换同设备旧会话；断线丢弃流，不透明重放数据。

### 配置与启动

需要 JDK 17、Maven、PostgreSQL。先准备 PKCS12 服务端证书，证书 SAN 必须匹配客户端使用的 VPS 域名。可以把已有 PEM 证书和私钥转换为 PKCS12；转换文件放仓库外，权限限制为服务进程可读，口令通过环境变量设置。证书续期后需要重新转换并重启网关，当前没有自动重载。

参考 `tunnel-example.yml`：TLS 入口默认 9443，映射入口固定只绑定 `127.0.0.1`。映射中的设备 ID 必须在 devices 表存在，目标是明确授权的 IPv4/端口；设备客户端也必须允许对应网段和端口。

```bash
export TUNNEL_KEY_STORE=/secure/path/tunnel.p12
# 设置 TUNNEL_KEY_STORE_PASSWORD、数据库变量和 PRINT_API_KEY 后启动
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.additional-location=file:./tunnel-example.yml"
```

在可访问打印机内网的 Linux 主机启动 `../device/simulator/client.py`，具体参数见其 README。当设备在线时，VPS 本机连 `127.0.0.1:19100` 会触发 OPEN 至示例 `192.168.1.50:9100`，后续字节原样双向转发。不要用真实打印机做随意文本探测；先用 echo 目标验证。

离线、单流忙或连接失败时，本机入口关闭连接；当前不提供自动排队、SOCKS5、Web 扫描接口或自动打印投递。任务调度、动态 Web 目标管理和打印结果判定待接入。在线状态目前在进程内存中，未接后台设备列表；设备固件尚未实现。

### 测试

```bash
mvn test
```

测试生成临时证书，不依赖数据库，使用 Linux 参考客户端跨语言验证真实 TLS、256 KiB 二进制双向透传、EOF 后响应、连续新流、心跳、单流忙、鉴权失败、旧会话替换、非法信用和超长帧。信用窗口耗尽时仍须响应心跳；不信任的 TLS 证书必须被拒绝。测试需要 Python 3 和非回环 IPv4 网卡。

9100 接收的单据写入 print_jobs：保留按行重建的原文、GBK 编码标记、解析字段、接收时间、渲染产物与状态；V4 起新增 source_ip (PostgreSQL INET)，记录 TCP socket 实际对端 IP，GET 任务详情返回 sourceIp。历史记录无法回填，保持 NULL；若经 TCP 中转/NAT，记录的是本服务看到的中转/出口地址，不信任单据自行声明的来源。
