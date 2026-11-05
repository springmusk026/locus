# Provider failures: no opaque errors, details, and ways out

A long agent task on the ChatGPT plan (gpt-6.1-sol, about 120k tokens of context, 24 minutes of tool
calls) ended with a red banner that said only "ChatGPT: the response failed". Try again failed the same
way every time. The run card read "Failed · checks failed", and release logcat held nothing. The cause
was in the stream, and Locus threw it away. This page covers how Locus now reads failures, what it
keeps, what the user sees, and what is still unknown about that run.

## Failure shapes (checked 2026-11-03)

| Where | Shape | Source |
|---|---|---|
| Platform `error` event | `{"type":"error","code":…,"message":…,"param":…,"sequence_number":n}`, with the fields at the top level | openai-python `types/responses/response_error_event.py` |
| ChatGPT backend `error` event | `{"type":"error","status":429,"error":{"type":"usage_limit_reached","message":…,"plan_type":"plus","resets_at":…},"headers":{…}}`, nested, sometimes with only a `type` | openai/codex `codex-api/src/sse/responses.rs` (reads `event.error`) and `endpoint/responses_websocket.rs` tests |
| `response.failed` | The whole response, with `response.error` = `{code, message, param, type}`. The ChatGPT backend can add `plan_type`, `resets_at` and `headers`. `error` may be null or empty | openai-python `response_failed_event.py`, `response_error.py`; codex `sse/responses_error.rs`, whose tests include `{"type":"response.failed"}`, `"response": {}` and `"error": null` |
| `response.incomplete` | The response, with `incomplete_details.reason` set to `max_output_tokens`, `max_messages`, `content_filter` or `steered` (WebSocket only) | openai-python `response_incomplete_event.py`, `response.py` |
| Non-2xx before the stream | `{"error":{code, message, type, param}}`. The ChatGPT backend uses `{"error":{"type":"usage_limit_reached", "plan_type", "resets_at"}}` with HTTP 429, and some routes send `{"detail":"…"}` | codex `codex-api/src/api_bridge.rs`; developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery |
| Request id | Response headers: `x-request-id`, else `x-oai-request-id`, else Cloudflare's `cf-ray`. The response's own id is `response.id` on `response.created` and on the terminal event | codex `api_bridge.rs` (`extract_request_tracking_id`), `sse/responses.rs` (`REQUEST_ID_HEADER`) |

The plan route's errors page documents codes and HTTP statuses. It says to keep the request id, but it
documents no streaming shapes and no size or context limits. The preview-limitations page lists the
omitted parameters, and it rejects `system`-role items. It says nothing about item counts, reasoning
items, `include` or `prompt_cache_key`.

**What Locus did before.** On `response.failed` it read only `response.error.code` and `.message`. On
`error` it read only the top-level `code` and `message`. So a backend error with only a `type`, or with
nothing, came out as "ChatGPT: the response failed". That error was not retryable, and no body was kept
for Diagnostics.

## Reading a failure (core/llm)

- `ResponsesError` reads the code, type, message, param, status and `resets_at` from every place listed
  above. `codeOrType` classifies the failure. On the ChatGPT backend, `type` carries the usage errors.
- `ChatGptErrors.fromCode` treats these as a usage limit (QUOTA, not retried, so a fallback model can take
  over), and says when the limit resets:
  - `usage_limit_reached`
  - the workspace limit and credit types
  - the plan's own `subscription_sharing_usage_limit_exceeded`

  `usage_not_included`, the billing codes, `server_is_overloaded` and `slow_down` each get their own
  handling.
- When the provider gives no code, no type and no message, the chat says: "ChatGPT ended the response
  with an error and gave no reason (request id …)." The failure is retried like a transient one,
  because Codex retries an unreadable `response.failed` too.
- An error with a code but no message names the code: "the response failed with “invalid_prompt”".
- `response.incomplete` is still kept as a turn when it has text or calls, with a stop reason of
  MAX_TOKENS or REFUSAL. One with nothing to show or run, such as a turn whose output went entirely on
  reasoning, now fails with its reason. Before, it ended the task as if the task were done.
- Each `LlmException` now carries `errorCode`, `requestId` and `responseId`. `rawBody` holds a scrubbed
  copy of the failure event (`scrubbedFailureEvent`), built like this:
  - The event's own fields are kept.
  - Of `response`, only the metadata is kept: id, status, model, error, `incomplete_details` and usage.
    Its `output` (model text and encrypted reasoning), `instructions` (the system prompt), `tools` and
    any echoed input are dropped.
  - Echoed headers that name a credential or cookie are dropped.
  - Every string loses bearer tokens, JWTs, `sk-` keys and base64 blobs (`FailureText`).
  - The result is capped at 4,000 characters.
- Wrappers (fallback notes, `retry-after` waits, `ScrubbingChatModel`) keep these fields
  (`LlmException.withMessage`). `Throwable.llmFailure()` finds the failure in a cause chain.

## What the user sees

