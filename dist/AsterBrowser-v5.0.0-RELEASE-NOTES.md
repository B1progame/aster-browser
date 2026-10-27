# Aster Browser v5.0.0

Aster v5.0.0 is one Android package (`app.aster.browser`) containing Chromium and GeckoView. Select the APK matching the device CPU:

- `AsterBrowser-v5.0.0-Android-arm64.apk` — ARM64 Android phones and Quest-family devices. Quest 3S compatibility is declared in the manifest, but physical Quest 3S runtime/controller behavior was not tested.
- `AsterBrowser-v5.0.0-Android-x86_64.apk` — Android Studio x86_64 emulators.
- `AsterBrowser-v5.0.0-SHA256SUMS.txt` — SHA-256 checksums for both APKs.

The repository [README feature tour](https://github.com/B1progame/aster-browser/blob/main/README.md#screenshots-and-feature-tour) includes emulator screenshots of the home screen, tab switcher, extension manager, and per-site extension controls. The [roadmap](https://github.com/B1progame/aster-browser/blob/main/docs/ROADMAP.md) proposes browser-data import and other next steps.

## Verification

On 2026-10-27, the x86_64 APK was installed on Android Studio's Pixel 10a emulator running Android 17. Chromium rendered `https://example.com/`. Switching Chromium → GeckoView → Chromium stayed inside the same Android task; GeckoView displayed the transferred example.com URL. The Chromium tab switcher showed 14 tabs before a force-stop; relaunch restored 10, so full tab restoration is not verified. No Aster fatal exception or ANR was observed in the tested launch/navigation/engine-switch flow.

Both APKs report package `app.aster.browser`, version 5.0.0, version code 805000000, and use the same signing certificate as the published v2.2.0 APKs. The ARM64 APK manifest declares `arm64-v8a` and Quest device metadata `quest2|questpro|quest3|quest3s`; ARM64 runtime behavior is not verified on hardware.

## Known limitations

- Chromium Tor route establishment is visible, but Tor pages timed out in the end-to-end check. Do not treat Chromium Tor web browsing as working.
- The local AI model showed Ready on Pixel, but a prompt produced no visible answer within 90 seconds; inference is not verified.
- Instagram Reels and YouTube desktop/fullscreen were not retested in this release pass. Earlier tablet checks saw a video frame and YouTube content, but did not verify continuous Reels playback, load-time benchmarks, or fullscreen.
- Full restoration of every tab after process death is unverified.
- Updating from v2.2.0 was not exercised end to end.
- This release does not claim compatibility with every desktop Chrome extension or full Gecko feature parity in Chromium.
- No physical Quest 3S/controller test was performed.
- GitHub issues #1–#3 remain open because requested behavior is not fully verified.
