# Cache-safe mid-chat changes

Changing a chat's thinking effort after it has messages used to change the request's top-level config
(Anthropic `output_config.effort`, OpenAI Responses `reasoning.effort`). Both providers render that
value into the prompt, so the next request missed the cache for the whole conversation
([cost](cost.md#effort): "one cache miss, as any mid-chat change"). Both now document a way to change
effort with an item inside the conversation instead, which leaves everything before it unchanged.
Locus uses it where the docs say it works and keeps the old behaviour everywhere else.

## What the docs say (read 2026-11-23)

Official documentation only. Field names are quoted exactly.

### Anthropic: per-message effort (beta)

Source: [Per-message effort](https://platform.claude.com/docs/en/build-with-claude/effort-per-message),
[Effort → Change effort mid-conversation](https://platform.claude.com/docs/en/build-with-claude/effort),
[Thinking → Thinking and prompt caching](https://platform.claude.com/docs/en/build-with-claude/thinking).

- **The beta exists, under the roadmap's name.** Beta header `anthropic-beta: mid-conversation-output-config-2026-07-01`
  (page front matter `betaHeader`, and the SDK constant `MID_CONVERSATION_OUTPUT_CONFIG_2026_07_01`).
  Some third-party proxies' issue trackers name a `per-turn-control-2026-07-01` header; it isn't on any
  Anthropic page, so Locus doesn't send it.
- **Shape.** A message in `messages` with `"role": "system"`, empty `content` and the new level in
  `output_config.effort`; the top-level value stays as it was:
  `{"role": "system", "content": [], "output_config": {"effort": "low"}}`.
- **When it applies.** "Directly after a `user` turn with new input: Claude's reply to that turn uses the
  new level." Anywhere else, "the new level starts at the next `user` turn with new input", and "a `user`
  turn that holds only `tool_result` blocks doesn't count as new input". The level "stays in effect until
  a later message changes it".
- **Placement rule.** "Put the message after everything you've already sent, and keep it in place on
  later requests. Don't insert it before turns you've already sent, and don't move or remove it later."
  On models with preserved thinking, doing so "invalidates the thinking blocks in the `assistant` turns
  after the message". An effort-only message "renders nothing at its position. It can follow any turn, or
  come first, but goes after everything already sent" ([mid-conversation system messages, Limitations](https://platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages#limitations)).
- **Cache.** "A per-message change leaves everything before it unchanged, so the cached prefix still
  matches. A top-level change can invalidate all or part of the cache." The thinking page: "On models
  that support per-message effort, an effort change carried in a `role: "system"` message inside
  `messages` leaves the cached prefix intact", and "setting a parameter explicitly to its default value is
  equivalent to omitting it". The caching guidance for system messages: "Append the system message after
  the breakpoint."
- **Models** (front matter `supportedModels`): `claude-fable-5-2`, `claude-mythos-5-2`, `claude-fable-5-1`,
  `claude-mythos-5-1`, `claude-opus-5-5`, `claude-opus-5`, `claude-sonnet-5-5`, `claude-haiku-5-5`, on the
  Claude API (beta). Not Claude Fable 5, Claude Sonnet 5 or earlier.
- **Errors.** Without the header: 400 `messages.N.output_config: Extra inputs are not permitted`. With it,
  on a model without the feature: 400 `output_config.effort requires a model that supports per-turn effort;
  this model does not`. On Claude Sonnet 5.5 with `thinking: {"type": "between_tools"}` and on Claude
  Haiku 5.5 with `thinking: {"type": "disabled"}`, a new level is a 400. Locus sends neither (Sonnet 5.5
  gets `adaptive`; Haiku 5.5 gets no `thinking` field, which the thinking page's table says means adaptive
  thinking).
- **Thinking.** Effort-only messages work with adaptive thinking ("To vary effort for each turn, use
  adaptive thinking"). Preserved thinking (Fable 5.2, Mythos 5.2, Fable 5.1, Mythos 5.1, Opus 5.5, Sonnet
  5.5, Haiku 5.5) is the reason the message must never move.
- **Not documented:** what happens to an effort message that sits before a server-side `compaction` block.
  The threshold compaction page says "The API drops what came before the block", so Locus assumes the level
  goes back to the top-level one and sets it again after the compaction turn (see below).

### Anthropic: mid-conversation system messages (stable)

Source: [Mid-conversation system messages and tool changes](https://platform.claude.com/docs/en/build-with-claude/mid-conversation-system-messages).

- Stable, no beta header, on Fable 5.2, Mythos 5.2, Fable 5.1, Mythos 5.1, Fable 5, Mythos 5, Opus 5.5,
  Opus 5, Opus 4.8, Sonnet 5.5 and Haiku 5.5 ("not available on Claude Sonnet 5").
- Shape: `{"role": "system", "content": "…"}` (string or `text` blocks) in `messages`. A message with
  content "must immediately follow a `user` turn (or an `assistant` turn ending in a server tool result)"
  and "must then end `messages` or be immediately followed by an `assistant` turn"; never between a
  `tool_use` and its `tool_result`; never first.
- Cache: "The cached prefix stays the same, so the next request still reads it from cache". Priority:
  a `system` message "is treated as coming from you, the application operator".
- Turn-scoped messages (`clear_at: "next_user_message"`) need another beta, `mid-conversation-system-clear-at-2026-08-21`.
- **For user input during a run on Claude Sonnet 5.5 and Claude Haiku 5.5:** "deliver the user's words
  in the user turn instead. These models are trained to resist prompt injection through tool results, so
  they can treat a system message placed right after a tool result as untrusted text and ignore it."
- "Not a place for untrusted content": text from outside the conversation gets operator authority there.

### OpenAI Responses: `configuration_update` input item

Source: [Reasoning models → Change reasoning mid-conversation](https://developers.openai.com/api/docs/guides/reasoning#change-reasoning-mid-conversation),
[Keep the prefix unchanged](https://developers.openai.com/api/docs/guides/keep-prefix-unchanged),
[Responses API reference, `input` → `ConfigurationUpdate`](https://developers.openai.com/api/reference/resources/responses/methods/create).

- **Shape.** An input item `{"type": "configuration_update", "reasoning": {"effort": "high"}}`
  (reference: `ConfigurationUpdate object { type, id, reasoning, text }`; `reasoning: optional object
  { effort }`, "Only effort is supported"; effort one of `none`, `minimal`, `low`, `medium`, `high`,
  `xhigh`, `max`). "Add the update between responses while leaving the request-level `reasoning.effort`
  unchanged. This preserves the original prompt prefix for prompt caching." "Add the following item before
  the next user message in the `input` array." The new level applies "for the next response and subsequent
  responses until another update overrides it".
- **Stateless history.** "Preserve updates with `previous_response_id`, or replay them in their original
  positions when managing conversation history manually." Locus sends `store: false` and replays.
- **Models.** "On supported GPT-6 and later models, you can update reasoning effort and response format in
  standard, single-agent mode." No per-model table; OpenAI's pages don't name the modes that refuse it.
- **Limits.** "Automatic truncation and the standalone `/responses/compact` endpoint still reject histories
  containing updates." Locus uses neither.
- **Keep the prefix unchanged** lists "Changing reasoning effort" first among prefix changes and gives
  `configuration_update` as the fix. For instructions it says to append a new `developer` message rather
  than edit the first one.
- **Not documented:** any of this for the ChatGPT plan route (Sign in with ChatGPT). Locus doesn't send it there.

### OpenAI Chat Completions

The [Chat Completions reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
has `reasoning_effort` at the top level only; there is no per-message configuration item. Nothing changes there.

## What Locus does

Only effort changes, only on the documented models, only on the providers' own APIs (`api.anthropic.com`,
and `api.openai.com` for Responses; gateways speaking either format may not know the fields).
`MidChatEffort` (core/llm) holds the model lists and the rules below.

### What's stored

Each assistant turn on such a model carries a hidden part, `Part.TurnEffort(effort, topLevel)`: the level
the turn was requested at (the chat's effort as the provider spells it; blank means the model's own
default) and the top-level value its request carried. It is in the message's parts JSON, so there's no
database change. It's never shown and never sent as content; other adapters ignore it.

### How a request is built

From those parts alone, so every request of a chat rebuilds every earlier one exactly:

1. **The top-level value** is the `topLevel` of the newest recorded turn. A chat with no recorded turn
   (new, empty, or every turn from before this change) sends the chat's effort, as before.
2. **Per-message changes.** Walking the recorded turns in order, a turn whose `effort` differs from the
   level in effect gets the change right before it (Anthropic: a `system` message after the user turn;
   OpenAI: a `configuration_update` before the run of user messages that led to it, or right before the
   turn when no user message did, as after Continue). A turn recorded under another top-level value (sent
   the old way, see the fallback) resets the level to the top-level one and gets nothing.
3. **A pending change.** When the chat's effort now differs from the level in effect at the end, the
   change goes at the end (Anthropic) or before the trailing user messages (OpenAI). The reply that comes
   back is recorded at the new level, so step 2 puts the change at the same place on every later request.
4. **Anthropic server compaction.** After a turn holding a `compaction` block the level goes back to the
   top-level one (the API drops everything before the block), so the next turn at another level gets the
   change again, after the compaction.

A blank level (the model's own default) is sent per message as the model's documented default
(`Effort.modelDefault`): medium on Opus 5.5, Haiku 5.5 and OpenAI's models, high on other Claude models.

The Anthropic beta header is sent only on requests that carry at least one effort message. The
conversation's cache breakpoint skips trailing `system` messages and stays on the last user block before
them, as the docs say ("Append the system message after the breakpoint").

### Fallback

A 400 that names the feature (Anthropic: the beta name, `messages.N.output_config`, or "per-turn effort";
OpenAI: `configuration_update`) is retried once the old way: top-level set to the chat's effort, no
per-message items. The provider and model are then remembered for the rest of the process
(`MidChatEffort.reject`) and get the old way only, as the Chat Completions adapter does for its cache
fields. Turns answered that way are recorded with `topLevel` equal to their effort, so if the app restarts
and the feature is tried again, the requests start from what was last sent, not from the chat's first
level.

### How the cache is kept

- New chats and chats with nothing sent: no recorded turns, so the request is exactly today's.
- Existing chats: requests don't change until their first change of effort. Their earlier turns have no
  record, so the top-level value is the chat's effort until a turn on a supported model is recorded.
  The first change in a chat whose newest turn predates this release is sent the old way (Locus can't
  know what was sent before); from then on changes are cache-safe.
- After a change: tools, system prompt, top-level config and every earlier message are byte for byte what
  the previous request sent; the only new bytes are after the old cache breakpoint
  (`MidChatEffortTest`, Anthropic and Responses).
- Locus's own compaction (a checkpoint) hides older turns. If it hides every recorded turn, the top-level
  value becomes the chat's current effort on the first request after it, which may differ from before; the
  summary has replaced the conversation's start anyway, so only the tools and system prompt may miss once.

### What the user sees

The effort sheet's note says, for a chat whose model supports this: a change applies from your next
message and keeps the chat's cache. Other models keep the old note (the next reply is a little slower and
costlier, once). A change made while a run is going, or followed by Continue instead of a message, takes
effect at the next message you send; that is when both providers apply it.

### Steering and mode notes stay in the user message

`ModeNote` (mid-task messages, plan mode, the completion check) stays appended to the user's message,
not a mid-conversation system message:

- It's already cache-safe: it rides on a newly appended message.
- For the main case, a message typed while a run works, Anthropic says the opposite for Claude Sonnet 5.5
  and Haiku 5.5: put the user's words in the user turn, because a system message after tool results may be
  ignored as an injection.
- A system message raises the note to operator priority, and the note carries the user's own words.
  Keeping them as user text keeps that boundary.
- OpenAI's equivalent is a `developer` message, so a switch would split one behaviour into two shapes for
  no gain in cache or cost.

Reconsider if a note ever needs to hold against the user (an operator rule), which none does today.

## Follow-up, not built: a low-then-high effort cascade

With per-message effort, a chat could answer each new message at low effort and move to high only when
the task proves hard (a failed command, a long tool loop, the model asking for more time), without the
cache miss that made this impractical. It needs a rule for when to step up and back down, and a way to
show the user which level answered. Not built: the roadmap marks it optional and nothing here needs it.
