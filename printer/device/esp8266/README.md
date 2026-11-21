# ESP8266 通用 TCP 网关

主程序：src/main.cpp。PlatformIO 固定 espressif8266 4.2.1 / Arduino Core 3.1.2 / ArduinoJson 6.21.5；默认 NodeMCU ESP-12E、4MB Flash、160MHz、115200 串口。代码已编译通过，尚无实物板联调，不表示性能或长期稳定性已验收。

Windows 7 32 位交付采用 Arduino IDE 1.8.19 的 .ino + 完整 ZIP，详见 ARDUINO-WIN7.md。tools/package_arduino.py 复用主程序生成同名 sketch，把所需 ArduinoJson 头文件一起打包，无需安装额外库。已用 Linux 上的 Arduino 1.8.19 arduino-builder + Core 3.1.2 编译通过，未在 Windows 7 真机上测试。公开模板不含密钥；--config 可生成私有专属包，禁止把它放公开站点。

## 构建

复制 include/config.example.h 为 include/config.h，填写 Wi-Fi、独立设备凭据、允许的 LAN 网段/端口。config.h 已被 Git 忽略；不能把设备密钥提交到仓库或公开分发站点。示例内嵌的是服务器公开信任证书，不含私钥。

```bash
pio run
pio run -t upload --upload-port /dev/ttyUSB0
pio device monitor --port /dev/ttyUSB0 --baud 115200
```

输出为 .pio/build/nodemcuv2/firmware.bin。编译时配置会进入 BIN；含占位凭据的构建不可用于实际联网。其他板型/Flash 容量要修改 platformio.ini 后重新编译。

## 连接与限制

固件仅做 TLS/设备认证、OPEN 指定目标、DATA 原样转发、信用流控、半关闭、心跳与重连。LAN 目标使用异步 lwIP TCP，VPS 连接使用 BearSSL；目标内存不足时背压，设备不存整份文件、不处理打印任务、不扫描或识别协议。设备 ID/密钥需先在 VPS devices 表注册。当前服务器映射配置为空；上线不等于已绑定打印机或自动打印。

实际目标仅保存在私有构建配置，公开源码 TUNNEL_HOST 使用占位值；服务端口为 9443。BearSSL 的名称验证针对 DNS SAN；数字 IP 模式通过 IPAddress 重载连接，使用指定服务器的精确自签证书作唯一信任锚，不执行 DNS 名称校验。不得把此模式的信任锚替换为宽泛公有 CA。未来配置匹配的 DNS 记录时，可用域名模式同时执行链与名称校验；没有 setInsecure 回退。

设备需要 NTP 时间验证证书有效期；无法获取时间时等待，不忽略证书验证。保留 BearSSL 默认接收缓冲，因为 1KiB 应用帧不代表 TLS 记录不超过 1KiB。握手和 TLS write 可能短时阻塞，需上板验证心跳延迟、剩余堆、watchdog、断线与半关闭。当前 ArduinoJson 控制帧解析不单独检测重复键，不能将未认证来源直接接入设备控制流。

不支持 UDP、UART/USB 打印机，首版同时一个 TCP 目标。打印渲染、扫描、任务调度、结果确认在 VPS。

## 单文件交付版

设备 ID 为 gateway-esp8266-1 的私有构建已内嵌凭据及 独立隧道公开信任证书，输出交付到工作区 artifacts；脱敏 Arduino ZIP 的 TUNNEL_HOST 需自行填写；BIN 含独立设备密钥，不入 Git 或公共文档站点。只对应一块设备，烧录到多块会导致同 ID 会话相互接管。

首次无 Wi-Fi 配置时启动 Gateway-<芯片ID> 热点，密码 setup8266。手机连接后手动打开 http://192.168.4.1，填写 2.4GHz Wi-Fi 名称/密码，保存后自动重启，配置保存在 LittleFS；长按 FLASH（GPIO0）5 秒重新配网（正常启动后操作，不在上电时按住）。配网页只允许从 AP 接口访问；正常模式关闭热点和配网页。NTP 时间同步成功后才尝试 TLS。

交付版 ALLOW_NETWORK=auto，限制为 Wi-Fi 当前 IP/掩码所在子网，拒绝网络/广播、回环、组播及未指定地址；ALLOW_PORT=0 表示允许该网段 TCP 端口。VPS 仍需明确配置映射，不会自动扫描或发打印任务。

NodeMCU / ESP-12E、4MB Flash 使用 UART 下载工具在地址 0x00000 写入 firmware.bin，DIO、40MHz、4MB；源配置采用 160MHz CPU。不要把 CPU 频率与 Flash 时钟混淆。可执行：

```bash
python -m esptool --chip esp8266 --port COM3 --baud 115200 write_flash --flash_mode dio --flash_freq 40m --flash_size 4MB 0x0 gateway-esp8266-1-nodemcu-4mb.bin
```

将 COM3 换成实际串口。此命令不要求额外 bootloader/分区文件。其他板型尤其 ESP-01 1MB 不要直接使用此构建。当前只有交叉编译验证，配网、lwIP 半关闭、证书和 Wi-Fi 仍需实物联调；不是已通过实机验收的固件。

2026-11-08 隧道切换到独立的 30 年自签证书（到 2056-11-08），新固件与私有源码包已更新信任证书；旧 BIN 不再匹配，必须重新烧录。Nginx 保持原证书和配置。公开 config.example.h 已内置独立隧道的公开证书；地址与设备凭据仍是占位值，使用前填写。

## 运维排查记录

2026-11-17：服务端日志显示 `sh-pudong` 隧道于当天 00:10 左右断开，之后未见重新上线记录。自动休眠或 Wi-Fi 省电目前只是排查假设，尚未确认；也需排除断电、供电不稳、Wi-Fi/AP 断连或网络变化。当前固件源码未发现显式调用休眠 API，但尚未核实 ESP8266 Core/SDK 的 Wi-Fi 省电行为及实机运行状态。下次接触设备时检查串口 uptime、Wi-Fi 断连原因和重连日志；可在测试时关闭 Wi-Fi 省电并保持设备供电，观察隧道是否持续在线，再与默认配置对比。

2026-11-21：串口诊断以 `[mem]`、`[protocol]`、`[buffer]` 开头。现代 PlatformIO 构建每 5 秒采样堆和连续栈低水位、每分钟输出堆空闲量/最大连续空闲块/碎片率/连续栈余量及 TLS stack-thunk 历史峰值，并在启动、TLS连接、协议解析失败、断线或接收缓冲背压时输出快照；使用 `pio device monitor --port <串口> --baud 115200` 查看。最大连续块和碎片率由 `-DUMM_INFO` 提供；`stack` 是 Arduino 连续任务栈余量，`tls_stack` 是 TLS stack-thunk 已使用峰值。旧 TLS1.0 兼容固件只记录堆指标。
