# Compare models

Send one message to two models and keep the better answer. Written 2026-11-21; idea #12 of
[feature opportunities, November 2026](../research/feature-opportunities-2026-11.md). No database change.

## What others do

- **Open WebUI** ([multi-model chats](https://docs.openwebui.com/features/chat-conversations/chat-features/multi-model-chats)):
  pick several models in the chat header and every message goes to all of them at once; the answers sit side by
  side, and you can merge them or carry on with one. Its arena mode hides the names and records which answer you
  preferred for a leaderboard (read through its docs and search summaries only).
- **RikkaHub** asks for the same ([#332](https://github.com/rikkahub/rikkahub/issues/332),
  [#1582](https://github.com/rikkahub/rikkahub/issues/1582): "several models at once", "compare answers"); not built there.

What fits Locus: an agent's answer is a whole turn (tool calls, files, messages), not one reply, and its chats
keep their prompt cache by never changing the frozen system prompt and tool list. So Locus compares **two** models,
one message at a time, as two ordinary branches, and keeps the second model from doing anything twice.

## Behaviour

1. **Pick.** The composer's + sheet has **Compare models**. It opens the model sheet in compare mode: the same
   search, favourites, recents and capability chips, but the chat's own model is marked "This chat's model" and
   can't be picked, and models known not to serve the chat can't either (no tool calling when the chat declares
   tools, no vision when the context or the message has images, a window smaller than the context so far; unknown
   never disqualifies, as everywhere: [model capabilities](model-capabilities.md)). The pick shows in the composer
   bar as "vs gpt-5.2"; tapping it reopens the sheet, which then offers **Stop comparing**. Home doesn't offer it:
   a new chat has nothing to compare on, and its first message settles its tool list.
2. **Estimate.** Sending with a second model picked first estimates both requests' input cost (`ModelCompare.estimate`,
   core/model): the context so far (the last request's prompt and reply) plus the new message.
   - The chat's model is priced as fresh input: an upper bound, since its prompt cache usually makes it cheaper.
   - The second model is priced as a cache write of the whole prompt (models.dev's write price, else input): its
     provider has never seen this chat, and caches are per model, so that's what its first request costs.
   - A model without a price (catalogue off, or not listed) shows "unknown". Only the first request's input is
     counted; the sheet says replies and tool steps cost more.

   The confirm sheet shows the first time Compare is used, and afterwards only when the known estimate is over
   $0.05 (`CONFIRM_ABOVE_USD`). An unknown price doesn't ask again: the first confirmation already said prices may be
   unknown. Cancel puts the message back in the composer; the second model stays picked.
3. **Send.** `AgentRunner.sendCompare` stores the user message once and runs two turns from it in **one task**:
   - **The second model first, read-only.** Its turn is an ordinary turn with that provider and model
     (`run(compare = …)`): `chatProvider(base, conversation.copy(modelOverride = …))`, the chat's effort mapped to its
     format, no backup models, no steering (messages typed meanwhile wait for the chat's own turn), no completion
     nudge, no title, follow-ups or reflection afterwards.
   - **Then the chat's own model**, from the same user message (`setActiveLeaf` back to it), exactly as a normal send:
     same request, same permissions, backups and steering. Its first step becomes the sibling of the second model's.
   - The second model failing (no key, quota, an error) is a notice, "gpt-5.2 couldn't answer the comparison: …",
     and the chat's model still answers. Stop ends both.
   - One send uses the pick up: the next message goes to the chat's model alone.
   - Not while the chat runs: a typed message then joins the running task as usual and the pick waits.
4. **Read.** Both answers are siblings under the message: the usual ‹ 1/2 › switcher (the second model's is 1/2,
   the chat's own 2/2 and shown when both are done). Wherever a turn has alternatives, each answer carries a compact
   label with its model; the second model's says "· read-only". While the second model writes, "Second answer:
   gpt-5.2 (OpenAI) · read-only" sits above the streaming text. Side-by-side columns weren't built: on a phone the
   switcher is the layout that fits, and a turn is more than one bubble.
5. **Keep.** Under each answer of a Compare turn, **Keep this one**. Switching branches already makes the shown answer
   the chat's continuation (the next message follows it); Keep records which one you preferred, swaps the button for
   "Kept", and counts it in the tally. Keeping the other one later moves the count; the other answer stays one
   switch away like any branch.

## Side effects: Compare is for answers only

Both turns could call tools, and two agents acting on one request would do everything twice (two emails, two
commits, two deploys). The rule:

- **The second model only reads.** `ChatPolicy.compareDenial` refuses every call that isn't an observation
  (`Tool.isObservation`, the same classification Read-only mode and scheduled runs use), with a reason the model can
  act on ("this answer is a comparison… answer from what you can read, and say what you would do"). Writing files,
  commands, mail and messages, browser clicks, helpers, memory writes: all refused. Its calls also run with the
  read-only context (`chatReadOnly`), so a page it opens can't download into the workspace. Like Read-only mode,
  it's refused at run time, so the request declares the chat's tools as always. The chat's own mode, plan mode,
  switched-off tools and project limits still apply on top.
- **The chat's model acts normally**, with the chat's permissions.
- **The second model goes first**, so it reads the workspace as it was before the message, not after the chat's
  model changed it, and its reads can't be confused by the other answer's writes.

The UI says it where the choice is made (the sheet's first line, the confirm sheet): "Compare doesn't let the
second model change anything." What it can still touch: observations that open an address send that address (as in
Read-only mode; taint rules ask before a new host once private data was read), and the chat's browser tabs it opens
stay open for the chat's model.

## Prompt cache and requests

- **The chat's model's request is byte-identical to a normal send.** It runs from the same leaf with the same frozen
  system prompt, tool list and path; the second model's rows are on another branch, so the chat's prefix and its
  cache entry (keyed per chat, written per model) are untouched. Tested in `CompareModelsTest`.
- **The second model** gets the same system prompt and the same Locus tool list, translated by its own adapter like
  any fallback model's: the tools are Locus's, not the provider's, so every format can declare them. Older assistant
  turns replay their `rawPayload` only to the same format (`Message.rawPayload`), so a different format rebuilds them
  from parts, exactly as after switching the chat's model or falling back. Caches are scoped to the model, so its
  first request pays a full prefix write, which the estimate prices as such.
- A comparison never changes the chat's own model or its settings.

## Budgets and receipts

Both turns run inside one task, so the task budget counts both (each request at its own model's price; a model
without a price adds tokens but no cost). The receipt counts every reply written during the task on any branch
(`TaskReceipts.build(otherBranches = …)`, from `ReceiptDao.observeReplyUsage`), so it lists both models, their tokens
and their cost. Insights already counted every branch.

## Your picks

`AppSettings.modelPicks` holds one `ModelPickTally` per pair of models (unordered: the pair is sorted, whichever was
the chat's model), with how often each answer was kept. `AppSettings.compareTurns` remembers the last 50 comparisons
(chat, message, both models, which one was kept) so "Keep this one" knows a Compare turn and can move a pick.
Insights shows **Your picks** as a small table (each pair: both models, each with how often its answer was kept), most compared pairs first,
whatever the period.

It lives in the settings DataStore on the phone: never in a request, never uploaded, and in encrypted backups
because settings are ([backup](backup.md)). It isn't sealed: it grants the agent nothing.

## Not done

- More than two models at once, and an anonymous arena.
- Side-by-side columns on wide screens.
- Comparing on home (a new chat).
- Merging the two answers into one.
