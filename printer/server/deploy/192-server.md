# 192 服务器部署记录

- 主机：SERVER_IP_REDACTED；SSH SSH_PORT_REDACTED。
- systemd：printer-tunnel，开机启动，独立非 root 用户 printer-tunnel。
- JAR：/opt/printer-tunnel/server.jar；配置：/etc/printer-tunnel/application.yml。
- 密钥与数据库环境变量：/etc/printer-tunnel/service.env，仅 root 可读，不入库。
- PostgreSQL：本机独立数据库/角色 printer_tunnel，Flyway V1--V3 已迁移。
- TLS：0.0.0.0:9443，由 Java 直接终止，不经过 Nginx。当前使用独立证书 /etc/printer-tunnel/tls/tunnel-30y/server.crt 和对应 server.p12；私钥仅 root 可读。
- 独立证书为 RSA-2048 / SHA-256 自签服务器证书，SAN 覆盖实际服务 IP，有效期 2026-11-08 09:08:01 UTC 至 2056-11-08 09:08:01 UTC（30 年）。此前复用的 Nginx 证书已退出隧道使用；Nginx 本身证书与配置未变。客户端必须更新信任证书，不跳过校验。
- 可分发的公开信任证书：/etc/printer-tunnel/tls/trusted-server.crt。
- HTTP：仅 127.0.0.1:18080，GET /healthz 返回 ok；旧 TCP 单据入口已恢复 0.0.0.0:9100。
- 当前 mappings 为空，未绑定真实打印机或开放本机映射入口；没有自动打印投递。
- 注册了 gateway-test-1 供验证；独立凭据保存在 /etc/printer-tunnel/bootstrap-device.json，不写入本记录。

2026-11-08 已通过 SSH 转发验证 IP 主机名校验、TLS 1.2、设备 AUTH_OK、PING/PONG；首次部署实际协商 ECDHE-RSA-AES256-GCM-SHA384，这是 Linux 测试结果，非 ESP8266 兼容性验证。切换独立证书后再次从公网完成 TLS 1.2、IP SAN 校验、AUTH_OK 和 PONG 验证。

2026-11-08 用户放行公网端口后，从文档 VPS 直连 SERVER_IP_REDACTED:9443 成功，完成 TLS 证书/IP 校验、设备 AUTH_OK 及 PING/PONG 验证。公网隧道入口可达；真实打印机转发仍未验证。

证书续期/替换后需要重新生成 PKCS12 并重启服务，当前没有自动重载。

安全检查与加固（2026-11-08）：9443 只接受 TLS 1.2 隧道并要求设备密钥 AUTH，单设备单流、全局最大连接数 64；9100 保持旧 GBK 明文兼容协议，但接收器已限制 64 个并发客户端、单连接 30 秒读超时、单连接 256 KiB 总字节和既有行数/行长上限，超过即关闭。9100 仍没有来源认证，建议云安全组只放行业务发送方 IP；若不能固定来源，不要把它当作安全认证入口。nftables 当前是监控计数规则而非阻断策略，公网边界由云安全组承担。

独立证书 SHA-256 指纹：46:F2:17:62:8A:C0:05:1C:ED:8E:06:C8:07:77:1D:93:4A:4B:AD:2A:E7:42:00:BC:0B:D2:3D:B9:D1:F9:43:75。前一环境文件备份为 service.env.before-independent-cert，原信任证书备份为 tls/trusted-server.before-independent.crt。新 BIN 已重编译，新私有源码 ZIP 更新证书且 TUNNEL_HOST 脱敏；旧 BIN 不再匹配当前证书。

2026-11-08 恢复公网 9100，外部发送两份明确标记的 GBK 测试单据：地址行触发与 EOF 触发各一次，包括跨 GBK 字节边界的分片写入。数据库确认中文客户、商品行正确，任务先进入 queued，分别生成 255/223 字节 ESC/POS 产物及 SHA-256；随后标记 cancelled，避免未来真实打印。未执行物理打印。

运维命令：

```bash
systemctl status printer-tunnel
journalctl -u printer-tunnel -n 100 --no-pager
curl -fsS http://127.0.0.1:18080/healthz
```

2026-11-08 部署来源 IP 记录：Flyway V4 添加 print_jobs.source_ip (INET)，旧 TCP 接收器从 socket 对端获取 IP，任务查询返回 sourceIp；历史行保持 NULL。公网测试验证来源 154.12.18.57、GBK 渲染正常，测试任务随后取消。另登记设备 gateway-esp8266-1，其私有凭据存 /etc/printer-tunnel/esp8266-device.json，不入库源码。
