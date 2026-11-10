# Quest 3S reopening — source candidate

This is a draft source candidate, not an installable or Quest-verified release.
No APK is attached. Existing upstream v5.0.0 APKs do not include these changes.

Changes move shared startup diagnostics off the main thread, coalesce Gecko
session/privacy saves on a worker, preserve the current snapshot during Activity
recreation, and pause hidden Gecko sessions/media. Chromium patch syntax and
configuration tests are repaired. A device reopen-check tool is included.
The Tor HTTP relay also uses an Android-29-compatible copy loop, proxy calls
have local feature guards, and the Tor animation reuses its drawing path.

Android compilation, 18 Android unit tests, the integration release AAR build,
15 Chromium configuration/patch checks, and five test-tool host tests pass.
Android lint passes with zero errors and 148 warnings remaining.
See `docs/QUEST_REOPEN_REVIEW.md` for the results and remaining limits.

The reported Quest 3S ANR has not been reproduced or confirmed fixed on hardware.
The combined ARM64 APK must be rebuilt, signed, and physically tested before
publication. Immediate process death can still lose an unfinished state write.
