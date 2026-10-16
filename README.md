# Aster Browser

## Public Android release

The current Chromium/Afterbird build is published as [Aster Browser v2.0.0](https://github.com/B1progame/aster-browser/releases/tag/v2.0.0). Choose the APK that matches the device CPU: `AsterBrowser-v2.0.0-Android-arm64.apk` for Android phones and Meta Quest, or `AsterBrowser-v2.0.0-Android-x86_64.apk` for the Android Studio emulator. Android will ask you to confirm installation. The public source repository is [B1progame/aster-browser](https://github.com/B1progame/aster-browser).

Aster is an Android browser project with two engine builds:

- The maintained app prototype uses GeckoView and package ID `app.aster.browser`.
- An experimental Chromium/Afterbird Android shell is built separately as package ID `app.aster.chromium` to test Chromium extensions without replacing the GeckoView app or its data.

## GeckoView prototype

- Touch-oriented browser shell with address/search, navigation, home, tabs, bookmarks, recent history, and theme/search-engine settings.
- Saved open-tab URLs and selected tab, restored when the app restarts.
- Midnight, Ocean, and Light app themes.
- GeckoView tracking protection, fingerprinting and cryptomining protection, and Global Privacy Control.
- PC sharing is visible as “In development” and sends no data.
- ARM64 Android and x86_64 emulator debug APKs are in `dist/`.

## Experimental Chromium extension build

- Source/patches: `chromium-engine/afterbird-source/` (Chromium 151 / Afterbird Android extension patches).
- The emulator build uses Chromium's desktop-Android extension runtime. Unpacked MV3 extensions with localization, a popup, and content scripts were verified in the Android Studio emulator, including after a browser restart.
- A disposable MV3 smoke fixture passed real `tabs.query`, `tabs.update`, `scripting.executeScript`, `windows.getCurrent`, and `tabs.create` calls on the x86_64 emulator. The fixture and its isolated browser profile were removed from the emulator after the run; source fixture: [`aster-scripting-smoke`](chromium-engine/afterbird-source/third_party/extensions/aster-scripting-smoke/README.md).
- The Chrome Web Store catalog, search results, detail page, and permission/install flow were verified on the x86_64 emulator. uBlock Origin Lite installed from the store, blocked 46 requests with `net::ERR_BLOCKED_BY_CLIENT` during a Dictionary.com reload, and was removed after the test.
- Phone toolbar integration: the compact-width extensions button opens Chromium's installed extension action sheet. Pinned extension actions keep their popup anchors visible on phones; Dark Reader's real toolbar popup was opened and its controls tested on the emulator.
- Current x86_64 APK: [`dist/AsterBrowser-Chromium-x86_64.apk`](dist/AsterBrowser-Chromium-x86_64.apk) (493,768,432 bytes; SHA-256 `298066738e9a71c9a324f22a271139cd127ec31e255670accc5a0277cbd00a59`; package `app.aster.chromium`). Installed and visually verified on the Android Studio Pixel 10a x86_64 emulator, including live Enabled/Off status updates.
- Current ARM64 phone/Quest APK: [`dist/AsterBrowser-Chromium-arm64.apk`](dist/AsterBrowser-Chromium-arm64.apk) (492,392,197 bytes; SHA-256 `057a980be7c2365b37cf0f25652e9193afdbafeeed7f1250f88aaeb0e4d0232d`; package `app.aster.chromium`). Built from the same v2 source; native ARM64 runtime remains unverified on a phone or Quest.
- Its Android new-tab page has Aster’s mint brand, “Browse with room to think” introduction, native search and shortcut tiles, plus an animated Extensions card that opens Chromium’s real extension manager. The Chromium tab grid uses the mint accent and shows saved pages.
- The Chromium build defaults to a dark browser theme, with Chromium’s existing theme setting retained and Aster colors adapted for light and dark mode. Other browser controls and settings remain Chromium UI.
- On narrow phones, the extension manager enlarges its type and extension cards, uses a 44px minimum action target, and labels each extension “On, extension enabled” or “Off” from its live state. The Pixel 10a emulator test toggled bundled uBlock both ways and confirmed the status changed, then left uBlock enabled.
- Test report and limitations: [`docs/chromium-extension-engine.md`](docs/chromium-extension-engine.md).

## Important limits

The Chromium build is not a universal converter for desktop extensions. Chromium documents desktop-Android extension support as experimental and still in development; extensions requiring native messaging, unsupported APIs, or desktop-only workflows may not work. The Aster x86_64 emulator build has been tested with a localized MV3 fixture, declarativeNetRequest blocking, live `tabs`/`scripting` API calls, a Web Store-installed MV3 blocker, and Dark Reader 4.9.133. These selected tests do not prove compatibility with every extension. The UI work covers the new-tab page, responsive and mint-themed extension manager, phone extension action sheet, extension shortcut, mint palette, and Chromium's native tab grid; most menus and settings retain Chromium's native UI. The ARM64 APK was built but not runtime-tested on native ARM64 hardware. Blocking is provided by an extension, not Brave's native shield engine. GeckoView profile data cannot be migrated into Chromium's separate profile format. Quest controller behavior has not been tested on a headset; neither build uses camera or body tracking.

Chrome Web Store theme installation is not verified: the Just Black theme page disabled “Add to Chrome” on the Android build, and Chromium's desktop ThemeService target is excluded from Android. The built-in browser Light/Dark/System themes remain available.

## GeckoView build

Open this project in Android Studio and build the `app` `assembleDebug` task. The checked-in build configuration targets Android SDK 37.2, AGP 9.4, Gradle 9.6, and JDK 17.

Debug APK outputs:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` — Android phones and Quest devices using ARM64.
- `app/build/outputs/apk/debug/app-x86_64-debug.apk` — x86_64 Android emulators.

These are debug-signed development builds, not store-ready release APKs.
