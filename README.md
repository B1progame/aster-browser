# Aster Browser

## Public Android release

The latest GeckoView release is [Aster Browser v2.2.0](https://github.com/B1progame/aster-browser/releases/tag/v2.2.0), with embedded Tor private windows. The latest Chromium/Afterbird release remains [v2.1.0](https://github.com/B1progame/aster-browser/releases/tag/v2.1.0), because a matching Chromium 2.2.0 build is not available yet. Gecko APKs are `AsterBrowser-v2.2.0-Gecko-Android-arm64.apk` for ARM64 phones and Quest, and `AsterBrowser-v2.2.0-Gecko-Android-x86_64.apk` for the Android Studio emulator. Chromium v2.1.0 APKs remain available on its release page. Android will ask you to confirm sideloading. The public source repository is [B1progame/aster-browser](https://github.com/B1progame/aster-browser).

Aster is an Android browser project with two engine builds:

- The GeckoView build uses package ID `app.aster.browser`.
- The Chromium/Afterbird build uses package ID `app.aster.chromium`. Both engine APKs share the Aster product identity, while keeping separate browser profiles and site data.

## GeckoView prototype

- Touch-oriented browser shell with address/search, navigation, home, tabs, bookmarks, recent history, and theme/search-engine settings.
- Saved open-tab URLs and selected tab, restored when the app restarts.
- Nineteen saved app themes: Midnight, Ocean, Light, Aurora, Forest, Ember, Rose, Cobalt, Sakura, Sand, Glacier, Solar, Violet, Mint, Graphite, Plum, Coral, Lagoon, and Arctic.
- Settings → Browser engine opens the separately installed Chromium build for Chrome extensions and hands over normal HTTP(S) tab URLs and the selected tab. The Chromium source patch imports those tabs on cold and warm launches and skips duplicate URLs. Switching back transfers Chromium's URLs, page titles, and selected tab to GeckoView. This is a tab handoff, not a shared browser profile: cookies, signed-in sessions, history, bookmarks, site permissions, and extensions remain engine-specific.
- GeckoView tracking protection, fingerprinting and cryptomining protection, and Global Privacy Control.
- A private-tab mode backed by GeckoView's private session setting. Private tabs are omitted from saved tabs, browsing history, and the local privacy activity counter.
- A local seven-day protection dashboard based on GeckoView's blocked-resource events, plus custom AI URL templates and a GitHub feature-request draft flow.
- Aster's on-device AI harness uses LiteRT-LM for local multi-turn chat, model import or optional Wi-Fi download, a real inference self-test, and opt-in site connection analysis. It offers a pinned 0.6B Qwen3 model download (about 329 MiB) alongside Gemma 4 E2B. See [`docs/ON_DEVICE_AI.md`](docs/ON_DEVICE_AI.md) for model setup, storage, memory, and privacy details.
- The local AI harness can list/search open Gecko tabs, open a validated HTTP(S) URL in a new tab, and search the configured engine. Local inference stays on-device; web search sends the search query to the selected search provider. It cannot read page bodies or invoke destructive actions.
- Website shredder clears GeckoView's host-scoped site data and site settings, closes matching regular tabs, and removes matching Aster history and bookmark entries after confirmation. Optional auto-shred clears site data when navigating away from a host or closing its tab. This does not erase data held by websites, Chromium's separate profile, or external password managers.
- Startup warm-up for GeckoView and per-session media suspension disabled to reduce avoidable first-page and background-media delays.
- Tor private windows in the GeckoView build start an embedded Guardian Project Tor client and wait for an established circuit before opening the isolated private profile. Traffic uses SOCKS5 with remote DNS and has no direct-connection fallback; HTTP/3 and WebRTC are disabled in the Tor profile. The Tor check endpoint confirmed `IsTor: true` on the Android Studio Pixel 10a emulator. This provides Tor routing and a private session, but does not provide full Tor Browser fingerprinting defenses or anti-fingerprinting parity.
- PC sharing is visible as “In development” and sends no data.
- ARM64 Android and x86_64 emulator debug APKs are in `dist/`.
- Issue #1 candidate release APKs are `dist/AsterBrowser-Gecko-arm64-issue1-candidate.apk` and `dist/AsterBrowser-Gecko-x86_64-issue1-candidate.apk`. Both use the existing development signing certificate so the x86 build could update the installed emulator app in place; they are not Play Store releases.
- Engine switching was exercised in both directions on the Pixel 10a x86_64 Android Studio emulator: Gecko settings launched Chromium, then the Chromium Settings → Browser engine row launched Gecko. The Chromium row is near the bottom of Settings, below Aster updates. The privacy dashboard and private-tab labeling were also checked; after app restart, private tabs were discarded while normal tabs persisted.

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

The Chromium build is not a universal converter for desktop extensions. Chromium documents desktop-Android extension support as experimental and still in development; extensions requiring native messaging, unsupported APIs, or desktop-only workflows may not work. The Aster x86_64 emulator build has been tested with a localized MV3 fixture, declarativeNetRequest blocking, live `tabs`/`scripting` API calls, a Web Store-installed MV3 blocker, and Dark Reader 4.9.133. These selected tests do not prove compatibility with every extension. The UI work covers the new-tab page, responsive and mint-themed extension manager, phone extension action sheet, extension shortcut, mint palette, and Chromium's native tab grid; most menus and settings retain Chromium's native UI. Blocking is provided by an extension, not Brave's native shield engine. GeckoView profile data cannot be migrated into Chromium's separate profile format. Neither build uses camera or body tracking.

### Meta Quest 3S check

- The current Chromium ARM64 APK declares `arm64-v8a`, Android minimum SDK 29, target SDK 36, application label `Aster`, and package `app.aster.chromium`. Its VR-mode, high-performance VR, head-tracking, and touchscreen feature declarations are optional. Activities are resizable. This is compatible on paper with Quest 3S's Android-based Horizon OS and its ARM64 hardware.
- Meta documents existing Android apps running as floating 2D panels and mapping tap, swipe, and scroll gestures to controllers or hand input. Aster does not implement a custom immersive interface or Quest-specific controller layer. A Quest 3S sideload test has not been performed; browser rendering, text entry, Web Store installation, extensions, audio/video, and update installation remain unverified on the headset.
- The APK does not declare Meta's Quest supported-device metadata. That does not prevent a developer-mode sideload, but it means the current APK is not prepared for Horizon Store submission. Quest sideloading requires Developer Mode and Android Debug Bridge access.

Chrome Web Store theme installation is not verified: the Just Black theme page disabled “Add to Chrome” on the Android build, and Chromium's desktop ThemeService target is excluded from Android. The built-in browser Light/Dark/System themes remain available.

## GeckoView build

Open this project in Android Studio and build the `app` `assembleDebug` task. The checked-in build configuration targets Android SDK 37.2, AGP 9.4, Gradle 9.6, and JDK 17.

Debug APK outputs:

- `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` — Android phones and Quest devices using ARM64.
- `app/build/outputs/apk/debug/app-x86_64-debug.apk` — x86_64 Android emulators.

These are debug-signed development builds, not store-ready release APKs.
