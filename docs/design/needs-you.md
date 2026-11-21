# Needs you

Status: built 2026-11-21. Not yet tried on the phone. Idea #5 of the [November feature research](../research/feature-opportunities-2026-11.md) ("Needs you" feed with drafts to approve), and the roadmap line "Scheduled tasks: let a run propose a write as an approval card".

Code:
- Policy: `Proposals.kt` and `AgentLoop` (`AgentConfig.proposals`, `propose`, `runApproved`) in core/agent; `ApprovalDecision.PROPOSED` and `ModeNote.proposalApproved` in core/model.
- Storage: `ProposalEntity`, `ProposalDao`, `CLAIM_PROPOSAL`, `RETIRE_RESTORED_PROPOSALS`, `scheduled_tasks.lastSeenRunAt` and `NeedsYouMigration` (core/database, v20).
- Engine: `core/data/.../needsyou/` (`Proposal`, `ProposalRepository`, `NeedsYou`, `NeedsYouFeed`, `NeedsYouNotifier`), `AgentRunner.approveProposal`, `ScheduledRun` (the task's origin), `TaskWorker` (the notification), `RestoredProposals` (backup).
- UI: `NeedsYouUi.kt` and the home screen (`ConversationsScreen`, `ConversationsViewModel`) in feature/chat; `ApprovalCard` (core/ui) gained a title, a byline, its button labels and a read-only form.

## What the user gets

One section on home, under the launcher row, that answers "what needs me?". It appears only when something does, and the status line under Locus's name counts it ("3 things need you", in the caution tone). Three kinds of item, each list newest first:

1. **Waiting now.** Approvals, `ask_user` questions and budget pauses from every chat, including a helper's (they land in the parent chat). An approval is the same card the chat shows and is answered in place, "Don't ask again" included; answering it on home is answering it in the chat. A question or a budget pause is a row that opens its chat, where its own card is. While anything waits here, the featured card above the launcher shows the chat that is working instead, so the same approval isn't shown twice.
2. **Proposals.** What scheduled and event-triggered runs wanted to send or write (below). Each is an approval card titled "Morning inbox proposes", with the tool's line, why it asks, its field rows (an event's guests in caution) and Details with the exact arguments; the byline says when it was proposed and when it expires. **Approve** and **Decline**. Three show, then "Show N more".
3. **Results to read.** The latest result of each scheduled or triggered task that the user hasn't opened since it arrived: the task's title, the first line of its answer, and its receipt's one line ("Done · 42s · 2 files · ≈$0.01"). Tapping opens the run's chat and marks it read; the check button marks it read without opening it; "Mark all read" sits on the section's label. While there are unread results, the old "latest result" card isn't shown, since it would say the same thing.

Approving shows one line in Locus's feedback channel: done, ran but failed, stopped by Locus's checks, expired, already answered, or "its chat is working, try again when it's done".

## Proposals

### The policy change

Scheduled and triggered runs are read-only: anything that isn't an observation is refused, and so is anything that would ask (docs/design/scheduled-runs.md). Now, in such a run (`AgentConfig.readOnly` and `unattended`, the run's own agent at depth 0, with a `ProposalSink`), a call to a **proposable tool** that would otherwise be refused goes through the checks a chat would apply instead, and where those would run it or ask, it's recorded:

- the chat's and project's limits (`restrict`) still refuse first;
- the user's Deny still refuses ("turned off" is never proposed);
- taint, private-data disclosure, a loaded skill's manifest and a workflow's escalation no longer refuse: they become the card's reason and flags (`ApprovalFlag`), exactly as they would on a live card, and the taint sources are stored;
- the tool's own precheck still refuses (a write over a file the run never read);
- the run may propose at most 5 calls (`Proposals.MAX_PER_RUN`); at most 50 wait at once (`MAX_PENDING`); the same call proposed again while one waits isn't recorded twice.

The model reads a normal, non-error result: "Not run yet: this is an unattended run, so Locus recorded the call as a proposal for the user (…). Don't call it again. In your final answer, say what you proposed." The audit log records `PROPOSED`, which Activity shows as "Proposed".

**Prompt caches.** Nothing in any chat's system prompt or tool list changes, and no tool was added: the run learns about proposals from the call's result. The scheduled run's own first message (written fresh for each run) now says sending an email, adding an event or writing a file is recorded as a proposal, where it used to say the run can't write or send anything. Interactive chats, the Read-only chat mode and the Telegram remote chat are unchanged: they aren't unattended, so they never propose.

Helpers that a scheduled run starts still refuse: only the run's own agent proposes, so a helper can't add to the user's list on its own.

### Which tools may be proposed

