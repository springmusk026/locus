# Task receipt

A task is one message from you and everything it sets off: the agent's steps, its helpers, its requests to the safety reviewer and its summaries. Before this, how a task went was spread over several places: the changes card (#68), verification cards (#74), Activity's approvals, Insights' tokens, the fallback note under a reply (#67) and the taint state (#72). A failure or a Stop was only a banner, gone after a restart. The task receipt puts all of that in one place under the task.

## What it shows

Collapsed, it's one line under the task's last turn:

> Done · 4m 12s · 3 files · tests passed · 2 approvals · ≈$0.04

Tap it to expand:

| Field | From |
| --- | --- |
| Outcome: done, stopped by you, paused at budget, failed (and why), model declined, step limit, interrupted | The task record (below) |
| Time and steps | The record's start and end; the chat's assistant turns in that time |
| Models, backups marked with the model they stood in for, helpers', the reviewer's and the summariser's | `ReplyInfo` on messages (#67), helper chats' messages, usage events |
| Tokens in and out, and the share read from cache | `usage` on the same messages and events |
| Estimated cost | models.dev prices, as the task budget uses them (#37). "Cost unknown" when no price is known; "≈$0.04+" when some requests went to a model without one |
| Files changed, linking to the changes card | Tracked changes (#68) of the task's turns, undone ones left out |
| Checks passed and failed, linking to the first failing card | `run_command` calls recognised as builds and tests (#74) |
| Approvals: asked, you approved, you declined, blocked by a policy or the reviewer; each opens those Activity entries | The audit log of the chat and its helpers in the task's time. A budget pause's Continue isn't an approval |
| Outside content read, when the run was tainted, and whether earlier messages had already tainted it | `Taint.sourcesIn` over the task's messages and the context it started with (#72) |
| Helpers it started | Helper chats whose parent call is in the task |

"Share chat" adds a short block after each task, and a scheduled run's notification gets the line under its answer.

## What's stored

Only what can't be worked out afterwards: when the task started, when it ended and how. That is a new table, `task_runs` (database v14, an automatic migration with `TaskRunsMigrationTest`): `id`, `conversationId`, `startedAt`, `endedAt`, `outcome`, `detail`. The runner opens a row when a task starts and closes it in the same `finally` that ends the task: the loop's own outcome, Stop (a cancelled job), the error that escaped, or for a scheduled run the time limit or Android. A row still open at startup was cut off with the app and is closed as interrupted. Rows go with their chat.

Everything else is computed by `TaskReceipts.build` (core/agent), a pure function over the chat's shown branch, the audit log, tracked changes, helpers' requests and usage events, so each field is tested from fixtures. A task's messages are those on the shown branch between its start and end; the receipt sits after the turn holding the last of them. A regenerated reply on another branch has its own task and its own receipt; tasks whose messages aren't on the shown branch have none.

## Never sent to a model

Receipts live outside the transcript: the table isn't read when a request is built, and the chat item is UI only. The system prompt, tool list and history don't change, so the prompt cache isn't affected. `TaskRecordTest` checks that a later request carries none of it.

## Limits

- Chats from before this change have no receipts: their outcomes weren't kept.
- A background helper that keeps working after its task ended counts toward that task only up to the end.
- Prices are estimates from models.dev, like the budget's; providers' bills can differ (batch, regional or negotiated prices).
- A summary you ask for from the token sheet isn't a task and has no receipt.
