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

网关使用 JDK JSSE TLS 1.2，与 ESP8266 BearSSL 预期版本兼容；复用数据库的设备独立凭据认证。默认 `printer.tunnel.enabled=false`，不会自动监听新端口。`TunnelGateway` 维护内存在线会话，每设备最多承载 3 条并行 TCP 流，支持 16 字节帧头、1 KiB DATA、4 KiB 双向信用窗口、PING/PONG、FIN 半关闭和 RESET。协议解码兼容 JSON v1 和生产设备使用的紧凑二进制 v2（AUTH、心跳遥测、OPEN/窗口控制）。新连接会替换同设备旧会话；断线丢弃流，不透明重放数据。

### 配置与启动

需要 JDK 17、Maven、PostgreSQL。先准备 PKCS12 服务端证书，证书 SAN 必须匹配客户端使用的 VPS 域名。可以把已有 PEM 证书和私钥转换为 PKCS12；转换文件放仓库外，权限限制为服务进程可读，口令通过环境变量设置。证书续期后需要重新转换并重启网关，当前没有自动重载。

参考 `tunnel-example.yml`：TLS 入口默认 9443，映射入口固定只绑定 `127.0.0.1`。每条映射把本机端口绑定到一个已认证的 `device-id`，再由该设备连接其局域网内的 `target-ip:target-port`。因此多台 ESP8266 使用不同 `device-id` 和本机映射端口；即使各自内网用了相同网段，连接仍会按映射选择正确网关。不同设备可以各自同时承载一条活动目标流，同一设备仍限制为一条活动流。映射中的设备 ID 必须在 devices 表存在，目标是明确授权的 IPv4/端口；设备客户端也必须允许对应网段和端口。本机映射端口不能重复，启动日志会打印完整的“本机端口 -> 设备 -> 目标地址”关系。

```bash
export TUNNEL_KEY_STORE=/secure/path/tunnel.p12
# 设置 TUNNEL_KEY_STORE_PASSWORD、数据库变量和 PRINT_API_KEY 后启动
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.additional-location=file:./tunnel-example.yml"
```

在可访问打印机内网的 Linux 主机启动 `../device/simulator/client.py`，具体参数见其 README。当设备在线时，VPS 本机连 `127.0.0.1:19100` 会触发 OPEN 至示例 `192.168.1.50:9100`，后续字节原样双向转发。不要用真实打印机做随意文本探测；先用 echo 目标验证。

离线、单流忙或连接失败时，本机入口关闭连接；当前不提供通用 SOCKS5 或 Web 扫描接口。在线状态目前在进程内存中，未接后台设备列表。自动投递保持关闭，需由后台用户显式触发测试打印或重打。

控制台管理动作通过独立 `X-Console-Key` 内部 API 转发，不复用只读数据库账号。配置 `printer.console-admin.api-key` 与扫描网段白名单 `printer.console-admin.allowed-cidrs` 后，可按指定 IPv4 网段探测常见打印端口、发送独立测试小票或重打某任务。重打图片模式从数据库读取原文，在 Java 服务端按参考小票排版渲染；预览与打印共用相同绘图逻辑，浏览器请求不上传图片。默认排版参数为店名 30、居中标题 38、正文 26、左对齐合计/实收金额 30；这些是 Java 字体字号值，不代表打印机物理点数。店名、正文与金额默认常规字重，仅标题加粗。输出使用通用 ESC/POS 1-bit raster，省略空白行并直接编码位图，避免 PNG 中转。渲染会保留商品表头、首项和末尾地址，过滤仓库及空操作员行；预览与打印使用同一渲染逻辑。打印、重打及预览日志包含渲染耗时、发送耗时、总耗时和数据量。运行图片模式需要安装 `Noto Sans CJK SC`；原文超过 200,000 字符时拒绝图片渲染。只有 RAW 9100 支持测试打印/重打；扫描发现端口只代表 TCP 可连通，不保证识别为打印机。所有动作写入 `printer_admin_actions` 审计表。打印操作返回“已发送/结果未知”，不等于确认出纸。

