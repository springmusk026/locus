# Agent browser: design

Status: researched and built 2026-10-31. Code is in `core/browser`. Unit tests cover the pure parts (`SnapshotTest`, `SensitiveActionsTest`, `BrowserArgsTest`, `TransfersTest`, `PageLogsTest`), and the page script was run against test pages in headless Chromium. Dropdown handling is tested on fixture pages in a real WebView (`DropdownsTest`, app/src/androidTest). Nothing here has been tried on the phone yet. The device checklist is at the end.

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
- **Modal scope.** When a modal dialog is open, only its elements are listed. That is what a person can reach, and it stops clicks on covered elements. Open menus and lists are listed too, wherever they are (see "Forms and dropdowns").
- **Shadow DOM** (open roots) and **same-origin iframes** are walked. Iframe elements get frame offsets, so taps land in the right place. Cross-origin frames are listed as `[frame] host (cross-origin; open its URL to use it)`.
- **Hints in the header:** history position (`back: 2`), open tabs, a pending dialog, and a likely login form or CAPTCHA (use `browser_ask_user`). Downloads, new tabs, alerts and load errors are reported as events at the top of the next result.
- **Redaction:** password, card and one-time-code values are never returned (unchanged).

### Actions and reliability
- **Before a click:** scroll into view, wait until the element's rect is stable across two frames, refuse disabled elements, and check what is on top at the centre point. If something covers it, the error names the covering element and says what to do (close the banner or dialog first).
- **Real input.** On screen we keep real taps (trusted events). Off screen we dispatch from script. Enter/Tab default actions are emulated (implicit form submission, focus order) because synthetic key events have no default action.
- **After an action** we wait for a navigation if one started, then for the page to settle (no DOM changes for 500 ms and no fetch/XHR in flight, capped). Navigation, new tabs, dialogs and downloads that the action caused are reported in its result.
- **Every `evaluateJavascript` call has a timeout.** A timeout while a dialog is pending becomes "A confirm dialog is open… use browser_dialog".
- **Errors say what to do next**, for example "No element e40 on this page; refs change after navigation. Take a new snapshot.", "e12 is covered by `div` "Accept cookies"; click that first", "Could not connect to localhost:3000. Is the server running? Check with linux_service_list."

### Forms and dropdowns

Status: built 2026-11-06. The agent failed on dropdowns whose options are "not in the same layer" as the control: menus and lists that a site appends to `<body>` (a portal) with `position: fixed` and a high z-index, usually far from the control in the DOM. It did fine on sites with native `<select>`s. Code: `locus-agent.js` (snapshot, taps, `selectStart`/`selectFind`/`selectCheck`), `DropdownSelect.kt` (the browser_select flow), `Snapshot.kt` (formatting). Tests: `SnapshotTest`, `DropdownSelectTest` (JVM), and `DropdownsTest` (app/src/androidTest; a real WebView on hand-written fixture pages, one per pattern below).

