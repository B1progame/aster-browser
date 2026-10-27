# Aster Browser roadmap

This is a proposal list, not a promise that a feature is already implemented. Priorities are based on user value and the current verification gaps. Reorder them as issues and device testing provide evidence.

## Next up: import bookmarks and history

Let the user import data from a browser they select, starting with bookmarks and history.

- Use Android's system document picker so the user chooses an exported file; do not try to read another app's private database or bypass its sandbox.
- Start with common portable formats such as Netscape Bookmark HTML and a documented JSON format. Add browser-specific formats only after testing real exports.
- Show source, number of records, date range, duplicates, and destination engine before import.
- Let the user choose Gecko or Chromium, merge or replace supported data, and undo the import.
- Keep the file on-device; do not upload browser history.

## Priority backlog

1. **Reliable tab/session recovery.** Persist tabs, groups, selected tab, and scroll/restore metadata safely across process death and engine switching. Keep the engine URL registry separate from cookies and extension profiles. Add tests for crash/restart and large tab sets.
2. **On-device AI that completes real requests.** Improve model download/resume, memory preflight, cancellation, progress, and result streaming. Add a local test harness for both engine surfaces and verify current-page context stays local unless the user explicitly sends it.
3. **Chromium Tor window completion.** Fix Chromium page rendering over the Tor tunnel, prove the request exits through a Tor circuit, and block navigation when Tor is unavailable. Show the route only when it is actually associated with the page stream.
4. **Extension compatibility dashboard.** Publish a per-API support matrix and tested extension list for Afterbird, separate from Gecko add-ons. Report unsupported APIs and permissions before installation; never promise all desktop Chrome extensions.
5. **Cross-engine browser-data tools.** Offer explicit, reviewable import/export for bookmarks and history where each engine's API allows it. Keep cookies, passwords, and extension data isolated unless a safe, user-authorized migration path exists.
6. **Android-to-PC handoff.** Finish the in-development share flow with paired-device confirmation, content selection, and a preview that excludes credentials and private data by default.
7. **Release/update validation.** Test signed upgrades from the latest public release on both ABI families. Verify rollback/recovery and ensure release assets always match their checksums and source tag.
8. **Tablet and Quest 3S quality pass.** Test responsive layouts and controller focus on physical hardware. The manifest entry alone is not Quest verification; keep all tracking permissions/features out.

## How a proposed item becomes complete

- Define the behavior and its privacy boundaries.
- Implement for each engine where the feature is claimed to work; show unavailable state where it is not supported.
- Add focused tests and a reproducible device test record.
- Update the README and close the related GitHub request only after the requested behavior passes its test.
