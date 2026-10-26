# Aster Browser

Aster is a single Android app package containing both GeckoView and the experimental Chromium/Afterbird browser engine. Choose the engine from Aster's browser settings; each engine keeps its own profile and site data.

## Current candidate

The local source is version **5.0.0**; GitHub still lists **2.2.0** as the latest release. The current x86_64 combined APK includes the Chrome H.264/AAC codec configuration and was installed on Android Studio's Pixel 10a and Medium Tablet x86_64 AVDs. Instagram's Reels page rendered a video frame and Chromium created its H.264 decoder; the signed-out account still receives the site's “Watch this reel in the app” gate, so continuous Reels playback is not verified. YouTube's desktop page and a video frame rendered on the tablet; load-time benchmarking and fullscreen are unverified. Aster's report flow offers a user-selected, privacy-safe crash/ANR summary. One earlier tablet run recorded a Chromium main-process ANR (479 MB RSS); the current Pixel smoke run had no Aster crash or ANR. Chromium Tor pages still time out, and the current ARM64 APK is being rebuilt from the updated source.

- `AsterBrowser-v5.0.0-Android-arm64.apk` — an older ARM64 artifact in `dist`; it predates the current Chromium Tor menu patch and is not a current Quest build.
- `AsterBrowser-v5.0.0-Android-x86_64-tor-menu-ai-test.apk` — current x86_64 emulator test candidate, not a release artifact.

Both package formats use package ID `app.aster.browser` and include Gecko and Chromium. The x86_64 APK is built from the pinned Chromium source and contains the current staged changes. The ARM64 output is being resumed from its saved Chromium build tree; it has no current APK yet. Quest 3S compatibility and current ARM64 behavior remain unverified, so v5 is not yet published.

## Browser features

- Touch-oriented browser UI, saved tabs, bookmarks, history, theme selection, and browser-engine switching.
- Tab URLs can transfer between engines. Profiles are separate: cookies, signed-in sessions, history, bookmarks, permissions, downloads, and extensions do not synchronize between Gecko and Chromium.
- GeckoView tracking protection, fingerprinting and cryptomining protection, private tabs, site-data controls, local privacy activity, and the website shredder.
- Chromium includes its own extension manager and experimental Android extension runtime, Chromium profile controls, Aster tools, local-AI interface, and a separate Tor-window entry point.
- Both engine issue-report surfaces can include a minimal recent crash/ANR summary only when the user selects it. The summary excludes exception text, raw logs, URLs, and saved browser data; it is not crash analytics and is never uploaded automatically.
- Aster's on-device AI uses LiteRT-LM after a compatible model has been downloaded or imported. AI availability and model inference must be checked on the target device; the app does not bundle the multi-gigabyte model. Search requests send their query to the selected search provider.
- Chromium's Tor entry opens an isolated Android WebView window proxied through Guardian's Tor service. It is not a Tor profile inside Chromium's native tabs and does not provide the Tor Browser's fingerprinting defenses. A Tor window must fail closed if Tor cannot establish its circuit.
- PC sharing remains marked “In development.”

## Chromium extension support

Chromium uses the experimental Afterbird Android extension runtime. The Chrome Web Store and selected Manifest V3 extensions have been exercised on the x86_64 emulator, including a content-script/API smoke fixture and selected blockers. This does **not** guarantee every desktop Chrome extension works on Android: unsupported APIs, native messaging, desktop-only interfaces, and some background workflows still need extension-specific support. Chrome Web Store themes are not supported by Android Chromium's desktop ThemeService; Aster's own themes are separate.

Gecko's add-on catalog and signed Firefox WebExtensions are a separate engine capability. Chrome extensions do not become Gecko add-ons through a general converter.

## Build and test

Open this repository in Android Studio and use the bundled JDK 17 and Android SDK. `:app:assembleDebug` builds GeckoView-only development variants. The combined package requires the pinned full Chromium checkout and the integration pipeline:

```bash
chromium-engine/afterbird-source/ci/chromium_android_pipeline.sh --full-build
```

The pipeline stages the Gecko AAR/runtime into Chromium and builds the combined APK; its exact checkout and dependencies are documented under [`chromium-engine/afterbird-source/`](chromium-engine/afterbird-source/). The checked-in Chromium directory is an overlay and patch set, not a complete Chromium source checkout.

Before publishing, verify the APK manifest, ABI, signing certificate, install/update behavior, engine switch, Tor routing, updater in both engine surfaces, and representative browsing on supported devices. H.264/AAC distribution is patent/licensing-sensitive. Test the current ARM64 artifact on an ARM64 device before claiming runtime verification. See [`docs/ON_DEVICE_AI.md`](docs/ON_DEVICE_AI.md) and [`docs/chromium-extension-engine.md`](docs/chromium-extension-engine.md) for implementation details and limitations.

## Meta Quest

The ARM64 APK targets Android API 29+ and declares Quest devices as supported for sideloading. Quest 3S uses ARM64 Android-based Horizon OS, but a sideload-compatible manifest is not proof of runtime compatibility. Touch, controller input, both engines, extensions, video, AI inference, Tor, and self-updating still require a Quest 3S device test. Aster does not use camera or body tracking.

## GitHub issues

The current open issues are [#1](https://github.com/B1progame/aster-browser/issues/1), [#2](https://github.com/B1progame/aster-browser/issues/2), and [#3](https://github.com/B1progame/aster-browser/issues/3). Chromium still does not have complete Gecko feature/profile parity or universal desktop-extension support, so those requests must not be described as fully resolved or closed based only on UI presence or selected smoke tests.
