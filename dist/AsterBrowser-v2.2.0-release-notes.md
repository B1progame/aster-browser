# Aster Browser v2.2.0

This GeckoView update adds a Tor private window powered by the embedded Guardian Project Tor client.

## Downloads

- `AsterBrowser-v2.2.0-Gecko-Android-arm64.apk` — ARM64 Android phones and Meta Quest.
- `AsterBrowser-v2.2.0-Gecko-Android-x86_64.apk` — Android Studio x86_64 emulators.

Tor windows use an isolated Gecko profile, wait for an established Tor circuit, route requests through SOCKS5 with remote DNS, disable HTTP/3 and WebRTC, and do not fall back to a direct connection. The Tor Project check endpoint returned `IsTor: true` in the Pixel 10a Android Studio emulator.

This is Tor routing with private browsing. It does not include Tor Browser's complete anti-fingerprinting protections. The v2.2.0 APKs in this release are GeckoView builds; Chromium remains at v2.1.0 until its matching version can be built.

Android will ask you to confirm sideloading. The APKs use the existing workstation debug signing key and are not Play Store releases.
