#pragma once
// Copy to config.h locally; never commit Wi-Fi password or device credential.
#define WIFI_SSID "YOUR_WIFI"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
// Must match the server certificate SAN. Prefer a DNS hostname.
#define TUNNEL_HOST "YOUR_TUNNEL_HOST"
#define TUNNEL_PORT 9443
#define DEVICE_ID "YOUR_DEVICE_ID"
#define DEVICE_KEY "YOUR_INDEPENDENT_DEVICE_KEY"
// Allowed local targets; adjust to the actual LAN. A zero port allows all TCP ports.
#define ALLOW_NETWORK "auto"
#define ALLOW_NETMASK "255.255.255.0"
#define ALLOW_PORT 0
// Copy the PUBLIC trusted-server.crt contents here (never its private key).
static const char TRUSTED_CERT[] PROGMEM = R"PEM(
-----BEGIN CERTIFICATE-----
PASTE_THE_PUBLIC_TUNNEL_CERTIFICATE_HERE
-----END CERTIFICATE-----
)PEM";
