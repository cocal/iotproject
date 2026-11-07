# Linux 隧道参考客户端

这是协议客户端，不是 ESP8266 芯片模拟器。使用 Python 3.11+ 标准库 ssl/socket，模拟网关认证、目标连接、信用窗口、半关闭、心跳和重连。打印数据和扫描协议都不解析，最多一个活动流。

## 运行

先按 `../../server/README.md` 配置 VPS TLS 证书、设备记录和本机映射。客户端运行在能访问目标设备的 Linux 内网主机上：

```bash
read -rsp 'Device key: ' TUNNEL_DEVICE_KEY
export TUNNEL_DEVICE_KEY
python3 client.py \
  --host printer.ohmyskills.top --port 9443 \
  --device-id gateway-room-1 \
  --allow-cidr 192.168.1.0/24 --allow-port 9100 \
  --lan-ip 192.168.1.20 --gateway 192.168.1.1
```

`--allow-cidr`、`--allow-port` 可重复；回环、广播和组播目标始终拒绝。默认使用系统信任 CA 并校验服务器主机名；自签测试证书通过 `--ca ./test-ca.pem` 显式信任，没有跳过验证开关。`--once` 禁用自动重连，方便故障测试。密钥不放命令行，不在断线日志输出报文或异常内容。

VPS 转发端口按配置只监听 127.0.0.1，可从 VPS 本机程序访问。首版动态目标来自服务端映射配置，需要更新配置并重启；Web 扫描/任务投递适配尚未接入。

## 验证

在 `printer/server` 下执行 `mvn test`。隧道测试会临时生成仅一天有效的 TLS 证书、启动网关和本机 TCP 目标、启动本客户端，并验证随机二进制双向传输和半关闭；不访问真实打印机或数据库。需要 JDK 17（含 keytool）、Maven、Python 3，以及一张非回环 IPv4 网卡。

Linux 客户端使用线程和系统 TLS，无法验证 ESP8266 的可用堆、TLS 缓冲、Wi-Fi、watchdog 或 socket 半关闭能力。这些仍需板端联调。
