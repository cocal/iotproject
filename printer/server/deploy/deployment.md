# Printer Deployment Architecture

This document describes the deployed component layout without publishing host addresses, public ports, domains, certificate fingerprints, or client IPs. Secret values and machine-specific connection details belong in the host's protected configuration, not in Git.

## Components

```text
External print clients
        |
        | Legacy GBK line-oriented TCP intake
        v
Application host
  +-- printer-tunnel (Java / Spring Boot, systemd, non-root service user)
  |     +-- legacy print intake and task rendering
  |     +-- TLS device tunnel listener with per-device authentication
  |     +-- optional, isolated TLS 1.0 compatibility listener (disabled by default)
  |     +-- loopback-only health endpoint
  |     +-- local PostgreSQL database
  |
  +-- Nginx: public HTTPS site and console reverse proxy
        |
        +--> printer-console (Python / Flask, loopback-only, read-only)
                    |
                    | WireGuard private network; read-only DB credentials
                    v
              Application host PostgreSQL

ESP8266 gateway -- outbound TLS connection --> printer-tunnel
       |
       +-- opens authorized TCP connections to targets on its local LAN
```

## Runtime Units

- `printer-tunnel` is a Spring Boot service managed by systemd. It receives legacy print submissions, stores and renders jobs, authenticates ESP8266 gateways, and terminates the gateway TLS tunnel directly. Its health endpoint is bound to loopback.
- PostgreSQL runs on the application host and stores devices, print jobs, source address metadata, and rendered artifacts. The console reaches it over WireGuard with a read-only database role.
- Nginx serves the HTTPS website and forwards the console path to the loopback-bound Flask process. The console exposes read-only job search and detail views; it does not issue retries, cancellations, or device commands.
- ESP8266 gateways initiate outbound TLS sessions to the application service. They do not accept inbound Internet connections. After device authentication, the server may request a TCP connection to an authorized LAN target; traffic beyond the TLS endpoint follows the target protocol's own encryption properties.

## Network Boundaries

- The public web path terminates HTTPS at Nginx.
- The device tunnel has its own TLS listener in the Java service and does not pass through Nginx.
- The legacy ESP8266 Core 2.4.2 listener is separately bound and disabled unless `TUNNEL_LEGACY_TLS10_ENABLED=true`; it shares the authenticated tunnel session and forwarding implementation. TLS 1.0 is obsolete, so expose this listener only where required and keep the TLS 1.2 listener unchanged.
- When enabling the old listener, set `TUNNEL_LEGACY_PORT` to the separately firewalled listener port and allow that port only from expected device egress ranges where practical. The Java process relaxes only the TLS 1.0 protocol disable entry while the explicit legacy switch is enabled; the listener itself negotiates TLS 1.0 only.
- The legacy Arduino ZIP is stored outside the Nginx document root. The Flask console serves it only through an authenticated session route when `CONSOLE_LEGACY_FIRMWARE` points to the deployed archive.
- The legacy print intake is a separate plaintext TCP protocol retained for compatibility. It has resource limits but no sender authentication; restrict its cloud firewall/security-group source ranges to trusted senders.
- The application health endpoint and console backend bind to loopback. Database access for the console uses the private WireGuard network and a read-only role.
- Host-specific addresses, ports, DNS names, firewall rules, device credentials, database passwords, and private keys are maintained in protected deployment configuration and are intentionally omitted here.

## Host Configuration

The Java service runs from a system-installed JAR with external Spring configuration and a root-readable environment file. TLS private-key material and per-device bootstrap credentials are stored outside the repository with restricted permissions. The Flask console has a separate systemd service and protected credentials/configuration. No production secrets or host-specific endpoint values should be committed.

The gateway tunnel authenticates devices and limits concurrent sessions. Print jobs are not automatically delivered. Administrative scan, test-print, and reprint actions are explicitly initiated from the authenticated console and relayed to the Java service with a separate internal key. Scan ranges are constrained by a server-side allowlist. RAW TCP transport cannot confirm that paper physically printed.
