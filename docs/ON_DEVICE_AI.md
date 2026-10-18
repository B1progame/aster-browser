# Aster on-device AI

Aster's AI screen runs a LiteRT-LM model in the app process. Prompts and answers are not sent to an AI service. The browser only supplies site context after the user taps **Analyze site**, and that context contains the hostname and whether the URL uses HTTPS. It does not include the page body, URL path, query string, cookies, or browsing history.

## Set up a model

Open **Menu → On-device AI**. Import a compatible `.litertlm` model from device storage, or open **Get a model** and choose one of the verified downloads:

- **Qwen3 0.6B · compact** — about 329 MiB, Apache 2.0, pinned to a specific LiteRT Community model revision. The LiteRT-LM model configuration supports Qwen3 tool-call parsing and fits Aster's constrained browser tool set. This is the recommended download for phones with limited storage. It is a small general model, so answers and tool choices can be less reliable than the larger model.
- **Gemma 4 E2B · stronger** — about 2.59 GB, available under Gemma terms. It needs more storage and working memory.

Aster shows download percentage, transferred bytes, speed, and estimated time remaining; the same button cancels an active transfer. Android's download service handles the transfer and its notification, so it can continue when you leave the AI screen. Aster downloads to an app-private staging file, verifies the pinned model's byte length (when specified) and SHA-256 digest, then moves it into place before loading. An interrupted transfer can be retried, but starts over. The compact model needs about 500 MiB free for download and verification; Gemma needs at least 3 GB. Models stay in Aster's app-specific model directory and are not bundled in the APK.

The Gemma 4 E2B model can use around 1.7 GB of memory while running. The 0.6B Qwen model is much smaller on disk, but CPU memory use varies with runtime, context, and device; Aster checks available memory before loading. First initialization can take several seconds. The default runtime uses LiteRT-LM's CPU backend for broad Android and Quest compatibility. Inference speed depends on device hardware. Review the model's license before using it.

The app also accepts other compatible `.litertlm` files. Imported files are copied into Aster's app-specific storage so LiteRT-LM can open them by filesystem path. Aster checks available device memory before loading the large Gemma 4 E2B model and refuses to start it when Android reports less than about 1.85 GB free. Deleting Aster's app data removes imported/downloaded models.

## Use the harness

After selecting a model, tap **Run**. The model status changes to **Ready** when initialization succeeds. Tap **Test** to send a deterministic smoke prompt through real local inference. Use the composer for a multi-turn chat; conversation context is held in memory for that session. The local model also has a small, explicit browser tool harness: it can find regular open tabs by title/URL, open an HTTP(S) address in a new tab, open search results from the selected engine, and inspect current-page protection configuration. Common browser commands use validated local routing so smaller models can still complete tab search, explicit URL opening, web search, and privacy inspection when they fail to emit a tool call. Other prompts use LiteRT-LM's model tool calling. The privacy tool reports settings only; it is not a tracker scan or guarantee that all ads and trackers were blocked. Web search sends its query to that search provider; model inference remains on-device. **Analyze site** passes only the current host and HTTPS state. **Analyze page** is a separate, user-triggered action: Aster's built-in GeckoView extension injects a fixed extraction script into the active regular tab, reads visible text, caps it at 10,000 characters, and sends it only to the local model. If no model is loaded, Aster reports that the bridge works and discards the extracted text. It does not run in private tabs, and page text is treated as untrusted input. The model receives no cookies, browser settings, hidden DOM, or destructive site-data actions. Content extraction cannot run on browser-internal pages, and some sites may provide no readable text.

If no model is installed, the chat stays unavailable and shows setup guidance. Aster does not silently replace local inference with a cloud service or a rule-based answer.

## Chat and browser layout

On phones, the AI opens as a focused chat screen with the current page, recent browser activity, conversation, suggested actions, and a composer fixed at the bottom. Switching to the browser and back keeps the conversation for the current app process. The history is not currently saved across app restarts.

At tablet widths, the AI view keeps the active Gecko page visible beside a chat sidebar. The address bar stays available, and navigation events appear in the sidebar so you can follow the browser as it moves between pages. When a browser tool opens a page in a tab, the active page pane follows the selected tab. On narrow screens, the same information is arranged vertically.

The Chromium source patch `0028-aster-local-ai-chat-companion.patch` adds an Aster AI shortcut to Chromium's new-tab page. It hands HTTP(S) tab URLs and titles to the installed Aster GeckoView companion, then opens the same local AI chat. It does not transfer cookies, private tabs, model files, or site data, and the chat runs in GeckoView rather than inside Chromium. The app dismisses any stale menu or site-protection sheet during the handoff. The source patch cannot be verified as a new Chromium build unless the pinned Chromium checkout and Android build environment are available.

## Runtime

The Android app uses `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, initializes `Engine` and `Conversation` on a dedicated worker thread, streams generated output back to the UI, and releases native runtime resources when the activity is destroyed. Model import and generation also run away from the UI thread. The page bridge uses GeckoView's built-in WebExtension and native-messaging APIs; its fixed extraction script is injected only after the user presses **Analyze page**.
