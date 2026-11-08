# 打印控制台

Flask read-only console for records stored by the Java server on the 192 host. It intentionally has no mutation route: no retry, cancel, reprint, or device-management operation is exposed here.

Configuration is supplied through `/etc/printer-console/service.env`; never commit that file or `admin.json`. The database URL must use the WireGuard address and a role with only `CONNECT`, schema `USAGE`, and table `SELECT`.

Run tests with:

```bash
/opt/printer-console/venv/bin/python -m unittest discover -s printer/console/tests -v
```
