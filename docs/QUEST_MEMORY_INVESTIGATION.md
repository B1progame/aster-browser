# Quest 3S memory-pressure investigation

## Report and limits

The user shared a Quest system notification: Aster was closed because active
memory was nearly exhausted; the notification reported that Aster needed
0.2 GiB. It does not identify a leak, an extension, or the total RAM used by
all Aster processes. The user later said no further problem was occurring;
there is no repeatable scenario, trace, OS version, extension inventory, or
before/after measurement yet. Do not equate this notification with storage
space or assume the reported 0.2 GiB is free system RAM.

The published v5.2.0 test APK has internal version 5.0.0. Determine the
installed APK/checksum and whether the warning recurs before comparing builds.

## Source findings and hypotheses

* Chromium's Gecko-to-Chromium handoff in patch 0027 opens each imported HTTP(S)
  URL as a new tab. Large imports could cause a transient load/memory spike;
  the screenshot does not establish that a handoff happened. Profile first.
* Gecko creates tab session objects when restoring, opens a session on selection,
  and deactivates the previous session. Previously selected sessions can remain
  open until the tab or Activity is closed. Test the real memory cost and
  preservation of history, forms, media and extension state before considering
  background session eviction; this is not a demonstrated Chromium cause.
* A historical repository profiler report observed roughly 30–65 MB PSS per
  extension renderer on its test environment. This is an old, workload-specific
  observation, not a measurement on the Quest, not a fixed per-extension cost,
  and does not establish that the user's extensions caused this warning.
  Chrome documents that extension service workers normally terminate after
  30 seconds of inactivity; events can reset the timer. Confirm actual worker
  activity and Chromium subprocesses on the candidate before drawing conclusions.
* The bundled APK includes two engines; packaging native libraries does not
  prove both engines are resident in RAM. Measure engine startup and engine
  switches instead of summing APK library sizes.

## Investigation plan

1. On Quest 3S, record headset OS, APK hash, engine, tab count, enabled
   extensions, other open apps and reproduction steps. Do not collect URLs,
   browsing history or extension data without the user's consent.
2. Compare cold start, one tab, several tabs and engine handoff, with extensions
   enabled versus temporarily disabled. Repeat after identical warm-up and idle
   periods. Record Quest memory warnings and whether Aster is actually killed.
3. With a trusted ADB connection, use `adb shell dumpsys meminfo app.aster.browser`
   and a system-wide `dumpsys meminfo` when permitted. Inspect all relevant
   renderer, GPU, utility and extension processes; a single PID or PSS number
   is not necessarily total package memory. Keep raw reports local and review
   for sensitive information before sharing. Capture the same measurements
   over time and compare runs, not just one sample.
4. If measurements show a problem, investigate lazy tab restoration/import,
   extension worker activity and freeing genuinely recreatable UI caches in
   response to Android memory callbacks. Preserve private tabs, form state,
   audio/video and engine switching behavior. Avoid forced garbage collection,
   unsafe process coalescing, arbitrary background tab kills, or security
   isolation changes as unmeasured “RAM boosts”.
5. Validate any proposed fix with the same workload and record PSS, peak memory,
   memory-pressure events, startup time, page responsiveness, and session
   correctness. A change is not a proven improvement without that comparison.

## References

* [Android memory management](https://developer.android.com/topic/performance/memory)
* [Chrome DevTools: fix memory problems](https://developer.chrome.com/docs/devtools/memory-problems)
* [Chrome extension service worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)
* [Historical Aster extension profiler report](../chromium-engine/afterbird-source/docs/superpowers/reports/2026-04-18-stability-profiler.md)
