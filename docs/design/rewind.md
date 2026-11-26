# Rewind here and Summarise from here

Status: built 2026-11-21. *Not yet tried on the phone or against a live key.* Idea #4 of the
[feature research](../research/feature-opportunities-2026-11.md); closes the roadmap's "Compaction: editable
summary and visible progress". Code: `Rewind` and `Compaction.from`/`fromPrompt`/`checkpointFrom`/`edited`
(core/agent), `ColdClearing` (pre-cleared results, checkpoints in the gap rule), `ChangeRepository.rewindPlan`/`rewind`
(core/data), `AgentRunner.previewRewind`/`rewind`/`summariseFrom`/`useSummary`/`editSummary`, `RewindUi.kt` and the
message menus (feature/chat).

## What others do

- **Claude Code** ([checkpointing](https://code.claude.com/docs/en/checkpointing)): Esc twice or `/rewind` lists the
  prompts; for one of them you restore the code, the conversation, or both, or "summarize from here" (the messages
  from that point on become a summary, the ones before stay) or "summarize up to here". Only the file-editing
  tools' changes are tracked: "Bash command changes not tracked", nor external changes.
- **Cline** ([checkpoints](https://docs.cline.bot/features/checkpoints)): a shadow git repository snapshots the
  workspace after each tool use; per message you can "Restore Files", "Restore Task Only" or "Restore Files & Task".
  Restoring the task deletes the later messages.
- **Gemini CLI** ([checkpointing](https://geminicli.com/docs/cli/checkpointing/)): a shadow git snapshot before a
  tool changes files; `/restore` brings back the files and the conversation as they were then.

Locus already has the pieces: per-turn change tracking with undo (change-tracking.md), branches
(branching.md) and checkpoints (compaction.md). Both actions are built from them; nothing is deleted.

## Rewind here

Long-press a message the user sent, or a reply's ⋯ menu → **Rewind here**.

**Where it cuts** (`Rewind.plan`). On a message the user sent: back to just before it, and its text goes back into
the composer (Claude Code does the same), so sending it again or a changed version starts the new branch. On a
reply: back to the end of that reply's turn; the turn and its file changes stay, everything after goes. Not offered
on the chat's first message (nothing comes before it: Edit already makes a sibling of it) or on a reply in the last
turn (nothing after it); the menu entry stays, greyed, with the reason.

**Files.** The later turns are the change-tracking turn ids after the cut (the message after each message that
starts a turn, as `AgentLoop.turnOwner` keys them). For every path those turns changed and that wasn't undone
already, `ChangeRepository.rewindPlan` says what happens:

- put back as it was before the oldest of them: the rows are undone newest first through change tracking's own
  undo, so a move is undone before the delete that preceded it, what's there now goes to the workspace trash first,
  and readers must read the file again (`FileReads.invalidate`);
- **left as it is** when it changed since the newest of those turns left it (SHA-256 against what the agent left,
  as Undo checks): you, a command or another app changed it, and a rewind never overwrites that. This is stricter
  than Undo, which warns and goes ahead: a rewind touches many files at once, and you can still Undo one by hand;
- **can't be put back** when an earlier version is no longer in the trash (or, for a command's change, in its
  snapshot).

In a project with "Snapshot before shell commands" on ([shell-snapshots.md](shell-snapshots.md)), the files the later
turns' `run_command` calls changed are tracked changes like any other: they go back too, newest first, through the
same undo and the same changed-since rule (a file changed after the command is left as it is). The sheet then says
"3 shell commands ran after this: the project files they changed go back with the others above; anything else they
did (packages, files outside the project) stays", instead of "their effects stay". A command that wasn't snapshotted
(over the caps, the setting off) is still only an effect that stays.

The check runs again under the recorder's lock when you confirm, so a change made while the sheet was open is
never overwritten either.

**Conversation.** The chat's leaf moves to the cut (`setActiveLeaf`); the old continuation stays in the tree.
Until you send something, a row under the transcript says "The later messages are on another branch · Show
them" (`ChatThread.continuation`: the shown branch's last message has children). Once you send, the new message
is a sibling of the old one and the usual ‹ 1/2 › switcher appears. A cut that lands mid-call gets the missing
"not run" results, as a branch switch does.

**What can't be rewound.** `Rewind.effects` lists every call after the cut that ran (its result's decision says
it wasn't denied) and isn't a tracked file tool or a read: shell commands (EXECUTE: run_command, Linux, SSH),
calls outside the phone (NETWORK: connectors, mail, deploys, MCP tools, a `fetch_url` that isn't a GET),
helpers (`agent_spawn`, `agent_send`: their file edits are in the parent's turn, so they are put back; anything
else stays), and other changes (WRITE tools that aren't workspace files: memory, browser, phone, `git_commit`;
calls run anyway). The sheet says so per kind ("3 shell commands ran after this; their effects stay.") with the
first few commands by name.

A `call_tool` is classified by the tool it names (`DiscoveryTools.unwrap`, as the task receipt does), and listed under that tool's name: a discovered `ssh_exec` is a command, `git_commit` another change, and an MCP server's tool (`mcp__<server>__<tool>`) outside the phone. A web search, the weather, a mailbox or a page read, which reach a service but change nothing there, are left out, whether called by name or through `call_tool`. A `call_tool` that named no tool ran nothing and isn't listed.

**The agent is told** the way Undo turn tells it (`noteUndo`): a `<locus_mode>` note (`ModeNote.rewound`) rides on
the user's next message, or joins at the next step boundary. It says the chat was rewound, that what came after is
on a branch it no longer sees, which files were put back (read them again), which were left as they are, and the
actions that ran and stay, with their commands. Undo notes and a rewind note pending at once go in one note.

**Confirm sheet.** Title "Rewind to here?", what happens to the chat, the files that go back (icon, name,
"Changed after this point: goes back" / "Made after this point: moves to the trash" / "Deleted after this point:
comes back", folder), a caution banner and list for files changed since, the ones that can't be put back, the
actions that stay, and that the agent will be told. The action is critical-toned when files change.

## Summarise from here

Long-press a message the user sent, or a reply's ⋯ menu → **Summarise from here**. On a message the user sent the
summarised part starts with it; on a reply it starts after that reply's turn, so the reply stays.

### The shape: keep the head, summarise the tail

Until now a checkpoint replaced everything before it (`Compaction.modelContext` took the latest checkpoint's view
and what came after). A summary from here must leave the messages before the point exactly as they were sent,
which is the whole point: the provider's cached prefix stays.

- `Part.Checkpoint.from` (new, JSON field, no Room change): the id of the first message of the model's context this
  summary stands in for. `modelContext` is now a fold over the stored path: each checkpoint replaces the context
  from `from` (or `supersedes`, below) to its end with its view, or everything when it has neither. For every
  existing checkpoint the result is the same as before.
- The checkpoint is **appended at the end** of the branch, like every checkpoint: storage stays append-only, and
  a fold of the stored path rebuilds the same context after a restart.
- The view starts "[Summary of the rest of this chat from here on, which you no longer see in full; everything
  before this point is kept as it was]", then the summary, the user's own messages from the summarised part word
  for word (as compaction keeps them), the checklist (the part's last `todo_write`, else the one kept before), and
  the pointer to `chat_history_read`.
- It is offered only on messages still in the model's context (one an earlier summary already covers is greyed:
  "Already part of an earlier summary"), with at least two messages from the point on.

**The request.** `Compaction.request` as always: the chat's frozen system prompt, its tools, the whole current
context (so it reads the cache), then one question: the compaction prompt from Settings, a scope paragraph that
quotes the first message of the part ("summarise only the part from the message quoted below to the end; everything
before stays word for word"), and the user's optional instructions in `<user_instructions>` ("keep the API
decisions, drop the debugging"). Model-facing text is English. Usage is recorded as compaction.

**Review and edit before it takes effect.** Nothing is stored when the summary comes back. It becomes a
`SummaryDraft` in the chat's run state and opens in the summary editor ("Review the summary": it stands in for
N messages; edit anything; Use summary / Discard). Closing the sheet keeps the draft as a card at the end of the
chat ("Summary ready to review" · Discard / Review). Using it appends the checkpoint with the edited text; if the
chat moved on meanwhile (its last message changed) it isn't used and the chat says so. A new turn drops the draft.

### Prefix byte-identity per provider

What the next request sends is `[messages before the point] + [view] + [new messages]`. The messages before the
point are the same objects, in the same order, as the request before the summary sent them, so every adapter
encodes them the same way. Per format:

- **Anthropic Messages** (preserved thinking: on current Claude models thinking blocks of earlier turns stay in
  context, and an edit to anything before a thinking block invalidates it). The kept turns are replayed from their
  stored `rawPayload`, thinking blocks and signatures included, after exactly the prefix they were produced with,
  so they stay valid. No thinking block from the summarised part is replayed: those turns are gone from the context,
  and the summary is a plain user message. This is "keep the head, summarise the tail", the simple-compaction shape
  with kept earlier turns; the problematic shape (keep the tail and rewrite what's before it) never happens. The
  view merges into one user message with the user's next message (the adapter merges consecutive user turns), as
  after "Summarise now". Cache: entries were written at the end of earlier requests; the lookback from the new
  request's breakpoint reaches the one written just before the kept reply, so everything before the point is a hit.
- **Server-side compaction** (Anthropic models with `compact_20260112`). A `compaction` block in the kept part is
  replayed as before (the API keeps applying it). A summary of a part that **holds** one would drop it, and the API
  would then see the turns it had folded away again, raw, in front of our summary: valid, but not what the user
  asked for, and a cache miss. So Summarise from here is greyed on messages before a server compaction ("The
  provider already compacted this part on its server"), and offered on messages after it. Nothing else changes for
  those models: Locus still doesn't compact them at a threshold itself.
- **OpenAI Responses and Sign in with ChatGPT**: kept turns replay their encrypted reasoning items as stored, after
  the same input; nothing after the point is replayed. Same rule as compaction.
- **Chat Completions** (OpenAI, OpenRouter, Gemini, DeepSeek, Groq, local servers): no replayed reasoning; Gemini's
  thought signatures belong to the turn's own calls, which are kept or dropped with their turn.
- **On-device**: the KV cache is reused while the transcript continues it (`TranscriptPrefix`); the kept part is
  that prefix.

`RewindSummariseTest` builds a chat, summarises it from a point, and serialises the request before and after with
the real Anthropic, Responses and Chat Completions request builders (`wireBody`): the messages before the point are
identical JSON in both (Anthropic's moving `cache_control` marker aside), no thinking block of the tail is in the
later request, and the tail is replaced by the view.

### Cold-cache clearing

Clearing (cold-cache-clearing.md) decides what an old tool result looks like from the messages around it. Two
changes keep it consistent with a kept prefix:

1. **A partial summary's view counts like a reply for the gap rule and is never a boundary itself.** Its summary
   request read the cache, kept part included, when it was written. Otherwise the view, written days after the
   kept part's last reply, would count as a cold boundary and clear the kept part's results, changing the prefix.
   Ordinary checkpoints behave as before: their view starts the context, so nothing before it could be cleared.
2. **`Checkpoint.clearedResults`.** Boundaries inside the summarised part may have cleared results of the kept part
   in what was last sent (a boundary clears results before it). Those boundaries are gone after the summary, so the
   checkpoint records the call ids that the view of the whole context cleared before the point
   (`ColdClearing.clearedBefore`), and the view clears them from the start. Boundaries inside the kept part decide
   exactly as before (they only see messages before them), so the kept part is cleared exactly as last sent.
   Clearing never applies on Anthropic, Responses or ChatGPT, where the field stays empty.

### Branches

The checkpoint lives on the branch it was written on. Switching to another branch shows that branch's own fold.
Rewinding to a point before a summary leaves the summary on the old branch; the context is then what it was at
that point.

### Editing a checkpoint (the roadmap's "editable summary")

A checkpoint the model hasn't read yet (it's the chat's last message, nothing after it: "Summarise now", a
summary just used, or an automatic one when the run stopped right after it) has **Edit summary** on its card. The
edited text is stored as a new checkpoint appended after it with the same fields and `supersedes` = the old one's
id; the fold replaces the old view with the new one. The old checkpoint stays stored; the chat shows the corrected
card ("You edited this summary.") once. Once the model has answered after a summary, editing it would rewrite what
the later turns were based on, so it isn't offered; Rewind or Summarise from here does that job.

### Visible progress

Already there before this change: the progress line while any summary is written ("Summarising earlier
messages", the in-task "Context full, summarising…"), the notice after ("Summarised 12 earlier messages to free up
context.") and the checkpoint card. Added: "Summarising from the chosen message" while a summary from here is
written, the review card, "Summary saved. The model reads it from its next reply." and "Couldn't write the summary:
…" on failure. What's left: a percentage while the summary streams, which providers don't report.

## Where they're offered

| | Rewind here | Summarise from here |
|---|---|---|
| A message the user sent | yes, not the chat's first | yes, while it's in the model's context and two or more messages follow from it |
| A reply | yes, unless it's in the last turn | yes, unless nothing starts a turn after it |
| A checkpoint card | no (Edit summary while unread) | no |
| While the agent works, or a summary is being written | no: "Not while the agent works" | no |
| Anthropic, part holds a server compaction | yes | no, explained |
| Temporary chats | yes: branches and change tracking work there | yes |
| Helpers' transcripts | no menus there | no |

## Not done

- Changes made outside the file tools aren't rewound (commands, Linux, SSH, connectors, sent messages); they're
  listed instead. Snapshotting the workspace before every command (Gemini CLI, Cline) is a bigger change.
- Rewind restores only the turns change tracking still keeps (the latest 30 turns with changes per chat).
- "Summarise up to here" (Claude Code's other direction) would rewrite the prefix, so it is a cache miss by
  design and on Anthropic the shape that invalidates kept thinking; not offered.
- A pending note lives in memory, as Undo's: after a restart before the next message, the agent isn't told (it
  still has to read files again).
