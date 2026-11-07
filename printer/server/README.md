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

新方案为通用反向 TLS TCP 网关（计划 9443），协议见 ../PROTOCOL.md。VPS 通过 OPEN 指定目标，再传输原始 DATA；设备不接收打印任务或执行协议探测。VPS 负责打印调度、扫描和结果判定，首版每设备一条活动目标流。隧道尚未实现；新路径不能沿用“流关闭即打印完成”或旧轮询的自动重试语义，不部署 MQ。
