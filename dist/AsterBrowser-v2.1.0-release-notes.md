# Aster Browser v2.1.0

Aster 2.1.0 ships two Android app builds under one version and release. Chromium and GeckoView are separate APKs and keep separate engine storage; the app names, Aster settings, update version, and home experience are aligned.

## Downloads

- `AsterBrowser-v2.1.0-Android-arm64.apk` — Chromium engine for ARM64 Android phones and Meta Quest 3S.
- `AsterBrowser-v2.1.0-Android-x86_64.apk` — Chromium engine for Android emulators.
- `AsterBrowser-v2.1.0-Gecko-Android-arm64.apk` — GeckoView engine for ARM64 Android phones and Meta Quest 3S.
- `AsterBrowser-v2.1.0-Gecko-Android-x86_64.apk` — GeckoView engine for Android emulators.

The Chromium build includes the Chrome Web Store extension runtime and Aster's in-app update check. Its home screen can hand open tab URLs and titles to the GeckoView app's on-device assistant when GeckoView is installed. The GeckoView APK includes its own update check and local assistant UI.

Both engines use Android's package installer for update confirmation. The APKs are sideload builds, not Play Store releases. GeckoView is signed with this workstation's Android debug key to preserve compatibility with the local development install; future GeckoView releases must use the same signing key for in-place updates.

SHA-256 values are in `AsterBrowser-v2.1.0-SHA256SUMS.txt`.
