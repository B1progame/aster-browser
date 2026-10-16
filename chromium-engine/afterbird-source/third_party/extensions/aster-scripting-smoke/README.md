# Aster Android MV3 scripting smoke fixture

This unpacked test extension checks that Android Chromium has working browser
implementations for `chrome.tabs.query`, `chrome.tabs.update`,
`chrome.scripting.executeScript`, `chrome.windows.getCurrent`, and
`chrome.tabs.create`; it does not infer support from a schema or JavaScript
binding alone. After Android restores its tab model, it navigates an existing
tab to `https://example.com/`, queries the tab, injects a DOM marker, reads the
current window, creates a background tab, and emits a JSON
`ASTER_EXTENSION_SMOKE:` line into Chromium's console log. A pass requires the
live page title to be `Example Domain` and all API callbacks to return valid
results. The delay is important: the first attempt raced Android's tab restore
and `tabs.create` returned `No current window`.

Run on a debuggable Aster Chromium build with the test extension folder copied
to app-readable storage, using the `--load-extension=<path>` command-line flag.
Inspect `adb logcat` for `ASTER_EXTENSION_SMOKE:`. Do not clear the browser
profile to run this check; remove the temporary extension after verification.
