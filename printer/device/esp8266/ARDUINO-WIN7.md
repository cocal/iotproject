# Windows 7 32 位：ESP8266 隧道烧录

交付格式与 IoT 文档的 Arduino 程序相同：解压 ZIP，双击主 .ino，点击验证和上传。源码和所需 ArduinoJson 头文件已一起打包，不需要 PlatformIO、pip、Node.js 或单独安装第三方库。ZIP 中的 ArduinoJson 文件必须保留在原位置，不要只复制 .ino。

## 1. 安装环境

- 使用 Arduino IDE **1.8.19** 传统版，Windows ZIP：https://downloads.arduino.cc/arduino-1.8.19-windows.zip 。不要安装要求 64 位新系统的 IDE 2.x。
- 在首选项“附加开发板管理器网址”填写 https://arduino.esp8266.com/stable/package_esp8266com_index.json 。
- 在开发板管理器安装 **esp8266 by ESP8266 Community 3.1.2**。其官方包索引提供 i686-mingw32（32 位 Windows）工具链及 Python 工具，不需单独装系统 Python。
- 若老系统无法下载 HTTPS 包，可在另一台联网电脑下载匹配的板卡包和工具后离线复制；不要改用 Core 2.3.0。参考项目的 Core 2.3.0/axTLS 只支持其旧 TLS 兼容入口，不能直接套用到本项目 TLS 1.2:9443。
- USB 转串口驱动按板上 CH340/CP210x 芯片安装对应 Windows 7 32 位版本。

本包在 Linux 上验证 Arduino IDE 1.8.19 构建工具 + ESP8266 Core 3.1.2 编译；Windows 7 实机安装/驱动兼容仍需现场确认，不声称已在 Windows 7 真机测试。

## 2. 打开程序

解压后目录为 printer_tunnel_esp8266，主文件为 printer_tunnel_esp8266.ino，两者名字必须一致。双击 .ino，不要把 ZIP 内文件散放或删除 ArduinoJson 文件夹。

公开下载模板需要在程序顶部填写 TUNNEL_HOST、DEVICE_ID、DEVICE_KEY；独立隧道公开信任证书已内置。私下发送的 gateway-esp8266-1 专属 ZIP 已填好设备凭据及独立隧道信任证书，TUNNEL_HOST 已脱敏，需自行填写实际地址；不要公开该 ZIP，也不要将同一设备凭据烧到多块板，否则会相互顶掉会话。

## 3. 选择工具菜单

| 菜单 | 值 |
| --- | --- |
| 开发板 | NodeMCU 1.0 (ESP-12E Module) |
| CPU Frequency | 160 MHz |
| Flash Size | 4MB (FS:1MB OTA:~1019KB)，或保持 NodeMCU 默认 4MB/1MB FS |
| Flash Mode（若显示） | DIO |
| Flash Frequency（若显示） | 40 MHz |
| Upload Speed | 115200 |
| lwIP Variant | v2 Lower Memory（默认） |
| Port | 实际 COM 串口 |

先点“验证”，成功后点“上传”。ESP-12F 裸模块需要 3.3V 稳定供电和 USB-TTL，GPIO0 拉低后复位进入下载模式，烧录完成松开 GPIO0 再复位。上述构建仅针对 4MB ESP-12E/F/NodeMCU；ESP-01 1MB 等不同板型需要重新配置。

## 4. 配 Wi-Fi

首次启动连接设备热点 Gateway-xxxx，密码 setup8266；用手机浏览器打开 http://192.168.4.1，填写现场 **2.4GHz** Wi-Fi 并保存。无需填写在源码里。网页只在配网热点模式开启，不走公网。

更换 Wi-Fi：正常启动后长按 FLASH（GPIO0）5 秒，重新打开热点配网；不要在上电时一直按住。

也可以在 .ino 顶部填写 WIFI_SSID/WIFI_PASSWORD（则首次启动直连）。来源网段默认从 Wi-Fi IP/掩码自动获得，VPS 仍需显式配置目标映射。

## 5. 看结果

串口监视器波特率 115200。完成 Wi-Fi、NTP 和 TLS/认证后输出 Tunnel online。连接目标、心跳、重连都由固件处理，不需要电脑一直开着。

烧录/上线只是网关就绪。192 服务器当前尚未配置真实打印机映射，自动打印任务投递也未接入。需要后续在 VPS 绑定目标 IP/端口，不能据此声称已经会打印。

隧道已独立使用 RSA-2048 / SHA-256 自签证书，有效期 2026-11-08 至 2056-11-08。它不经过 Nginx，也不再复用 Nginx 证书；原证书版本固件不能继续连接，必须更新信任证书或使用新 BIN。脱敏源码中的地址不代表改成域名，不修改现有 DNS。
