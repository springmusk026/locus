# Agent browser: design

Status: researched and built 2026-10-31. Code is in `core/browser`. Unit tests cover the pure parts (`BrowserToolsTest`, `SnapshotTest`, `SensitiveActionsTest`). Nothing here has been tried on the phone yet. The device checklist is at the end.

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
- **`addDocumentStartJavaScript` / `addWebMessageListener`** (features `DOCUMENT_START_SCRIPT`, `WEB_MESSAGE_LISTENER`) inject into frames matching origin rules. For an agent that visits any site, the rule would be `*`, which exposes the bridge object to every page and third-party iframe. We keep script injection on demand through `evaluateJavascript` and add no page-reachable bridge. Cross-origin iframe content stays out of reach, and the snapshot says so.
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
| `browser_save(format, path?)` | **new** | Saves the page to the workspace as `markdown`, `mhtml` (archive) or `png` (screenshot of the screen). |
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
- **Hints in the header:** history position (`back: 2`), open tabs, a pending dialog, a likely login form or CAPTCHA (use `browser_ask_user`), and downloads in progress.
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
- **Text the agent may not type.** Card numbers are still refused, and typing into password, card or one-time-code fields is refused with "hand over with browser_ask_user".
- **Uploads always confirm**, naming the files and the site. Paths must be inside the workspace.
- **Read-only runs:** open, snapshot, read, scroll, back, wait, tabs and logs are observations. Click, type, form fill, key presses, dialogs, uploads and saves are not. Downloads are refused.
- **Popups:** only from a user tap or within 2 s of an agent action (agent clicks are synthetic and never carry a gesture). At most 6 tabs. Each tab's URL is shown in the browser UI.
- **Permissions:** geolocation, camera and microphone requests from pages are denied (unchanged default, now explicit).

### Skipped, and why
- **Credential fill from the vault.** The vault holds connector API keys, not site logins, and typing any of them into a page is the exfiltration path prompt injection aims for. Atlas's logged-out mode and Claude for Chrome's guidance both point to the user doing the login. The separate profile keeps that login for later runs. Revisit only with a per-site password store that binds each secret to an origin.
- **`evaluate` / run arbitrary JS.** It's RCE-equivalent (Playwright calls its variant `run_code_unsafe`), it could read what the user typed into password fields, and the model rarely needs it.
- **Hover and drag.** There's no hover on a touch screen, and sites built for phones don't depend on it. Drag is rare and unreliable from synthetic events.
- **Coordinate clicks and screenshots *for the model*.** The provider adapters send text tool results only, and changing them is out of scope here (`core/llm`). `browser_save png` gives the user a screenshot. Image tool results would be the next step for vision models.
- **PDF.** The print callbacks are hidden API. MHTML and Markdown cover "keep a copy"; the Linux workspace can convert if needed.
- **Network request bodies, mocking, storage and cookie tools, tracing.** These are developer-only and opt-in even in Playwright. Failed requests in `browser_logs` cover dev-server debugging.
- **Forward and reload tools.** Reload is `browser_open` of the same URL. Forward is rarely needed by an agent and not offered by Playwright MCP's core set.
- **`addDocumentStartJavaScript` + `WebMessageListener` bridge.** It would need `*` origin rules for arbitrary sites, exposing a bridge to every frame. Polling with `evaluateJavascript` is enough for our async needs.
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
10. `browser_save` markdown, mhtml and png, with the browser on screen and off screen (png off screen may be blank; see Unverified).
11. Start a dev server in Linux with a deliberate 404 and a `console.error`, then check `browser_logs`.
12. A scheduled read-only task that opens and reads a page works; one that tries to click is refused.
13. A browser helper (sub-agent) still takes the lease, and the full-screen view and the chat panel both show the active tab.

## Unverified on device
- Whether `View.draw` produces pixels for an **off-screen** WebView (`png` off screen).
- Whether `isUserGesture` is false for script-dispatched clicks (we allow popups within 2 s of an agent action either way).
- Whether `DataTransfer`-assigned files satisfy every upload widget (most read `input.files` on `change`).
