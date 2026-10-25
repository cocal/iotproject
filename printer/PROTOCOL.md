# 外包打印接收协议

## 当前入口

- 传输：TCP
- 监听：`0.0.0.0:9100`
- 编码：GBK
- 消息格式：按行读取的文本，不是 JSON
- 一次 TCP 连接：一份单据
- 结束条件：客户端关闭连接；兼容 JAR 也会在读到包含 `地址` 的行时触发处理

服务端必须保存原始行列表。解析失败时不能丢弃原始报文，任务应进入 `render_failed` 并允许人工重试。

## 已确认的旧字段

旧源码和 `IP80C9600.jar` 中出现的业务字段包括：

- 序号
- 时间
- 客户/往来单位
- 清单号/单号
- 品名/商品明细
- 数量
- 单位
- 单价
- 金额
- 合计金额
- 实收
- 备注
- 地址

旧 JAR 的销售单逻辑以关键字定位字段，而不是固定行号。已确认的关键字包括 `品名`、`合计金额`、`地址`、`实收`、`会员` 和 `往来单位`。商品明细会从 `品名` 行之后读取，直到合计相关行。

## 兼容边界

1. 9100 接收器只负责收包、保存原文和基础解析，不直接打印。
2. 解析出的字段进入统一 `PrintJob`，后续由芯烨 ESC/POS 渲染器处理。
3. 新接口和设备接口使用 UTF-8/JSON，不复用 GBK TCP 报文。
4. 旧 JAR 的默认端口 9600 不作为现网监听端口；如需迁移兼容，可通过配置增加第二监听器。

## 设备接口

当前实现的设备接口使用 HTTPS/JSON，并在每个请求携带 `X-Device-Key`。数据库只保存密钥的 SHA-256，不保存明文。轮询返回短期租约、产物大小和 SHA-256；设备依次上报 `received`、`started` 和 `completed`/`failed`。它也是专用设备连接不可用时的恢复通道。

## 反向 TCP 隧道方案

首版设备通信决策是 ESP8266 主动连接 VPS 的独立 TLS TCP 端口（建议 `9443/TCP`），不部署 MQ，不开放设备入站端口。设备隧道既承载打印任务，也支持经认证、按目标策略授权的通用内网 TCP 代理。当前仓库尚未实现该连接网关；实现后，HTTPS 轮询仍保留作打印任务恢复通道，不承载代理流。

长连接使用带长度边界的应用层消息帧，至少包含设备 ID、消息类型、帧长度、stream/task ID 和租约 ID。设备上线时完成独立凭据鉴权并发送固件/打印机能力；双方使用心跳检测断线。任务帧包含产物长度、分块序号和 SHA-256，设备校验后发送应用层 `received` ACK，开始打印前发送 `started`，完成或失败后发送 `completed`/`failed`。TCP ACK 只表示网络栈收到字节，不能代替业务确认。

## 反向 TCP 隧道协议

ESP8266 主动连接 VPS 的 `9443/TCP`，完成 TLS 服务端证书校验和每设备独立凭据鉴权。该连接承载打印任务、设备心跳、打印结果和受控的反向 TCP 代理流。首版每台设备只允许一个活动代理流，以控制 ESP8266 的内存和 socket 占用。

所有帧使用长度边界和消息类型。控制帧建议为：

```text
AUTH
AUTH_OK
OPEN      {stream_id, target_ip, target_port}
OPEN_OK   {stream_id}
OPEN_ERROR {stream_id, error_code}
DATA      {stream_id, bytes}
CLOSE     {stream_id}
RESET     {stream_id, error_code}
TASK_BEGIN {task_id, lease_id, size, sha256, format}
TASK_DATA  <ESC/POS binary chunk>
TASK_END
EVENT      {received|started|completed|failed}
PING / PONG
```

VPS 收到已认证的代理请求后发送 `OPEN`；ESP8266 校验本地目标策略并主动连接内网目标，成功后返回 `OPEN_OK`，双方通过 `DATA` 双向转发。首版只接受配置的 IPv4 网段/主机和端口，设置单流、帧大小、空闲超时和速率限制。

ESP8266 完整接收并校验打印任务后发送 `received`，开始向固定配置的 UART 或打印机网络接口输出前发送 `started`，输出结束后发送 `completed`/`failed`。TCP ACK 只表示网络栈收到字节，不能代替业务确认。打印任务继续使用任务 ID、租约和至少一次投递语义。代理流断开时释放目标 socket，不创建或重试 `PrintJob`。
