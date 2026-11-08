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
- The legacy print intake is a separate plaintext TCP protocol retained for compatibility. It has resource limits but no sender authentication; restrict its cloud firewall/security-group source ranges to trusted senders.
- The application health endpoint and console backend bind to loopback. Database access for the console uses the private WireGuard network and a read-only role.
- Host-specific addresses, ports, DNS names, firewall rules, device credentials, database passwords, and private keys are maintained in protected deployment configuration and are intentionally omitted here.

## Host Configuration

The Java service runs from a system-installed JAR with external Spring configuration and a root-readable environment file. TLS private-key material and per-device bootstrap credentials are stored outside the repository with restricted permissions. The Flask console has a separate systemd service and protected credentials/configuration. No production secrets or host-specific endpoint values should be committed.

The current gateway tunnel authenticates devices and limits concurrent sessions. Real printer forwarding and automated print delivery through the new tunnel still require end-to-end validation before being treated as production-ready.
