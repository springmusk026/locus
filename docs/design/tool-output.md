# Tool output, parallel reads and cache lifetimes

How a turn's tool calls run, how their results are fitted into the transcript, and how long the provider keeps the prompt cache. Items #5, #7 and #8 of [the algorithms research](../research/agent-algorithms-2026.md). The code is `AgentLoop.executeAll` and `bound`, `LongOutput`, `SavedOutput` (core/agent) and `AnthropicChatModel.buildBody` (core/llm).

## Long output goes to a file

Every result passes through one step in `AgentLoop` (`bound`) before it is stored, audited and sent to the model.

- **Short results** (up to 16,000 characters in a chat, 8,000 in a helper) are kept as they are.
- **Long results** keep their start (about two thirds) and end (one third) inline. Between them is one line: how many characters were cut, `Full output: .locus/out/<file>`, and how to read it (`search_files` with that path, or `read_file` with `start_line` and `max_lines`). Cuts land on line breaks where one is close.
- **The whole result** is saved to `.locus/out/<time>-<rand>-<tool>.log` in the workspace, after the vault's values and credential-looking tokens are scrubbed (`SecretScrubber`). Saving happens through `AgentConfig.spill` (`WorkspaceOutputSpill`, from `ToolCatalog.outputSpill`).
- **`run_command`** already has its whole output on disk: output past what it keeps in memory exists only in the spool file, scrubbed by `OutputRedactor` with the secrets Locus gave the command. It returns its shortened copy with `ToolResult.savedTo`, and the loop names that file instead of saving another.
- **Opt-outs.** Tools that page their own output set `Tool.pagesOwnOutput`: `read_file` (`start_line`, `max_lines`), `chat_history_read` (`offset`) and the browser tools (bounded snapshots, then page diffs). Their results, and any result that couldn't be saved, are only capped at `AgentConfig.maxResultChars` (60,000), head and tail, with a note that nothing was saved.
- **Clean-up.** The newest 30 files are kept (`SavedOutput.KEEP`; run_command kept 20 when it was the only tool saving). Files from before this change (`cmd-<time>-<rand>.log`) are sorted by their time with the new ones. `.locus/.gitignore` keeps the folder out of the user's repository.

