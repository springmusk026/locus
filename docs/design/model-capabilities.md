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
Reasoning when they're known yes. A model that can't serve this chat stays listed, with a caution
chip "Can't use here: no vision". Hard needs nobody could confirm show in the subtitle ("Vision not
confirmed").

**Helpers.** A helper (sub-agent) always calls tools. If the helper model, or a profile's own
model, is known not to, the helper runs on the chat's model instead (`servesHelper`). A fork
already runs on the chat's model.

The helper model's side jobs don't need tools or images: titles, suggestions and side questions
are short text-only prompts.

## Not done

- **Structured output** is modelled and checked, but no chat request asks for it yet.
- **Prompt-caching style** is resolved but only informs. A backup on another provider loses the
  cache whatever its style (fallbacks.md).
- **Provider lists are fetched when a model sheet opens.** A run that starts before any sheet has
  opened in this process uses the catalogue and the rules.
- **On-device images:** only the latest user message's images reach the model. The rule says yes
  to vision.
