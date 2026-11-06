# Agent browser: design

Status: researched and built 2026-10-31. Code is in `core/browser`. Unit tests cover the pure parts (`SnapshotTest`, `SensitiveActionsTest`, `BrowserArgsTest`, `TransfersTest`, `PageLogsTest`), and the page script was run against test pages in headless Chromium. Nothing here has been tried on the phone yet. The device checklist is at the end.

## What constrains us

- The agent drives one in-app Android **WebView**, not Chrome. There is no CDP: no network domain, no accessibility tree API, no `Input.dispatch*` and no `Page.printToPDF`. What we have is `evaluateJavascript`, a few androidx.webkit additions, the `WebViewClient`/`WebChromeClient` callbacks, and `View` drawing and touch dispatch.
- **The tool list is fixed per conversation**, for prompt caching. Each tool has to earn its place in every request. Arguments select modes instead of extra tools.
- **Approvals.** Each tool has an Allow/Ask/Deny policy, and `Tool.mustConfirm` forces Ask for logins, payments and form submits. Read-only background runs may only open and read (`Tool.isObservation`).
- **Page content is untrusted.** **No AccessibilityService, ever.** **Secrets never reach the model.**
- **The model sees text only.** `ToolResult` is a string, and the provider adapters (`core/llm`, out of scope here) send images only for user attachments.

## Research

