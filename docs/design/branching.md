# Message branching and forking

Regenerate a reply or edit a message without losing what was there, flip between the versions, and
start a new chat from any point. Written 2026-10-31 for schema v7.

## How other clients model it

- **RikkaHub** (studied for ideas only; AGPL): a conversation is a list of *nodes*, one per position,
  and each node holds its alternative messages plus a `selectIndex`. The shown chat is "the selected
  message of every node". Regenerating a message drops every node after it, so alternatives exist at
  one position at a time and later turns of the old version are lost. Forking copies the nodes up to
  the chosen message (alternatives included) into a new conversation that keeps the assistant,
  system prompt and settings, and titles it `Title(1)`, `Title(2)`…
- **ChatGPT** (its data export), **Open WebUI** and **LibreChat** store a *tree*: every message has
  a parent (ChatGPT's `mapping` with `parent`/`children` and a `current_node`; Open WebUI's
  `history.messages[id].parentId` with a `currentId`; LibreChat's `parentMessageId`). The chat on
  screen is the path from the root to the current leaf; "‹ 2/3 ›" counts the children of one parent.

The node list is simpler but throws history away, and in Locus one *turn* is many rows (assistant
steps, tool results, more steps), so "a position" isn't one message. A parent-pointer tree fits our
append-only table: a branch is just new rows that point at an older one.

## Data model (Room v7)

- `messages.parentId` — the message this one follows; `NULL` for the first message of a chat.
- `messages.copiedFrom` — set on rows copied into a fork (the source row's id), so Insights doesn't
  count the same requests twice.
- `conversations.activeLeafId` — the last message of the branch that's shown and continued; `NULL`
  means "the newest message", which is exactly a linear chat.

Rows are still never updated or deleted. Regenerating, editing and forking only insert rows and
move `activeLeafId`.

The path logic is pure Kotlin in `core/model` (`MessageTree`): the leaf is `activeLeafId` if it
belongs to the chat, else the newest row; the path walks parents from the leaf to the root (a parent
that doesn't exist ends the walk, a cycle can't loop). Siblings are rows with the same parent, oldest
first, and the switcher shows `index/count` among them. Switching to a sibling moves the leaf to the
newest row in that sibling's subtree, so you come back to where you last were in that branch.

## What the model sees

`ConversationRepository.history()` and `observeMessages()` return the **active path only**, as an
ordinary linear list. Everything built on them is unchanged and branch-blind: `AgentLoop`
(`ChatRequest(conversation.systemPrompt, path, tools.specs)`), compaction and `modelContext`
(the latest `Part.Checkpoint` *on this path*), reasoning stripping (`forModel`), Recovery,
token-based compaction triggers (the last `usage` on the path), `rawPayload` replay (Anthropic
thinking signatures, Responses encrypted reasoning: every replayed turn is one that really followed
its predecessors), the safety reviewer's trusted user messages, sub-agents started from the chat,
and `chat_history_*`.

The system prompt and tool list are frozen per conversation and branches don't touch them, so a
new branch shares the cached prefix up to the point where it diverges. `TranscriptStore.append`
attaches each new row to the current leaf, inside a Room transaction that also moves the leaf.

## Operations

- **Regenerate** a reply: the leaf moves back to the user message (or checkpoint) that started the
  turn and the agent runs again; its first step becomes a sibling of the old turn's first step. If
  the run produces nothing (no key, error before the first reply), the old leaf comes back.
- **Edit** a user message: the new text (with the original's attachments) is inserted as a sibling
  of the original, under the same parent, and the agent runs from there. Edits skip automatic
  compaction; it happens on the next normal send.
- **Switch**: `‹ i/n ›` under a reply (alternatives of that turn) or under a user bubble
  (alternatives of that message). Disabled while the chat is running. After a switch, a branch that
  was cut off mid-call gets the usual "not run" results appended (as Recovery does at startup) and a
  branch whose last turn never finished offers Continue.
- **Fork into a new chat**, from the long-press menu: on a reply, the new chat holds the path up to
  and including that reply; on a user message, it holds everything before it and the message text
  is put in the composer to send or change. The fork keeps the source's frozen system prompt,
  provider, model, effort, plan mode and tool switches, so the copied `rawPayload` stays valid.
  Copies get new ids, keep their timestamps and token usage (so the context ring is right), and
  carry `copiedFrom`; Insights skips them. Tool call ids are kept, so a copied `agent_spawn` still
  opens the original helper's transcript. Audit entries stay with the chat where the call ran.

### "Edit as new message"

Folded into Edit. Edit puts the text in the composer with an "Editing" banner; sending creates the
branch. Closing the banner keeps the text, and sending then appends it at the end — the old
"edit as new message" behaviour — so nothing is lost and the menu stays short.

## Migration 6 → 7

An `AutoMigration` adds the three nullable columns. Its spec's `onPostMigrate` fills `parentId` for
existing rows with the previous row of the same chat by `seq`:

```sql
UPDATE messages SET parentId = (SELECT p.id FROM messages AS p
  WHERE p.conversationId = messages.conversationId AND p.seq < messages.seq ORDER BY p.seq DESC LIMIT 1)
```

`activeLeafId` stays `NULL` (the newest row), so every existing chat — helper chats included — is
one branch, shown and sent exactly as before. No row is changed apart from the new column.

## What counts where

| | Active path | Every branch |
|---|---|---|
| Model requests, compaction, recovery, chat_history tools, reviewer | ✓ | |
| Chat transcript, context ring, todo card | ✓ | |
| Insights tokens and requests (spent is spent; fork copies excluded) | | ✓ |
| Home search (finds a chat if any branch matches) | | ✓ |
| Activity / audit log (per chat and call) | | ✓ |

## Not done

- No tree view or "delete branch"; branches are reached through the switchers.
- A reply's switcher only appears where branches diverge; deeply nested branches are reached one
  level at a time.
