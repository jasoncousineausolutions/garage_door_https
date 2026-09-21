# HTTPS for ESPHome on the ESP32-S3

A garage door opener built on ESPHome — but the reason this repository exists is
that **the device serves its web interface over HTTPS**, which ESPHome can't do
out of the box.

ESPHome's `web_server` is plain HTTP. If you expose it beyond your own network,
your login crosses the internet unencrypted: Basic Auth is Base64, not
encryption. The [feature request for TLS][fr] has been open since October 2023
with no implementation, and the usual advice is to put a reverse proxy in front
of the device — a Raspberry Pi or similar always-on machine running Caddy or
nginx.

This does it on the ESP32 itself instead. It needs an **ESP32-S3 with PSRAM**,
about $20, which for a new project costs barely more than a plain ESP32 and
means no second machine to buy, power and maintain.

**Status: working, unmaintained.** Verified September 2026 against ESPHome
2025.11.2 on an ESP32-S3-DevKitC-1 (WROOM-1 N16R8). It has run for days at a
time under normal use. Read the limitations before relying on it.

[fr]: https://github.com/esphome/feature-requests/issues/2432

## What's here

    garage_door.yaml          the device configuration
    secrets.example.yaml      copy to secrets.yaml and fill in
    cert-config.example       copy to cert-config and fill in
    make-cert.sh              generates the TLS certificate
    reflash.sh                build and flash helper
    components/
      web_server_idf/         the HTTPS fork - this is the interesting part
    android/                  optional client app, with certificate pinning

If you only want HTTPS for your own ESPHome project, `components/` and the
`external_components:`, `esp32:` and `psram:` sections of the YAML are all you
need. The garage door is just the application it was built for.

## Hardware

- ESP32-S3 with PSRAM. **Not optional** — see below.
- A 5V relay module, wired across the opener's wall-button terminals, so a
  brief pulse is exactly equivalent to pressing the button.
- Two lever microswitches, one at each end of the door's travel.

Pins as used here:

    GPIO21   relay
    GPIO41   door fully closed
    GPIO42   door fully open

Three hardware traps on the S3, all learned the hard way:

**Don't put the relay on GPIO39 or GPIO40.** GPIO39–42 are the S3's JTAG pins,
and 39/40 are pulled high during download boot mode. With the relay on GPIO40,
every reboot and every OTA update opened the garage door. It happens in ROM
before any firmware runs, so no configuration can prevent it. Inputs are fine on
those pins; outputs are not.

**GPIO35, 36 and 37 are unusable on N16R8 boards.** The octal PSRAM interface
uses them. They're on the header and look completely ordinary.

**The 5V pin may be dead out of the box.** Some DevKitC boards need a solder
jumper closed before the 5V pin carries anything.

Also consider lever switches over reed switches. Reed switch magnets often
settle just outside detection range once the door stops, so an arrived door
reports nothing — and no software fixes that reliably.

## How the HTTPS works

ESPHome's `web_server` sits on an internal component called `web_server_idf`,
which wraps Espressif's `esp_http_server`. Espressif also ship
`esp_https_server`: the same server with TLS underneath, sharing the same
request handlers.

`components/web_server_idf/` is a fork of that internal component. Because it
has the same name as the built-in one, ESPHome uses it in its place, and the
stock `web_server` sits on top unchanged — entities, the REST API, the event
stream and the UI all work normally.

The changes, all marked `// FORK:` in `web_server_idf.cpp`:

- **`begin()`** starts an SSL server with an embedded certificate instead of a
  plain one. It deliberately does not set upstream's custom `close_fn`, because
  the SSL server installs its own open/close hooks to manage each connection's
  TLS context — overriding them leaks it.
- **`end()`** uses the SSL-aware stop so that context is freed.
- **The event-stream send override is removed.** Upstream replaces each
  session's send function with one that writes straight to the socket; under
  TLS that would push plaintext into an encrypted connection.
- **Socket timeouts.** Without them, a single write to a client that had gone
  away froze the HTTP task permanently.
- **LRU purging, always on.** See below — this is the one that matters most.

## Two things that make or break it

### PSRAM, and `CONFIG_MBEDTLS_EXTERNAL_MEM_ALLOC`

A plain ESP32 can run the HTTPS server. It starts, serves pages, and handles
requests.

But every TLS handshake allocates and frees 30–40 KB of internal RAM, and the
fragmentation is permanent. On a plain ESP32 running WiFi and Bluetooth, the
largest free block fell from 72 KB to 8 KB within a handful of connections,
after which no handshake could complete. The device stayed up and looked healthy
— it just refused every TLS connection.

`CONFIG_MBEDTLS_EXTERNAL_MEM_ALLOC` sends those allocations to PSRAM instead.
With it, the largest free block stays flat through days of use. It's the single
option that makes this work.

### Leaked session slots, and `lru_purge_enable`

The hardest bug in this project, and the one most worth knowing about.

The HTTP server has a fixed table of session slots — 7, of which 3 are reserved
internally, leaving **4** for actual clients. A browser tab holds one open
permanently for its live-update event stream.

