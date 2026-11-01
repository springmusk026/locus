# Fallback chains and key pools

When a chat's model can't answer (out of credit, key refused, rate limited, overloaded), Locus can
try another API key of the same provider, and then other models, without the user starting over.
Roadmap item: "ordered backup models across providers; switch at once on quota errors, retry on
429/5xx, never replay a partial reply."

## What others do

Sources were checked on 2026-11-01.

| System | Key rotation | Model fallback | Retry-After | Partial streams |
|---|---|---|---|---|
| LiteLLM Router | Several deployments of one model (different `api_key`s) are load-balanced (`simple-shuffle` by default). A deployment cools down (`cooldown_time`, default 5 s) after a 429, 401, 404 or 408, or after `allowed_fails` failures | `fallbacks` run in order once `num_retries` are used up. Context-window and content-policy errors have their own lists (`context_window_fallbacks`, `content_policy_fallbacks`) | Honoured when it's between 0 and 60 s, plus jitter. With other healthy deployments it retries at once on another one | "Mid-stream fallbacks" pass the text so far to the fallback model as `previous_content` |
| OpenRouter | BYOK keys are tried in the order the user sets, then shared capacity. Ordered failover, not round-robin | `models: [...]`: "any error can trigger the use of a fallback model". The response's `model` field says which model answered, and you pay that model's price | – | Errors after the stream opens arrive as an SSE chunk with `error` and `finish_reason: "error"`, under HTTP 200 |
| Vercel AI SDK | – | Not built in. The community `ai-fallback` package switches on 401, 403, 408, 409, 413, 429, 498 and ≥ 500, and goes back to the first model after 3 minutes | `maxRetries` 2, 2 s exponential backoff. Reads `retry-after-ms`, then `retry-after`, and uses it when it's under 60 s | `streamRetries` defaults to off, and "partial output … cannot be retracted". `ai-fallback`'s `retryAfterOutput: true` appends the new model's stream onto the partial output |
| RikkaHub | Several keys in one field, picked least recently used on every request. Doesn't look at failures | Not found | – | – |
| Cherry Studio | Round-robin over enabled keys on every call. Keys can be switched off one by one | – | – | – |
| openai-python | – | – | Reads `retry-after-ms`, then `retry-after`. Waits at most 120 s, and doesn't retry beyond that. `x-should-retry` overrides | – |

