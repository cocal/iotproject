# 192 服务器部署记录

- 主机：SERVER_IP_REDACTED；SSH SSH_PORT_REDACTED。
- systemd：printer-tunnel，开机启动，独立非 root 用户 printer-tunnel。
- JAR：/opt/printer-tunnel/server.jar；配置：/etc/printer-tunnel/application.yml。
- 密钥与数据库环境变量：/etc/printer-tunnel/service.env，仅 root 可读，不入库。
- PostgreSQL：本机独立数据库/角色 printer_tunnel，Flyway V1--V3 已迁移。
- TLS：0.0.0.0:9443；复用现有 Nginx 的 /etc/nginx/ssl/iotmonitor-8899-selfsigned.crt 和对应私钥，转换到 /etc/printer-tunnel/tls/shared-nginx.p12。
- 原证书为 RSA 自签证书，SAN 覆盖 iot.ohmyskills.top 与 SERVER_IP_REDACTED，有效期至 2036-08-27。客户端必须显式信任该证书；不跳过证书验证。没有改动 Nginx 监听或证书配置。
- 可分发的公开信任证书：/etc/printer-tunnel/tls/trusted-server.crt。
- HTTP：仅 127.0.0.1:18080，GET /healthz 返回 ok；旧 TCP 入口仅 127.0.0.1:19101。
- 当前 mappings 为空，未绑定真实打印机或开放本机映射入口；没有自动打印投递。
- 注册了 gateway-test-1 供验证；独立凭据保存在 /etc/printer-tunnel/bootstrap-device.json，不写入本记录。

2026-11-08 已通过 SSH 转发验证 IP 主机名校验、TLS 1.2、设备 AUTH_OK、PING/PONG；实际协商 ECDHE-RSA-AES256-GCM-SHA384，这是 Linux 测试结果，非 ESP8266 兼容性验证。

2026-11-08 用户放行公网端口后，从文档 VPS 直连 SERVER_IP_REDACTED:9443 成功，完成 TLS 证书/IP 校验、设备 AUTH_OK 及 PING/PONG 验证。公网隧道入口可达；真实打印机转发仍未验证。

证书续期/替换后需要重新生成 PKCS12 并重启服务，当前没有自动重载。

运维命令：

```bash
systemctl status printer-tunnel
journalctl -u printer-tunnel -n 100 --no-pager
curl -fsS http://127.0.0.1:18080/healthz
```
