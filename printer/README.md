# Printer

远程打印机项目：本地 ESP8266 控制板连接远程服务端，由服务端管理设备通信和打印任务。

## 模块

- `device/esp8266/`：ESP8266 控制板固件
- `server/`：部署在远程主机上的服务端
- `remote-print/`：远程打印任务、队列和打印机适配器

部署组件及网络边界见 [`server/deploy/deployment.md`](server/deploy/deployment.md)。该文档不包含机器地址、端口、域名或密钥。

