# Afterbird extension API coverage

Snapshot of what `chrome.*` extension APIs are implemented, stubbed, or
intentionally out of scope on the desktop-android build. Keep this in sync
with `chrome/browser/extensions/desktop_android/` when APIs land or change
status.

Status legend:

- `full` — backed by a real implementation that does what upstream does.
- `partial` — real implementation exists for the specifically tested methods;
  other methods in the same namespace may be stubbed or unavailable.
- `stub-ok` — responds with a plausible shape so extensions don't crash, but
  without the real side effect. Extensions that check for the API's existence
  and then do something useful with the returned data mostly still work.
- `schema-only` — the IDL is compiled (JS binding exists), but no
  `ExtensionFunction` is registered. Calls log `Unknown Extension API` and
  resolve with `Access to extension API denied.`
- `unavailable` — the IDL itself isn't compiled; `chrome.<ns>` is `undefined`.
- `infeasible` — fundamentally can't be supported on the current Android
  surface. See per-row rationale.

## Implemented

### Current Aster M151 emulator evidence (2026-10-15)

The older v1.5 rows below predate the Aster Chromium 151 APK and are not a
complete description of that APK. Aster's disposable MV3 fixture verified
live `chrome.tabs.query`, `chrome.tabs.update`, `chrome.tabs.create`,
`chrome.scripting.executeScript`, and `chrome.windows.getCurrent` calls on the
x86_64 Android Studio emulator. The injected DOM marker and returned page title
were checked. This makes those specific methods `partial` for the Aster M151
build; it does not establish every method in those namespaces. Other methods
remain in the historical/stub inventory until individually verified. See
[`docs/chromium-extension-engine.md`](../../../../docs/chromium-extension-engine.md)
for the Aster build and runtime scope.

The matrix of nine extensions and the separate function checks below are
Afterbird project reports. They are not a claim that every extension, or every
API, was tested in Aster.

| API | Status | Notes |
| --- | --- | --- |
| `chrome.alarms` | full | Core extensions/browser API; works. |
| `chrome.declarativeNetRequest` | full | Rulesets compile; dynamic rules persist. Tested with the probe. |
| `chrome.i18n` | full | `getUILanguage`, `getMessage`, `getAcceptLanguages` all work against the app locale. |
| `chrome.tabs.query`, `chrome.tabs.update`, `chrome.tabs.create` | partial (Aster M151 emulator) | Real browser-side behavior passed the `aster-scripting-smoke` fixture. Other `tabs.*` methods are not covered by this result. |
| `chrome.scripting.executeScript` | partial (Aster M151 emulator) | Injected a DOM marker into the live page; `insertCSS` and `removeCSS` were not established by this check. |
| `chrome.windows.getCurrent` | partial (Aster M151 emulator) | Returned the active browser window in the fixture. Other `windows.*` methods are not covered. |
| `chrome.idle` | full | Upstream core implementation. |
| `chrome.runtime.getManifest/id` | full | |
| `chrome.runtime.sendMessage` / `connect` (cross-extension) | full (since v0.7) | MessageService was null-stubbed pre-v0.7; now routes through the real service. Self-echo (SW → own SW) still fails because the service worker isn't treated as its own listener. |
| `chrome.storage.local` | full | |
| `chrome.storage.session` (MV3) | full | |
| `chrome.contextMenus.create` | stub-ok (moved to Stubbed section in v1.5) | The prior "full" label was inaccurate — no `ContextMenusCreateFunction` was registered on desktop-android; it logged `Unknown Extension API - contextMenus.create` (61 occurrences in the stability report). Now stubbed to accept the registration and return the id. No `onClicked` event ever fires. |
| `chrome.webRequest` (MV2) | full | Blocking listeners work; see probe. |
| `chrome.test` | full | Core extensions/browser API; present. |
| `chrome.events` | full | |
| `chrome.developerPrivate` | partial | Only the handlers chrome://extensions needs (`getExtensionsInfo`, `getProfileConfiguration`, `updateExtensionConfiguration`, `removeMultipleExtensions`, `reload`, `loadUnpacked`). Upstream has ~40 methods. Everything else returns "not implemented". |
| `chrome.webstorePrivate.beginInstallWithManifest3` | partial | Shim that hands off to `CrxInstallCoordinator`. Other webstorePrivate methods are stubs. |

## Stubbed (feature/api-coverage-v2, v1.5)