Sources:
- LiteLLM: [docs.litellm.ai/docs/routing](https://docs.litellm.ai/docs/routing), [docs.litellm.ai/docs/proxy/reliability](https://docs.litellm.ai/docs/proxy/reliability), [`calculate_retry_after` in `utils.py`](https://raw.githubusercontent.com/BerriAI/litellm/main/litellm/utils.py)
- OpenRouter: [model fallbacks](https://openrouter.ai/docs/guides/routing/model-fallbacks), [provider selection](https://openrouter.ai/docs/guides/routing/provider-selection), [BYOK](https://openrouter.ai/docs/guides/overview/auth/byok)
- Vercel AI SDK: [`retry-with-exponential-backoff.ts`](https://raw.githubusercontent.com/vercel/ai/main/packages/ai/src/util/retry-with-exponential-backoff.ts), [`stream-text.ts`](https://raw.githubusercontent.com/vercel/ai/main/packages/ai/src/generate-text/stream-text.ts), [remorses/ai-fallback](https://github.com/remorses/ai-fallback)
- RikkaHub: [`KeyRoulette.kt`](https://raw.githubusercontent.com/rikkahub/rikkahub/master/ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt)
- Cherry Studio: [`ProviderService.ts`](https://github.com/CherryHQ/cherry-studio/blob/main/src/main/data/services/ProviderService.ts)
- openai-python: [`_base_client.py`](https://github.com/openai/openai-python/tree/main/src/openai)

Takeaways:

- Rotating keys on every request (RikkaHub, Cherry Studio) spreads load but ignores failures. LiteLLM
  and OpenRouter switch keys because a key failed.
- Everyone who honours `retry-after` caps it, at 60 s (LiteLLM, AI SDK) or 120 s (openai-python).
- Appending a second model's output to a partial reply is a known bad pattern. It is `ai-fallback`'s
  default.

## Provider error shapes

| Provider | Rate limit (retry) | Out of quota (don't retry) | Overloaded | Retry hint |
|---|---|---|---|---|
| Anthropic ([errors](https://platform.claude.com/docs/en/api/errors), [rate limits](https://platform.claude.com/docs/en/api/rate-limits)) | 429 `rate_limit_error` | 402 `billing_error`. The 429 spend cap with `details.error_code: "enforced_spend_limit_reached"` and no `retry-after`. A 400 "You have reached your specified API usage limits" | 529 `overloaded_error`, also as a mid-stream SSE `event: error` | `retry-after` (seconds), plus `anthropic-ratelimit-*` |
| OpenAI ([error codes](https://developers.openai.com/api/docs/guides/error-codes), [rate limits](https://developers.openai.com/api/docs/guides/rate-limits)) | 429 `rate_limit_exceeded`, `slow_down` | 429 with `insufficient_quota`, `credit_balance_exhausted`, `*_spend_limit_exceeded` or `organization_usage_limit_exceeded` | 503 `server_is_overloaded` | `retry-after` "may appear on 429 and 503", plus `retry-after-ms` and `x-ratelimit-*` |
| Gemini ([troubleshooting](https://ai.google.dev/gemini-api/docs/troubleshooting), [rate limits](https://ai.google.dev/gemini-api/docs/rate-limits), [errors](https://ai.google.dev/gemini-api/docs/api-errors)) | 429 `RESOURCE_EXHAUSTED` with a per-minute `QuotaFailure` | The same 429 when the `quotaId` is counted per day (`…PerDay…`). Also 402 | 503 `UNAVAILABLE` | `google.rpc.RetryInfo.retryDelay` in the body (`"37s"`). The OpenAI-compatible endpoint has been seen to wrap the body in an array ([forum](https://discuss.ai.google.dev/t/gemini-api-returns-429-issue-when-using-openai-compatible-api/52253)) |
| OpenRouter ([errors](https://openrouter.ai/docs/api/reference/errors-and-debugging)) | 429 | 402 insufficient credits | 502, 503 | Mid-stream errors arrive as a chunk with `error.code` |
| DeepSeek ([errors](https://api-docs.deepseek.com/quick_start/error_codes)) | 429 | 402 Insufficient Balance | 503 Server Overloaded | – |
| Groq ([errors](https://console.groq.com/docs/errors)) | 429 | – | 498 (flex tier at capacity), 503 | `retry-after` on 429 |

Limits are counted per organization or project, not per key: Gemini says so outright, and Anthropic
and OpenAI count per organization. Pooling keys only adds quota when the keys come from different
accounts or projects. The editor says so.

## Design

### Classification (core/llm)

`LlmException` gains `kind: LlmFailure` and `retryAfterMs`. The kinds are `RATE_LIMIT`,
`OVERLOADED`, `TRANSIENT`, `QUOTA`, `AUTH` and `OTHER`.

- `httpError` reads the body: the `error` object, also inside an array, with its `code`, `type`,
  `status` and `details`.
- `throwIfFailed` reads `retry-after-ms`, then `retry-after` (as seconds or an HTTP date).
  Gemini's `retryDelay` in the body is used when there's no header.
- Errors that arrive mid-stream are classified too: Anthropic's `event: error`, Responses
  `response.failed` and `error`, and OpenRouter's error chunks.
- Calls that only pass `retryable` keep their old meaning (`TRANSIENT` or `OTHER`).

`Failover` holds the rules, so the key pool and the agent loop agree:

| Kind | Another key | Another model |
|---|---|---|
| `QUOTA`, `AUTH` | at once | at once |
| `RATE_LIMIT` | at once | after retries |
| `OVERLOADED`, `TRANSIENT` | no (it's the provider, not the key) | after retries |
| `OTHER` (bad request, unknown model) | no | no (another model wouldn't fix it) |

Retries keep the loop's bound of 3. Each one waits for the provider's `retry-after` if it gave one,
up to `MAX_WAIT_MS` = 60 s, and otherwise 2^n s. A `retry-after` longer than 60 s switches to the
next model at once. With no next model, the chat fails and the message says how long the provider
asked to wait, instead of retrying into the same wall.

### Key pools

- A provider keeps its own key under `provider:<id>`, unchanged.
- Backup keys go in the vault under `provider-keys:<id>`, one per line. That name is outside
  `provider:`, so it never counts as the key, and outside `connector:`, so no connector can name it.
- Backups are included in vault backups like every other secret. Removing the provider removes them.

`ProviderCredentials` wraps a provider that has two or more keys in a `KeyPoolChatModel`. The rule is
**sticky until error**: every request uses the key that last worked. Why sticky:

- Prompt caches belong to the account behind a key, so switching keys means a cache miss.
- For one person's chats, a cache miss costs more than spreading load saves.

Rotation is the one OpenRouter BYOK and LiteLLM's cooldowns use, not RikkaHub's or Cherry Studio's:

- A key that fails with a key-kind error before anything streamed rests, and the same request goes
  out at once on the next key.
- How long a key rests: `retry-after` if the provider gave one; otherwise 60 s for a rate limit, an
  hour for quota, and until the keys are edited for a refused key.
- If every key is resting, a request still goes out, on the key with the shortest rest.
- If every key fails, the last error surfaces.
- `KeyPool` remembers only indexes and timestamps, in memory. Keys never reach the model, the logs
  or the UI.
- Once output has streamed, the pool never switches keys. The agent loop decides instead.

### Fallback chain

- `AppSettings.fallbackModels` holds up to 4 `ModelRef`s, in order. It is one default list for all
  chats, set in Settings → Models → Backup models.
- `FallbackChain.resolve` leaves out the chat's own model, providers that were removed, on-device
  models and repeats.
- `AgentRunner` gives the loop the chain as `FallbackModel`s. Each opens on demand, with the chat's
  effort mapped to that provider. A backup that can't open (for example, no key) is skipped.

In `AgentLoop.streamWithRetry`:

- An error that `Failover` says should switch moves the run to the next model. It emits
  `AgentEvent.FellBack(from, to, reason)`, and the next model gets its own retries.
- The rest of the run stays on the backup. The next turn starts again on the chat's own model, like
  `ai-fallback`'s reset.
- A dropped connection doesn't switch models. It's usually the phone's network, and a chain of
  models would only make a network failure slower to report.

Fallbacks apply to the user's chats and scheduled runs. Helpers (sub-agents), titles, the reviewer
and compaction don't use the chain, but they do use key pools, which sit below every request.

### Never replay a partial reply

A switch happens at the same clean point the existing retry uses. Errors can arrive at any time,
and a dropped connection counts only before a tool call starts. Tools run only after a completed
turn, so a switch can't repeat a tool call.

`FellBack`, like `Retrying`, tells the UI to discard what streamed. The backup gets the original
request, so its reply never includes the partial text, either as an assistant prefill or as a "continue
from here". The saved reply comes from one model only. This is deliberately not LiteLLM's mid-stream
continuation or `ai-fallback`'s append.

### Keeping the request valid

The backup gets the same `ChatRequest` object:

- the chat's frozen system prompt;
- the chat's fixed tool list, in the same order;
- the same messages and cache key.

Adapters already rebuild other formats' assistant turns from `parts` and ignore their `rawPayload`
(`Message` docs), the same way they handle a chat whose model the user switched.

**The prompt cache is lost when the provider changes.** Caches belong to a provider and an account,
so the first reply on a backup provider pays full input price. Settings → Models says so. A backup
on the same provider (another model) may still miss, since caches are also per model.

### Showing it

- While it happens, the progress line reads "<model> couldn't answer, switching to <backup>…".
  Retries now say how long they'll wait when the provider asked ("retrying in 37 s").
- `ReplyInfo` already records the provider and model that answered. It gains `fallbackFrom`: the
  chat's own model, named as in the note. This is a new nullable Room column `messages.fallbackFrom`,
  added in v11 by an AutoMigration.
- The reply then shows a small line under it: "Answered by gpt-5.2 · claude-opus-5-5 (Anthropic)
  couldn't answer".
- Insights and budgets already use `ReplyInfo`'s provider and model, so cost is counted against
  the model that actually answered. "What was sent" shows the backup's provider.

### UI

- **Settings → Models → provider:** a "Backup keys (optional)" password field under the API key.
  Keys are separated by commas. Once saved, it shows "saved — type to replace", with Remove.
- **Settings → Models → Backup models:** the chain in order, with move-up and remove on each row and
  "Add a backup model". Adding opens a sheet: pick a provider, and optionally another of its models
  (the same model field as the helper model).

## Not done

- **A chain per chat.** One default list covers the roadmap's "or as a default". A per-chat list
  would need a column on conversations and a place in the model sheet.
- **Context-window fallbacks** (LiteLLM's `context_window_fallbacks`). A 400 for a prompt that's too
  long doesn't switch models. Compaction handles long chats.
- **On-device models as a backup.** They run one reply at a time and have a much smaller context.
- **Remembering a model that's out of quota across turns.** The next turn tries the chat's own model
  again, which costs one failed request.