Those event-stream sessions leak. Because they're long-lived by design, the
receive timeout never applies to them, and the server only notices a dead one
when a send to it fails. A client that disappears leaving a half-open socket —
a phone losing WiFi, a laptop going to sleep — is never noticed. The slot stays
occupied forever.

Once enough slots leak, the server accepts new TCP connections and then has
nowhere to put them. The symptom is a connection reset immediately after the TLS
Client Hello, with **nothing in the device log at all**, because the failure is
below anything the application sees. Meanwhile the device looks perfectly
healthy: memory fine, sensors updating, Bluetooth working. A reboot always fixes
it, until it happens again.

How fast it happens depends entirely on how many clients come and go. During
active testing it failed within minutes; under light use it took days. That
variability is a large part of why it was hard to find.

The fix is an ESP-IDF option upstream only enables for the captive portal:
`lru_purge_enable`. When a new connection arrives and no slot is free, the
least-recently-used session is evicted to make room, instead of the new
connection being refused. A stale session is a far better thing to lose than a
live one.

The leak itself still happens. You'll see the session count sit at its maximum
for long stretches — that's expected and harmless. What would matter is
connections failing while it does.

## Setup

1. Copy `secrets.example.yaml` to `secrets.yaml` and fill it in.

2. Copy `cert-config.example` to `cert-config` and set your device's IP, plus a
   public hostname if you'll reach it from outside.

3. Generate the certificate:

       chmod +x make-cert.sh
       ./make-cert.sh

   This writes `components/web_server_idf/cert.h`, which is compiled into the
   firmware. **Note the base64 fingerprint it prints** — the Android app pins
   it. The certificate is self-signed with a 10-year life, so there's no
   renewal to automate.

4. Flash:

       chmod +x reflash.sh
       ./reflash.sh usb      # first time, over USB
       ./reflash.sh          # afterwards, over the network

   Changing the sdkconfig options forces a full rebuild, so the first build is
   slow.

5. Browse to `https://<device_ip>` — note **https**, and no port number.

## The certificate warning

Your browser will warn that the connection isn't private. That's expected: the
certificate is self-signed, so no certificate authority has vouched for it. The
traffic is still encrypted — the warning is about *who* you're talking to, not
whether it's encrypted.

You have two options:

**Click through it.** Most browsers offer an "Advanced" or "Proceed anyway"
link. Chrome remembers the exception until the browser restarts; Firefox offers
to store it permanently.

**Trust the certificate on your device**, which removes the warning entirely.
Copy `cert.pem` to the device and install it:

- **Android:** Settings → Security → Encryption & credentials → Install a
  certificate → CA certificate
- **Windows:** double-click `cert.pem` → Install Certificate → Local Machine →
  "Trusted Root Certification Authorities"
- **macOS:** open `cert.pem` in Keychain Access, then set it to "Always Trust"
- **Linux:** varies by distribution; for Debian/Ubuntu, copy to
  `/usr/local/share/ca-certificates/` with a `.crt` extension and run
  `sudo update-ca-certificates`

Only install certificates you generated yourself.

The Android app never shows a warning: it pins the certificate's public key,
which is stricter than ordinary HTTPS — it trusts exactly one key rather than
every certificate authority in existence.

## Access from outside your network

Forward an external port on your router to port 443 on the device. A dynamic DNS
name (DuckDNS, for example) saves you tracking your public IP.

Expect it to be found. Within hours, internet-wide scanners will start probing
the port, and you'll see a steady trickle of failed handshakes in the device log
with a mix of error codes. That's normal and harmless — the device rejects them
— but it's why the web password must be long and random.

## Diagnostics

The config includes sensors for free heap, largest free heap block, loop time,
and a 30-second log line showing which session slots are occupied. They're left
in deliberately: TLS on a microcontroller runs close to its limits, and both
sets of numbers explained real failures during development.

Healthy looks like: **largest free block roughly flat over days**, and loop time
normally under 30 ms. Remove the `debug:` and `interval:` sections if you'd
rather not have them.

To watch the logs:

    esphome logs garage_door.yaml

## Limitations

**Self-signed certificates only.** No Let's Encrypt, because that needs an ACME
client with 90-day renewal running on the device.

**Each TLS handshake costs about 350 ms** of device time, so a button press over
HTTPS has a noticeable delay. The device handles keep-alive correctly, but the
included Android client makes a new connection per request, as reuse proved
unreliable.

**You own this fork.** It's copied from ESPHome 2025.11.2. Upstream changes to
`web_server_idf` won't reach you, and may break it. Before upgrading ESPHome,
diff the `// FORK:` sections against the new upstream — or simply stay on
2025.11.2.

**Not security-reviewed.** The traffic is genuinely encrypted, but nobody who
does this professionally has audited it.

## Licence

Derived from ESPHome, so the ESPHome licence applies — see `LICENSE`. It's
mixed: the C++ runtime files, which includes the forked component, are GPLv3.
