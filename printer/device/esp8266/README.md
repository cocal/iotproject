# ESP8266 通用 TCP 网关（待实现）

固件只做 TLS/认证、按 VPS OPEN 的目标 IPv4/端口建立 TCP、DATA 原样双向转发、信用流控、心跳与重连。首版同时一个活动目标连接，支持半关闭；不处理任务、文件、ESC/POS、IPP 或扫描协议。

打印、扫描和设备识别都在 VPS；扫描复用普通 OPEN/DATA，没有 PROBE 帧。网络目标可动态指定，不写死打印机 IP。

协议以 [PROTOCOL.md](../../PROTOCOL.md) 的 v1 为准。当前目录尚无固件；建议使用 ESP8266 Arduino Core BearSSL WiFiClientSecure，实机验证 TLS 内存、非阻塞建连、半关闭、窗口耗尽时心跳、watchdog 与重连稳定性。
