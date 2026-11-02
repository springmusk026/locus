# Transcript reads and transcript rows

How the chat screen reads a chat and turns it into list rows, and why it doesn't use Room paging.
Written 2026-11-02 for [audit #12](../research/reliability-security-audit-2026-11.md#12-transcript-query-and-paging)
and the Compose half of "Measurement" in the roadmap. No schema change (still v17).

## What was wrong

- `MessageDao.observeTree` selected `messages.*`, so every emission loaded `rawPayload` for every
  message of the chat, all branches included. For OpenAI Responses that is the whole output array with
  encrypted reasoning. The UI never reads it.
- The query joined `conversations`. Room's invalidation tracker works per table, so every insert into
  `messages` and every write to `conversations` (a `touch`, a rename, a helper's status, another
  chat's leaf moving) re-ran it for every open observer, and each run decoded the JSON of every
  message on the path. A helper chat working next to an open chat re-read the open chat on each step.
  Each helper row in a chat (`SubagentRunner.observeHelper`) did the same for its own transcript.
- The screen then rebuilt every row as a new object. Rows hold lists and classes from `core:model`, a
  module built without the Compose compiler, so they are unstable; under strong skipping they are
  compared by identity, so every visible row recomposed on every emission. `groupSteps` ran on the
  main thread, and `markWaitingPlan` made a new list on every run-state change while a task ran.

Request building is a separate path and is not changed: `ConversationRepository.history()` reads
`MessageDao.tree()` with every column, and within a run `AgentLoop` keeps the transcript in memory
(the audit found that fine). The system prompt, tool list and append-only history the provider sees
are byte-identical to before; `TranscriptReaderTest` checks that `tree()` still carries the payloads.

## Room paging, or a window: what fits

[Room 3](https://developer.android.com/jetpack/androidx/releases/room3) (3.0.3 stable, 2026-09-09)
moved paging into `androidx.room3:room3-paging`: a DAO registers
`@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)` and returns
`PagingSource<Int, T>`, which pages the query with `LIMIT`/`OFFSET`. Room's invalidation is per
table, and a `PagingSource` that sees its data change is invalidated and replaced, the next one
loading around `getRefreshKey` ([Paging: load and display paged data](https://developer.android.com/topic/libraries/architecture/paging/v3-paged-data)).
For this screen that is a poor fit:

- The chat streams at the bottom and saves a message per step. Every save (and every write to another
  chat in the same table) would invalidate the source and reload pages; offsets shift as rows are
  appended.
- The shown branch is a path through a tree (`parentId`), not a plain `ORDER BY`. Paging it needs a
  recursive CTE or a stored path index (a schema change).
- What the screen shows is derived from the whole path, not row by row: tool calls are paired with
  results from later messages, steps are grouped per turn, plans take their status from the next user
  message, receipts span a task, and the header needs token totals, the current checklist and the
  context size since the last checkpoint. With `LazyPagingItems` each of these would need its own
  query.
- `LazyColumn` already composes and lays out only the visible rows
  ([Lists and grids](https://developer.android.com/develop/ui/compose/lists)); the cost was never the
  list, it was re-reading and re-deriving the whole chat on every write.

So the window is on the read side: the transcript only grows at the end (messages are never updated,
and only deleted with their chat), so after the first read only new rows are read.

## The read path (`TranscriptReader`)

1. `MessageDao.observeVersion(chat)` reads one row: the chat's message count, newest `seq` and active
   leaf, in one statement. Room still re-runs it on any write to either table, but it is an index
   lookup on `conversationId` with no JSON. `distinctUntilChanged()` drops every run where this chat
   didn't change, so other chats, `touch`es and renames emit nothing.
2. When the version changes, `MessageDao.viewRows(chat, after, upTo)` reads only rows with `seq` in
   (last read, version's newest]. It selects the display columns only: no `rawPayload`, `searchText`
   or `copiedFrom`. Bounding by the version's `seq` keeps rows and leaf from the same snapshot.
3. If the counts don't add up (the chat was deleted, or rows appeared below what was read), it reads
   the chat again from the start, so the result is always what a full read would give.
4. The path is recomputed from the cached ids and parents (cheap, no JSON), and a message is decoded
   the first time it is on the shown path. Unchanged messages stay the same instances, and equal
   threads aren't emitted again (a message saved off the shown branch, a leaf set to where it was).

Branch switching only moves the leaf: the new path is built from rows already read.

## Rows the screen can skip (`InstanceKeeper`, `TranscriptRow`)

The Compose guidance under strong skipping (on by default since Kotlin 2.0.20; Locus uses 2.4.21) is
to keep instances across emissions rather than force structural equality on collections:
"preserve the collection's instance reference across emissions (for example, by caching
transformations in your ViewModel …) so Compose can skip using instance equality"
([Fix stability issues](https://developer.android.com/develop/ui/compose/performance/stability/fix)).

- `ChatViewModel` derives items and rows on the `Default` dispatcher, once per change of this chat,
  and passes both through an `InstanceKeeper`: an element equal to the one it had under the same key
  comes back as the previous instance, and an unchanged list as the previous list. Unchanged rows
  therefore skip recomposition, and `remember(state.items)` blocks don't re-run.
- `groupSteps` moved from the screen into the ViewModel; the rows are `ChatUiState.rows`.
- On-device speed lines are applied in the same background step, and `markWaitingPlan` returns the
  same list when there is no waiting plan (it used to copy it on every run-state change).
- `changes.observe` and `receipts.inputs` re-emit on writes from any chat (audit log, tracked
  changes); equal results are now dropped before the transcript is derived again.

### `key` and `contentType`

Transcript rows already had stable keys (message ids, `steps:<first step>`). They now also have a
`contentType` (the item's class, or `Steps`), as do the live turn and queued messages: "Compose is
able to reuse compositions only between the items of the same type" ([Lists and grids](https://developer.android.com/develop/ui/compose/lists#content-type)).
The chat list mixes about ten row kinds (bubbles, step cards, plans, receipts, diffs).

### Stability configuration

`compose-stability.conf` at the repository root is passed to every Compose module by the
`locus.android.compose` convention plugin (`composeCompiler.stabilityConfigurationFiles`). Following
the guidance to list only "small, flat classes with cheap `.equals()` checks" from non-Compose
modules, it lists message parts and metadata from `core:model` (attachments, tool calls and results,
token usage, reply info, todo items, branch info, plans, verification outcomes) and `TrackedChange`
from `core:data`. Classes holding large collections (`Message`, `TaskReceipt`) are left out on purpose.

To read the compiler's report: `-Plocus.composeReports=true` writes `build/compose-reports` for each
Compose module. After editing the stability file, rebuild with `--rerun-tasks`: the Kotlin compile
task was reported up to date after a change to the file alone.

## Measurements

JVM tests, so counts rather than milliseconds; the phone checks are in the PR.

**Reads** (`TranscriptReaderTest`, "read cost while another chat works"): a 400-message open chat
(200 replies with a 4,000-character payload) while a helper chat saves 40 steps and is touched 40
times, and the open chat gets 10 replies.

| | Before (full query per invalidation) | After |
|---|---|---|
| Query runs that read messages | 91 (1 + 90 write transactions) | 11 (1 + 10 for this chat) |
| Message rows read | ≥ 36,400 | 410 |
| Provider payload characters loaded | ≥ 72.8 M | 0 |
| Emissions to the screen | 91 | 11 |
| Extra cost | none | 91 one-row version reads |

"Before" counts Room's behaviour (one invalidation per write transaction; `insertAsLeaf` is one).
The in-memory fake isn't transactional, so the test itself prints 141 invalidations for the same
script; the "after" column is identical either way.

**Rows** (`ChatViewModelTest`, "rows keep their instances…"): another chat saving six messages and
being renamed produces no new rows list; a new message in this chat produces one new list in which
the unchanged rows are the same instances.

**Stability** (Compose compiler report for `feature:chat`, with and without the configuration file):

| | Without | With |
|---|---|---|
| Composable parameters reported unstable | 38 | 29 |
| `ChatItem` types inferred stable | 4 of 12 | 9 of 12 |

`ChatItem.User` (a list of attachments) and `ChatItem.Changes` (a list of changes) stay
runtime-checked and `ChatItem.Receipt` unstable; the `InstanceKeeper` covers them.

## Not done here

- **Opening a very long chat** still reads and decodes its whole shown branch once (now without
  payloads). Showing the newest rows first would need the header's totals, the checklist and the
  model-fit check from columns that don't need the parts JSON; worth doing if a chat of thousands of
  messages opens slowly on the phone.
- **Macrobenchmark** (startup, long-chat fling, streaming frame timing) needs a device to run, so it
  stays out of CI; the roadmap keeps it.
- `panelToggles` and the remaining `remember(state.items)` helpers still run on the main thread, now
  only when this chat's rows change.
- Markdown is still parsed in composition (`ChatMarkdown.kt`); pre-parsing replies off the main
  thread is a separate audit item.