**2026-11-22 打印乱码排查记录：** 52,208 字节驱动渲染任务最初因发送侧只等打印机 750 ms 响应而在约 1.8 秒后发生 `TARGET_IO`；修复等待窗口确认后，设备已完整接收负载并正常发送 `FIN`、`CLOSED`，但服务端仍因窗口计数与关闭帧的到达竞态报 `delivery_unknown`。ESP8266 使用 4 KiB 窗口、最多 1 KiB DATA 分帧，并在目标 TCP ACK 后返还 `WINDOW` 额度；固件在所有目标数据 TCP ACK 后才正常关闭流。服务端现在以额度归还完毕或干净的设备 `FIN`+`CLOSED` 作为传输确认，`RESET` 和超时仍失败。打印机回传内容在打印操作中被丢弃并正常归还接收窗口。TCP ACK/干净流关闭只能确认数据到达打印机 TCP 栈，不能确认打印机解析成功或实际出纸；控制台仍显示“已发送/结果未知”。无需更新 ESP8266 固件。

驱动实验模式以相同排版预览生成 PNG，再通过 CUPS 转成 XP-80 PPD 对应的 CUPS raster，并调用 `rastertosnailep-xprinter` 输出打印字节流。可用 `printer.render.driver.cupsfilter`、`printer.render.driver.filter`、`printer.render.driver.ppd` 覆盖默认路径。控制台预览显示的是驱动接收的排版图，不是打印机回读的纸面结果；该模式标记为实验，不作为默认方案。调用驱动转换不会启动 CUPS 队列或自动打印。

### 测试

```bash
mvn test
```

测试生成临时证书，不依赖数据库，使用 Linux 参考客户端跨语言验证真实 TLS、256 KiB 二进制双向透传、EOF 后响应、连续新流、心跳、单流忙、鉴权失败、旧会话替换、非法信用和超长帧；另覆盖 52,208 字节打印负载在延迟窗口确认后仍完整送达。信用窗口耗尽时仍须响应心跳；不信任的 TLS 证书必须被拒绝。测试需要 Python 3 和非回环 IPv4 网卡。

9100 接收的单据写入 print_jobs：保留按行重建的原文、GBK 编码标记、解析字段、接收时间、渲染产物与状态；V4 起新增 source_ip (PostgreSQL INET)，记录 TCP socket 实际对端 IP，GET 任务详情返回 sourceIp。历史记录无法回填，保持 NULL；若经 TCP 中转/NAT，记录的是本服务看到的中转/出口地址，不信任单据自行声明的来源。

## 打印控制台

当前服务器已部署只读后台 `https://printer.ohmyskills.top/console/`。登录后可分页查看 `print_jobs` 的接收时间、单号、客户、TCP 来源 IP、状态、渲染产物大小，并打开原文和解析结果详情。筛选支持单号/客户、来源 IP、状态和北京时间日期范围。

控制台是独立 Python/Flask 进程，只通过现有 WireGuard 到 192 服务器 PostgreSQL `10.79.0.2` 访问；数据库账号为只读并设置了 5 秒查询超时。Nginx 仅反代 `/console/`，应用绑定 `127.0.0.1:18082`。管理密码不写仓库，保存在当前服务器 `/etc/printer-console/admin.json`，运行密钥在 `/etc/printer-console/service.env`。

```bash
systemctl status printer-console
journalctl -u printer-console -n 100 --no-pager
```

### 2026-11-29 报文预处理与串行投递

9100 接收后保留 `raw_payload` 原文，`LegacyReceiptPreprocessor` 统一清理每行首尾不可见字符并生成解析对象；`itemRows` 将商品名称与后续数量/单价/金额行合并成结构化明细。客户名称缺失不再单独触发废弃。原文重打仍使用原始报文。

接收、校验和数据库记录完成后，渲染任务交给单核心 FIFO 后台线程。生成的任务仍由 ESP8266 轮询领取；数据库 advisory transaction lock 与活动任务检查限制全局同一时刻只有一个未完成设备任务，完成或租约回收后才发下一单。表格商品名固定单行显示（超出列宽截断），商品字号 28、常规字重；数量和单价列分别右移约 3 个、1 个汉字宽度。单号、时间、客户/用户名采用独立的常规字号。图片/驱动格式的手动重打默认附加“打印单据时间”，控制台可取消；原文/原生文本模式保留原始输出。
