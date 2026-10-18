# Aster on-device AI

Aster's AI screen runs a LiteRT-LM model in the app process. Prompts and answers are not sent to an AI service. The browser only supplies site context after the user taps **Analyze site**, and that context contains the hostname and whether the URL uses HTTPS. It does not include the page body, URL path, query string, cookies, or browsing history.

## Set up a model

Open **Menu → On-device AI**. Import a compatible `.litertlm` model from device storage, or tap **Get Gemma 4 E2B · 2.59 GB** to download Google's mobile model over Wi-Fi. Aster shows download percentage, transferred bytes, speed, and estimated time remaining; the same button cancels an active transfer. Android's download service handles the transfer and its notification, so it can continue when you leave the AI screen. Aster downloads to an app-private staging file, verifies the model's SHA-256 digest, then moves it into place before loading. An interrupted transfer can be retried, but starts over. Keep at least 3 GB free. The app stores the model in its private app-specific model directory. Aster does not bundle the model in the APK because that would add several gigabytes to every browser update.

The Gemma 4 E2B model is a large download and can use around 1.7 GB of memory while running. The first initialization can take several seconds. The default runtime uses LiteRT-LM's CPU backend for broad Android and Quest compatibility. Inference speed depends on device hardware; lower memory devices may be unable to load this model. Review Google's Gemma model terms before using the model.

The app also accepts other compatible `.litertlm` files. Imported files are copied into Aster's app-specific storage so LiteRT-LM can open them by filesystem path. Aster checks available device memory before loading the large Gemma 4 E2B model and refuses to start it when Android reports less than about 1.85 GB free. Deleting Aster's app data removes imported/downloaded models.

## Use the harness

After selecting a model, tap **Run**. The model status changes to **Ready** when initialization succeeds. Tap **Test** to send a deterministic smoke prompt through real local inference. Use the composer for a multi-turn chat; conversation context is held in memory for that session. The local model also has a small, explicit browser tool harness: it can find regular open tabs by title/URL, open an HTTP(S) address in a new tab, open search results from the selected search engine, and inspect current-page protection configuration. The privacy tool reports settings only; it is not a tracker scan or guarantee that all ads and trackers were blocked. Web search sends its query to that search provider; model inference remains on-device. **Analyze site** passes only the current host and HTTPS state. **Analyze page** is a separate, user-triggered action: Aster's built-in GeckoView extension injects a fixed extraction script into the active regular tab, reads visible text, caps it at 10,000 characters, and sends it only to the local model. If no model is loaded, Aster reports that the bridge works and discards the extracted text. It does not run in private tabs, and page text is treated as untrusted input. The model receives no cookies, browser settings, hidden DOM, or destructive site-data actions. Content extraction cannot run on browser-internal pages, and some sites may provide no readable text.

If no model is installed, the chat stays unavailable and shows setup guidance. Aster does not silently replace local inference with a cloud service or a rule-based answer.

## Chat and browser layout

On phones, the AI opens as a focused chat screen with the current page, recent browser activity, conversation, suggested actions, and a composer fixed at the bottom. Switching to the browser and back keeps the conversation for the current app process. The history is not currently saved across app restarts.

At tablet widths, the AI view keeps the active Gecko page visible beside a chat sidebar. The address bar stays available, and navigation events appear in the sidebar so you can follow the browser as it moves between pages. When a browser tool opens a page in a tab, the active page pane follows the selected tab. On narrow screens, the same information is arranged vertically.

The Chromium source patch `0028-aster-local-ai-chat-companion.patch` adds an Aster AI shortcut to Chromium's new-tab page. It hands HTTP(S) tabs to the installed Aster GeckoView companion and opens the same chat. This is a companion-app handoff, not an AI panel embedded in Chromium's native tab. The source patch cannot be verified as a Chromium build unless the pinned Chromium checkout and Android build environment are available.

## Runtime

The Android app uses `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, initializes `Engine` and `Conversation` on a dedicated worker thread, streams generated output back to the UI, and releases native runtime resources when the activity is destroyed. Model import and generation also run away from the UI thread. The page bridge uses GeckoView's built-in WebExtension and native-messaging APIs; its fixed extraction script is injected only after the user presses **Analyze page**.
