# Aster Browser mission memory

## Product mission

- Build one Android app/package that contains both GeckoView and the Chromium/Afterbird engine. Switching engines should stay inside the same app task and preserve the tab list/URLs; each engine keeps its own incompatible cookies, extension state, and site profile.
- Implement browser tools natively in each engine where feasible. Do not make Chromium buttons silently hand the user to Gecko and describe that as Chromium support.
- Support Android phones, tablets, and Meta Quest 3S without camera, eye, hand, or body tracking. Quest support is not verified until the current ARM64 APK is installed and exercised on a physical Quest 3S, including controller input.
- Be exact about Chromium extension compatibility: do not claim every desktop Chrome extension runs on Android. Name unsupported APIs and validate each extension/runtime path.
- AI must run locally after a compatible model is installed. Do not imply an online model or bundled model when that is not true.
- Tor privacy windows must be tested end to end for circuit establishment, page rendering, and fail-closed behavior. A menu entry or Tor proxy alone is not proof that Tor browsing works.

## User's project instructions

- Work on the repository's existing default branch `main` only. **Never create a second branch.**
- The user wants source, README, GitHub issues, and releases kept current. Publish a release only from the exact tested source and attach the right ABI APKs; do not call a local candidate an official release.
- Test on Android Studio emulators for phone/tablet UI. Stop emulators when testing is over to avoid wasting power. Keep only the newest Aster package installed on the selected test emulator when requested.
- Preserve the Android SDK, Gradle dependencies, signing material, pinned Chromium source checkout, source tree, and resumable build outputs. Remove only confirmed disposable APK duplicates, screenshots, logs, temporary files, and obsolete emulator installs.

## Open GitHub requests to verify

- [#1](https://github.com/B1progame/aster-browser/issues/1): Tor private browsing, privacy visuals, local AI, and in-app feature requests.
- [#2](https://github.com/B1progame/aster-browser/issues/2): Chromium feature coverage, AI, Tor, feature requests, error handling/telemetry, startup and memory behavior.
- [#3](https://github.com/B1progame/aster-browser/issues/3): Chromium YouTube load time and Instagram Reels playback.

Reproduce each request before changing code. Record the device, engine, steps, and result; close an issue only when the requested behavior has a verified fix.

## Last known state (2026-10-25)

- GitHub `main` was updated on 2026-10-25: README verification note commit `5392cf6b01abf56c2439945b8c0ee58d5a6dcd82`, then unsafe fullscreen injection removal commit `d2a58a594daaccf871294436aca3152c7b717da2`. Latest release remains v2.2.0; version 5.0.0 is still test-candidate metadata, not a published release.
- The current x86_64 v5 candidate passed selected Pixel emulator AI/browser UI checks. Chromium's Tor window opened but did not render a page in the end-to-end check.
- The ARM64 APK in `dist/` is stale relative to the current Chromium Tor menu patch. Rebuilding ARM64 required 40,793 Ninja targets and was stopped after 169 to avoid a very long high-load build. Quest 3S has not been tested.
- Issues #1–#3 are still open. Do not report full feature parity, working Tor in Chromium, or Quest readiness until verified.
- Pixel and tablet AVD names: `Pixel_10a` and `Medium_Tablet`. Android SDK is in `C:/Users/bslid.BENJI-PC/OneDrive/Dokumente/BENJI/ran/.android-sdk`; AVD data is in this repository's `.android-avd/`.

## Latest verification and cleanup (2026-10-25)

- Rebuilt Chromium `chrome_public_apk` for x86_64 from the pinned WSL checkout after removing `0030-aster-youtube-fullscreen-fit.patch`. Its `evaluateJavaScript()` call reached non-WebUI pages and triggered Chromium's `CanExecuteJavaScript()` fatal check.
- Installed the rebuilt `ChromePublic.apk` over `app.aster.browser` on `Pixel_10a`; Android reported `Success`, and package metadata reported version `5.0.0`, version code `805000000`, ABI `x86_64`. `pm list packages` showed only `app.aster.browser` among Aster packages.
- Chromium rendered YouTube's consent page after direct navigation without the prior JavaScript CHECK crash. This verifies page rendering past navigation, not video playback, fullscreen, or Instagram Reels; issue #3 remains open.
- The emulator/ADB became unavailable during the follow-up consent dismissal and log check. The emulator process is no longer running. Chromium Tor still failed its earlier end-to-end rendering check. Quest 3S, ARM64, cross-engine state, and full issue coverage remain unverified.
- GitHub has no v5 release. Issues #1–#3 were checked on GitHub and received factual progress comments; all remain open. Never label this test candidate a release.
- The WSL Chromium build output is resumable and outside this project folder; keep it. Within `.build-tmp/`, only `android-sdk` is referenced by the tracked integration script. Preserve that SDK; other files there are temporary artifacts/caches.