`Proposals.TOOLS`, deliberately short. Each writes or sends one thing the user can read in full on the card, and none spends money or drives a screen.

| Tool | Why it's on the list |
|---|---|
| `email_send` | A mail from the agent's own mailbox. It always asks in a chat too; the card shows the recipients, subject and body. |
| `calendar_create` | A new event in the one calendar the user lets Locus change; guests show in caution. Repeating events are created as a whole series, as in a chat. |
| `write_file` | A file in the workspace, a draft the user can open. Never a connected folder (the user's own files outside Locus, `Tool.asksFirst`): those are refused as before. |

Not on it, and why:

- **Payments, the browser and anything that drives a page**: a proposal is approved later, against a page that has changed or gone; payment-like clicks always confirm on the spot.
- **Shell commands, SSH, deploys, deletes**: their effects can't be shown field by field on a card, and they run against a machine whose state has moved on.
- **Edits to existing files** (`edit_file`, `multi_edit`): Locus forgets after a restart which files a chat has read, so an edit approved the next day would be refused. A run can propose a new file instead.
- **Memory and skills**: they have their own approval flows (docs/design/memory-approval.md).
- **Phone hand-offs** (SMS, dial, share sheets): they need the user's screen anyway.
- **`notify_me`**: not needed. It only reaches the user's own channels, so background runs may already use it.

### Approving

`AgentRunner.approveProposal`:

1. **Once, whatever happens.** The proposal is claimed with one statement, `UPDATE … SET status = 'RUNNING' WHERE id = ? AND status = 'PENDING' AND expiresAt > now` (`CLAIM_PROPOSAL`). Of two taps, a tap and a retry, or a tap racing the expiry, exactly one gets past it; the others are told it was already answered or expired. A proposal a process was running when it died stays RUNNING; at the next start it becomes INTERRUPTED ("Locus closed while it ran: check if it happened") and is never run again. At most once, never twice.
2. **Not in the middle of a turn.** It holds the chat's slot like a rewind does; if the chat is working, the user is asked to try again when it's done.
3. **Through the normal path, with today's settings.** A one-call `AgentLoop` (`runApproved`) with the chat's tools, the user's policy now, the chat's and project's limits now, the chat's workflow, trusted hosts and skill manifests, and taint from the chat's history plus the stored sources. The call always asks (so the result says "the user approved this call", never "allowed by a rule"), and the user's tap stands in for the gate only for **that** call: if the checks now ask for something the card didn't show (a new flag such as "the task read your private data"), it isn't run and the chat is told to ask again in a chat. A tool the user turned off since, plan mode switched on, or a precheck that now fails stops it too.
4. **Nothing is remembered.** The loop has no `ApprovalMemory`, so no "Allow for this chat" or "Always" can come out of a proposal, and the card offers none.
5. **Secrets never reach the model**: the tool resolves them itself, as in a chat, and the result is what the tool returns.
6. **The agent is told.** The outcome is appended to the run's chat as a Locus note (`ModeNote.proposalApproved` plus a result part): "The user approved your proposed email_send call (call-1) in Needs you, and Locus ran it … Its result: …", or why it didn't run. It doesn't start a turn; the agent reads it the next time the chat continues, and the chat shows "Approved in Needs you: …" as a line of the run's turn. The audit log records the call as the user's approval.

**Decline** marks it declined; it never runs and the chat isn't changed. **Expired** proposals (7 days, `Proposals.EXPIRY_MS`) stay on the list as read-only cards ("Expired 2 days ago · it never ran") with Dismiss; the claim refuses them, so even a tap in flight can't run one. Failed, interrupted and restored ones are rows with Dismiss; tapping opens the chat. Done and declined ones leave the list.

### Storage

**Room schema change: v19 → v20** (`AutoMigration` with `NeedsYouMigration`):

- a new table `proposals` (id, chat, task, the task's title, call id, tool, arguments, summary, reason, flags, taint sources, preview rows as JSON, created, expires, status, decided, outcome, dismissed), indexed by chat and status, deleted with its chat (`ON DELETE CASCADE`); deleting the task keeps what it proposed;
- `scheduled_tasks.lastSeenRunAt`, the unread mark for results. The migration sets it to each task's `lastRunAt`, so updating doesn't present every old result as new.

`NeedsYouMigrationTest` runs the generated SQL on the v19 schema with the system sqlite3 (as the other migration tests do): existing tasks keep everything and count as read, the shape matches a fresh v20 database, proposals go with their chat, the claim runs once and never after expiry, and the restore statement; the path from v18 is covered too.

Unread results reuse what the task already records (`lastRunAt`, `lastConversationId`, `lastSummary`, `lastOutcome`) and the run's receipt; only the "seen" mark is new.

### Backups

The database is in every Locus backup, so proposals are too. A backup is a snapshot: a proposal waiting in it may have been approved and run after the backup was made, and restoring it as waiting would let the same email go out twice. So when a restore is confirmed, before the swap, `RestoredProposals` sets every PENDING or RUNNING proposal in the **staged** database to STALE (`RETIRE_RESTORED_PROPOSALS`, with the same bundled SQLite Room uses). After the restore they show as "From a restored backup: it won't run", with Dismiss. A backup from before v20 has no such table and is left byte for byte. Expiry is an absolute time, so an old backup's proposals would be expired anyway; STALE covers the recent ones. (`RestoredProposalsTest`.)

## App lock

Exactly as the chat list ([app lock](app-lock.md)):

- With Lock Locus on, home (and so Needs you) shows only after the unlock.
- An item from a locked chat this session hasn't unlocked keeps its place but shows only "Locked chat" with a padlock and its state ("Waiting for you", "Proposed an action", "New result"): no title, no summary, no card, no arguments. Tapping opens the chat, whose gate asks. The task's title of a locked run's result is hidden too.
- The feed waits until the lock's settings are read, so nothing shows before it can be hidden.

## The notification

One notification for all waiting proposals, never one per item (`NeedsYouNotifier`): "2 things need you", and up to three lines "Morning inbox: Send email to …", then "and N more".

- It alerts when a scheduled or triggered run has just proposed something (`TaskWorker` calls it after each run). Approving, declining or dismissing only updates it if it's still showing (or removes it when nothing waits), so a notification the user swiped away doesn't come back for a decline.
- **A locked chat's proposal is counted but never described**, lock on or off; if every waiting proposal is from a locked chat, the text is "Open Locus to see what's waiting".
- With Lock Locus on it is `VISIBILITY_PRIVATE` with the public version "Locus · Unlock your phone to see details" (`LockedNotifications.privateWhenLocked`).
- It has **no actions**: a tap opens Locus, which shows its own lock first. Approving happens on the card in Locus, never from the shade, Telegram or voice, so there is no action to guard with `setAuthenticationRequired` on Android 12+.
- Its channel is "Needs you". The task's own result notification is unchanged; a run that proposed something says so in its answer.

Live approvals, questions and budget pauses keep their own notifications from the running chat (AgentService), as before.

## Tests

- `ProposalsTest` (core/agent): a scheduled run's email becomes a proposal with its exact arguments, call id and flags, nothing is sent and nobody is asked; a tool set to Allow is proposed too; a tainted run's proposal carries its sources and reason; non-proposable tools, a tool turned off, a connected folder, no sink, a Read-only chat (not unattended) and a helper are refused as before; an interactive chat asks and runs; at most five per run, a repeat not counted; a sink's refusal; approving runs exactly the call once as the user's approval with nothing appended, re-checks the policy and the chat's limits, a refusing gate runs nothing, and taint is still shown.
- `ProposalApprovalTest` (core/data, the full app graph): a scheduled run's `write_file` is recorded and not written; Approve writes it once, marks it done, audits it as the user's approval and adds the note to the chat; a second tap and two taps at once run it once; declined and expired ones never run; a tool turned off since is blocked and the chat says why; nothing is remembered; an interrupted one never runs again; duplicates and the 50-waiting cap; a proposal goes with its chat; rows read back with flags, taint and preview.
- `NeedsYouFeedTest` (core/data): waiting items from every chat newest first with their titles; locked chats redacted until the session is unlocked; proposals newest first, expired shown as expired, settled ones gone, only waiting ones counted; unread results newest first; the notification never describes a locked chat's proposal and counts the rest.
- `RestoredProposalsTest` (core/data) and `NeedsYouMigrationTest` (core/database), above.
- `NeedsYouHomeTest` (feature/chat, through the home view model): a scheduled run's proposal and result reach home, Approve runs it and says so, Decline says so, a result opened is marked read, a locked chat's items are redacted until the unlock.
- `HomeScreenshots`: home with the feed (waiting items, proposals, results, locked items), an empty feed, a proposal card, an expired proposal and a locked chat's items.

## Not covered

- Approving from the notification, Telegram or voice. A proposal is approved on its card in Locus only.
- Editing a proposal before approving it (change the email's wording). Decline and run the task again, or ask in the chat.
- Proposals from helpers of a scheduled run.
- `edit_file` and other tools beyond the three above; adding one means checking that its effects can be shown on a card and still make sense days later.
- A per-task history of results: Needs you shows each task's latest run, like Tasks.
- "Pause when the user takes over" and "unmetered-only" from the same roadmap line are separate items.
