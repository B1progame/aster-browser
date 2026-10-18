"use strict";

const nativePort = browser.runtime.connectNative("aster_ai");

nativePort.onMessage.addListener(async (request) => {
  if (!request || request.type !== "analyze-active-page" || !Number.isInteger(request.requestId)) return;

  try {
    const candidates = await browser.tabs.query({});
    const tab = candidates.find((candidate) => candidate.url === request.url)
      || candidates.find((candidate) => candidate.active && /^https?:\/\//i.test(candidate.url || ""));
    if (!tab || !Number.isInteger(tab.id) || !/^https?:\/\//i.test(tab.url || "")) {
      nativePort.postMessage({ type: "page-error", requestId: request.requestId, error: "No regular webpage is active." });
      return;
    }
    const [page] = await browser.tabs.executeScript(tab.id, {
      code: `(() => {
        const bodyText = document.body?.innerText || "";
        const text = bodyText.replace(/[\\t\\f\\v ]+/g, " ").replace(/\\n{3,}/g, "\\n\\n").trim().slice(0, 10000);
        return { title: document.title || "", text };
      })()`
    });
    nativePort.postMessage({
      type: "page-result",
      requestId: request.requestId,
      url: tab.url,
      title: typeof page?.title === "string" ? page.title.slice(0, 180) : "",
      text: typeof page?.text === "string" ? page.text.slice(0, 10000) : ""
    });
  } catch (_) {
    nativePort.postMessage({ type: "page-error", requestId: request.requestId, error: "This page did not provide readable text." });
  }
});
