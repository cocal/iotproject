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

设备必须先写入 `devices` 表，`secret_sha256` 为设备密钥 UTF-8 字节的 SHA-256 小写十六进制值。当前代码中设备使用 `X-Device-Key` 调用 `/api/v1/devices/{deviceId}/poll`，当前租约时长为 120 秒。架构决策是后续增加 ESP8266 主动建立的打印专用 TLS TCP 隧道（建议独立端口 `9443`）作为主连接，承载打印任务、心跳和结果回传；隧道网关尚未实现。轮询接口保留作打印任务恢复通道，不部署 MQ，也不提供通用内网代理。

租约过期由后台定时任务回收，默认每 30 秒执行；每个任务最多投递 3 次，超过后进入 `failed`。
