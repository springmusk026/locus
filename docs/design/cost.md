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
- **One-tap move to the model's default** (2026-11-23). An Anthropic, Responses or ChatGPT provider whose
  stored effort is still "high", on a model whose own default is known and lower (Opus 5.5, Haiku 5.5,
  OpenAI's current models: medium), gets an Info banner on Settings → Models and above Effort in its sheet:
  why (High was every provider's default before 0.1.7), what changes (replies start sooner and use fewer
  thinking tokens, so they cost less; hard tasks may get less thorough answers), and what happens to chats.
  **Use the model's default** clears the provider's effort; **Keep High** changes nothing. Either answer is
  stored (`AppSettings.effortChosen`, ids only) so the banner doesn't come back; nothing changes without the
  tap. A provider where the move changes nothing (other Claude models default to high anyway) isn't
  offered. Saving a provider with a changed or new effort also records it as chosen; saving with the same
  "high" (a new key, say) keeps the offer. `EffortDefaults.offersModelDefault`, `useModelDefault`, `keep`,
  `saved`.
  - *Which "high" is old.* Nothing recorded which providers were saved between 0.1.7 and this offer, so a
    "high" picked in the editor in that window can't be told apart from the old default and is offered once.
  - *Prompt cache.* Effort is rendered into the cached prefix (`Effort` in `ChatControls.kt`): changing it
    under a chat invalidates the cached conversation once. So the move first gives every chat that already
    has messages and follows the provider's setting (`effort` null, top-level, on this provider, or falling
    back to it as the default) its current level as its own (`ConversationDao.keepEffort`,
    `ConversationRepository.moveToModelDefaultEffort`). Their next request is byte for byte what it would
    have been; the composer's effort sheet shows High chosen for them, and the user can change it there
    (one cache miss on models without per-message effort; none on those with it, see
    [mid-chat changes](#cache-safe-mid-chat-changes)). New chats, and chats with nothing sent yet, use the model's
    default. Helper chats keep their profile's effort. No Room schema change: the column existed (v5).
- **Side jobs.** Titles and follow-up suggestions run at `low` (`HelperModel.QUICK_EFFORT`), as the
  reviewer already did. Anthropic recommends low for "simpler tasks that need the best speed and lowest
  costs, such as subagents". `low` rather than `minimal`, because GPT-6.1 Sol takes neither `none` nor
  `minimal`.
- **Chat Completions** gets an effort field only on hosts that document one
  (`Effort.CHAT_EFFORT_HOSTS`), and only for a model not known to lack reasoning:
  - OpenAI: `reasoning_effort` (API reference: none … max, "Not all reasoning models support every value");
  - Gemini's [OpenAI-compatible endpoint](https://aistudio.google.com/docs/openai): `reasoning_effort`, `minimal|low|medium|high`, with higher levels sent as `high` and `none` as `minimal`;
  - OpenRouter: `reasoning: {effort}`, per its [reasoning tokens guide](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens). That guide doesn't document a top-level `reasoning_effort`.

  A 400 or 422 that refuses the field itself gets the request once more without it, as the Responses
  adapter does for `reasoning` and `include`. "Refuses the field" means the error's `param` names it
  (`reasoning_effort`, `reasoning.effort`, `reasoning.summary`), or, without a `param`, the message
  names the field (`refusesReasoningField`). An error that only mentions reasoning, such as one about a
  replayed `rs_…` item, is not retried that way (#308): the request would fail again, and if it didn't,
  later turns would lose their encrypted reasoning. Other hosts (Groq, Ollama, local servers) never get it.

## Cache-safe mid-chat changes

Changing effort in a chat that has messages no longer changes the top-level config on models whose docs
give a per-message form: Anthropic's per-message effort (beta `mid-conversation-output-config-2026-07-01`,
an effort-only `role: "system"` message) on Opus 5.5, Opus 5, Sonnet 5.5, Haiku 5.5, Fable 5.1/5.2 and
Mythos 5.1/5.2 on Anthropic's API, and OpenAI's `configuration_update` input item on GPT-6 and later on
OpenAI's API. The request keeps its top-level effort and every earlier byte; a 400 naming the feature is
retried once the old way and remembered per provider. Chat Completions has no such item and is unchanged.
Research with sources, the request shapes, the fallback and why steering notes stay in the user message:
[midchat-config.md](midchat-config.md).

## System prompt placement on GPT-6.2 and later

Locus sent each chat's frozen system prompt in the Responses `instructions` field, on OpenAI's API and on
the ChatGPT plan route alike. From GPT-6.2 that field is no longer part of the cached prefix. OpenAI's
[prompt caching guide](https://developers.openai.com/api/docs/guides/prompt-caching), "How prefix
matching works" (read 2026-11-23):

> Use top-level `instructions` for developer guidance that applies only to the current request. […]
> Models starting with GPT-6.2, including Astra and Sol, append these instructions after `input`, beyond
> eligible cache breakpoints. […] OpenAI bills those instruction tokens at the uncached input-token rate
> on every request, even if you repeat identical instructions. To make reusable instructions eligible for
> caching, put them in a `developer` message at the beginning of `input`.

The same page, "Summary of model differences": "In the Responses API, models starting with GPT-6.2 append
top-level `instructions` after `input` and bill those tokens at the uncached input rate, even on a cache
hit. Put reusable instructions in the first `developer` message in `input`."
[Keep the prefix unchanged](https://developers.openai.com/api/docs/guides/keep-prefix-unchanged), row
"Updating developer instructions": "Put reusable instructions in the first `developer` message in `input`"
and "For models starting with GPT-6.2, use top-level `instructions` for turn-scoped developer messages."

So every request of a GPT-6.2 chat paid the whole system prompt at the full input rate.

**What Locus sends now** (`SystemPlacement`, core/llm). On GPT-6.2 and later (`gpt-6.2…`, `gpt-6.3…`,
`gpt-7…`: the model id's major.minor, so `gpt-6-astra` and `gpt-6.1-sol` are not included), at
`api.openai.com`, in a new chat, the system prompt goes first in `input`:

```json
{"type": "message", "role": "developer", "content": [{"type": "input_text", "text": "<frozen system prompt>"}]}
```

and `instructions` is left out. Everything else in the request is unchanged. The ChatGPT plan route gets
the same: its [preview rules](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
say "Use instructions or developer messages; explicit {type: "message", role: "system"} items are
rejected", and it runs the same models on `https://api.openai.com/v1`. Earlier models, other hosts and
Chat Completions send byte for byte what they did.

**Only chats that start with it move.** Each reply whose request used the developer message gets a hidden
`Part.SystemInInput` record (stored in the message's parts, like `Part.TurnEffort`: no database change). A
request uses the developer message only when every earlier assistant turn of its format has that record.
A new chat has none, so it starts that way and stays that way. A chat with turns from before this change
keeps `instructions` for good. Turns from another provider don't count: their prefix is new to OpenAI
anyway. The placement is a function of the stored messages (and of a refusal this run, below), so every
request is rebuilt the same.

Why not move chats already under way, with numbers. Take a 6,000-token system prompt and a 60,000-token
conversation on GPT-6.2 Sol (reads 0.05x, 30-minute writes 1.25x, same guide):

| | Each later request | Once |
|---|---|---|
| Keep `instructions` | system prompt at 1x: 6,000 | nothing |
| Developer message, new chat | read at 0.05x: 300, saves 5,700 | first write at 1.25x instead of 1x: +1,500, back by the second request |
| Developer message, chat under way | saves 5,700 | the whole prefix misses: 66,000 written at 1.25x = 82,500 instead of 60,000 × 0.05 + 6,000 = 9,000, so +73,500, back after about 13 requests |

The money would come back within a long task. What doesn't come back is the reasoning. The
[reasoning guide](https://developers.openai.com/api/docs/guides/reasoning), "Reasoning item removal across
requests", lists `prefix_mismatch` as a reason the API removes a replayed reasoning item: "On GPT-6.2 and
later, you insert or edit content before the reasoning item." A developer message inserted at the start comes
before every reasoning item the chat already has, and each item is checked against the request that made
it, so those items would be dropped on the move and on every request after it. A chat under way keeps
`instructions` and pays what it paid before; new chats get the saving with nothing dropped.

**Fallback.** A 400 or 422 that names `developer` or `instructions`, for a request that sent the developer
message, is retried once with the system prompt in `instructions`, and that provider (id, format, address,
model) gets `instructions` until the app restarts, as with `configuration_update` (`MidChatEffort.reject`).
The retried turn has no record, so its chat stays on `instructions`.

**What else stays the same.** `configuration_update` items (mid-chat effort, above) go where they went,
"before the next user message", after the developer message. Encrypted reasoning items are replayed untouched in
their places; Locus sends `store: false` and never `previous_response_id`, so the whole history,
developer message included, travels on every request. "What was sent" shows the developer message as the
system section, as it shows `instructions`.

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

## Offline cache simulator

Built 2026-11-23. *Not yet tried on the device or against a live key.* Answers "what would this chat or bench run
have cost under a different caching or compaction policy?" without calling a model. Developer-facing: there is no
screen for it, because Locus doesn't export a chat's sequence anywhere a user could pick it up, and a screen with
nothing to feed it would be half a feature. Code: `dev.locus.core.agent.cachesim` (core/agent): `CacheSimulator`,
`CachePolicy`, `SimSequence`, `RecordedChats`, `PolicyReport`. Tests: `CacheSimulatorTest`, `RecordedChatsTest`,
`CacheSimReportTest` (core/agent), `CostBreakdownTest` (core/model), and a bench test that runs the real loop.

### What goes in

A `SimSequence` is one chat's requests in order, a model, its `ModelPrice` (models.dev, as the app prices it) and the
policy the chat ran under. Each `SimStep` has when it was sent and when the reply ended, how much the prompt grew
since the request before (`newTokens`: the previous reply sent back, plus tool results and the user's message), the
user's own tokens, the sizes of its tool results, the output tokens, and, when recorded, the `TokenUsage` the
provider reported. The prompt is the frozen prefix (system prompt and tool list, `prefixTokens`) plus the history the
policy keeps plus the step's new tokens. JSON, so a fixture is a small file (`core/agent/src/test/resources/cachesim`).

Where sequences come from, using only what Locus already stores:

- **A stored chat.** `RecordedChats.sequenceOf(messages, …)` reads the shown branch: each assistant message's
  `usage` and stored time (less `ReplyInfo.totalMs`), the tool results and user messages before it, and checkpoints.
  Summary requests that Locus itself made aren't messages; pass them from the usage log (`UsageSource.COMPACTION`).
- **A bench run.** `TrialRunner` writes one JSON per chat to `<bench.out>/traces/` when the model's price is known
  (`BenchMain` always does). Re-price a real run under other policies without paying for it again.

What the store doesn't keep is estimated, and each estimate is listed under the table it affects:

| Estimated | How | Where it shows |
|---|---|---|
| System prompt and tool list size | The first request's prompt less its user message (4 characters a token). Pass `prefixTokens` when known. | `approximations` |
| What a step added after a summary or a cleared-results gap | The prompt shrank, so growth can't be read from it: estimated from the stored text. | `approximations` |
| When a request was sent | Stored reply time less its duration; the stored time if no duration was kept. | `approximations` |
| How long a summary is | The mean of the chat's own checkpoints, else 4,000 tokens (the sample above). | `observedSummaryTokens` |
| A tool result's tokens | Its characters over 4, as compaction and cold clearing estimate, scaled down if the recorded growth was smaller. | none |

### The rules, and where each comes from

Nothing is priced twice: the simulator builds a `TokenUsage` per request and prices it with `ModelPrice.cost`, so price
steps, 1-hour writes and server-side summaries are the app's own rules ([What a request costs](#what-a-request-costs),
[Price steps](#price-steps)). `ModelPrice.breakdown` (new, and now what `cost` is built on) splits that into fresh
input, reads, writes and output for the table. Compaction uses `Compaction.due`, `CompactionSetup.limit`,
`PriceSteps.ceiling` and `AnthropicChatModel.compactionTrigger`; clearing uses `ColdClearing.view` over stand-in
messages of the recorded sizes.

| Rule | Source |
|---|---|
| Two entries: the frozen prefix and the conversation so far. A request reads the longest one still alive, writes the rest, and refreshes both. | [Cache lifetimes](#cache-lifetimes) ("restarts its 5-minute lifetime") |
| The prefix is kept an hour, the conversation an hour in a main chat and 5 minutes elsewhere; a gateway or OpenRouter only gets 5 minutes. | [Cache lifetimes](#cache-lifetimes), [OpenRouter](#openrouter-and-chat-completions-caching) |
| A write is priced by its own lifetime: 2x input for an hour (`cacheWrite1h`), `cost.cache_write` for the short one. | [What a request costs](#what-a-request-costs) |
| Automatic providers (OpenAI, Gemini, DeepSeek…) use one idle lifetime for both entries and bill no writes unless the policy says they do (GPT-5.6 on). The default is 30 minutes, "at least 30 minutes on 5.6 and later". | [cold-cache clearing](cold-cache-clearing.md#cold-boundaries), [Cache writes on OpenAI](#cache-writes-on-openai) |
| Locus compacts when the last request's context plus the results about to be added pass the lower of 80% of the window and the price-step ceiling; never at the step right after another compaction. | [compaction](compaction.md#at-a-step-boundary), [Compacting below a price step](#compacting-below-a-price-step) |
| A summary request is the next turn's prompt plus the compaction prompt: it reads the cache, keeps the 5-minute lifetime, and its own write is never read. | [compaction](compaction.md#the-summary-request), [prompt caching](compaction.md#prompt-caching) |
| After a summary the transcript is one message: the summary, the user's messages kept word for word (up to `Compaction.keptTokens`), and the pointer. The next request reads only the frozen prefix. | [compaction](compaction.md#what-replaces-the-transcript) |
| Anthropic's server-side summary runs inside the request that crosses the trigger (90,000 on Haiku 5.5, else 150,000), reads the whole prompt at the input price, and the request's own prompt is then the summary. | [Server-side compaction](#server-side-compaction), [compaction](compaction.md#server-side-compaction-anthropic) |
| A request is priced at the highest price step its whole prompt is over. | [Price steps](#price-steps) |
| Cold-cache clearing: boundaries, the five newest results kept, 2,000 characters, 20,000 tokens, errors and the running task stay, never on Anthropic or Responses. A clearing that changes the prompt breaks the cache from the first cleared result on. | [cold-cache clearing](cold-cache-clearing.md) |

### Running it

`PolicyReport.table(sequence)` prices a sequence under the recorded policy and one change at a time (conversation
cache 5 minutes or 1 hour, no compaction, a different compaction point, a different server trigger, cold clearing
off or on, a different automatic lifetime), skipping what the provider can't do. Pass your own `CachePolicy` list to
compare others.

```
locus-gradle-lock.sh -q :core:agent:test --tests '*CacheSimReportTest*' -i
LOCUS_CACHESIM_FILE=/path/to/trace.json locus-gradle-lock.sh -q :core:agent:test --tests '*CacheSimReportTest*' --rerun -i
```

The first prints a table per fixture and writes `core/agent/build/reports/cache-sim/<name>.md`; the second prices a
bench trace or any `SimSequence` JSON. Output for the checked-in Claude Opus 5.5 fixture (a chat with pauses; synthetic):

| Policy | Total | vs first | Uncached input | Cache read | Cache write | Output | Compaction | Requests | Summaries |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Locus today (recorded) | $0.3054 | +0.0% | $0.0000 | $0.0054 | $0.2720 | $0.0280 | $0.0000 | 4 | 0 |
| Conversation cache 5 min | $0.2874 | -5.9% | $0.0000 | $0.0044 | $0.2550 | $0.0280 | $0.0000 | 4 | 0 |

The 5-minute row is cheaper here. It pays 1.25x instead of 2x on every conversation write, and loses only at the
11-minute pause, where it rewrites 6,000 tokens that the 1-hour setting reads. A chat with more pauses between 5
and 60 minutes would flip it; the break-even is the one in [Cache lifetimes](#cache-lifetimes).

### Validation

- `CacheSimulatorTest` runs each fixture under its recorded policy and requires every simulated request to equal the
  reported `TokenUsage`, and the total to equal what `RunBudget` (the receipts' and budgets' accounting) and
  `ModelPrice.cost` give for the recorded usage.
- The fixtures' usage was worked out by hand from the rules above (and checked with an independent script), **not
  captured from a provider**. So the tests show the simulator and the app's pricing agree, not that Anthropic or
  OpenAI bill this way; the rules' sources are the pages cited above.
- `FakeRunTest` runs a bench trial through the real `AgentLoop` with a priced fake model, writes its trace, and
  requires the simulated total under the recorded policy to equal the trial's cost from `RunBudget`.
- `RecordedChatsTest` builds stored messages, turns them into a sequence and does the same.

### Not modelled, and not done

- The provider's minimum cacheable length, Anthropic's 20-block lookback, and misses a provider adds on its own. A live
  bill can differ.
- The model's output is taken as recorded under every policy. A summary changes what a model writes next, and the
  simulator can't know how.
- A server-side summary is priced as uncached input (the upper bound, as in the sample above), and its effect on the
  next prompt is "the summary, then the user's new message".
- The bench's own compaction ceiling uses the documented price step only, while the simulator takes the catalogue's
  when the price has tiers. They agree for Haiku 5.5 and GPT-5.x.
- OpenAI's actual lifetimes depend on retention and load, which a request can't choose; the 30-minute default is a
  parameter (`automaticIdleMs`), not a measurement.
- No export of a real chat from the app: a chat's sequence can be built in a test from its stored messages, but no
  screen or file produces one. That is the missing piece for a user-facing surface.
