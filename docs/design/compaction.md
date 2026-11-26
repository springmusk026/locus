# Compaction

How Locus keeps a long task inside the model's context window: summarising the transcript into a
checkpoint, both between the user's messages and inside a running task, and recovering when a provider
says the request no longer fits.

## Why

Chats have had no step limit since #41, so one task can make hundreds of requests. Before this change,
compaction ran only before a new user message (`AgentRunner.send` → `needsCompaction`) or when the
user asked (`compactNow`). `AgentLoop.run` keeps the transcript in memory, so a long task on OpenAI,
an OpenAI-compatible server, Gemini or Ollama filled the window and ended as `Outcome.Failed`, and
context-overflow errors weren't recognised at all. Helpers had no compaction. Only Anthropic models
with server-side compaction were covered.

## What happens

### At a step boundary

After a step, once every call of that step has its result, the loop checks the last request's
reported usage (`TokenUsage.context`, input plus output) plus a rough estimate of everything added after
that reply (`Compaction.estimateTokens(messages)`: the tool results, the after-edit rules' output that
follows them, and about 1,600 tokens for each image; before #303 only the tool results' text counted, so a
long failing test run could push the next request over the window and into overflow recovery) (four characters a token, but one token for each Chinese, Japanese or Korean character: `Compaction.estimateTokens`, since real tokenizers spend 0.7 to 1.5 on one and four characters a token put 2,100 Chinese characters at 525). If that's above 80% of the model's window
(`Compaction.THRESHOLD`, the same threshold as before), the loop compacts before the next request.

The window and the threshold come from the same rule as before (`compactionSetup` in `AgentRunner.kt`):
the provider's `contextWindow`, not on-device models, and not Anthropic models that compact on the
server (they still get the overflow recovery below).