**What the others do.**
- **Playwright** ([actionability](https://playwright.dev/docs/actionability); `packages/injected/src/injectedScript.ts`, `packages/playwright-core/src/server/dom.ts`):
  - Before a click it waits until the element is visible, *stable* ("the same bounding box for at least two consecutive animation frames"), enabled and *receives events* (it is the hit target at the click point, checked with `elementFromPoint` through shadow roots). It retries with backoff, and scrolls differently on each retry "to scroll from under position:sticky elements".
  - `selectOption` follows a label to its control, refuses non-`<select>` elements ("Element is not a <select> element") and disabled options, sets `option.selected`, then fires `input` (bubbles, composed) and `change` (bubbles).
  - Its new WebView backend (`packages/injected/src/webview/webViewInput.ts`) has the same constraint as us, with no CDP. It hit-tests at dispatch time and taps with touchstart, touchend, mousedown, mouseup, click.
- **Browser Use** (`browser_use/tools/service.py`, `watchdogs/default_action_watchdog.py`):
  - It has separate `dropdown_options` and `select_dropdown` actions. For an ARIA combobox it expands the control, waits 0.5 s and reads `getElementById(aria-controls)`.
  - Its snapshot marks new elements `*` and summarises a `<select>` with its first 4 options.
  - After typing into a combobox it tells the model to click a suggestion rather than press Enter.
- **Stagehand** (`packages/extension/prompt.ts`, `services/actService.ts`):
  - It uses `selectOption` for a `<select>`. Anything else is a "two-step" action: click to expand, re-snapshot from the Chrome accessibility tree (`Accessibility.getFullAXTree`), diff, and act only on what changed.
- **The accessibility tree** (Puppeteer's `page.accessibility.snapshot`, Chrome DevTools) places nodes by `aria-owns` rather than DOM position. [WAI-ARIA 1.2](https://www.w3.org/TR/wai-aria-1.2/#aria-controls) defines `aria-controls` as the element "whose contents or presence are controlled by the current element". The [APG combobox pattern](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/) sets `aria-expanded` and `aria-controls` on the combobox and keeps DOM focus there (`aria-activedescendant`).
- **The libraries** render their popups in different places:
  - MUI's Select menu is a Popover, a portal to `<body>` that sets `aria-hidden` on the rest, and it opens on **mousedown**.
  - Radix Select portals to `<body>`. It opens on pointerdown for a mouse and on click for touch, and selects on pointerup for a mouse and on click otherwise.
  - react-select opens on mousedown or touchend, can portal (`menuPortalTarget`), and selects on click.
  - Select2 attaches its dropdown "to the end of the body" by default. Its docs point to `dropdownParent` for the known trouble inside Bootstrap modals.

We have no accessibility tree and no CDP, only a script in the page. So the helper rebuilds the parts of the tree that matter here: who owns which popup, and where it is.

| Pattern | Before | Root cause | Now |
|---|---|---|---|
| Native `<select>` (single, optgroups) | `browser_select` set `el.value` and fired input/change. The snapshot showed only the selected text, so the model guessed option names. | Options weren't listed. Matching ignored disabled options and optgroups. | The snapshot lists the first 8 options ("Asia: Nepal", "(disabled)") and the count. Matching tries the exact value or label, then a unique start, then a unique part, accents ignored. A disabled option or optgroup is refused. The input then change sequence is Playwright's. Errors list the options. |
| `<select multiple>` | `el.value = x` replaced the selection with one option. | Single-value assignment. | `"Ham \| Olives"` selects exactly those; the snapshot says `selected (multiple)=`. |
| `<datalist>` suggestions | Not shown. | Not read. | Listed as `options:` on the input. |
| Custom ARIA combobox / listbox button (MUI, Radix, Headless UI, Angular Material, Choices.js) | `browser_select` refused ("not a native dropdown… click it"). The model clicked, then had to find the options in the next snapshot. | No custom path. | `browser_select` opens it, finds its popup (aria-controls/aria-owns, the `aria-activedescendant` list, or what appeared), finds the option by text, taps it and checks what the control shows. The snapshot says `opens=listbox`, `expanded`, `highlighted=[e41]`. |
| **Portals** (menu appended to `<body>`, fixed, high z-index) | Listed last, in DOM order, with nothing tying them to the control. Cut off after the 250th element on long pages. **Dropped entirely while a modal was open**: Select2 in a Bootstrap modal, any portal outside a dialog. | The snapshot walked only the modal, in document order, with one element cap. | Open popups are found wherever they are, including inside the modal's scope, and are never counted against the page cap. Each is listed right after its control: `open listbox "Country" from [e12] (195 choices, 50 listed, scrolls):` with its options indented below. With no ARIA link, timing ties it to the control just acted on or the focused field (`near [e12]`). |
| What an action opened | A line diff; a new menu was some `+` lines at the bottom. | No popup notion. | The result starts with `Opened: listbox "Country" from [e12] (…)` or `Closed: …`. |
| Menus that open on mousedown or pointerdown (MUI, react-select, Select2, Radix with a mouse) | Off screen: pointerdown, mousedown, `focus()`, pointerup, mouseup, then `el.click()` at no coordinates. It mostly worked. | Order and focus differed from a browser's: focus was forced even when the page cancelled mousedown to keep it in the field, which closes autocompletes on blur. | A tap in a phone browser's order: pointerover/enter, pointerdown, touchstart, pointerup, touchend, then mouseover/enter, mousemove, mousedown, focus, mouseup, click. All at the hit-tested point, on the element actually there. A cancelled pointerdown drops the mouse events, a cancelled touchend drops the click, and a cancelled mousedown keeps focus where it is. On screen it is still a real tap. |
| Hover and focus menus | Off screen, no enter events, so menus that open on a wrapper's mouseenter stayed shut. | No enter or leave events. | Enter events go to every element the pointer enters and leave events to what it leaves, so JavaScript hover menus open and stay open while an item inside is tapped. CSS-only `:hover` menus still need a real pointer: they work on screen, not off screen. |
| Virtualised long lists (react-window, MUI virtualised Autocomplete) | Only rendered rows were visible; the model had to scroll and look again. | No search. | `browser_select` types into the list's search field when there is one; otherwise it scrolls the list (up to 60 times) until the option is rendered. The header gives the full size from `aria-setsize`. |
| Autocomplete (type, then suggestions arrive) | The suggestions showed up in a diff, if the 500 ms quiet wait caught them, at the end of the page. | No popup notion, no waiting for the filter. | `browser_select` on the field types the value, waits for the page to settle (requests done, DOM quiet, short animations finished) and polls up to 4 s, then taps the match. `browser_type` results show the suggestion list under the field. |
| Open shadow roots, same-origin iframes | Walked, so their elements were listed. | Popups inside a shadow root have selectors that can't see them. | Floating listboxes and menus in shadow roots are recognised during the walk. `aria-controls` ids are resolved in the control's own root. Iframe selects work as native selects. |
| Date pickers | `type=date` silently dropped a value in the wrong format and reported success. A custom picker's grid wasn't listed. | Value not checked; grid cells weren't treated as interactive. | `browser_type` says which format the field takes ("YYYY-MM-DD"). A picker opened from a control (`aria-controls`, role dialog or grid) is listed as a popup, its grid cells included. |
| Covered elements | The centre point was hit-tested once. Any cover gave "covered by …". | No retry, one point. | Up to 1.5 s of retries (a menu sliding open, an overlay fading). Four more points inside the element are tried when the centre is covered by a sticky header or badge. When the cover is an open menu or its backdrop: "is behind the menu [e12] opened… pick an option or press Escape". |
| Stale refs after a re-render | "No element e45… take a new snapshot". | Refs follow element identity, and frameworks replace nodes. | A ref whose element is gone resolves to the one element that now has the same role and name, as a Playwright locator would. With none or several, it still fails. |
| Animated menus | The settle step waited for a quiet DOM, not for CSS transitions. | Not checked. | Settle also waits for running animations of up to 2 s (never for endless spinners), capped as before. |

**Safety is unchanged in substance.**
- A native select is set in the page without a gate, as before (on a verified page).
- A custom dropdown is opened and an option tapped. That is a click, so it goes through the click gate for the control. An option whose text reads like an outward action ("Delete", "Send") asks, as clicking it would.
- On a WebView whose page helper isn't verified, `browser_select` asks, as clicks do.
- Typing into a list's search field refuses password, card and one-time-code fields. A value that looks like a card number is refused before anything happens (`SensitiveActions.refuseText`).
- Taint treats `browser_select` as choosing, as before. The text it may type is the option the model was asked to pick.

**Tool list.** No tool was added and no argument changed. `browser_select`'s description now says it handles custom dropdowns too. A description is part of the tool schema, so the first request of each existing chat after the update writes its prompt cache once more. Later requests hit it as before.

**Still not handled.**
- CSS-only `:hover` menus off screen. A script can't set `:hover`, and an unattached WebView gets no real pointer. With the browser on screen, real taps do hover.
- Canvas-drawn dropdowns (some Flutter web and game UIs), and closed shadow roots.
- Cross-origin iframes: the payment and address widgets on many checkouts.
- Lists that load more options only when scrolled to the bottom through an `IntersectionObserver`, if the WebView isn't drawing frames (off screen). The `scroll` event is sent, but observers wait for a frame.
- A popup with no ARIA link that appears more than 30 s after the action, or for no action at all. It is listed as an ordinary element when it is near the screen.
- Drag-to-select ranges, sliders as dropdowns, and multi-step pickers (month, then day) beyond tapping each step.

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

### Skipped, and why
- **Credential fill from the vault.** The vault holds connector API keys, not site logins, and typing any of them into a page is the exfiltration path prompt injection aims for. Atlas's logged-out mode and Claude for Chrome's guidance both point to the user doing the login. The separate profile keeps that login for later runs. Revisit only with a per-site password store that binds each secret to an origin.
- **`evaluate` / run arbitrary JS.** It's RCE-equivalent (Playwright calls its variant `run_code_unsafe`), it could read what the user typed into password fields, and the model rarely needs it.
- **Hover and drag tools.** There's no hover on a touch screen, and sites built for phones don't depend on it; a tap sends the pointer's enter events, which opens script hover menus ("Forms and dropdowns"). Drag is rare and unreliable from synthetic events.
- **Coordinate clicks and screenshots *for the model*.** The provider adapters send text tool results only, and changing them is out of scope here (`core/llm`). `browser_save png` gives the user a screenshot. Image tool results would be the next step for vision models.
- **PDF.** The print callbacks are hidden API. MHTML and Markdown cover "keep a copy"; the Linux workspace can convert if needed.
- **Network request bodies, mocking, storage and cookie tools, tracing.** These are developer-only and opt-in even in Playwright. Failed requests in `browser_logs` cover dev-server debugging.
- **Forward and reload tools.** Reload is `browser_open` of the same URL. Forward is rarely needed by an agent and not offered by Playwright MCP's core set.
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
22. With the email integration set up: "what is your email address?" The agent calls `email_list` and answers with the mailbox's address, never the password.

Dropdowns ("Forms and dropdowns"). Try each twice: once with the browser panel open (real taps) and once with it closed (scripted taps). Each should take one `browser_select` call, and the result should say what was selected.

23. Native select: https://the-internet.herokuapp.com/dropdown. "Choose Option 2." The snapshot lists `options:`.
24. APG select-only combobox: https://www.w3.org/WAI/ARIA/apg/patterns/combobox/examples/combobox-select-only/. "Pick Banana." APG autocomplete: https://www.w3.org/WAI/ARIA/apg/patterns/combobox/examples/combobox-autocomplete-list/. "Pick Wyoming." After typing, the suggestions show as `open listbox … from [eN]`.
25. MUI Select (portal, opens on mousedown): https://mui.com/material-ui/react-select/ ("Age: Twenty"). MUI Autocomplete: https://mui.com/material-ui/react-autocomplete/ (a film from the first demo). Further down the same page, the virtualised demo (option "MKZ…").
26. Radix Select: https://www.radix-ui.com/primitives/docs/components/select ("Banana"). Headless UI Listbox: https://headlessui.com/react/listbox (a person's name). react-select: https://react-select.com/home ("Purple" in the first demo).
27. Select2: https://select2.org/getting-started/basic-usage (a state). Choices.js: https://choices-js.github.io/Choices/ (single select with search).
28. Angular Material select: https://material.angular.dev/components/select/overview. Bootstrap dropdown (no aria-controls): https://getbootstrap.com/docs/5.3/components/dropdowns/ ("Another action").
29. A dropdown inside a modal: Select2's `dropdownParent` example (https://select2.org/dropdown), or any site's address dialog with a country picker. The open list is listed while the dialog is open.
30. Shadow DOM: Shoelace / Web Awesome select, https://shoelace.style/components/select ("Option 2").
31. Date: APG date picker combobox, https://www.w3.org/WAI/ARIA/apg/patterns/combobox/examples/combobox-datepicker/. Open it: the calendar grid is listed under the field. `browser_type` with "11/06/2026" into a native date input (e.g. https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/date, in its live sample) explains the format if refused.
32. Hover menu (limitation): https://the-internet.herokuapp.com/hovers is CSS `:hover`. With the panel open a tap shows the caption; with it closed it doesn't, as documented.

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