Cursor writes long tool and MCP output to files and has the agent read them when it needs to ([dynamic context discovery](https://cursor.com/blog/dynamic-context-discovery)); Claude Code does the same for long Bash output. Before this, every tool but `run_command` was cut to its first 60,000 characters with nothing kept, so a helper's single page read could take about 96k tokens of its budget.

### Marked text stays marked

Results that carry outside text wrap it in `<untrusted_web_content>` … `</untrusted_web_content>` (`UntrustedContent`). A cut can run through such a block, or through several. `LongOutput.shorten` keeps the wrapping balanced:

1. A cut point that falls inside a marker moves to the marker's start, so no marker is split.
2. If the kept start ends inside a block, the block is closed before the note.
3. If the kept end starts inside a block (its first marker is a closing one), the block is opened again after the note.

So the note, which is Locus's own text, is always outside any block, and no outside text loses its marking. Taint (docs/design/taint.md) is decided from the full result before it's shortened.

## Calls that only look run together

`executeAll` splits a turn's calls into runs. A run of two or more consecutive calls that pass `runsTogether` starts them all at once (`coroutineScope { async }`). Every other call runs on its own, in order. A call qualifies when:

- it only looks (`Tool.isObservation`);
- it would run without asking: Allow for the user's policy, no chat limit (plan mode, a switched-off group), no forced confirmation (`mustConfirm`), and not covered by the safety reviewer;
- it doesn't need to run alone (`Tool.runsAlone`): the browser tools (one WebView, and the helper's browser lease), `ask_user` (waits for the user), `todo_write` and `plan_propose` (state later calls read), `send_file` (files arrive in order) and the helper tools (their own admission and limits);
- taint couldn't hold it back. This is checked as if the run were already tainted. Run one at a time, a `web_read` taints the run, and a `fetch_url` to a site the user never named then asks. Side by side, the fetch would already be running. So a call taint can gate never runs beside another.

Whether a call qualifies is decided when the loop reaches it, after every call before it has finished. A turn like read, read, write, read runs the two reads together, then the write, then the last read.

**Order.** Results go into the transcript in the order the model made the calls, whatever order they finish in. Each call's events, taint, bounded result and audit entry are handled in that order too. A call's events wait until the calls before it are done, since a flow can only be emitted to from its own coroutine. The transcript is the same as a one-at-a-time run.

**Step boundary, budgets, change tracking.** All calls of a step finish before the step's tool message is stored. Budgets, compaction and the user's queued messages all act at the step boundary, so they see the same thing as before. Calls that run together only look, so they record no workspace changes (docs/design/change-tracking.md).

**Cancellation.** Stop cancels every running call. Calls that finished keep their results; the rest are answered "Cancelled by the user before it finished", so the transcript has no unanswered call.

**Not covered.** If the user changes a tool's policy while a batch runs, a call already in the batch may still ask. Its approval card then appears without the matching event until its turn. This needs a policy change during a read lasting a few seconds.

Codex runs tools that support parallel calls at the same time (`codex-rs/core/src/tools/parallel.rs`). MCP tools qualify when the server marks them `readOnlyHint` (`handlers/mcp.rs`). Locus's MCP tools are observations under the same rule (`McpToolRisk`).

## Cache lifetimes

**Anthropic** ([prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching), read 2026-11-01). Locus sets two breakpoints:

- **The frozen prefix** (tools and system prompt) gets `cache_control: {"type": "ephemeral", "ttl": "1h"}`.
- **The moving breakpoint** (end of the conversation so far) keeps the default 5 minutes.

On a phone, the gaps between a chat's requests often pass 5 minutes: the user reads, approves a call, or answers `ask_user` (up to 30 minutes). Each such gap meant writing the whole prefix again.

What the docs say:

- **Ordering.** "Cache entries with longer TTL must appear before shorter TTLs (that is, a 1-hour cache entry must appear before any 5-minute cache entries)." The prefix always comes before the messages, so this holds.
- **Pricing.** A 5-minute write costs 1.25× base input and a 1-hour write 2×. A read costs 0.1×, except 0.05× on Claude Opus 5.5 and Sonnet 5.5 and 0.025× on Fable 5.1 and Mythos 5.1. On Opus 5.5 the price per million tokens is $4 input, $5 for a 5-minute write, $8 for a 1-hour write and $0.20 for a read. A read refreshes the lifetime at no cost.
- **No beta header.** The page sets `ttl` on `cache_control` and names no beta. The pricing table gives a 1-hour write price for every model it lists.
- **Platforms.** The page lists the 1-hour lifetime on the Claude API, Amazon Bedrock, Claude Platform on AWS, Claude Models on Google Cloud (Google managed) and Microsoft Foundry.

Locus sends `ttl` only to Anthropic's own API. A gateway that speaks the Messages format may not know the field, and an unknown field can be a 400. Other base URLs keep the default lifetime on both breakpoints, which also satisfies the ordering rule.

**Measuring hits.** `usage.cache_creation` splits writes by lifetime (`ephemeral_5m_input_tokens`, `ephemeral_1h_input_tokens`). Locus stores the 1-hour part as `TokenUsage.cacheWrite1h` and prices it at 2× input. models.dev's write price is the 5-minute one. Every request already stored its cache reads and writes. Now:

- the chat's context sheet lists them for the latest 8 requests (`#12 48k in · 45k read · 1.2k written`) and the chat's total written;
- Insights shows `cache … read · … written (… for 1 h)` under the token total.

A warm cache reads nearly the whole prompt and writes only the newest messages. After a gap of more than 5 minutes but less than an hour, the prefix should still be read, not written.

**OpenAI** ([prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching), read 2026-11-01). Locus sends no lifetime:

- On models before GPT-5.6, `prompt_cache_retention` defaults to `"24h"` for organisations without Zero Data Retention. A ZDR organisation's default is `"in_memory"`, which is its own policy.
- GPT-5.6 and later replace it with `prompt_cache_options.ttl`, whose default is a 30-minute minimum.

Setting either would add nothing. `prompt_cache_key` is still sent; on GPT-5.6 and later it only separates cache accounting.

**Not done.** The research also suggested keeping the cache warm with a ping while an approval is pending. Changing thinking settings breaks the message cache, so a safe ping is unverified, and it isn't done.