**Below a price step.** Some models charge more for every token of a request once the prompt passes a
size: Claude Haiku 5.5 at 100,000 tokens (5x), GPT-5.x at 272,000 (models.dev `cost.tiers`). There the
loop compacts at the lower of 80% of the window and `CompactionSetup.ceiling`, which is a tenth of the
step below it (at least 10,000 tokens, never under 50,000; `PriceSteps.compactBelow`). That's 90,000 on
Haiku 5.5 and 244,800 on a 272,000 step. The step comes from the catalogue when it has one, else from
the documented table. Anthropic models that compact on the server send the same ceiling as their
`trigger` instead (see [Server-side compaction](#server-side-compaction-anthropic)). Costs and sources are
in [cost](cost.md#compacting-below-a-price-step).

A threshold compaction never runs at the step right after another one: if the summary view is
already that large, summarising again wouldn't help (an overflow error still recovers).

On Chat Completions models the summary usually starts earlier, in the background, and is ready by
then; see [Starting the summary early](#starting-the-summary-early).

Compaction comes before the user's queued messages are added at that boundary (#55). Those messages
then follow the summary as ordinary messages, with their mid-task note, so the model reads them as
new rather than as part of the summary. So they aren't in the estimate: checking after them would put
them inside the summary. They're usually short; a "Run anyway" result that's long enough to overflow
still has overflow recovery. Room for the reply is the 20% of the window above the threshold (the
threshold isn't a setting); a price-step ceiling limits the prompt, not the reply. Every adapter's
`TokenUsage.input` includes cache reads and writes (Anthropic adds them to `input_tokens`; OpenAI's
`prompt_tokens` and Responses' `input_tokens` already include them), so `context` is the whole prompt.

### The summary request

`Compaction.request` is the next turn's request (the same frozen system prompt, the same tool list,
the same transcript, the same `prompt_cache_key` and task budget) plus one user message with the
compaction prompt from Settings. So it reads the provider's prompt cache, and only the question and
the summary are new tokens. It runs on whichever model is serving the run, so after a fallback it's
the backup model. The summary's usage counts toward the task budget, the helper's token limit, and
Insights ("compaction").

### What replaces the transcript

(A checkpoint written by Summarise from here replaces only the part from a chosen message on, and an edited
summary replaces the one it corrects; see [rewind](rewind.md#summarise-from-here). What follows is the ordinary,
whole-transcript checkpoint.)

The store stays append-only. A `Part.Checkpoint` message is appended, as manual compaction already
did. In memory, the transcript becomes one user message, the checkpoint's view (`Compaction.view`):

1. the summary;
2. the user's latest messages before the checkpoint, word for word, oldest first, up to about 20k
   tokens (`KEEP_USER_TOKENS`; an eighth of the window on smaller models, so a 32k model keeps 4k and
   the view stays well under the threshold). This follows Codex's `compact.rs`: newest first,
   `COMPACT_USER_MESSAGE_MAX_TOKENS = 20_000`, and the message that doesn't fit is cut to the space
   left (Locus keeps its start and end, cutting between whole characters so an emoji is never split into a lone surrogate, and counting a CJK character as a token). Locus's own notes (plan mode, mid-task, helper reports,
   "Run anyway" results) are left out; attachments are named. Messages kept by an earlier checkpoint
   carry forward;
3. the checklist as it stood (`todo_write`), carried forward from an earlier checkpoint when there's
   no newer one;
4. a pointer: the chat's id, and that `chat_history_read` with `offset` and `include_tool_results`
   reaches anything the summary dropped (Cursor gives its agent the history file for the same reason;
   cursor.com/blog/dynamic-context-discovery);
5. "If you were in the middle of a task, carry on from where this leaves off."

The kept messages and the checklist are stored in the checkpoint itself (`recentUserMessages`,
`todos`), and the view is a pure function of the checkpoint. So the text a running task sends after
compacting is byte-identical to what `Compaction.modelContext` rebuilds from storage for the next run,
and the cached prefix holds across runs. Checkpoints written before this change have neither field
and render as summary plus pointer. The view keeps the `Checkpoint` part next to its text (providers
send only text parts), so a later compaction can carry its kept messages forward and the safety
reviewer never counts the summary as something the user typed.

The checkpoint also records what the run had read from outside (`untrustedSources`, the run's
taint; docs/design/taint.md). The summary may repeat that text, and a later run starts from the view
rather than the tool results, so `Taint.sourcesIn` reads the sources off the checkpoint and that run
starts tainted too.

Nothing is cut in half: the whole transcript is replaced, after every call of the step has its result,
so no tool call is ever separated from its result.

### Overflow recovery

`ContextOverflow` (core/llm) recognises "the prompt doesn't fit" per provider, and `httpError`, the
Anthropic stream `error` event, the Responses `response.failed`/`error` events, the ChatGPT plan's
errors and OpenAI-compatible stream errors classify it as `LlmFailure.CONTEXT_OVERFLOW`. Overflow is
never retried as is and never moves to another key or backup model.

| Provider | What it sends | Source |
| --- | --- | --- |
| Anthropic | 400 `invalid_request_error`, "prompt is too long: N tokens > M maximum" | platform.claude.com/docs/en/build-with-claude/context-windows ("returns a 400 `invalid_request_error` ("prompt is too long")"); full text as LiteLLM and Claude Code users report it |
| OpenAI Chat Completions | 400, `code: "context_length_exceeded"`, "This model's maximum context length is …" | community reports; LiteLLM and Codex key on the code |
| OpenAI Responses | `response.failed` with `error.code: "context_length_exceeded"` | Codex `codex-api/src/sse/responses_error.rs` and its test fixture |
| Gemini | 400 `INVALID_ARGUMENT`, "The input token count (N) exceeds the maximum number of tokens allowed (M)." | observed (google-gemini/api-examples#94, the Gemini forum); LiteLLM and opencode match the same words |
| OpenRouter | `error.metadata.error_type: "context_length_exceeded"` | openrouter.ai/docs/api/reference/errors-and-debugging |
| DeepSeek, Groq, xAI | the OpenAI shape, sometimes without the code | issue reports; Vercel AI SDK maps the code |
| vLLM | "This model's maximum context length is …" (`code` is the HTTP status) | vllm `renderers/params.py` |
| llama.cpp (and LM Studio on it) | `type: "exceed_context_size_error"`, "exceeds the available context size" | llama.cpp `tools/server/server-context.cpp` |
| Mistral | "Prompt contains N tokens, too large for model with M maximum context length" | observed; opencode's pattern |
| Ollama | nothing by default: `truncate` and `shift` default to on, so old messages are dropped silently; with shift off, "the prompt is longer than the context length …" | ollama `api/types.go`, `server/routes.go`, `server/prompt.go` |

Structured fields are checked first, then the message (or the whole body when the error isn't an
object, as on Ollama), with the phrase lists of LiteLLM's `is_error_str_context_window_exceeded` and
opencode's `provider-error.ts`. Only 4xx statuses other than 429 count, and rate-limit wording
("tokens per min", "please wait") and `string_above_max_length` (a per-field limit) are excluded first.

On overflow the loop compacts, then sends the step once more. If the summary request overflows too,
the oldest quarter of the transcript (at least two messages) is left out of it and it's sent again,
up to four tries, as Codex drops the oldest item on `ContextWindowExceeded`. Leaving messages out
never strands a tool result and always starts with a user message. If there's no summary, or the
retried step still overflows, the run fails with a message that says so, names the model, suggests a
new chat (whose agent can read this one with `chat_history_read`) or a model with a larger window,
and quotes the provider.

Anthropic's `model_context_window_exceeded` stop reason is not an error: the turn is treated as
truncated (as `max_tokens` already was), and its usage, close to the full window, makes the next step
boundary compact.

Ollama can't be detected from errors at all; there the threshold check against the configured window
is the only protection, so the provider's context window should match its `num_ctx`.

### Helpers

Helpers (`SubagentEngine`) get the same `CompactionSetup` through `PreparedChild.compaction`, built
from the helper's own model. A helper compacts its own hidden chat, the summary's tokens count toward
its token limit and the parent's task budget, and Insights records it under the helper's chat. A
fork's in-memory copy of the parent's transcript is part of what gets summarised; once a helper has a
checkpoint, `agent_send` continues from the checkpoint's view and no longer resends the fork prefix.

### chat_history_read

`chat_history_read` numbers a chat's entries from `#0` and gains two optional arguments:

- `offset`: read forward from that entry, with the offset of the next page at the end. Without it the
  tool shows the end, as before.
- `include_tool_results`: also show what tools returned, each cut to 4,000 characters and wrapped in
  `UntrustedContent` markers, since it can be web pages, mail or other outside text.

The whole result is passed through `SecretScrubber` with the vault's values first, as "What was sent"
does. A checkpoint shows as "A summary was written here".

### Starting the summary early

Writing the summary at the threshold stops the task while the request runs. Codex starts its mid-turn
compaction before the limit (`async_compaction::maybe_start` with `CompactionPhase::MidTurn`, `take_ready`),
so nobody waits; Locus does the same.

- **When.** At a step boundary where the context is past `CompactionSetup.earlyLimit`: 7/8 of the point
  where compaction is due (`Compaction.EARLY_SHARE`), so 70% of the window at the 80% threshold, and
  78,750 tokens below Haiku 5.5's 90,000 ceiling. Not at the step right after a compaction, and once per
  stretch between compactions (one that failed isn't sent again each step). `compactionSetup` turns it
  on for chats and helpers (`CompactionSetup.background`); there's no setting, since it changes when the
  summary is written, not what the model sees or what compacting costs.
- **The request.** Exactly the summary request the threshold would send at that boundary:
  `Compaction.request` over the transcript so far, same system prompt, tools, cache key, task budget and
  cold-cache view, plus the prompt. So it reads the prefix the step's own request just wrote, as today's
  summary does. It goes out from a child coroutine of the run on the model serving the run then. It
  doesn't touch the transcript: the run's own requests are exactly what they'd be without it until the
  summary is used.
- **At the threshold** (`AgentLoop.useEarly`): if it's back, it's used at once. If it's still out, the
  run waits for it (the chat shows "Summarising earlier messages…"): it started earlier than a new
  request would, so waiting is never slower, and a second request would be billed again. The run
  never sends a request over the threshold meanwhile. It falls back to the ordinary summary, at once, when:
  - the request failed or came back with no summary (a failed request has no usage to record);
  - what the run added since it started, by the provider's own counts, plus the summary's view would be
    over half the compaction point (`Compaction.KEPT_AFTER_SHARE`): an ordinary summary leaves a smaller
    context;
  - the run fell back to a model that isn't Chat Completions meanwhile, or a kept reply came from one.
  A summary that came back but isn't used is billed all the same (see below).
- **The checkpoint.** `Part.Checkpoint.coversUpTo` names the last message the summary covers (the
  step's results, or the last note at that boundary). The checkpoint is stored after the turns that ran
  meanwhile (the store stays append-only), and `Compaction.place` puts its view where the covered part
  was: `[view] + [turns after the covered part] + [what comes next]`. The running task applies it with
  the same function `modelContext` uses, so the next request is byte-identical to what a restart
  rebuilds from storage (`EarlySummaryTest` checks it on the Chat Completions wire). Its kept user
  messages and checklist are the covered part's; later `todo_write` calls are in the kept turns, and a
  later compaction carries both forward as usual. The view ends with "The messages after this summary
  came later and are kept word for word: carry on from the latest of them." An id that isn't in the
  context (never on a consistent path) makes the checkpoint replace everything before it, as an
  ordinary one does, which is always a valid request. An edited summary of it (`supersedes`) replaces
  only the view it corrects; the kept turns stay.
- **Taint.** The checkpoint records the run's taint when it's stored plus what the covered part read
  (`Compaction.checkpoint`), so it covers at least everything it summarises; the kept turns carry their
  own sources.
- **Billing.** A summary that's used is recorded like the ordinary one: the run's task budget, the
  helper's token limit, and `AgentEvent.Compacted` for usage records, so receipts and Insights show it
  as "compaction". One that came back but isn't used (the run ended before the threshold, it fell back,
  or an overflow error) is recorded the same way through `AgentEvent.SummaryUnused`, before the run's
  `Finished` event, and nothing is stored. One still out when the run ends (or an overflow drops it) is
  cancelled, and counted the same way from an estimate (#305): the provider read its prompt even though no
  usage came back. The estimate is the context it started at plus the question, with the part the last
  request read or wrote in the provider's cache counted as a cache read (none when that request reported no
  caching); no output. One still out when the run is stopped or the app is killed is cancelled uncounted, as
  for a reply cut off by Stop (a stopped run can't report it). Nothing was stored, so a restart sees the chat
  as it was.
- **Overflow.** A context-overflow error drops it (counted if it came back) and summarises everything:
  keeping the turns after it is what didn't fit.
- **Where not.** Only on Chat Completions (`Compaction.keepsTurnsAfterSummary`), where Locus replays no
  reasoning. Keeping turns after a summary is the "keep the tail, rewrite what's before it" shape that
  [rewind](rewind.md#prefix-byte-identity-per-provider) avoids: on Anthropic, preserved thinking is
  checked against everything before a thinking block, so rewriting the turns before a kept one would
  invalidate it; the Responses API and the ChatGPT plan replay encrypted reasoning items, which Locus
  never sends after a changed prefix. Those keep the blocking summary, and so does Anthropic with server
  compaction, which doesn't compact in Locus at all. On-device models don't compact at a threshold.
- **In the chat.** The checkpoint card still says "Summarised N earlier messages", and its note says the
  latest messages above it, written while it was prepared, are still sent as they were.
  `chat_history_read` shows it as "replaced the entries up to #k, and kept the ones after".
- No Room change: `coversUpTo` is a field of the serialized part, absent (null) in older checkpoints.

## Server-side compaction (Anthropic)

On models Anthropic lists for [threshold compaction](https://platform.claude.com/docs/en/build-with-claude/compaction-threshold),
every request carries `context_management.edits: [{"type": "compact_20260112"}]` (beta `compact-2026-01-12`),
and the API summarises earlier turns into a `compaction` block that's replayed like any other block.

- **Trigger.** The default is 150,000 input tokens ("`value` must be at least 50,000"). Where the model
  has a documented price step, the edit carries `"trigger": {"type": "input_tokens", "value": 90000}`
  (Haiku 5.5; `AnthropicChatModel.compactionTrigger`). Anthropic: "set its `trigger` a little below
  100,000 minus your prompt's largest growth between requests". The value is a pure function of the
  model, so `context_management`, a prompt-affecting parameter, is the same on every request of a chat.
  A chat started before this change sends the new trigger from its next request, which misses the
  conversation's cache once (the system prompt and tools stay cached).
- **Billing.** The summary is billed in `usage.iterations` (a `compaction` entry), not in the top-level
  counts. Locus keeps it as `TokenUsage.serverCompaction`, priced and budgeted as a request of its own
  ([cost](cost.md#server-side-compaction)).

## Prompt caching

- The system prompt and tool list never change; the summary request uses them unchanged.
- The summary request keeps the conversation breakpoint's 5-minute lifetime even in a main chat (whose
  turns write it for an hour, [cost](cost.md#cache-lifetimes)). Its own write is never read again: the
  next request starts from the checkpoint.
- History stays append-only; only the in-memory view changes, and it's rebuilt identically from storage.
- The summary request sends the same view of the transcript as the next turn, old tool results cleared past a cold-cache gap included ([cold-cache clearing](cold-cache-clearing.md)).
- The first request after a compaction is a new prefix (it has to be), and every later request extends it.
- Changing `chat_history_read`'s description changes the tool list once, for every chat, on the update
  that ships it; checkpoints written before render with the new pointer text, also once.

## Not done

- ~~Editing the summary~~: done, see [rewind](rewind.md#editing-a-checkpoint-the-roadmaps-editable-summary).
- ~~Starting the summary early in the background~~: done on Chat Completions, see
  [Starting the summary early](#starting-the-summary-early). On Anthropic and Responses it would need
  their replayed reasoning to survive a rewritten prefix, which it doesn't (Anthropic) or isn't documented
  to (Responses).
- **OpenAI Responses server-side compaction.** The request shape is verified from the API reference
  (developers.openai.com/api/reference/resources/responses/methods/create) and the guide
  (developers.openai.com/api/docs/guides/compaction): a top-level array,
  `"context_management": [{"type": "compaction", "compact_threshold": 200000}]` (`type` must be
  `"compaction"`, `compact_threshold` an integer of at least 1000), and the output then includes an
  encrypted item `{"type": "compaction", "id": …, "encrypted_content": …}` to pass back on later
  requests. Neither page says which models support it, and Locus's stateless replay
  (`store: false`, `rawPayload`) would have to keep and resend that item. Follow-up in the roadmap.
- **A reserved buffer** for the summary itself (opencode's `reserved`): the threshold leaves 20% of the
  window, and an overflowing summary request is retried with less.