All live in `chrome/browser/extensions/desktop_android/desktop_android_stub_apis.{cc,h}`.
The intent of every stub is "return a valid-shape default so defensive
`.filter()`/`.map()`/`await` chains don't throw". None of these produce real
side effects. See `docs/superpowers/api-infeasible.md` for why each is
stubbed-not-implemented.

| API | Status | Rationale |
| --- | --- | --- |
| `chrome.permissions.getAll` | stub-ok | Reconstructs `{ permissions, origins }` from the extension's manifest. No runtime permission changes are tracked — granted-at-install is all we know. |
| `chrome.permissions.contains` | stub-ok | Tests against the same manifest-reconstructed set. False for any runtime-requested addition. |
| `chrome.commands.getAll` | stub-ok | Transcribes manifest `commands`. `shortcut` is always empty — desktop-android has no keyboard accelerator surface to bind them to. |
| `chrome.notifications.create` | stub-ok | Echoes or synthesises an id and logs; no actual toast. |
| `chrome.notifications.update/clear` | stub-ok (new in v1.5) | Return `true`. No-op; was "Access denied" pre-v1.5. |
| `chrome.notifications.getAll` | stub-ok (new in v1.5) | Returns `{}` (no active notifications). |
| `chrome.notifications.getPermissionLevel` | stub-ok (new in v1.5) | Returns `"granted"` so extensions don't short-circuit their setup. |
| `chrome.tabs.query` | historical stub (v1.5); superseded in Aster M151 | The Aster smoke fixture verified live query results. Do not apply the v1.5 behavior description to the newer Aster APK. |
| `chrome.tabs.create` | historical stub (v1.5); superseded in Aster M151 | The Aster fixture verified a real background tab was created. |
| `chrome.tabs.remove/reload/goBack/goForward/ungroup/highlight` | stub-ok (new in v1.5) | No-op; shape-correct callback. |
| `chrome.tabs.update` | historical stub (v1.5); superseded in Aster M151 | The Aster fixture verified a real navigation update. |
| `chrome.tabs.duplicate` | stub-ok (new in v1.5) | Returns a synthetic Tab with id+1. |
| `chrome.tabs.get` | stub-err (new in v1.5) | Returns an error ("Tab not found on desktop-android"). Extensions that check `lastError` behave correctly. |
| `chrome.tabs.getCurrent` | stub-ok (new in v1.5) | Returns `null` — matches the upstream semantics when called from a non-tab context (service worker). |
| `chrome.tabs.detectLanguage` | stub-ok (new in v1.5) | Returns `"und"` (undetermined). |
| `chrome.tabs.group` | stub-ok (new in v1.5) | Returns `-1`. |
| `chrome.tabs.discard` | stub-ok (new in v1.5) | Returns `null`. |
| `chrome.windows.getAll/get/getCurrent/getLastFocused/create/update` | stub-ok (new in v1.5) | Returns a single synthetic Window dict with id=0. |
| `chrome.windows.remove` | stub-ok (new in v1.5) | No-op. |
| `chrome.action.*` (MV3) | stub-ok (new in v1.5) | All setters are no-ops; `getTitle/getBadgeText/getPopup` return `""`; `getBadgeBackgroundColor` returns `[0,0,0,255]`. No per-extension action state storage yet. |
| `chrome.browserAction.*` (MV2) | stub-ok (new in v1.5) | Same shape as `action.*`. |
| `chrome.contextMenus.create` | stub-ok (new in v1.5) | Accepts the registration, returns the id (from `createProperties.id` or a synthetic numeric id). **No `onClicked` event ever fires** — there's no contextual-menu surface on Android. |
| `chrome.contextMenus.update/remove/removeAll` | stub-ok (new in v1.5) | No-op. |
| `chrome.cookies.get/set/remove` | stub-ok (new in v1.5) | Returns `null` (not found / no-op set). |
| `chrome.cookies.getAll` | stub-ok (new in v1.5) | Returns `[]`. |
| `chrome.cookies.getAllCookieStores` | stub-ok (new in v1.5) | Returns one synthetic store (`id: "0"`). |
| `chrome.types.ChromeSetting.get/set/clear` | stub-ok (new in v1.5) | `get` returns `{ value: null, levelOfControl: "not_controllable" }`; `set`/`clear` are no-ops. Used by `chrome.privacy.*`. |
| `chrome.extension.isAllowedIncognitoAccess` | stub-ok (new in v1.5) | Returns `false` (matches the actual state — incognito extension access isn't surfaced). |
| `chrome.extension.isAllowedFileSchemeAccess` | stub-ok (new in v1.5) | Returns `false`. |

## Further API coverage needed

The items below were noted in the historical v1.5 audit. For Aster M151,
`tabs.query/update/create`, `scripting.executeScript`, and
`windows.getCurrent` have passed the live fixture noted above. The unverified
methods still need individual runtime checks before being described as fully
supported. See `docs/superpowers/api-infeasible.md` for the historical
rationale and estimated effort.

| API | Priority | One-line plan |
| --- | --- | --- |
| Other `chrome.tabs.*` methods/events | high | Extend the runtime probe to `get`, `remove`, reload/navigation events, and verify API behavior across multiple tabs. |
| `chrome.scripting.insertCSS / removeCSS` | high | Verify live CSS injection and removal on a page; the existing fixture only proves `executeScript`. |
| `chrome.cookies.*` (real backing) | medium | Plumb `StoragePartition::GetCookieManagerForBrowserProcess` through the extension permission gating. Currently shape-only stubs (`[]`/`null`). |
| `chrome.action.*` state storage (MV3) | medium | Per-extension state store so getters returning setters' values round-trip. Currently stateless stubs. |
| `chrome.browserAction.*` state storage (MV2) | medium | Same implementation as action.*. |
| `chrome.webNavigation` (real events) | medium | Clean `WebContentsObserver`-based impl upstream. Currently schema-only; listener registration works via bindings layer but events don't fire. |
| `chrome.history` / `chrome.bookmarks` | medium each | Android has the stores; extension adapters are desktop-only. |
| `chrome.downloads` | medium | Android `DownloadManager` exists; adapter is desktop-only. |

## Likely infeasible on this build (at least near-term)

| API | Why |
| --- | --- |
| `chrome.storage.sync` | No Google account sync on desktop-android build. `ExtensionSyncService` is compiled out. Would need wire-up to Android's Backup/Sync, which is not in Afterbird's scope. |
| `chrome.management.getAll / setEnabled / ...` | `management.json` schema is explicitly excluded in `extensions/common/api/schema.gni` because the `ManagementGet*` function implementations aren't compiled for desktop-android and cause link errors. We already bridge the *UI* calls (chrome://extensions) through `developerPrivate.*` instead; giving extensions the full `management` surface would require recompiling that whole tree. |
| `chrome.fontSettings` | Android per-site font controls aren't a thing; there's no `FontPrefs` equivalent on this build. Extensions that call this see a `GetAPISchema miss` warning and a denied function call. |
| `chrome.privacy.*` | Relies on `prefs::` keys that only exist in the desktop build. |
| `chrome.bluetooth*`, `chrome.usb`, `chrome.hid`, `chrome.serial`, `chrome.printerProvider`, `chrome.system.*` | Removed from `extensions_api_schema_files_` by `schema.gni` — they're chromeos/desktop-only. Re-enabling means pulling large device-access stacks into desktop-android. |
| `chrome.debugger` | Exposes the devtools protocol to other extensions; gated on `enable_extensions` (full desktop). Not a good fit on Android where DevTools is over ADB. |
| `chrome.identity` / OAuth flows | No Sign-In Services plumbing on desktop-android profile. |
| `chrome.omnibox` | Upstream needs the `OmniboxController` which Android replaces with a completely different `UrlBarCoordinator`. A port is possible but would be a whole feature, not a stub. |
| `chrome.commands` keyboard shortcut *routing* | Schema exists, `getAll` works (see stubs). Actually wiring up `Alt+Shift+X`-style extension shortcuts requires a new `KeyboardShortcutDelegate` on the Android side — our `ChromeActivity` doesn't route custom accelerators to extensions. Treated as infeasible until someone needs it. |

## Source of truth

- IDL inclusion: `extensions/common/api/schema.gni` + `chrome/common/extensions/api/api_sources.gni`.
- Registration of browser-side functions: `DesktopAndroidExtensionsBrowserClient::RegisterExtensionFunctions` (via the Afterbird `AfterbirdChromeExtensionsBrowserAPIProvider`).
- Error path when a function is missing: `extensions/browser/extension_function_dispatcher.cc` — logs `Unknown Extension API - <name>` and responds with `Access to extension API denied.`
