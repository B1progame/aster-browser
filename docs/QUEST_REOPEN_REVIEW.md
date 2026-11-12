# Quest 3S reopening review — 2026-11-10

The user reports that the newest Aster release opens after installation but shows
“app isn't responding” when reopened after closing. Both Chromium and Gecko are
affected on Quest 3S; Chromium is the priority. No headset ANR trace was supplied.
The upstream latest release was confirmed as v5.0.0. This review identifies
blocking work in source; it does not establish the cause of the reported ANR.

## Changes

| Source finding | Change and scope |
| --- | --- |
| The shared crash-init provider queries Android's process-exit history synchronously before either engine's Activity opens. Binder service latency can delay startup. | Install the crash handler immediately and query history on a daemon worker. Serialize summary updates so historical results cannot overwrite a newer crash report. This provider is included in the combined Chromium package. |
| Gecko title/location/selection callbacks repeatedly serialize every saved tab and enqueue preference writes. Android can wait for `apply()` writes when stopping an Activity. | Debounce normal updates by 350 ms, snapshot immediately on pause, and encode/commit on a process-owned serial worker. Keep at most the latest pending write for each key. Privacy counters use the same worker. |
| A replacement Activity can reopen while the preceding disk save is unfinished. | Restore from the latest immutable process-local snapshot when available. Private tabs are excluded from both snapshots and disk. |
| Hidden Gecko sessions remain active and explicitly allow inactive media. | Deactivate the previous/current session on tab switch/pause, activate on resume, and suspend inactive media. Selection while paused cannot reactivate a hidden session. Background Gecko audio/video now pauses. Chromium's own tab/media policy is unchanged. |
| The Tor HTTP response path calls `InputStream.transferTo()`, which requires Android API 33 despite the app's API 29 minimum. | Reuse the buffered stream-copy loop available on older Android versions. Strengthen the HTTP relay test with a binary body spanning multiple buffers; remove a queue-consumption race in the fake SOCKS server. This fixes an API compatibility error, not Tor's unverified end-to-end rendering. |
| Lint flags WebView proxy calls without a local feature guard and an allocation on every Tor animation draw. | Guard proxy attach/cleanup with `PROXY_OVERRIDE` checks and reuse/reset the animation's `Path`. |
| Chromium patch `0029` is syntactically corrupt; `0027` has a redundant trailing handoff line outside its hunk. | Recount the malformed hunk, remove a no-op updater hunk and stray trailing line, and add a parse check for every patch. This is syntax validation, not proof that all patches apply to the full pinned Chromium tree. |
| Three configuration tests have stale assumptions about versions, Tor routing, and which patch introduces tab-export code. | Check version consistency against the app configuration and follow the current bridge/export locations. |

## Validation

- Android app Kotlin/Java compilation, Android unit tests, and the combined
  engine's release AAR build passed with Gradle 9.6.0, JDK 17, and Android SDK 37.2.
- All 18 Android unit tests passed, including three new tests for a 1,000-update
  burst, submitting a newer lifecycle snapshot while disk is blocked, and
  recovery after a failed write. The burst test retains one latest pending tab
  write, not 1,000 queued writes; this is not a device performance benchmark.
- All 15 Chromium configuration/patch-syntax tests passed.
- All five host tests of the device-check tool passed, covering launch rejection,
  a process disappearing after launch, an ANR after a successful `am start`, an
  unrelated app's ANR, and a best-effort kill that does not stop the app.
- Independent code review covered the startup, persistence, lifecycle, patch,
  and test-tool changes. `git diff --check` passed.
- On 2026-11-12, the Quest ARM64 build reached Chromium Java compilation but
  failed on nine references to an absent API/resource. Patch 0039 replaces those
  references. It applies to the exact pinned, previously patched Chromium files;
  both page-capture callbacks compile against API-shaped test stubs. Independent
  source review and two host tests of the incremental resume helper passed. A full
  Chromium recompilation and runtime test are still needed.

Final `:app:lintDebug` passed with zero errors and 148 warnings remaining.
The blocking API-compatibility error, two proxy-feature warnings, and drawing
allocation warning were fixed. Remaining warnings are mostly localization/style
suggestions, dependency/target-SDK notices, backup/icon metadata, and a touch
feedback accessibility warning. They are not a clean bill of health or proof
that every runtime error has been found.

Reproduce the host checks with:

```bash
gradle --no-daemon :app:lintDebug :app:testDebugUnitTest :aster-engine:bundleReleaseAar
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s chromium-engine/afterbird-source/tests/config -v
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/tests -v
```

## Remaining limits

- The pinned Chromium 151 lacks the accessibility snapshot API used by the
  previous AI page-sharing code. The current candidate uses Chromium's page
  content API after explicit consent, with bounded text extraction and private
  tab checks. This replacement needs Chromium compilation and runtime/privacy
  verification; if settings has no browser Activity, it opens AI without page
  text. The consent dialog notes that embedded and offscreen text may be included.

- No physical Quest 3S or emulator runtime test was completed in this environment.
  No on-device startup time, RAM, CPU, battery, or frame-time improvement is
  claimed. Page rendering and controller input remain unverified.
- Persistence is asynchronous. A force-stop, crash, or low-memory kill before a
  pending commit finishes may lose the newest snapshot. The in-memory cache
  protects Activity recreation, not process death. Reopening and restoring every
  tab after process death must be checked on the headset.
- The full Chromium checkout/build output and original signing key are not
  available here. This small environment cannot rebuild the combined ARM64
  browser from its overlay alone. Compiling its Gecko integration AAR does not
  compile Chromium or verify its native ARM64 packaging.
- Existing issues for Tor rendering, AI responses, video playback, extension
  compatibility, and complete session restoration remain open/unverified. This
  change does not claim all browser errors are fixed.

## Quest validation before an APK release

Build the combined ARM64 APK from this exact source with the pinned Chromium
pipeline, using the original signing key for an update installation. Bump package
version metadata consistently before publishing an update. Preserve the user's
profile; do not clear data to conceal a startup failure. Verify the APK's ABI,
version, signature, and checksum. Test both clean installation and upgrading v5.

After enabling USB debugging, completing first-run setup and unlocking the Quest,
run from a computer with Android platform-tools:

```bash
adb devices -l
python3 tools/quest_reopen_check.py --serial YOUR_QUEST_SERIAL --cycles 5 --output /tmp/aster-quest-reopen.json
```

The script uses the installed MAIN/LAUNCHER Activity with Android launcher flags.
Each cycle checks cold launch, Home/resume, and background-process termination
followed by relaunch. It verifies that `am kill` actually stops the process;
otherwise that check is reported as unexercised. It does not clear app data or
logcat. It fails on an app crash/ANR, failed launch, or missing process. Evidence
stays in the chosen local JSON file; exit-info on failures can contain diagnostic
details, so inspect it before sharing. Exit codes: 0 checks passed, 1 app/launch
failure, 2 tooling error or an unexercised process-death check.

In the combined APK the resolved launcher is Chromium. Gecko's Activity is not
exported, so this script cannot directly launch it through ADB. Manually switch
engines and repeat the Quest window-close/reopen operation in each engine; also
test Home, task removal, sleep/wake, and reboot. Open multiple normal/private
tabs, navigate pages, and verify normal tab URLs/count/selection survive while
private tabs do not restore after a restart. Confirm background Gecko media
pauses, foreground media resumes appropriately, and controller input works.
Record the headset OS, APK checksum, engine, exact close action, launch timing,
and filtered ANR/crash evidence.

Only publish an installable release after these checks pass on the exact ARM64
artifact. Do not attach an old upstream v5 APK as though it contains these fixes.