### Microsoft Playwright MCP
Source: [README](https://github.com/microsoft/playwright-mcp).
- The model acts on an **accessibility snapshot** (role + accessible name + ref), not pixels. The README notes that "a screenshot can't drive actions" and that the snapshot is preferred.
- Core tools: `browser_navigate`, `navigate_back`, `snapshot`, `click`, `type` (`submit`, `slowly`), `select_option`, `fill_form` (many fields per call), `press_key`, `hover`, `drag`, `file_upload` (paths; none cancels the chooser), `handle_dialog` (accept, `promptText`), `wait_for` (text / textGone / time, capped at 30 s), `tabs` (list/create/close/select), `console_messages` (level filter), `network_requests`, `take_screenshot`, `evaluate`, and `find` (search the snapshot by text, cheaper than a full snapshot).
- Coordinates (`vision`), PDF, storage, tracing and testing are **opt-in capabilities**, kept out of the default list. That is the same "earn your place" rule.
- A JS dialog is reported as a modal state that the model must resolve with `handle_dialog` before doing anything else.

### Chrome DevTools MCP
Source: [tool reference](https://github.com/ChromeDevTools/chrome-devtools-mcp/blob/main/docs/tool-reference.md).
- `take_snapshot` gives a text tree with a `uid` per element: "Always use the latest snapshot". Action tools can return a fresh snapshot (`includeSnapshot`).
- `fill_form` is recommended over repeated `fill`/`click`. `fill` handles both inputs and `<select>`.
- `upload_file` takes the file input **or the element that opens the chooser**.
- `wait_for` waits for any of several texts. `handle_dialog` handles dialogs.
- `list_console_messages` and `list_network_requests` cover the time since the last navigation, with type filters and pagination. Console and network data is aimed at debugging web apps, which is exactly our dev-server use case (`linux_service_start` + `browser_open http://localhost`).

### browser-use
Sources: [tools/service.py](https://github.com/browser-use/browser-use/blob/main/browser_use/tools/service.py), [system prompt](https://github.com/browser-use/browser-use/blob/main/browser_use/agent/system_prompts/system_prompt.md), [agent parameters](https://docs.browser-use.com/customize/agent/all-parameters).
- Elements are indexed `[i]`. "Elements tagged with a star `*[` are the new interactive elements" since the last step (same URL). Locus already used this marker.
- **Multi-action steps** (`max_actions_per_step`, default 5: "for form filling the agent can output 4 fields at once"). "If the page changes after an action, the remaining actions are automatically skipped." For us, batching earns its keep for forms only, which `fill_form` covers.
- A stale index returns a message telling the agent to refresh state, not a crash. Clicking a `<select>` returns its options instead.
- `upload_file` finds the file input nearest the target element. Paths must be files the agent owns.
- `navigate` re-checks for an empty DOM and reloads, which is a reliability fix for slow SPAs.

### Stagehand
Source: [act docs](https://docs.stagehand.dev/v3/basics/act).
- `act` (one atomic action), `observe` (candidate actions with selectors, preview before acting) and `extract` (schema-typed data).
- Advice: keep actions atomic, make sure the page has loaded, check iframes, and pass secrets as variables that are "not sent to LLM providers".
- `observe`/`act` map onto snapshot + click/type. `extract` is an LLM call over page text, which our model already does on `browser_read` output, so we don't need a second model in the loop.

### Anthropic: Claude for Chrome and prompt-injection research
Sources: [Piloting Claude in Chrome](https://claude.com/blog/claude-for-chrome), [Mitigating prompt injection in browser use](https://www.anthropic.com/research/prompt-injection-defenses).
- **Permissions are the first line of defence.** Claude for Chrome asks before "publishing, purchasing, or sharing personal data", even in autonomous mode for highly sensitive actions, and it blocks high-risk site categories.
- Without mitigations, 23.6% of 123 attack cases succeeded. Mitigations cut that to 11.2%. Later model training plus classifiers on "all untrusted content that enters the model's context window" reached about 1%, which "still represents meaningful risk".
- Takeaway for us: mark everything from the page as untrusted, keep outward actions behind confirmation, and keep secrets and the user's other sessions out of reach.

### OpenAI: Atlas agent mode
Sources: [Hardening Atlas against prompt injection](https://openai.com/index/hardening-atlas-against-prompt-injection) (Dec 2025; the page refused automated fetches, so this is from coverage of it), [Simon Willison on the CISO's notes](https://simonwillison.net/2025/Oct/22/openai-ciso-on-atlas/).
- Prompt injection is "unlikely to ever be fully solved".
- Atlas's mitigations: **logged-out mode** (act without the user's credentials), **watch mode** on sensitive sites (the user must have the tab in view), and confirmations before consequential actions.
- Takeaway: Locus's separate WebView profile is our logged-out-by-default mode, and handing the page to the user for logins is the right default. A credential fill that types vault secrets into pages would undo that, so we don't build it (see Skipped).

### Android WebView
Sources: SDK sources for API 36.1 (`android/webkit/WebView.java`, `WebChromeClient.java`, `android/print/PrintDocumentAdapter.java`), [androidx.webkit releases](https://developer.android.com/jetpack/androidx/releases/webkit), [Google Maven metadata](https://dl.google.com/android/maven2/androidx/webkit/webkit/maven-metadata.xml).
- **Version.** androidx.webkit **1.18.0** (2026-10-21) is the latest stable on Google Maven and is what `libs.versions.toml` pins. No bump is needed. 1.17 added `WebViewCompat.navigate` (a trackable `Navigation`). 1.16 stabilised `NavigationListener`/`Page` and added `addJavaScriptOnEvent`.
- **`evaluateJavascript`** runs "in the context of the currently displayed page", on the UI thread, in the **main frame only**. It can't await promises, so async work (blob downloads, uploads) is started in the page and polled. It never returns while a JS dialog blocks the renderer, so every call needs a timeout.
- **`addDocumentStartJavaScript` / `addWebMessageListener`** (features `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`) inject into frames matching origin rules. For an agent that visits any site, the rule would be `*`, which exposes a bridge object to every page and third-party iframe, so we add no `WebMessageListener` bridge and still call the helper through `evaluateJavascript`. The helper script itself (no bridge, nothing native) is added with `addDocumentStartJavaScript` so it runs before the page's scripts and locks `window.__locus`; otherwise a page could define its own `__locus` first and answer the confirmation check falsely (see docs/design/taint.md). Cross-origin iframe content stays out of reach, and the snapshot says so.
- **`onShowFileChooser`.** "To handle each request programmatically, respond to this callback with `true`". Pass `null` to cancel. "WebView does not enforce any restrictions on the chosen file(s)", so we only ever pass workspace files the user approved. The default cancels every request.
- **`onCreateWindow`** needs `setSupportMultipleWindows(true)`. The docs say to refuse windows when `isUserGesture` is false, and warn not to overlay a popup in a way that hides which site is shown. So we treat popups as **tabs** whose URL is shown like the main page's.
- **JS dialogs** (`onJsAlert/Confirm/Prompt/BeforeUnload`): return `true` and always call `JsResult.confirm()/cancel()`, or the page stays blocked.
- **Downloads** (`setDownloadListener`) give the URL, user agent, content disposition, MIME type and length. We fetch the file ourselves with the profile's cookies.
- **Print/PDF.** `createPrintDocumentAdapter` exists, but `PrintDocumentAdapter.LayoutResultCallback`/`WriteResultCallback` constructors are `@hide`. Writing a PDF without the system print UI needs a hidden-API workaround, so we skip it.
- **`saveWebArchive`** (MHTML) is public and asynchronous. `capturePicture` is deprecated in favour of drawing the view.
- **Console** messages come from `onConsoleMessage`. Failed loads come from `onReceivedError` and `onReceivedHttpError`, for subresources too.

## Decisions

### The tool set (fixed; 10 → 17 tools)

| Tool | Status | Why it earns its place |
|---|---|---|
| `browser_open(url, new_tab?)` | kept, extended | Navigation. Clear errors for DNS/refused/HTTP errors, with a hint for localhost. `new_tab` keeps the current page. |
| `browser_snapshot(mode?, find?)` | improved | The accessibility-style view. `mode: interactive \| full`; `find` searches the whole page (Playwright's `browser_find`) without a separate tool. |
| `browser_read(max_chars?)` | kept | Main text as Markdown, the cheapest way to read. |
| `browser_click(ref)` | improved | Scroll-into-view, stability and cover checks; popups, downloads and navigation are reported. |
| `browser_type(ref, text, submit?)` | improved | Refuses password, card and one-time-code fields. Submit is classified like a click. |
| `browser_select(ref, value)` | kept | Lists the valid options on a miss. |
| `browser_fill_form(fields)` | **new** | Many fields (text, select, checkbox, radio) in one call (DevTools/Playwright/browser-use agree). |
| `browser_press_key(key)` | improved | Named keys with real default actions: Enter submits like a browser, Tab moves focus. |
| `browser_scroll(direction?, ref?)` | kept | |
| `browser_back()` | kept | History position is shown in every snapshot header. Reload is `browser_open` of the same URL; forward is rare enough to skip. |
| `browser_wait(text?, gone?, seconds?)` | **new** | Waits for text to appear or vanish, or a pause (cap 30 s). Fixes most "acted too early" failures on SPAs. |
| `browser_tabs(action, index?)` | **new** | Popups such as OAuth windows and `target=_blank` become tabs (cap 6); list/select/close. |
| `browser_dialog(accept, text?)` | **new** | Answers JS `confirm`/`prompt`. Alerts are accepted automatically and reported. |
| `browser_upload(ref, paths)` | **new** | Workspace files to a file input or the button that opens the chooser. Always confirms (data leaves the phone). |
| `browser_save(format, path?, full_page?)` | **new** | Saves the page to the workspace as `markdown`, `mhtml` (archive) or `png`: the viewport, or with `full_page` the page scrolled through and stitched (capped; see "Screenshots"). |
| `browser_logs(kind?)` | **new** | Console messages and failed requests since the last navigation, for debugging dev servers. |
| `browser_ask_user(reason)` | kept | Handoff for logins, CAPTCHAs and payments. Snapshots now flag likely login/CAPTCHA pages. |

**Downloads** need no tool. A download started by a page (link, button, `browser_open` of a file) is saved to `downloads/` in the workspace and reported in that action's result. In read-only runs it is refused, since it writes files.

### Snapshot
- **Structured in, text out.** The page script returns JSON (header facts and one record per element). `Snapshot.kt` (pure Kotlin, unit-tested) formats it as lines like `[e12] button "Sign in"`, with `*` on elements new since the last snapshot of the same page.
- **Diffs to save tokens.** After an action on the same URL, if the new snapshot shares most lines with the previous one, the result lists only the added and removed lines plus the header (`Changes since the last snapshot: …`). `browser_snapshot` always returns the full view.
- **Stable refs without touching the DOM.** Refs live in a `WeakMap` (element → ref) plus a `Map` of ref → `WeakRef`. The old `data-locus-ref` attribute mutated the page, which frameworks can notice and which triggered our own mutation observer. A ref stays the same while its element lives.
- **Visibility.** `checkVisibility()` where available (opacity, visibility and `content-visibility`), plus `aria-hidden` and `inert`. Zero-size elements are skipped. Elements near the viewport are listed by default, everything in `full` mode.
- **Modal scope.** When a modal dialog is open, only its elements are listed. That is what a person can reach, and it stops clicks on covered elements.
- **Shadow DOM** (open roots) and **same-origin iframes** are walked. Iframe elements get frame offsets, so taps land in the right place. Cross-origin frames are listed as `[frame] host (cross-origin; open its URL to use it)`.
- **Hints in the header:** history position (`back: 2`), open tabs, a pending dialog, and a likely login form or CAPTCHA (use `browser_ask_user`). Downloads, new tabs, alerts and load errors are reported as events at the top of the next result.
- **Redaction:** password, card and one-time-code values are never returned (unchanged).

### Actions and reliability
- **Before a click:** scroll into view, wait until the element's rect is stable across two frames, refuse disabled elements, and check what is on top at the centre point. If something covers it, the error names the covering element and says what to do (close the banner or dialog first).
- **Real input.** On screen we keep real taps (trusted events). Off screen we dispatch from script. Enter/Tab default actions are emulated (implicit form submission, focus order) because synthetic key events have no default action.
- **After an action** we wait for a navigation if one started, then for the page to settle (no DOM changes for 500 ms and no fetch/XHR in flight, capped). Navigation, new tabs, dialogs and downloads that the action caused are reported in its result.
- **Every `evaluateJavascript` call has a timeout.** A timeout while a dialog is pending becomes "A confirm dialog is open… use browser_dialog".
- **Errors say what to do next**, for example "No element e40 on this page; refs change after navigation. Take a new snapshot.", "e12 is covered by `div` "Accept cookies"; click that first", "Could not connect to localhost:3000. Is the server running? Check with linux_service_list."

### Safety
- **Untrusted marking.** Every piece of page-derived text (snapshot, read, logs, dialog messages, tab titles, file names) is wrapped in `<untrusted_web_content>`. A closing tag inside page text is neutralised, so a page can't end the block early (unit-tested).
- **Sensitive-action classification** (`SensitiveActions.kt`, pure and unit-tested). A click, an Enter, or a `type`/`fill_form` with submit asks first when any of these holds:
  - The label says pay/buy/order/delete/send/… (existing word list).
  - It submits a form that has a password field (login or sign-up).
  - It submits a form that has payment fields.
  - It submits any form that isn't a search form (role=search, a search input, or a single query field with GET).
  - It accepts a confirm dialog whose message is sensitive.
- **Text the agent may not type.** Card numbers are still refused, and typing into password, card or one-time-code fields is refused with "hand over with browser_ask_user". This fails closed: with no facts about the field (it's gone, or the page didn't answer) nothing is typed, and the page script checks the field again in the same call that types, so a field that turned into a password field after the check is refused too.
- **Uploads always confirm**, naming the files and the site. Paths must be inside the workspace.
- **Taint and the site you named.** Every browser result taints the run (docs/design/taint.md). Clicks, typing, selects, form fills, key presses and dialog answers report the page's host (`Tool.pageAction`, `PageActions`), read from the browser's own record of the chat's page. On a host the user named in the chat they get only the checks above, so browsing there doesn't ask on every step. On any other host they ask, and the card offers **Allow site** for the rest of the task. Typing after the run read private data (a file, an email) asks on every host and can't be allowed per site. See taint.md, "Browsing a site you named".
- **Read-only runs:** open, snapshot, read, scroll, back, wait, tabs and logs are observations. Click, type, form fill, key presses, dialogs, uploads and saves are not. Downloads are refused.
- **Popups:** only from a user tap or within 2 s of an agent action (agent clicks are synthetic and never carry a gesture). At most 6 tabs. Each tab's URL is shown in the browser UI.
- **Permissions:** geolocation, camera and microphone requests from pages are denied (unchanged default, now explicit).

### One browser per chat

`AgentBrowser` is one app-wide singleton, but until now it also had a single set of tabs, one active tab, one ref namespace and one global "downloads allowed" flag. Two tasks running at once (two chats, a chat and a scheduled run, a parent and a background helper) drove the same page: chat B's `browser_open` moved the page chat A had just taken a snapshot of, so A's next `browser_click e12` landed on whatever B's page had at `e12`. The download flag was set from each call's read-only state and reset to "allowed" in `finally`, outside the browser lock, so a read-only run's page could download into the workspace while another chat's call was running, or after its own call ended.

- **Sessions keyed by conversation.** Each conversation id (`ToolCallContext.conversationId`) has its own tabs, active tab, pending dialog, handoff, events, pop-up grace and file-chooser state, with its own lock. Tools don't name a session: `AgentBrowser` takes it from the call's context, so the tool list and descriptions are unchanged (no prompt-cache cost). `AgentLoop` now runs `mustConfirm` with the call's context too (as it already did for `precheck`), so the sensitive-action check inspects the calling chat's page. Calls without a context (none in the app) use the chat on screen.
- **Helpers get their own session.** A helper runs as its own conversation (its agent id), so it gets its own tab. Inheriting the parent's would bring back the bug: a background helper and its parent working at once would move each other's page. Cookies and storage are one WebView profile for all sessions, so a login the user made in one chat's tab still counts in the helper's. `SubagentEngine`'s browser lease (one helper in the browser at a time) is unchanged; it now bounds memory rather than guarding correctness.
- **Refs belong to a tab and a document.** After each snapshot, `BrowserSessions.snapshotTaken` records the tab id and the page's document id (`__locus.doc`, see below). Every action that takes a ref (click, type, select, fill_form, scroll in an element, upload) first checks the session's active tab is the one the snapshot came from ("Those refs are from a snapshot of another tab…"), and then runs only if the page is still that document, checked in the same script call as the action ("The page changed since the last snapshot… Take a new snapshot"). Tools also check the tab in `precheck`, so nobody is asked to approve a call that can only fail. Within one document, refs from older snapshots stay valid: they name the same element for as long as it exists (that's the WeakMap design above), so a "snapshot version" finer than the document would only add failures.
- **Download permission per session, at the moment the download starts.** Each call records its conversation's read-only state on its session (`BrowserSessions.setReadOnly`), and it is kept, not reset, when the call ends. The download listener checks the owning session's policy when the page starts the download, so a timer-delayed download is judged by the run that drove that page there. A session that no call has set yet doesn't download.
- **The screen shows the chat you're in.** A chat's live panel shows that chat's tab (`WebViewHost(…, conversationId)`), and opening a chat makes it the focused one, which the full-screen browser shows (tabs, dialog, Done for a handoff). Chats read their own `stateOf(conversationId)`.
- **Memory is bounded.** Each tab is a WebView with a renderer behind it. At most 6 tabs per chat (unchanged) and 8 across all chats (`BrowserSessions.MAX_LIVE_TABS`). When a tab would go over, or whenever a tab opens, chats idle for 30 minutes and then the least recently used chats lose their tabs. A chat with a call running or a handoff waiting, the chat on screen, and the chat asking are never picked. If that isn't enough, the call is refused with what to do. A chat whose tabs were closed is told so on its next browser call.

`BrowserSessions` (pure Kotlin, `BrowserSessionsTest`) holds the bookkeeping: refs, download policy, busy and last-used times, and eviction. `AgentBrowser` holds the WebViews.

### Trusting the page helper

The gate above is only as good as the facts `__locus.inspect` reports, and those come from script running in the page's own JavaScript world. Two things could make them lies: a page that defines its own `window.__locus` before ours runs, and our helper getting in only after the page's scripts.

- **Document start, checked rather than assumed.** The helper is registered with `WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))` (androidx.webkit 1.9+, feature `DOCUMENT_START_SCRIPT`). The [reference](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)) says the script "will run before any of the page's JavaScript code" in "any frame whose origin matches", and "will only run in frames which begin loading after the call returns", which is why it is registered as each tab's WebView is created, before any load. It then locks `window.__locus` (non-writable, non-configurable) and its functions. Before this change a failed registration was swallowed and the `onPageFinished` fallback injected the helper after the page's scripts, where `if (window.__locus.v === 2) return` let a page's own `__locus` win.
- **A per-tab secret on every answer.** Each tab gets 128 random bits (`PageReply.newSecret`). Only the copy registered at document start has it substituted in; the fallback copy (evaluated before each call and on `onPageFinished`, for WebViews without the feature) has an empty one. Every answer goes back through `__locus.seal`, which returns `{k: secret, d: documentId, r: result}`, serialised with `JSON.stringify` and `Object.keys` captured at document start and its own walker, so a later `Object.prototype.toJSON` or replaced `JSON.stringify` can't rewrite it. `PageReply.open` marks an answer verified only when `k` matches the tab's secret (constant-time compare).
- **Unverified facts fail closed.** `ElementFacts.verified` comes from that check, never from the page. A click, submit or activating key (Enter, Space) on unverified facts asks the user (`SensitiveActions.untrusted`: "this phone's WebView can't protect the page check"), and so does typing or `fill_form`. On a current WebView this never happens on normal pages; on a WebView without `DOCUMENT_START_SCRIPT` every such action asks. Reads (snapshot, read, scroll, wait) are unaffected.
- **Missing means unknown.** `ElementFacts.parse` requires every field with the right JSON type. A missing or mistyped field makes the whole answer unknown (null), and every gate treats null as "ask" (`unverified`); typing refuses. Before, a missing boolean read as `false`, the harmless value.
- **The page didn't change in between.** The helper gives each document a random id (`__locus.doc`, locked). Facts carry the id of the document they describe, and `type`/`fill` run only if the page is still that document, in the same script call (otherwise: "The page changed since it was checked… Take a new snapshot").

What the secret doesn't do, and why that's acceptable:
- A page can call `__locus.seal` itself and so learn the secret, but only in a document where our helper ran first and is locked in place, so it can't swap in its own `__locus` there. Using it elsewhere would need a document in the same tab where the document-start script didn't run; with the `*` rule that doesn't happen for http(s) pages.
- The facts are still read from a DOM the page controls. A hostile page can redefine DOM getters (`HTMLInputElement.prototype.type`, say) or simply label a pay button "Next" and do the payment in script. The gate is a heuristic that catches the agent being steered into consequential actions on honest sites; it is not a boundary against a site that is itself malicious. Taint (docs/design/taint.md) and the separate profile are the defences there.

Channels looked at and not used:
- **`addWebMessageListener`** (feature `WEB_MESSAGE_LISTENER`) authenticates the *frame* (`sourceOrigin`, `isMainFrame`), not the script: the reference says the object is injected into "any frame whose origin matches" and that with `"*"` "the app must treat received messages as untrustworthy and validate any data carefully". Any page script in that frame can post the same messages our helper would, so it adds no proof that our helper answered, and it is asynchronous where `evaluateJavascript` gives us a result directly.
- **Isolated execution worlds.** androidx.webkit 1.16 added `JavaScriptExecutionWorld`, `WebViewCompat.getExecutionWorld`, `addJavaScriptOnEvent` and a world-scoped `addWebMessageListener` (feature `JS_INJECTION_IN_FRAME_AND_WORLD`). A helper in its own world would have globals the page can't touch, which is the real fix. But there is no evaluate-in-world call (the helper would have to answer by message, a rewrite of every call into request/response), the [reference](https://developer.android.com/reference/androidx/webkit/JavaScriptExecutionWorld) doesn't state what isolation a world gives, and support depends on the WebView build. It's the next step once it's documented and common; until then the per-tab secret plus fail-closed gates is what we rely on.

### Take over and hand back

Status: built 2026-11-06; unit-tested (`BrowserControlTest`, `BrowserTakeoverTest`, `AddressInputTest`, the address-bar cases in `BrowserTaintTest`, `SnapshotTest`), not yet tried on the phone.

Before this, control changed hands only when the agent asked (`browser_ask_user`). The full-screen browser accepted touches at any time, so a user who tapped while a run was acting raced it: a click the agent planned from its last snapshot could land on a page the user had just changed. Only the document-id ref check caught that, and not within a single-page app.

**What other agents do.**
- **OpenAI Operator / ChatGPT agent:** the user takes over the virtual browser ("…" → Take over browser), and "while you control the browser, screenshots are not captured, which helps protect passwords and other sensitive data you enter". The agent picks up from the page as the user left it ([ChatGPT agent help](https://help.openai.com/en/articles/11752874-chatgpt-agent), 2025-07; the article now says the agent has moved to ChatGPT Work. Operator's launch post, 2025-01-23, says it "does not collect or screenshot information entered by the user" in takeover mode: [introducing Operator](https://openai.com/index/introducing-operator/), wording seen second-hand because openai.com refuses automated fetches).
- **Manus:** in its cloud browser Manus prompts the user to **Take Over** at SMS codes, CAPTCHAs and two-factor steps; the user finishes, then hands control back and Manus continues ([cloud browser](https://manus.im/docs/features/cloud-browser); [help: taking over](https://help.manus.im/en/articles/11711218-how-can-i-take-over-manus-browser-or-vs-code)). The user can also take over on their own from the task. The docs don't say what Manus sees meanwhile and suggest the local Browser Operator for sensitive logins.
- **Browser Use Cloud:** a person drives through the session's `live_view_url` only after the run has stopped at a checkpoint ("Wait for the run to stop at the checkpoint before letting a person interact"); a follow-up run in the same session continues "from the current page", and the docs say to treat the live-view URL as a credential ([human takeover](https://docs.browser-use.com/tips/live-view/human-takeover), read 2026-11-06).

They agree on an explicit switch, the agent stopped rather than racing the person, and resuming from whatever state the page is in. Operator adds that the agent doesn't observe what the person entered.

**What Locus does.**
- **Take over.** While the chat's run is working and the page is on screen, the full-screen browser is watch-only: a banner reads "Locus is browsing" with **Take over**, and a tap on the page says so instead of reaching it (a tap would race the agent's real taps). Take over (`AgentBrowser.takeOver`, `BrowserControl`) marks the chat's browser as the user's. The page, address bar, back, forward and reload are theirs until they hand back.
- **The agent waits, it isn't refused.** Every browser tool call of that chat goes through `takeoverGate`: it waits while the user has control, then runs. Inside `AgentBrowser` every page operation for a tool call (`action`, `logs`) waits too, so a call already running when the user took over takes no snapshot after its action until they hand back. Nothing about the page reaches the model while the user has it: no snapshot, read, find, screenshot or logs.
- **Timeout.** A call waits at most 5 minutes (`BrowserControl.WAIT_MS`, long enough to sign in with a code). Then it fails with a result that says the user still has the browser, nothing was read, and the agent should continue without the browser or ask the user, not retry in a loop. The user keeps control. The banner says "Locus is waiting for you" while a call waits.
- **Hand back** opens a sheet with an optional **note**. The note goes to the chat as the user's own message (`AgentRunner.send`): queued behind the running turn, it joins at the next step boundary, right after the browser call that was waiting returns. It never goes into a tool result, which is untrusted page territory, and as the user's words its hosts count as named. The waiting call's result (or the next browser call's) starts with a notice: how long the user had control, that the page may have changed and refs no longer apply, that typed values show as `value=<typed by the user, hidden>`, and whether a note follows. Refs are void after a takeover (`BrowserSessions.userTookOver`: "The user had the browser since your last snapshot… Take a new snapshot") even within one document, and the next snapshot is shown in full rather than as a diff.
- **Leaving the browser hands back** without a note, so a run never waits out the timeout for someone who has gone back to the chat.
- **`browser_ask_user`** is the agent-started version of the same state ("Your turn"). Its **Done** button now opens the same hand-back sheet, so the user can add a note there too. If the agent asks while the user already has control, the hand-back answers it: the user isn't asked again.
- **Values the user typed stay hidden.** The page helper records text fields that received a trusted `input` event (the keyboard; scripts can't make one) in a WeakSet, also in same-origin frames and open shadow roots, and reports them as `entered` without their value. The agent's own typing (`setValue`, including `execCommand` in rich editors, whose events are trusted too) clears the mark, so the field is described again once the agent writes into it. `PageSnapshot.parse` drops any value that comes with `entered`, whatever the page sent. This holds for anything the user types into the page, during a takeover, a "Your turn" hand-over or while just browsing. Password, card and one-time-code fields stay hidden as before.
- **No new tool and no prompt change.** The pause lives inside the existing browser tools, the notice in their results, the note in a user message. Tool list, tool descriptions and system prompt are unchanged, so no chat loses its prompt cache.

### The address bar

The full-screen browser has back, forward, reload (stop while loading) and an address bar (`AddressField` in core/designsystem; see [Chrome](#chrome) for where each sits). It is usable whenever the user drives: no run is acting, the user took over, or the agent asked for them. While Locus is browsing it shows the address read-only.

- **What it opens** (`AddressInput`): an http(s) URL as typed; a bare host (`github.com/x`, `localhost:3000`) over https, or http for loopback and IP addresses; anything else is a DuckDuckGo search. Other schemes (`file:`, `intent:`) are refused with a message.
- **A typed host counts as named** for taint in that chat, as if typed in the chat (`Taint.hostsIn` on the typed text). `AgentBrowser.addressBarHosts` keeps them per chat, and `AgentRunner` adds them to the run's trusted hosts, which every gated call reads, so a host typed mid-run counts from the next call. Helpers read the same list through their parent's limits.
- **Only typed text counts.** The bar starts empty when the user taps it (the page's address shows only at rest), so whatever is submitted is the user's own text, never the page's URL. Navigation the page causes (links, redirects, `window.open`) goes through the WebView and never through `userOpen`. A page saying "the user named evil.example" doesn't add it (`BrowserTaintTest`).
- **Kept in memory**, like the tabs themselves: after the app restarts, a host typed only in the address bar has to be typed again (or named in the chat).
- **Load errors** show over the WebView's own error page as Locus's error state ("example.org couldn't be reached: the address could not be found"), with Try again when the user drives.

**Opening the browser on its own.** The Workspace's top bar has a **Browser** button beside **Terminal**. It opens "Your browsing", a session that belongs to no chat: there is no agent, so no Take over, and hosts typed there count for no chat. It is still Locus's browser profile, so a site the user signs in to there stays signed in for every chat's browser (until Clear browsing data). That is the reason to have it: sign in once, then ask a chat to work there. It goes beside the terminal because that is where the other user-driven tool lives; Home's launcher row is five doors designed to fit one row at 360 dp, and a sixth would not. A "use this tab in a chat" hand-over was left out: a chat can open the same address, and the login is shared already.

### Chrome

Status: built 2026-11-06 (`BrowserToolbar` and `AddressField(security, trailing)` in core/designsystem; `BrowserChromeTest`, `BrowserScreenshots`), not yet tried on the phone.

The browser used two rows above the page: a top bar (back to the chat, the page title, clear data) and a row of back, forward, the address and reload. On a 360 × 780 dp phone that was 120 dp of chrome under a 24 dp status bar, so with a 24 dp gesture bar the page got 612 dp, 78% of the screen. It is now **one row**, 52 dp, and the page gets 680 dp, 87%.

- **The row** (`BrowserToolbar`): close (✕, "Back to chat", or "Close browser" for Your browsing), the address pill, the tab count once there are two tabs, and the overflow menu. While typing an address the pill takes the whole row, as before. Its bottom edge is a hairline that becomes the load progress line, drawn inside the row so the page never moves when loading starts or stops.
- **The pill** shows the host only (`booking.com`, without `www.`), led by a padlock over HTTPS, a caution triangle over plain HTTP, or a globe for anything else; TalkBack reads "Address: booking.com, secure connection". A long host is cut from the start (`…account.booking.com`), because a host reads from the right. Reload, or Stop while loading, sits at the pill's end, where it acts on the address beside it.
- **The overflow** (`BrowserMenu`) starts with the page's title and full address, which the bar no longer shows, then Back, Forward, Copy link, Open in another app (the phone's browser, through `ACTION_VIEW`; a message when nothing handles web links) and Clear browsing data.
- **System back** ends address editing first, then goes back through the page's history while the user drives, then leaves the browser (`browserBack`). Before, it always left, even with history to go back through. While Locus drives, back leaves at once: that history is the agent's, and the page is watch-only.
- The Take over, Your turn and Hand back banners, the error state and the empty state are unchanged.

What other browsers do, and why this layout:

- Chrome, Firefox, Samsung Internet and Brave on Android all keep one toolbar: the address with the site's security state, and an overflow menu that holds back, forward and reload (Chrome 150 put Back at the top of that menu in July 2026, [Android Headlines](https://androidheadlines.com/2026/07/google-chrome-on-android-is-finally-getting-a-real-back-button.html)). Chrome ([Google blog, June 2025](https://blog.google/products-and-platforms/products/chrome/address-bar-position-change/)), Firefox ([Mozilla support](https://support.mozilla.org/en-US/kb/move-navigation-bar)) and Brave ([Brave](https://brave.com/whats-new/bottom-address-bar/)) can move it to the bottom. Locus keeps it on top: the bottom already holds the Take over and Your turn banners, and every other Locus screen has its bar on top.
- Material 3 allows a top app bar to stay put or to hide on scroll ([M3 app bars](https://m3.material.io/components/app-bars/guidelines)); Compose's `enterAlwaysScrollBehavior` does the second through nested scrolling ([TopAppBarDefaults](https://developer.android.com/reference/kotlin/androidx/compose/material3/TopAppBarDefaults)). The menu is Material's `DropdownMenu` with 48 dp items ([Compose menus](https://developer.android.com/develop/ui/compose/components/menu)). Back through page history before leaving is the WebView guide's own pattern ([Navigate web page history](https://developer.android.com/develop/ui/views/layout/webapps/webview)).

**Hide on scroll was left out.** Compose can only see a child View's scrolling if the View dispatches nested scroll ([Compose nested scrolling interop](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/nested-scroll)), and WebView doesn't (Google's Ian Lake: "WebView doesn't support nested scrolling at all", [Kotlin Slack](https://slack-chats.kotlinlang.org/t/512616/have-i-found-a-defect-with-androidview-when-used-with-a-webv)). The workaround, watching `setOnScrollChangeListener` and resizing the page area, resizes the page's viewport on every reveal: the page re-lays itself out and fires `resize`, near the end of a page the shorter or taller viewport changes `scrollY` and can flip the bar back, and the agent, which reads and taps this same WebView, would see the viewport change between its snapshot and its tap. Chrome avoids all three because its renderer knows the toolbar height and scrolls it in the compositor; WebView exposes nothing like that. Drawing the bar over the page instead would hide the page's own top bar. One fixed 52 dp row already gives the page 87%.

**Known gaps.** The chat's small live panel still accepts touches while the agent acts (the race is unchanged there; Take over is in the full-screen browser). Text the user typed into a rich editor (`contenteditable`) is hidden in snapshots but `browser_read` returns the page's text, which includes it. The user's own file picker is still missing.

### Skipped, and why
- **Credential fill from the vault.** The vault holds connector API keys, not site logins, and typing any of them into a page is the exfiltration path prompt injection aims for. Atlas's logged-out mode and Claude for Chrome's guidance both point to the user doing the login. The separate profile keeps that login for later runs. Revisit only with a per-site password store that binds each secret to an origin.
- **`evaluate` / run arbitrary JS.** It's RCE-equivalent (Playwright calls its variant `run_code_unsafe`), it could read what the user typed into password fields, and the model rarely needs it.
- **Hover and drag.** There's no hover on a touch screen, and sites built for phones don't depend on it. Drag is rare and unreliable from synthetic events.
- **Coordinate clicks and screenshots *for the model*.** The provider adapters send text tool results only, and changing them is out of scope here (`core/llm`). `browser_save png` gives the user a screenshot. Image tool results would be the next step for vision models.
- **PDF.** The print callbacks are hidden API. MHTML and Markdown cover "keep a copy"; the Linux workspace can convert if needed.
- **Network request bodies, mocking, storage and cookie tools, tracing.** These are developer-only and opt-in even in Playwright. Failed requests in `browser_logs` cover dev-server debugging.
- **Forward and reload tools.** Reload is `browser_open` of the same URL. Forward is rarely needed by an agent and not offered by Playwright MCP's core set. (The user has both in the full-screen browser.)
- **`WebMessageListener` bridge.** It would need `*` origin rules for arbitrary sites, exposing a bridge to every frame. Polling with `evaluateJavascript` is enough for our async needs. (`addDocumentStartJavaScript` is used, for the helper script only.)
- **User-side file picker** (the user taps an upload button themselves). It needs an Activity result flow from a WebView callback. Left as a known gap; the agent can upload for them.

## Device test checklist
1. Open a news site: the snapshot lists headings and links with refs, and `browser_read` returns Markdown.
2. Click through a cookie banner. A covered-element error should name the banner.
3. Search on DuckDuckGo with `browser_type submit=true`: no confirmation (search form). On a contact form, `submit` asks.
4. A login page: the snapshot shows the login hint, typing into the password field is refused, and `browser_ask_user` hands over. After Done, the agent continues.
5. `browser_fill_form` on a multi-field form with a select and a checkbox.
6. A `target=_blank` link and an OAuth popup ("Sign in with Google"): a tab appears, the tabs indicator shows 2, `browser_tabs` lists and switches them, and closing the popup returns to the opener.
7. `confirm()` and `prompt()` test pages (e.g. a JS dialog demo): the result reports the dialog, `browser_dialog` answers it, and alerts are auto-accepted.
8. Download a PDF link: it is saved under `downloads/` and opens in Workspace. Repeat with a blob download (a "Export CSV" button on a web app).
9. Upload a workspace image to a file input (e.g. an image converter site). Confirmation names the file and site.
10. `browser_save` markdown, mhtml and png, with the browser on screen and off screen, including after the panel was opened and closed; png with and without `full_page` on a short and a long page.
11. Start a dev server in Linux with a deliberate 404 and a `console.error`, then check `browser_logs`.
12. A scheduled read-only task that opens and reads a page works; one that tries to click is refused.
13. A browser helper (sub-agent) still takes the lease, works in its own tab, and the parent chat's page doesn't move while it works.
16. Two chats at once: in chat A open a shop page and stop; in chat B start a task that browses elsewhere; back in A, ask it to click something from its snapshot. It clicks on A's page. Each chat's panel shows its own page, and the full-screen browser opened from a chat shows that chat's tabs.
17. A scheduled (read-only) task opens a page that starts a download after a few seconds, while another chat is browsing: nothing is saved, and the scheduled run's next result reports the refused download.
18. Open tabs in four or five chats and leave them, then browse in another: older chats' tabs close (their chat is told on its next browser call), and the app doesn't run out of memory.
14. Trusted helper: on a current WebView, a search on DuckDuckGo and "Next page" clicks don't ask, and a "Pay now"/login button still does. Typing into a password field is refused even if its `type` changes from text to password after the snapshot.
15. Old WebView (or a phone where System WebView updates are disabled, e.g. an emulator image with WebView older than 87): clicks and typing ask with "this phone's WebView can't protect the page check"; snapshots and reading still work.
19. Named site: "search wikipedia.org for Kathmandu, then open the second result". After the first page loads, clicks, scrolling and typing in the search box don't ask. A "Sign up"/"Log in" button still asks.
20. Unnamed site: "find a pancake recipe" (no site named). The first click on a result site asks, with **Allow site**. Tap it: further clicks and typing on that host don't ask; a click that leads to another host asks again. The next message's task asks again.
21. Private data, then typing: "read notes.md, then search duckduckgo.com for its title". Typing the search asks (card shows the text), with no **Allow site**; clicks don't.
23. Take over: ask "find a hotel in Lisbon on booking.com", open the full-screen browser while it works. The banner says Locus is browsing; tapping the page shows "Locus is using this page" with Take over. Take over, scroll and type in the search box: the agent's next step waits (banner: "Locus is waiting for you"). Hand back with no note: the agent continues, its result says you had control, and the search box shows `value=<typed by the user, hidden>` (check "What was sent").
24. Hand back with a note ("only 4 stars and up"): the note appears in the chat as your message and the agent follows it.
25. Take over and wait 5 minutes: the agent's call gives up with "hasn't handed it back", the chat goes on, and you keep the browser.
26. Take over, then press Back to the chat: the agent continues at once.
27. Address bar: in a chat that never named a site, type `wikipedia.org` in the bar, then ask the agent to click the first article. No approval card. A link on that page to another site still asks when the agent opens it.
30. Chrome: the page starts right under one row (✕, the pill, ⋮). The pill shows the host with a padlock (a triangle on an `http://` site); a long host keeps its end. ⋮ shows the title and full address, Back and Forward (disabled at the ends of history), Copy link, Open in another app (opens your own browser) and Clear browsing data. System back steps back through pages, then returns to the chat; while typing an address it closes the field first. While Locus is browsing, back returns to the chat at once. At 200% font the row grows and nothing is cut but the host's start.
28. Address bar: back, forward (in ⋮), reload and stop work; `file:///sdcard` says only web addresses open; a typo like `bookng.com` shows "couldn't be reached" with Try again.
29. Workspace → Browser: "Your browsing" opens empty; sign in to a site, go back, then in a chat ask the agent to open that site: it is signed in. Returning to a chat shows that chat's page again.
22. With the email integration set up: "what is your email address?" The agent calls `email_list` and answers with the mailbox's address, never the password.

## Screenshots
`browser_save png` draws the WebView into a bitmap (`View.draw`). Three facts shape it:
- **A WebView that has been in a window and was taken out stops drawing.** Chromium's `BrowserViewRenderer::IsClientVisible()` is `!was_attached_ || (attached_to_window_ && window_visible_)`; when it's false the begin-frame source is cut off, so `View.draw` repeats the last frame. The agent's WebView is attached whenever the chat panel or the full-screen browser shows it. After either closes, the old frame is panel-sized, `layoutOffscreen` then lays the view out at screen size, and the rest of the picture was blank. So unless the view is on a visible screen, the capture lends it a hidden window: a `Presentation` on a private `VirtualDisplay` (no permission needed; `OffscreenWindow`). It then waits for `postVisualStateCallback`, draws, and puts the view back. If that fails, the tool reports an error, never a blank picture.
- **The draw must be translated by `-scroll`.** WebView's software draw adds the scroll offset back (`SoftwareCanvasHolder`), expecting the parent's translation. Without it, a scrolled page is drawn shifted down.
- **Only the viewport is painted.** WebView draws the part of the document near the visible rect; `WebView.enableSlowWholeDocumentDraw()` would lift that, but it is process-wide, must be called before the first WebView, and costs memory and speed for every WebView in the app (artifact previews, Mermaid). `capturePicture` is deprecated, and printing to PDF lays the page out for paper. So `full_page` scrolls the window a viewport at a time and stitches, as browser "full page" extensions do. Fixed and sticky elements shorter than 40% of the viewport are hidden after the first screen, then restored, as is the scroll position. `CapturePlan` caps the page at 15,000 px and the image at 8 megapixels and 8,192 px on the long edge. The result text gives the size, whether it's the viewport or the full page, and any scaling or cut-off. A page that scrolls inside a panel rather than the window is captured as its viewport, and the result says so.

## Unverified on device
- That the `OffscreenWindow` capture works on every Android version from 8 (before Android 12 a Presentation has no window context).
- Full-page stitching on pages with lazy loading or infinite scroll.
- Whether `isUserGesture` is false for script-dispatched clicks (we allow popups within 2 s of an agent action either way).
- Whether `DataTransfer`-assigned files satisfy every upload widget (most read `input.files` on `change`).
