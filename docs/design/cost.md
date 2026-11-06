# Cost accounting and defaults

What Locus counts when it shows a cost (receipts, task budgets, Insights, the bench), and the defaults
that keep long chats cheap. Sources were read on 2026-11-06; each rule names its page.

## What a request costs

`ModelPrice.cost(TokenUsage)` (core/model) prices every request. Receipts (`TaskReceipts`), task
budgets (`RunBudget`), Insights (`AnalyticsRepository`) and the bench (`TrialRunner`) all go through it,
and budget tokens go through `TaskBudget.budgetTokens`.

| Part | Price | Source |
|---|---|---|
| Fresh input | `cost.input` | models.dev |
| Cache read | `cost.cache_read` (0.1x input; 0.05x on Opus and Sonnet 5.5; 0.025x on Fable 5.1) | Anthropic [pricing](https://platform.claude.com/docs/en/about-claude/pricing) |
| Cache write, short lifetime | `cost.cache_write` (Anthropic 5 min: 1.25x; OpenAI 30 min: 1.25x) | Anthropic pricing; OpenAI [prompt caching](https://developers.openai.com/api/docs/guides/prompt-caching) |
| Anthropic 1-hour write (`cacheWrite1h`) | 2x input | Anthropic [prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching) |
| OpenAI 12-hour write (`cacheWrite12h`) | 2.5x input | OpenAI prompt caching |
| Output, thinking included | `cost.output` | models.dev |
| Server-side compaction (`serverCompaction`) | priced as a request of its own | Anthropic [threshold compaction](https://platform.claude.com/docs/en/build-with-claude/compaction-threshold) |

### Server-side compaction

Anthropic bills its summary inside the request that triggered it, in `usage.iterations`: "The top-level
`input_tokens` and `output_tokens` do not include compaction iteration usage … To calculate total tokens
consumed and billed for a request, sum across all entries in the `usage.iterations` array." Before this
change Locus read only the top level, so every server-side summary (150,000 tokens or more) was missing
from receipts, budgets and Insights.

`AnthropicChatModel` now sums the `compaction` entries into `TokenUsage.serverCompaction`. It isn't part
of `input` or `context`, so how full the chat looks doesn't change. Insights counts it as one more
request under **Compaction** in "Where tokens went". Receipts and the bench add it to their token totals.

The docs show only `type`, `input_tokens` and `output_tokens` on an iteration entry. If one ever carries
cache counts, `usageOf` reads them too.

### Cache writes on OpenAI

On GPT-5.6 and later, OpenAI bills cache writes. `cached_tokens` "excludes tokens charged as writes",
`cache_write_tokens` is the total written, and `cache_write_tokens_details.ttl_30m_tokens` and
`ttl_12h_tokens` "do not overlap and sum to `cache_write_tokens`". The Responses adapter reads all three.
Chat Completions reads `prompt_tokens_details.cache_write_tokens`, which the
[API reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
lists ("The unadjusted number of prompt tokens written to cache") and OpenRouter also reports.

### Price steps

Some models charge more for a long prompt. models.dev lists the steps in `cost.tiers`, each
`{"tier": {"type": "context", "size": 100000}, …prices}`. Older entries only have `context_over_200k`.
`ModelPrice.tiers` holds them, and a request is priced at the highest step its whole prompt is over.
Anthropic says "A request's prompt length counts all of its input tokens, including cache reads and cache
writes". Before, Locus assumed a 200,000-token step for every model, so Claude Haiku 5.5 (100,000) and
GPT-5.x (272,000) were priced wrongly between the two. The catalogue cache version went to 3, so the
next refresh picks up the tiers.

Anthropic's pricing page: "Claude 4.6 and later models (except Claude Haiku 5.5) … include the full 1M
token context window at standard pricing", and "Claude Haiku 5.5 is priced by prompt length: a request
whose prompt is over 100,000 tokens pays higher prices". Above the step every rate is 5 times as high,
output included. `PriceSteps` (core/model) keeps that documented step for when the catalogue is off.

## Compacting below a price step

`PriceSteps.compactBelow(step)` puts the compaction point a tenth of the step below it, and at least
10,000 tokens below (never under 50,000). For Haiku 5.5 that's 90,000. Anthropic's cost guide says: "set
its `trigger` a little below 100,000 minus your prompt's largest growth between requests". It also
reports that "a trigger of 90,000 cost 70% less per request than the default".

- **Anthropic server-side compaction** sends `trigger: {"type": "input_tokens", "value": 90000}` on
  Haiku 5.5. The value comes from the documented table only, never from the downloaded catalogue. That
  makes it a pure function of the model, so every request of a chat sends the same `context_management`.
  It's a prompt-affecting parameter: the [cache diagnostics](https://platform.claude.com/docs/en/build-with-claude/cache-diagnostics)
  page lists it with `tool_choice`, `thinking` and `output_config`. Models without a step keep the API
  default of 150,000.
- **Locus's own compaction** (every other provider) starts at the lower of 80% of the window and the
  ceiling (`CompactionSetup.ceiling`). The ceiling comes from the catalogue's step when it has one,
  else from the documented one. GPT-5.6 with a 272,000 step compacts at 244,800, not at 840,000.

One cost: chats on a stepped model are summarised more often. The kept user messages, the checklist
and `chat_history_read` are unchanged (see [compaction](compaction.md)).

## Cache lifetimes

| Breakpoint | Main chat, Anthropic API | Helpers, side jobs, summaries | Gateways |
|---|---|---|---|
| Tools and system prompt | 1 hour | 1 hour | default (5 min) |
| Conversation so far | **1 hour** (new) | 5 minutes | default |

Claude through OpenRouter gets both breakpoints at the default lifetime; see
[OpenRouter and Chat Completions caching](#openrouter-and-chat-completions-caching).

`ChatRequest.longCache` is on only for the user's own interactive runs (`AgentConfig.longCache`,
`AgentRunner`), not for scheduled or triggered runs. Anthropic's
[cost guide](https://platform.claude.com/docs/en/about-claude/models/optimizing-for-cost-and-intelligence)
says to "use the 1-hour cache duration once about 1 turn in 20 follows a pause between 5 minutes and an
hour", gives the break-even as "about 1 turn in 30" on Sonnet and Opus 5.5, and says "at 1 turn in 10
the 5-minute setting cost 54% more". On a phone, reading a reply, approving a call or answering
`ask_user` often takes longer than 5 minutes.

The order rule holds: "Cache entries with longer TTL must appear before shorter TTLs", and the prefix
always comes first. Switching an existing chat doesn't cost a miss. "A request that reads an entry
cached with a 5-minute TTL restarts its 5-minute lifetime, even if the request now sets `"ttl": "1h"`",
so the old entry is still read and only new tokens are written for an hour.

The trade: every new write costs 2x input instead of 1.25x. A chat with no pauses pays 0.75x input more
on each step's new tokens.

## The cache line

"What was sent" has a **Cache** row built from the reply's usage (`SentView.cacheLine`), for example
`warm · 93% read · 2.1K written (1h + 5m)`. It shows:

- warm (something was read) or cold (nothing was read);
- the share of the prompt read from the cache;
- the tokens written, with the lifetimes the request asked for, read from its `cache_control` markers;
- a server-side summary, when one ran.

A provider that reports neither reads nor writes shows "none reported".

**Anthropic `diagnostics` is not used.** The cache diagnostics page says the API "stores a fingerprint
only for requests that include the `diagnostics` object". It also says the comparison is "independent
of whether the cache actually hit". It doesn't say whether adding the field to an existing chat keeps
that chat's cache. The rule for this work was to add it only if the docs confirm it is cache-neutral,
and they don't. It's also Claude API only, and it keeps a fingerprint at Anthropic.

## Effort

- **The default is the model's own.** `ProviderConfig.effort` now defaults to blank, which sends no
  effort. Anthropic: "Setting `effort` to the model's default (`"medium"` on Claude Opus 5.5 and Claude
  Haiku 5.5, `"high"` on other models) produces exactly the same behavior as omitting the `effort`
  parameter entirely". OpenAI's current models default to `medium`
  ([reasoning](https://developers.openai.com/api/docs/guides/reasoning)). The provider sheet has a
  **Default** option that names the model's level, and the composer's effort sheet says "Medium, the
  model's own default".
- **Existing settings.** Settings are stored with every default written out, so a provider saved
  before has `"effort": "high"` whether or not anyone chose it. Anthropic and Responses providers have
  always shown the control, so their stored level may be a choice and is kept: their requests don't
  change. Chat Completions providers never showed it, so their "high" was never a choice and was never
  sent. A one-time DataStore migration (`EffortDefaults`, `EffortDefaultsMigration`) clears it. Saving a
  provider marks the settings as migrated, so a "high" chosen later is never undone.
- **Side jobs.** Titles and follow-up suggestions run at `low` (`HelperModel.QUICK_EFFORT`), as the
  reviewer already did. Anthropic recommends low for "simpler tasks that need the best speed and lowest
  costs, such as subagents". `low` rather than `minimal`, because GPT-6.1 Sol takes neither `none` nor
  `minimal`.
- **Chat Completions** gets an effort field only on hosts that document one
  (`Effort.CHAT_EFFORT_HOSTS`), and only for a model not known to lack reasoning:
  - OpenAI: `reasoning_effort` (API reference: none … max, "Not all reasoning models support every value");
  - Gemini's [OpenAI-compatible endpoint](https://aistudio.google.com/docs/openai): `reasoning_effort`, `minimal|low|medium|high`, with higher levels sent as `high` and `none` as `minimal`;
  - OpenRouter: `reasoning: {effort}`, per its [reasoning tokens guide](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens). That guide doesn't document a top-level `reasoning_effort`.

  A 400 or 422 that names reasoning gets the request once more without the field, as the Responses
  adapter does. Other hosts (Groq, Ollama, local servers) never get it.

## OpenRouter and Chat Completions caching

Claude through OpenRouter was never cached: the Chat Completions adapter sent no `cache_control`, and
OpenRouter says Claude needs it. Every step paid full input. `OpenAiChatModel.cacheHints` now adds
what each host documents, and nothing anywhere else. Sources (read 2026-11-06): OpenRouter
[prompt caching](https://openrouter.ai/docs/guides/best-practices/prompt-caching),
[provider routing](https://openrouter.ai/docs/guides/routing/provider-selection) and
[usage accounting](https://openrouter.ai/docs/guides/administration/usage-accounting); the OpenAI
[Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create);
Anthropic [prompt caching](https://platform.claude.com/docs/en/build-with-claude/prompt-caching).

| Host and model | What's added | Why |
|---|---|---|
| OpenRouter, `anthropic/*` | `cache_control: {type: "ephemeral"}` on the system message as a content part, the same at the top level, and `session_id` | Explicit caching is required for Claude there. Two breakpoints, as on Anthropic's API (see below). |
| OpenRouter, any other model | `session_id` only | OpenAI, DeepSeek, Gemini 2.5+, Grok, Moonshot, Groq and Z.AI cache automatically there. |
| `api.openai.com` Chat Completions | `prompt_cache_key` | "Used by OpenAI to cache responses for similar requests to optimize your cache hit rates." |
| Anything else (Groq, Gemini's endpoint, DeepSeek, Ollama, LM Studio, vLLM, other proxies) | nothing | An unknown field can be a 400 there. |

Request shape for a Claude chat through OpenRouter, the same on every turn apart from the new messages:

```json
{
  "model": "anthropic/claude-opus-5.5",
  "stream": true,
  "messages": [
    {"role": "system", "content": [{"type": "text", "text": "<frozen system prompt>", "cache_control": {"type": "ephemeral"}}]},
    {"role": "user", "content": "…"},
    {"role": "assistant", "content": null, "tool_calls": […]},
    {"role": "tool", "tool_call_id": "…", "content": "…"}
  ],
  "tools": […],
  "cache_control": {"type": "ephemeral"},
  "session_id": "locus-chat-<id>"
}
```

- **Two breakpoints, as on Anthropic's own API.** The marker on the system message ends the frozen prefix
  (tools come before the system prompt in Claude's prefix order). The top-level marker is Anthropic's
  automatic caching, which "applies the cache breakpoint to the last cacheable block and advances it
  forward as conversations grow"; OpenRouter documents it for Anthropic, Vertex, Azure, Bedrock (which it
  converts to a trailing breakpoint) and Claude Platform on AWS. Anthropic: "Automatic caching is
  compatible with explicit cache breakpoints. When used together, the automatic cache breakpoint uses one
  of the 4 available breakpoint slots." The two have the same lifetime, so the 400 for "a different TTL"
  can't happen.
- **Why not a marker on the last user message.** In an agent loop the last message is usually a tool
  result, and OpenRouter's API reference types a tool message's `content` as a plain string. The
  top-level marker reaches it; a content-part marker on the last user message would cache only up to the
  user's turn and miss every tool step after it. Messages are sent exactly as before.
- **5 minutes, not an hour.** OpenRouter supports `ttl: "1h"` for Claude, but "Sticky sessions expire
  after 10 minutes of inactivity", after which the next request may reach a provider without the entry.
  `cache_write_tokens` also isn't split by lifetime, so a 2x write would be priced as 1.25x.
- **Sticky routing.** `session_id` (the chat's `PromptCacheKey`, `locus-chat-<id>`, or
  `locus-helper-<profile>` for a helper) keeps the chat on one upstream provider: with it, "stickiness
  activates immediately on the first successful request". Cache hits need the same provider. No
  `provider.order` is sent: "Sticky routing is not used when you specify a manual provider order". The
  key is at most 64 characters (OpenRouter's limit is 256).
- **One-off requests** (titles, suggestions, the reviewer: `ChatRequest.cache = false`) get no markers,
  since their write would never be read. They carry no key, so no `session_id` either.
- **A 400 or 422 that names `cache_control`, `session_id` or `prompt_cache_key`** gets the request once
  more without them, as effort does. OpenRouter doesn't document a provider that rejects them.
- **Usage.** OpenRouter returns `prompt_tokens_details.cached_tokens` ("read from the cache") and
  `cache_write_tokens` ("only returned for models with explicit caching and cache write pricing"), which
  `OpenAiChatModel.tokenUsage` already read into `TokenUsage.cacheRead` and `cacheWrite`. Receipts, the
  cache line in "What was sent", and Insights show them with no other change. `prompt_tokens` is taken
  as the whole prompt, cached parts included, as OpenAI defines it; OpenRouter's own example (10,318
  cached of 10,339) reads the same way, though the page doesn't say so in words.

Cache safety: every added value is fixed for the chat's life, so the bytes before the new messages are
the same from turn to turn (`ChatCompletionsCachingTest` checks two turns). The main chat's tools and
prompt are unchanged.

Not done: Qwen on OpenRouter also needs explicit breakpoints, and Gemini takes them there too (only the
last one counts). Both are left out until there's a reason to mark them.

## The helper model

**Setting.** Settings → Helper model & extras → **Helper model** has three choices: **Automatic** (the
default), **Same as each chat**, or a provider with an optional model. A project's own helper model
still wins over all three (`HelperModel.providerFor`).

**Automatic** (`HelperModelChoice`, core/llm) picks a cheaper model of the chat's own provider, on the
same key. With no pick it uses the chat's own model.

1. Never for a chat already on a cheap tier (a Haiku, mini, nano, Flash or Lite model), an on-device
   model or a local server.
2. With the models.dev catalogue on: the newest model on the same endpoint, of the same maker (the
   `vendor/` prefix) and model line ("claude", "gpt", "gemini"), whose name marks a cheap tier (`haiku`,
   `mini`, `flash`). It must not be deprecated, must call tools, must have at least 128,000 tokens of
   context, and must cost less than the chat's model for input and no more for output. Nano and Lite
   tiers and special-purpose models (image, audio, realtime, search, deep research) are left out. The
   catalogue now keeps `release_date` and `status` (cache version 4).
3. Without the catalogue: the documented default for Claude only. That's `claude-haiku-5-5` on Anthropic's
   API ([models overview](https://platform.claude.com/docs/en/about-claude/models/overview): "From $0.10
   / input MTok, From $0.50 / output MTok", tool use, 1M window) and `anthropic/claude-haiku-5.5` on
   OpenRouter ([model page](https://openrouter.ai/anthropic/claude-haiku-5.5)). Other providers need the
   catalogue's prices to know what's cheaper.
4. Capability negotiation: the pick is then checked against what the provider says
   (`ModelDirectory.capabilities` and, once fetched, its model list). A model known not to call tools,
   one with a window under 128,000, or one the key's list doesn't have is skipped for the chat's own.

| Chat's model | Automatic picks | From |
|---|---|---|
| `claude-opus-5-5`, `claude-sonnet-5-5`, `claude-fable-5-1` (Anthropic API) | `claude-haiku-5-5` | catalogue, else the documented default |
| `anthropic/claude-*` (OpenRouter) | `anthropic/claude-haiku-5.5` | catalogue, else the documented default |
| `gpt-6.1` and other GPT models | the newest `gpt-…-mini` (not nano, realtime or o-series) | catalogue only |
| `gemini-3-pro` | the newest `gemini-…-flash` (not Flash-Lite or image) | catalogue only |
| `openai/…`, `google/…` on OpenRouter | the same, under OpenRouter's ids | catalogue only |
| `claude-haiku-5-5`, `gpt-…-mini`, `gemini-…-flash-lite` | itself | already cheap |
| a model with no cheaper line-mate (Mistral Large, Llama on Groq whose mini has 32K) | itself | nothing fits |
| local servers, on-device, unknown hosts | itself | unknown |

**Who uses it.**

- **Titles and follow-up suggestions** use it, at `low` effort. They're a few hundred tokens of
  checkable output each, sent uncached (`cache = false`), so moving them off the chat's model costs no
  cache hit. Anthropic: "High-volume work with outputs you can check: try Claude Haiku 5.5 first".
  Workflow drafts use it too.
- **Helpers whose profile says `model: helper`**: the built-in researcher and explorer, and any user
  helper that asks for it. `agent_spawn`'s `model: "helper"` asks for it per call. A fork keeps the
  parent's model, since only that model can continue (and cache) the parent's transcript.
- **Coder, ops and browser keep the chat's model.** Anthropic's [cost guide](https://platform.claude.com/docs/en/about-claude/models/optimizing-for-cost-and-intelligence)
  found delegation ahead only for "routine work with a long cost tail" and "work larger than one context
  window". On coding, Haiku 5.5 helpers under Opus 5.5 "cut cost per attempt by 32% and the pass rate by
  about 4 percentage points (95% interval 1.3 to 7.1), about what a mix of `low` and `medium` without
  subagents would give at that cost". Sonnet 5.5 helpers under Opus 5.5 "cost about a third less … and
  scored about the same" on work larger than one context. Driving a browser is one long dependent chain
  over untrusted pages, where a weaker model is easier to mislead. Writing code and changing machines
  have the same stakes.
- **The safety reviewer never gets the automatic pick.** It decides which actions run without asking, so
  a cheaper model does that only when the user chose one. Haiku 5.5 is at 85% on GPQA Diamond against
  91% for Opus and Sonnet 5.5 (same guide).
- **Compaction summaries stay on the chat's model.** The summary request reuses the chat's cached prefix
  (system, tools and history); on another model that prefix would be a full miss.

**Cache impact.** Helper chats are separate requests with their own prefix and key
(`locus-helper-<profile>`), written once per helper and read on its later steps, the same as before.
Side jobs are uncached. The main chat's tools, system prompt and model are unchanged, so its cache is
untouched. Existing users with no helper model set move to Automatic, which only changes requests that
were never part of the chat's prefix.

### Cost per helper run

`HelperSpend` (core/agent) adds each of a helper's requests at its model's models.dev price
(`ModelPrice.cost`, so cache reads, writes and server-side summaries count as on any receipt):

- **The helper's sheet** (tap a helper row) shows it in the subtitle: `Done · claude-haiku-5-5 · 15K
  tokens · ≈$0.02`. A request answered by another model (a backup) has no price there, so the total
  becomes a floor and ends in `+`. With no known price, the cost is left out. A continued helper counts
  on from its saved transcript (`SubagentRunner.helper`).
- **The task receipt** lists each helper with its model and cost (`ReceiptHelper`,
  `TaskReceipts.helpersLabel`), one a line in the sheet and on one line in the shared text:
  `Researcher #1: ≈$0.02 on claude-haiku-5-5`. Only the helper's requests inside the task count. The task's
  total already included them.

Nothing new is stored: both are worked out from the usage already kept per message.

### Estimated savings

Computed offline from Anthropic's published prices (Opus 5.5: $4 input, $20 output, $5 5-minute write,
$0.20 read; Sonnet 5.5: half that; Haiku 5.5: $0.10, $0.50, $0.125, $0.01). These are models, not
measurements.

| Case | Before | After | Change |
|---|---:|---:|---:|
| Claude Opus 5.5 through OpenRouter, a 40-step task (12K prefix, 3K added and 400 out per step) | $12.85 | $1.64 | −87% |
| The same on Sonnet 5.5 | $6.42 | $0.82 | −87% |
| A researcher helper, 10 steps (6K prefix, 4K of pages and 600 out per step), cached, on Opus 5.5 → Haiku 5.5 | $0.40 | $0.011 | −97% |
| Suggestions after 100 turns plus 10 titles, Opus 5.5 → Haiku 5.5 | $0.87 | $0.02 | −97% |

The OpenRouter figure assumes every step after the first hits the cache on the same provider, so it's
the upper bound. Anthropic's own measurement of "no caching" was 3.1–3.3 times the cost of the same task
cached, which is about −70%. A helper on Haiku may also need more steps or a retry: Anthropic's coding
measurement above is the honest guide for helpers, about a third cheaper at a few points lower, and
cheaper per call isn't the same as cheaper per solved task. The bench (`multi` suite) can measure cost
per passed trial with `helper = chat model` against Automatic. That run costs money, so it wasn't done.

## Sample: a long Haiku 5.5 chat

Computed offline from Anthropic's published Haiku 5.5 prices. Below 100,000 tokens: $0.10 input, $0.125
5-minute write, $0.20 1-hour write, $0.01 read and $0.50 output per million tokens. Above, each rate is 5x.

The sample chat:

- a 12,000-token cached prefix, then 200 requests;
- each request adds 4,000 tokens and writes 1,000 output tokens;
- every tenth request follows a 20-minute pause;
- a server summary reads the whole prompt at the input price and writes 4,000 tokens.

| | Cost | Requests over 100K | Summaries |
|---|---:|---:|---:|
| Before (150K trigger, 5-minute conversation cache) | $2.02 (summaries $0.52) | 72 | 6 |
| Before, as Locus reported it (no summaries, 200K step) | $0.51 | n/a | n/a |
| After the trigger only (90K, 5-minute cache) | $0.51 | 0 | 11 |
| After (90K trigger, 1-hour conversation cache) | $0.49 (summaries $0.12) | 0 | 11 |

That's about 76% less than before, and the receipt now matches the bill. The figures are a model, not a
measurement. Anthropic's cost guide calls its own numbers "directional", and the real effect depends on
the chat. The summary's own cache use isn't documented, so it's priced as uncached input, which is the
upper bound.
