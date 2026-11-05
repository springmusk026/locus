# Model capabilities

Before a request goes to a model, whether it's the chat's own model, a backup from the fallback
chain (docs/design/fallbacks.md) or the helper model, Locus checks whether that model can serve it.
Roadmap item: "Model capability negotiation".

## What a model can do

`ModelCapabilities` (core/llm) has these fields. Each ability is tri-state (`Support`: yes, no or
unknown), and unknown never means no.

| Field | Meaning |
|---|---|
| `tools` | Tool (function) calling |
| `vision` | Image input |
| `reasoning` | Thinking or reasoning effort |
| `structuredOutput` | JSON-schema constrained output |
| `contextWindow` | Input window in tokens, or null |
| `caching` | `EXPLICIT` (Anthropic `cache_control`), `AUTOMATIC` (OpenAI, Gemini implicit, DeepSeek), `NONE` (on-device) or `UNKNOWN` |

### Sources, in order

`ModelCapabilityResolver.resolve` takes each field from the first source that knows it.

1. **The provider's own model list** (`RemoteModel`, filled by `ModelLister`). Checked 2026-11-01:
   - OpenRouter `GET /api/v1/models` ([docs](https://openrouter.ai/docs/guides/overview/models)):
     - `supported_parameters` is the list of parameters a model accepts. `tools` means tool calling.
       `reasoning`, `include_reasoning` or `reasoning_effort` means reasoning. `structured_outputs`
       means structured output. When the list is present, a missing entry is a known no.
     - `architecture.input_modalities` containing `image` means vision.
     - `context_length` is the window.
     - On the live list, 66 of 452 models lack `tools`.
   - Anthropic `GET /v1/models` ([docs](https://platform.claude.com/docs/en/api/models/list)) reads
     `capabilities.image_input`, `.thinking` and `.structured_outputs` (each `.supported`), and
     `max_input_tokens`. Tool calling isn't listed, since every current Claude model has it.
   - Gemini: the OpenAI-compatible `/v1beta/openai/models` returns ids only
     ([docs](https://ai.google.dev/gemini-api/docs/openai#list-models)). For Gemini's own endpoint,
     Locus also reads the native `GET /v1beta/models` ([docs](https://ai.google.dev/api/models)) for
     `inputTokenLimit` and `thinking` ("Whether the model supports thinking"). This is best effort:
     it gives up after 5 s and is ignored on failure. The native list has no tool or vision flags.
   - The context window set in Settings for a provider counts here too, for its default model only.
2. **The models.dev catalogue**, when the user turned it on. Its fields were checked against
   `https://models.dev/api.json` on 2026-11-01 (8,940 models):
   - `tool_call`, `reasoning`, `modalities.input` and `limit.context` are present on every model,
     so their false values are a known no.
   - `structured_output` is present on 6,796 of them. Where it's missing, it's unknown.
   - `attachment` is broader than images (it covers files), so vision comes from `modalities.input`.

   The cached copy used to store "no" and "unknown" alike as `false`, so `CACHE_VERSION` went to 2
   and the copy is fetched again.
3. **Built-in rules** (`CapabilityRules`) for well-known families: Claude, GPT-3.5/4o/4.1/5,
   o-series, Gemini 1.5 to 3, DeepSeek chat and reasoner, and the on-device Gemma (tools, images,
   a 16K window). Anything else stays unknown. Prompt caching follows the endpoint more than the
   model, so it comes from the API format and host.
4. Otherwise **unknown**.

## What a chat needs

`ModelRequirements.of(request, …)` (core/llm) reads the request:

- **tools** when it declares any;
- **images** when a message it sends carries an image the adapters send inline (JPEG, PNG, GIF,
  WebP). A PDF isn't an image;
- **contextTokens**: the provider's own count for the latest reply that has one, plus about four
  characters a token for what came after. Without a count, the whole request is estimated. An
  image counts 1,600 tokens.

The chat screen's `chatNeeds` adds the composer's attachments and text. It counts only what the
model will see: after a compaction checkpoint, earlier images no longer count. It also asks for
**reasoning** when the chat chose a thinking effort of its own, other than Off.

`ModelCapabilities.fit(needs)` returns `Fit`:

- `lacks`: needs the model is known not to meet;
- `unconfirmed`: needs no source could confirm.

A model is unusable only when it lacks a **hard** need: tools, vision, the context window or
structured output.

Reasoning is **soft**. A model that doesn't reason still answers, and the adapters drop effort
settings a model rejects. So it never skips a backup or blocks a send.

**The context window** is too small when the window is less than the context plus a margin for the
reply. The margin is 8,192 tokens, but at most an eighth of the window, so a 16K on-device window
keeps most of its room. An unknown window is mentioned only when the context passes 32K, since
nearly every current model takes that.

## Where it's used

**Fallbacks.** `FallbackModel` carries the backup's capabilities, resolved by
`ModelDirectory.capabilities` when the run starts. The resolver uses the provider's last fetched
list, the catalogue on disk and the rules, and never waits on the network.

When the loop switches (`Serving.switch`), it computes the needs of the request being retried, so
the context so far is counted at that moment. It skips backups that lack a hard need. Unknown
abilities are tried.

`AgentEvent.FellBack` carries the skipped backups and what couldn't be confirmed about the new
one. The progress line reads like this:

> main couldn't answer, switching to c (skipped b: no vision)… Not confirmed: tool calling.

When no backup is left, the error names the skipped backups and why.

**Before sending.** In `ChatViewModel.send`, if the chat's own model lacks a hard need, the
message isn't sent and a dialog says why. Unknown never triggers it. The dialog suggests the first
model the user has picked before that is known to cover what's missing: favourites, then recents,
then backups, then each provider's default. The choices are:

- **Switch and send**: switch to the suggested model and send;
- **Send anyway**;
- **Back**: the text returns to the composer, and attachments stay.

**The model sheet.** Each row shows compact chips: the window ("200K"), then Tools, Vision and
Reasoning when they're known yes, and "Chat only" when tool calling is known no. A model that can't
serve this chat stays listed, with a caution chip "Can't use here: no vision". Hard needs nobody could
confirm show in the subtitle ("Vision not confirmed"). An empty chat doesn't need tools yet: it
starts chat only on a model without them ([Small models and tools](#small-models-and-tools)).

**Helpers.** A helper (sub-agent) always calls tools. If the helper model, or a profile's own
model, is known not to, the helper runs on the chat's model instead (`servesHelper`). A fork
already runs on the chat's model.

The helper model's side jobs don't need tools or images: titles, suggestions and side questions
are short text-only prompts.

## Small models and tools

Found on the phone (2026-11-05): Qwen3.5 0.8B (GGUF, llama.cpp) was in the recommended list with
Tools. In agent mode it took about 47 s a reply and gave non-answers. With the chat's tools switched
off and the phone offline, it made 26 refused tool calls in a row on "Name three planets with rings"
until it was stopped. Switched-off tools stay declared, because the tool list is fixed per chat for
the prompt cache (`ToolGroup`, ARCHITECTURE.md "Append-only history"), and a refused call returns an
error the model is meant to read. Small models call again instead.

### Options

The constraints are the prompt-cache invariants: a frozen system prompt, a tool list fixed per chat,
and append-only history. Three options were weighed:

| Option | What it fixes | Cache | Taken |
|---|---|---|---|
| **(a) Loop breaker** in the agent loop | Any model spinning on calls that can't run, today and with models we haven't seen | Untouched: it only ends the turn | Yes |
| **(b) Chat only from the start** for models that can't use tools | The small model never sees tools, so it can't loop on them. Its prompt is also much shorter: the tool list is thousands of tokens to prefill on a phone CPU | Decided before the first message is sent, then fixed | Yes |
| **(c) Re-tier the catalogue**: should 0.8B carry Tools? | The label promised something the model can't do | Labels only | Yes, as the input to (b) |

They work together. (c) says which models can't use tools, (b) gives chats on them no tools, and
(a) catches everything else: a big chat switched to a small model, a model with an unknown size, or a
capable model stuck on one failing call.

**Practice elsewhere.** Gemini CLI's `LoopDetectionService` stops a turn after 5 identical
consecutive tool calls (`TOOL_CALL_LOOP_THRESHOLD = 5`, hash of name and arguments,
packages/core/src/services/loopDetectionService.ts). llama.cpp's own
[function-calling docs](https://github.com/ggml-org/llama.cpp/blob/master/docs/function-calling.md)
list the models "known to work". The smallest native one is granite-4.1-3b, and the others run from
7B to 70B. They give no small-model guidance, beyond warning that extreme KV quantisation "can
substantially degrade the model's tool calling performance".

### The threshold: under 1B parameters is chat only

`SmallModels` (core/model) reads a model's parameters from its size label (GGUF
`general.size_label`, LiteRT's "E2B") and draws the line at 1B. The evidence, read 2026-11-05:

- **Qwen3.5-0.8B** ([model card](https://huggingface.co/Qwen/Qwen3.5-0.8B), "General Agent"): it
  scores **25.3** on BFCL-V4 and **11.6** on TAU2-Bench, where Qwen3.5-2B scores 43.6 and 48.8. The
  card gives its uses as "prototyping, task-specific fine-tuning, and other research or development
  purposes", and says it "is more prone to entering thinking loops compared to other Qwen3.5 models".
- **1.2B to 1.7B** ([LFM2.5-1.2B-Instruct card](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct),
  BFCLv3): LFM2.5-1.2B scores 49.1, Qwen3-1.7B 46.3 and Granite 4.0-1B 52.4. Liquid recommends its
  model "for agentic tasks". Older 1B models score far lower: Llama 3.2 1B 21.4 and Gemma 3 1B 16.6.
  Gemma 3's template has no tools, so it's chat only anyway. Llama 3.2 1B isn't in the catalogue, and
  the loop breaker covers it if someone imports it.

So under 1B a model is **Chat only**, whatever its template says (`GgufModel.callsTools`,
`GgufCatalogEntry.callsTools`, `OnDeviceModel.callsTools`). From 1B up it keeps **Tools** where its
template takes them. A model whose size isn't known keeps its tools, since unknown never means no.
In the recommended list, only Qwen3.5 0.8B changes. It stays as a fast chat model ("Chat only: too
small to use tools reliably") and is no longer a top pick. Both LiteRT models (Gemma 4 E2B and E4B)
are above the line.

`ModelDirectory` reports these models with `tools = false`, so everything that already handles a
"chat only" model applies: the sheet's chip, the send warning, fallbacks and helpers. llama.cpp
requests for them declare no tools (`GgufWire`, `withTools = callsTools`).

### Chat only from the start

`Conversation.chatOnly` (database v18) marks a chat that declares no tools: `ToolCatalog.forChat`
gives it an empty registry, so its requests carry no tool list.

- **How it's set:** `ChatOnlyRule` sets it from the model the chat starts on, when that model is known
  not to call tools (`ModelDirectory.capabilities(...).tools == NO`). That covers an on-device model
  under the threshold, one whose template has none, and a cloud model its provider lists without
  tool calling.
- **While the chat is empty:** `ConversationRepository.setModel` follows the model you pick, both
  ways. The first message sets it once more, from what's known then (`appendUserMessage`). Nothing
  has been sent before that, so no cached prefix is lost.
- **Once a message is sent:** it's fixed like the rest of the tool list. A chat-only chat switched to
  a large model stays without tools, and a tool chat switched to a small model keeps its tools; the
  loop breaker covers that case. A copy of the chat keeps it.
- **System prompt:** unchanged. Chat-only GGUF models already got the same prompt without tools.

In the UI:

- **Model sheet:** a "Chat only" chip marks models known not to call tools. In an empty chat a note
  says that picking one starts the chat without tools. In a chat-only chat that has started, the note
  says it stays that way.
- **Tools menu:** in a chat-only chat it shows "No tools in this chat" and why, with no switches.
  The composer's tools button reads "None in this chat".
- **Home composer:** marks a draft on such a model the same way.

### The loop breaker

`ToolLoopGuard` (core/agent) runs after every step, once each call has its result, and ends the run
with `Outcome.ToolLoopStopped`:

- **Refused steps:** 3 steps in a row in which every call was refused before it ran. That means a chat
  or project limit, Deny, read-only (`POLICY_DENIED`), or a tool the chat doesn't have. It counts steps,
  not calls, so a model that calls three switched-off tools side by side and then answers isn't stopped.
- **Repeated failures:** the same call (tool and arguments) failing with the same error 5 times in a
  row, as Gemini CLI does. Only failures count: polling a job or a helper with the same call is how
  waiting works.
- **What doesn't count:** calls the user declined or the reviewer blocked (the reviewer has its own
  breaker).

The chat shows "Stopped: this model kept calling tools it can't use in this chat (3 times in a row).
Switch to a larger model, or turn tools on in the tools menu." A scheduled or triggered task has nobody
in the chat and no tools menu to reach for (`AgentConfig.unattended`), so its run says the model kept
calling tools the task can't use (or kept failing the same call) and suggests editing the task or picking
a more capable model for it. The task's receipt says "Stopped"
(`TaskOutcome.TOOL_LOOP`), and a helper reports a partial result. Every call has its result, so the
next message continues the chat as usual. The refusals now say "Don't call it again: answer the user
without it, or ask them to turn it on." They are tool results, not the system prompt.

Tests: `ToolLoopGuardTest` (core/agent), `ChatOnlyChatsTest` (core/data), `SmallModelsTest`
(core/model), `GgufCatalogTest.tinyModelsAreChatOnly`, and `ChatOnlyMigrationTest`.

## Not done

- **Structured output** is modelled and checked, but no chat request asks for it yet.
- **Prompt-caching style** is resolved but only informs. A backup on another provider loses the
  cache whatever its style (fallbacks.md).
- **Provider lists are fetched when a model sheet opens.** A run that starts before any sheet has
  opened in this process uses the catalogue and the rules.
- **On-device images:** only the latest user message's images reach the model. The rule says yes
  to vision.
