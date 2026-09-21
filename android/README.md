# Android client

A reference implementation, not a polished app. It exists mainly to show the
two non-obvious parts: pinning a self-signed certificate, and keeping
credentials out of the source.

## Files

Four Kotlin files, all in the same package directory:

    MainActivity.kt    UI and connection logic
    GarageConfig.kt    config model, parsing, validation, storage
    Net.kt             HTTP client and certificate pinning
    Prefs.kt           UI preferences (slider, log visibility)

## Setting up

New project in Android Studio: **Empty Activity** (the Compose template, not
Empty Views Activity). Minimum SDK 26.

Add one dependency to `app/build.gradle.kts`:

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

Add to `AndroidManifest.xml`, inside `<manifest>`:

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="false" />

    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" tools:targetApi="s" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" tools:targetApi="s" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

`<manifest>` also needs `xmlns:tools="http://schemas.android.com/tools"` for the
`tools:targetApi` attributes. Android Studio's template includes it.

No cleartext-traffic exception is needed - everything is HTTPS.

Then paste the four files in, matching the `package` line to your project's
package name.

## Package name

The source says `com.example.garagedooropener`. That is Android's placeholder
namespace: fine for a personal build, but rename it if you publish an APK, as
`com.example` is reserved for samples and app stores reject it.

## First run

The app starts on a setup screen with no configuration. Paste JSON describing
your device. `config.example.json` in this folder shows the structure, and the
setup screen has a "Paste example template" link that fills in the same thing.

Any one of the three connection methods is enough. Omit `localUrl` and it never
tries a local address; omit the whole `bluetooth` block and that option
disappears. With only Bluetooth configured, the app is a single control and no
door status, because status comes from the web server.

Configuration is stored in the app's private storage. It is reachable later via
[Settings] on the main screen, so credentials can be rotated without rebuilding.

That storage is private to the app, which is reasonable, but it is not strongly
protected - anyone with root or the unlocked device can read it. Treat it about
as well protected as a password saved in a browser.

## Certificate pinning

The device uses a self-signed certificate, which Android's trust store rejects
outright. Rather than disabling validation (which would accept anything), the
app pins the device's public key: it trusts exactly one key, which is stricter
than ordinary HTTPS.

`certPinSha256` in the config is the value `make-cert.sh` prints. If the
device's certificate is regenerated with a new key, the config must be updated
or the app will refuse to connect - that is the pin working, not a fault.

## Options

**Slide to open** (default on) requires a deliberate slide rather than a tap, so
a stray touch can't open the door. Turn it off for a plain button.

**Show activity log** (default off) reveals the diagnostic log on the main
screen. Also toggled by tapping the "Activity log" heading.

## Known limitations

Every request opens a new TLS connection. Connection reuse was attempted and
does not work reliably against this server, so each request costs a handshake -
about 350ms of work for the ESP32, which is why a press is not instantaneous.

Door status is polled once a second rather than pushed. An earlier version held
the device's event stream open, which permanently occupied one of its few
session slots.

Bluetooth carries the button only, not door status. Attempts to expose sensor
state over BLE reported wrong values consistently, and it is the "standing next
to the door" fallback anyway.