- **The banner** shows the error in words. For a model failure, it also shows **Details** and, where they
  apply, **Summarise chat** and **Switch model**. A failure that keeps coming back adds a line saying it's
  probably something in the request, with the chat's size.
- **Details** (`FailureDetailsSheet`, built from design-system components) lists:
  - provider and model
  - HTTP status ("None (the stream had started)" for a mid-stream failure)
  - error code
  - request id and response id
  - time
  - how many times in a row it has failed
  - the provider's response, pretty-printed

  **Copy** puts all of it on the clipboard. The payload is scrubbed twice: once in core/llm, and once
  more with the vault's values (`ProviderAccess.scrubForDisplay`, SecretScrubber). It never includes the
  request, the auth header or the chat's messages.
- **Diagnostics.** `ProviderErrorLog` already recorded failures that carry a body. Stream failures now
  carry one, so each lands in Settings → About & privacy → Diagnostics with its code and ids. It is kept
  across restarts and capped like every other entry. The sheet links there.

## Repeat failures (core/agent `RunFailure`)

`AgentRunner` keeps each chat's last failure across runs. A new failure counts as a repeat when two things
match:

- the same provider, model, status, code and kind;
- the same transcript point (the last message's id). Nothing new was added, so the same request went
  out again.

| Failure | First time | Repeated |
|---|---|---|
| QUOTA (usage limit) | USAGE_LIMIT: Switch model offered | the same |
| AUTH | AUTH: Open Models (from the message) | the same |
| CONTEXT_OVERFLOW | CONTEXT: Summarise chat | the same |
| Rate limit, overload | TRANSIENT | TRANSIENT, with Switch model offered |
| A dropped connection | TRANSIENT | TRANSIENT (it's the network) |
| No code, or `server_error` | TRANSIENT | **REQUEST**: Switch model, plus Summarise chat when the context is large (64k tokens or more, or half the window) |
| Any other code | OTHER | **REQUEST**, as above |

A failure the provider couldn't explain, which comes back after every in-loop retry and then again on Try
again at the same point, isn't a passing glitch. The request itself is the likely cause.

**Continue in a new chat with a summary** doesn't exist as a feature. Forking copies the same history,
including whatever breaks the request, so it isn't offered. Summarise chat does the same job inside the
chat: the model sees only the checkpoint from then on.

## The run in question: what code and docs say, and what's still unknown

Possible causes, checked against the code and docs:

- **Context limit.** Ruled out as a plain overflow. models.dev lists gpt-6.1-sol at 1,050,000 tokens of
  context (922,000 input), and 120k is far below that. A real overflow comes with
  `context_length_exceeded` (codex tests), which Locus already handles by summarising.
- **One huge tool output.** Bounded on every route. `AgentLoop.bound` cuts each result to 16,000
  characters inline in a chat, and 60,000 for tools that page their own output, before it is stored. So
  no single item is unbounded. A model-written tool call (a whole file in one `write_file`) is replayed
  as written. The 8 MB stream limit caps it.
- **An invalid item sequence.** None found from Stop, tool errors or crashes: `executeAll` answers
  cancelled calls, and `recoverTranscript` answers calls lost to a crash. Locus now normalises the input
  anyway, as Codex does (`context_manager/normalize.rs`). `ResponsesInput` does four things:
  - It gives a call with no output a "Not run" output right after it.
  - It drops an output with no call.
  - It drops a reasoning item without `encrypted_content`, which can't be resolved with `store: false`.
  - It drops a repeated item id.

  On a sound history it returns the very same list, so the request and its cached prefix don't change.
  Unit tests build bodies for 150 steps of reasoning, call and 16k output; a tool error; a stop
  mid-step; a lost result; an orphan; and a mix of providers. All are valid.
- **Replayed reasoning items.** No documented limit on their number or size. The reasoning guide says
  items come back with `encrypted_content` by default and that the API "will smartly ignore" ones that
  aren't relevant. Locus now also sends `include: ["reasoning.encrypted_content"]`. The guide calls it a
  legacy value the API still accepts, and Codex sends it on the plan route. This makes sure replayed
  reasoning always resolves. It doesn't change the prompt, so the cache holds.
- **`prompt_cache_key`.** Constant per chat (`locus-chat-<id>`, hashed past 64 characters), and accepted
  on the plan route for the 24 minutes before the failure. An invalid key would be refused at once with
  a 400, which Locus already handles by sending again without it.

**Most likely cause:** a usage limit. The ChatGPT backend reports one as an error whose only field is
`type: "usage_limit_reached"`, often without a message (codex tests). Locus read only `code` and
`message`, so it showed exactly "ChatGPT: the response failed". A 24-minute agent task that sends about
120k tokens on every step is the kind of run that reaches a plan's 5-hour limit. Try again then fails the
same way until the limit resets. Locus now says it's a usage limit and when it resets, and offers another
model.

**Still unknown:** the raw event of that run wasn't kept, so this can't be confirmed from the code. If
it happens again, Details (and Diagnostics) will show the event, the request id and the response id. That
is what to look at, or to send to OpenAI.
