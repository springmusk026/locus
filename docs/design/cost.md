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
