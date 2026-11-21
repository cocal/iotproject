# ESP8266 tunnel: truncated HTTP responses

**Status:** source changes and diagnostics are prepared; firmware build, device flash, and live retest are still pending.

## Symptoms and evidence

The HTTP target at `192.168.180.49:80` accepts connections, but requests through the ESP8266 tunnel sometimes return a truncated body. `curl` reported `transfer closed with 5401 bytes remaining to read`; the public `/mng/` route consequently returned `502`.

The ESP8266 `/logs.txt` output recorded this sequence:

1. `OPEN accepted 192.168.180.49:80`.
2. TCP connect callback returned `OK` and the stream connected.
3. Tunnel data and target response data were both observed.
4. `Control JSON invalid type=33 stream=2 len=15 err=NoMemory`.
5. The firmware logged `Forwarding error: invalid control frame JSON` and disconnected the tunnel.

Protocol frame type `33` is `WINDOW` / `WINDOW_UPDATE`. The Java gateway sends this frame with a JSON payload shaped like `{"credit":N}`. Before the fix, firmware sent all JSON control frames through a local `StaticJsonDocument<1536>` and `deserializeJson()`, including `WINDOW_UPDATE`. A parse error sets `protocolBad`; the main loop then disconnects TLS. That is consistent with the truncated response and proxy `502`.

`NoMemory` is the JSON deserializer's reported error. This observation alone does **not** prove that total ESP8266 heap was exhausted; stack pressure, allocator state, or a difference between the running firmware and checked-in source may also matter. Device logs previously reported firmware `tls10_v1 v1.0.16`, while the then-current compatibility source was version `1.0.3`; verify the flashed build before attributing the exact allocator failure to this source revision.

## Forwarding model

The firmware forwards TCP data incrementally, not as a whole HTTP response. DATA frames are limited to 1024 bytes, with separate 4096-byte receive and transmit rings and 4096-byte flow-control windows. When a ring is full, the lwIP receive callback returns `ERR_MEM` so TCP can apply backpressure and retry; that is distinct from the observed JSON parse failure.

## Code changes

- `WINDOW_UPDATE` now bypasses ArduinoJson and uses a bounded parser for the protocol's single positive `credit` integer. It rejects malformed, overflowing, out-of-range, or extra-field payloads.
- The change is mirrored in `src/main.cpp`, the Core 2.4.2 TLS1.0 compatibility sketch, and the downloadable sketch under `site/doc/files/`.
- The compatibility sketch package version is `1.0.5`.
- Modern firmware logs JSON/frame errors, TLS and disconnect reasons, receive-buffer backpressure, heap metrics, Wi-Fi status/RSSI, ring occupancy, and stack metrics. Periodic snapshots are emitted once a minute; heap and stack low-water marks are sampled every five seconds.
- The modern PlatformIO build enables `UMM_INFO` for maximum free-block and heap-fragmentation metrics. TLS stack usage is the stack-thunk high-water usage. The legacy Core 2.4.2 sketch logs heap only to avoid relying on unverified old-core metric APIs.

## Validation and deployment

- `git diff --check` passes.
- The private TLS1.0 compatibility ZIP builds and passes `unzip -t` as `esp8266-core-2.4.2-tls10-v1.0.5.zip`.
- PlatformIO is not installed in the development environment, so the updated modern firmware has not been compiled here.
- No firmware has been flashed as part of this change. Existing device behavior and the live `/mng/` result are therefore unchanged until a matching firmware is built, flashed, and tested.

After flashing, capture serial output at 115200 baud while requesting the full HTTP response. Confirm `WINDOW_UPDATE` no longer causes a disconnect, compare heap/max-block/fragmentation and stack fields around TLS connect and request handling, and verify the response length completes. If it still disconnects, use the emitted frame type, reason, Wi-Fi state, and memory metrics to distinguish JSON/protocol, heap-fragmentation, stack, Wi-Fi, or TCP-buffer pressure.
