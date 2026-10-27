# Aster Browser

Aster is a single Android app package containing both GeckoView and the experimental Chromium/Afterbird browser engine. Choose the engine from Aster's browser settings; each engine keeps its own profile and site data.

## v5.0.0 release verification

The v5.0.0 release contains one package, `app.aster.browser`, with GeckoView and Chromium. It includes separate ABI assets: `AsterBrowser-v5.0.0-Android-arm64.apk` and `AsterBrowser-v5.0.0-Android-x86_64.apk`.

On 2026-10-27, the x86_64 APK was installed on the Android Studio Pixel 10a emulator (Android 17). Chromium rendered `example.com`; switching from Chromium to Gecko and back stayed in the same Android task, and the Gecko tab list contained the transferred `https://example.com/` URL. A cold relaunch restored 10 Chromium standard tabs, while the switcher had shown 14 before force-stop, so complete tab restoration is not verified. No Aster fatal exception or ANR appeared in the tested launch/switch flow.

The ARM64 APK was built from the same pinned Chromium checkout and staged Aster runtime. Its manifest reports package `app.aster.browser`, version 5.0.0, ABI `arm64-v8a`, and Quest device metadata `quest2|questpro|quest3|quest3s`. Its signing certificate matches the x86_64 APK and the existing v2.2.0 signing key. This is a manifest/build check only: the ARM64 APK was not installed on a physical ARM64 device or Quest 3S.

Known issues remain open. Chromium Tor page rendering did not pass the previous end-to-end check. In this Pixel run, the local AI model reached its ready state, but a prompt produced no visible answer after 90 seconds; Android also logged memory-pressure kills of Chromium renderer processes. Instagram Reels and YouTube desktop/fullscreen were not retested in this release pass. v5 does not claim full desktop Chrome extension compatibility or complete Gecko feature parity in Chromium. See [`docs/chromium-extension-engine.md`](docs/chromium-extension-engine.md) for test details and limitations.

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

The v5 release has not verified Chromium Tor page rendering, full AI inference, every-tab cold restoration, update installation from v2.2.0, or Quest 3S controller/runtime behavior. H.264/AAC distribution is patent/licensing-sensitive. See [`docs/ON_DEVICE_AI.md`](docs/ON_DEVICE_AI.md) and [`docs/chromium-extension-engine.md`](docs/chromium-extension-engine.md) for implementation details and limitations.

## Meta Quest

The ARM64 APK targets Android API 29+ and declares Quest devices as supported for sideloading. Quest 3S uses ARM64 Android-based Horizon OS, but a sideload-compatible manifest is not proof of runtime compatibility. Touch, controller input, both engines, extensions, video, AI inference, Tor, and self-updating still require a Quest 3S device test. Aster does not use camera or body tracking.

## GitHub issues

The current open issues are [#1](https://github.com/B1progame/aster-browser/issues/1), [#2](https://github.com/B1progame/aster-browser/issues/2), and [#3](https://github.com/B1progame/aster-browser/issues/3). Chromium still does not have complete Gecko feature/profile parity or universal desktop-extension support, so those requests must not be described as fully resolved or closed based only on UI presence or selected smoke tests.
