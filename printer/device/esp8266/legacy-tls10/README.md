# ESP8266 Core 2.4.2 TLS 1.0 Compatibility

Package version is recorded in `VERSION` and in the ZIP filename. Increment `VERSION` whenever the sketch or bundled dependencies change.

This separate sketch retains the existing tunnel framing, device authentication, reconnect loop, and LAN TCP forwarding. It uses the ESP8266 axTLS `WiFiClientSecure` client and SPIFFS for Arduino IDE 1.x / ESP8266 Core 2.4.2. It pins the tunnel server certificate fingerprint before sending the device credential.

The server must have the opt-in TLS 1.0 listener enabled and reachable on port 9443. Configure `TUNNEL_HOST`, `DEVICE_ID`, `DEVICE_KEY`, and Wi-Fi credentials before compiling. `TUNNEL_HOST` must match the certificate subject alternative name; this sketch verifies both that name and the pinned SHA-1 fingerprint. The public ZIP deliberately contains placeholders only.

In Arduino IDE 1.6.8, install ESP8266 Core 2.4.2, choose a 4 MB NodeMCU ESP-12 board, 80 MHz CPU, 115200 upload speed, and a 4 MB flash layout (the filesystem size does not affect compilation). Open the matching `.ino` inside its same-named folder. Keep the bundled `ArduinoJson` folder beside the sketch. The source avoids template helpers that Arduino 1.6.8's prototype generator mishandles and uses the IPv4 macro available in Core 2.4.2.

TLS 1.0 is obsolete and should only be enabled for this isolated compatibility listener. The certificate pin uses SHA-1 because that is the fingerprint format supported by this old client API; it authenticates the server certificate but does not modernize TLS 1.0's cryptography. Prefer the maintained TLS 1.2 firmware whenever a newer core can be installed.

Serial diagnostics use 115200 baud. `[mem]` lines report current and sampled minimum free heap, Wi-Fi state/RSSI, stream and buffer state; `[protocol]` and `[buffer]` lines identify parse failures and receive backpressure. This Core 2.4.2 sketch does not report maximum-block, fragmentation, or stack metrics.
