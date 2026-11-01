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
reported usage (`TokenUsage.context`, input plus output) plus a rough estimate of the tool results it
just added (four characters a token). If that's above 80% of the model's window
(`Compaction.THRESHOLD`, the same threshold as before), the loop compacts before the next request.

The window and the threshold come from the same rule as before (`compactionSetup` in `AgentRunner.kt`):
the provider's `contextWindow`, not on-device models, and not Anthropic models that compact on the
server (they still get the overflow recovery below).

A threshold compaction never runs at the step right after another one: if the summary view is
already that large, summarising again wouldn't help (an overflow error still recovers).

Compaction comes before the user's queued messages are added at that boundary (#55). Those messages
then follow the summary as ordinary messages, with their mid-task note, so the model reads them as
new rather than as part of the summary.

### The summary request

`Compaction.request` is the next turn's request (the same frozen system prompt, the same tool list,
the same transcript, the same `prompt_cache_key` and task budget) plus one user message with the
compaction prompt from Settings. So it reads the provider's prompt cache, and only the question and
the summary are new tokens. It runs on whichever model is serving the run, so after a fallback it's
the backup model. The summary's usage counts toward the task budget, the helper's token limit, and
Insights ("compaction").

### What replaces the transcript

The store stays append-only. A `Part.Checkpoint` message is appended, as manual compaction already
did. In memory, the transcript becomes one user message, the checkpoint's view (`Compaction.view`):

1. the summary;
2. the user's latest messages before the checkpoint, word for word, oldest first, up to about 20k
   tokens (`KEEP_USER_TOKENS`; an eighth of the window on smaller models, so a 32k model keeps 4k and
   the view stays well under the threshold). This follows Codex's `compact.rs`: newest first,
   `COMPACT_USER_MESSAGE_MAX_TOKENS = 20_000`, and the message that doesn't fit is cut to the space
   left (Locus keeps its start and end). Locus's own notes (plan mode, mid-task, helper reports,
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

## Prompt caching

- The system prompt and tool list never change; the summary request uses them unchanged.
- History stays append-only; only the in-memory view changes, and it's rebuilt identically from storage.
- The first request after a compaction is a new prefix (it has to be), and every later request extends it.
- Changing `chat_history_read`'s description changes the tool list once, for every chat, on the update
  that ships it; checkpoints written before render with the new pointer text, also once.

## Not done

- **Starting the summary early in the background** (about 70%, as Codex's `async_compaction` does with
  `CompactionPhase::MidTurn`). The checkpoint would cover the transcript up to where the summary
  started, but it's stored after the steps that ran meanwhile, and `modelContext` treats everything
  before the latest checkpoint as summarised. Doing it needs a checkpoint that names the message it
  covers up to. Left for later; the summary request reads the cache, so the wait is short.
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
