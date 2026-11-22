# 打印控制台

Flask console for records stored by the Java service. Job search remains read-only against PostgreSQL; printer actions are relayed to the authenticated internal Java management API and never write through the console's read-only database connection.

Configuration is supplied through `/etc/printer-console/service.env`; never commit that file or `admin.json`. The database URL must use the WireGuard address and a role with only `CONNECT`, schema `USAGE`, and table `SELECT`. Set `CONSOLE_ADMIN_API_URL`, `CONSOLE_ADMIN_API_KEY`, `CONSOLE_DEVICE_ID`, and optionally `CONSOLE_TEST_PRINTER_IP` there to enable scan, test-print, and reprint actions. The test print form sends a confirmation-gated sample receipt to RAW TCP port 9100. The Java service separately requires the same `CONSOLE_ADMIN_API_KEY` and an explicit `CONSOLE_ADMIN_ALLOWED_CIDRS` allowlist.

Build the legacy ESP8266 Core 2.4.2/TLS 1.0 source ZIP with `printer/device/esp8266/tools/package_legacy_arduino.py`. The script reads `printer/device/esp8266/legacy-tls10/VERSION` and produces a versioned filename. Increment that MAJOR.MINOR.PATCH value whenever the sketch or bundled dependencies change, then deploy the new archive outside the Nginx document root and update `CONSOLE_LEGACY_FIRMWARE` plus `CONSOLE_LEGACY_FIRMWARE_VERSION`. The download route requires the normal authenticated console session and sends `Cache-Control: no-store`.

Scanning is restricted to a configured IPv4 range no larger than a `/24`. Only RAW port 9100 can receive test prints or reprints. Reprints can send either the stored original text as GBK text or a receipt image rendered by the Java service from the stored source text. The browser sends only the job ID and print options; it never uploads the preview image. Preview and printing use the same server-side renderer. The image style editor appears in a record's detail dialog and lets the operator adjust font size, weight, alignment, line spacing, and table spacing; settings are saved in that browser's local storage and included in previews and reprints. Defaults keep only the `销售单` title bold; the shop name, body, and total use regular weight. The ESC/POS output is 1-bit raster; blank source lines are omitted, and the renderer sends raster bytes directly without a PNG encode/decode cycle. Logs record render and send duration and payload size for print and preview requests. Java rendering requires `Noto Sans CJK SC`; source text longer than 200,000 characters is rejected for image rendering. The status action sends ESC/POS `DLE EOT 1..4` and displays raw response bytes; a response reports printer status, not completion of a specific print job. Test prints may produce paper output; TCP delivery cannot confirm that the printer physically printed it.

Run tests with:

```bash
/opt/printer-console/venv/bin/python -m unittest discover -s printer/console/tests -v
```
