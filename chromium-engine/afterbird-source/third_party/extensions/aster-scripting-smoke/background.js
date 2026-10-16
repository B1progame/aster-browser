// End-to-end MV3 API smoke check. Results are emitted as one logcat marker.
const MARKER = 'ASTER_EXTENSION_SMOKE:';
let running = false;

function report(result) {
  console.log(MARKER + JSON.stringify(result));
}

// Browser startup restores tab models asynchronously. Waiting here avoids
// racing ChromeTabbedActivity's restore and creating a tab before its model is
// ready (which triggers Chromium's authoritative-model assertion).
chrome.runtime.onStartup.addListener(scheduleRun);
chrome.runtime.onInstalled.addListener(scheduleRun);
scheduleRun();

function scheduleRun() {
  setTimeout(run, 15000);
}

function run() {
  if (running) return;
  running = true;
  const timeout = setTimeout(() => report({ ok: false, error: 'example.com load timed out' }), 45000);
  chrome.tabs.query({}, (allTabs) => {
    const queryError = chrome.runtime.lastError?.message;
    if (queryError || !allTabs?.length) {
      clearTimeout(timeout);
      report({ ok: false, stage: 'tabs.query', error: queryError || 'no tabs returned' });
      return;
    }
    const tab = allTabs.find((candidate) => candidate.active) || allTabs[0];

    function onUpdated(tabId, changeInfo, updatedTab) {
      if (tabId !== tab.id || changeInfo.status !== 'complete' ||
          !/^https:\/\/example\.com\//.test(updatedTab?.url || '')) return;
      chrome.tabs.onUpdated.removeListener(onUpdated);
      clearTimeout(timeout);
      chrome.scripting.executeScript({
        target: { tabId: tab.id },
        func: () => {
          document.documentElement.dataset.asterScriptingSmoke = 'passed';
          return { title: document.title, marker: document.documentElement.dataset.asterScriptingSmoke };
        },
      }, (results) => {
        const scriptingError = chrome.runtime.lastError?.message;
        const result = results?.[0]?.result;
        if (scriptingError || result?.marker !== 'passed' || result?.title !== 'Example Domain') {
          report({ ok: false, tabsQuery: 'passed', tabsUpdate: 'passed', scripting: 'failed', error: scriptingError || 'unexpected result' });
          return;
        }

        chrome.windows.getCurrent({}, (currentWindow) => {
          const windowError = chrome.runtime.lastError?.message;
          if (windowError || currentWindow?.id == null) {
            report({ ok: false, tabsQuery: 'passed', tabsUpdate: 'passed', scripting: 'passed', windowsGetCurrent: 'failed', error: windowError || 'no window id' });
            return;
          }
          chrome.tabs.create({ windowId: currentWindow.id, url: 'https://example.com/', active: false }, (newTab) => {
            const createError = chrome.runtime.lastError?.message;
            report({
              ok: !createError && newTab?.id != null,
              tabsQuery: 'passed',
              tabsUpdate: 'passed',
              scripting: 'passed',
              windowsGetCurrent: 'passed',
              tabsCreate: createError ? 'failed' : newTab?.id != null ? 'passed' : 'unexpected result',
              title: result.title,
              error: createError || '',
            });
          });
        });
      });
    }

    chrome.tabs.onUpdated.addListener(onUpdated);
    chrome.tabs.update(tab.id, { url: 'https://example.com/' }, () => {
      const updateError = chrome.runtime.lastError?.message;
      if (updateError) {
        chrome.tabs.onUpdated.removeListener(onUpdated);
        clearTimeout(timeout);
        report({ ok: false, stage: 'tabs.update', tabsQuery: 'passed', error: updateError });
      }
    });
  });
}
