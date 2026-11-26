# Scheduled runs and background starts

How a scheduled task runs under Android's background limits, what happens when Android stops it, and
what Locus does when Android won't let a chat continue in the background. Roadmap items: "A failed
scheduled run says it failed", "never send a prompt twice" and "Guard background
`startForegroundService`" (research: [product B1, B2](../research/product-platform-2026.md#b-background-work),
[audit #8, #10](../research/reliability-security-audit-2026-11.md)).

## The limits

Sources were checked on 2026-11-01, against WorkManager 2.12.0 (the version Locus uses).

- **Ten minutes per worker.** "A ListenableWorker is given a maximum of ten minutes to finish its
  execution and return a Result. After this time has expired, the worker will be signalled to stop"
  ([`ListenableWorker` javadoc](https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/java/androidx/work/ListenableWorker.java),
  [reference](https://developer.android.com/reference/androidx/work/ListenableWorker)).
- **Quotas.** On Android 16, regular and expedited jobs get a runtime quota by standby bucket, and it
  now also applies to jobs started while the app was visible and to jobs running alongside a
  foreground service. Google suggests logging `WorkInfo.getStopReason()`
  ([Android 16 behaviour changes](https://developer.android.com/about/versions/16/behavior-changes-all)).
  On Android 14+, an app whose jobs time out too often can be moved to the restricted bucket
  ([optimise battery use](https://developer.android.com/develop/background-work/background-tasks/optimize-battery)).
- **Stop reasons.** `ListenableWorker.getStopReason()` (API 31+) returns the platform's
  `JobParameters.STOP_REASON_*` values: timeout, quota, connectivity lost, device state, background
  restriction and so on (WorkManager 2.12.0's `WorkInfo` constants).
- **Long-running workers.** `setForeground(ForegroundInfo(id, notification, type))` turns a worker
  into a foreground service that can run longer than ten minutes; apps targeting Android 14+ must give
  a foreground service type and declare it on WorkManager's `SystemForegroundService`
  ([long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)).
  But "calling `setForegroundAsync` will fail with an `IllegalStateException` when the process is
  subject to foreground service restrictions. Consider using `setExpedited`" (the javadoc above), and
  since Android 12 an app in the background can't start a foreground service except in listed cases:
  a notification tap, an exact alarm, battery optimisation turned off for the app, and a few others.
  Running a job isn't one of them
  ([background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
  The same page now warns that long-running workers can use up the job quota on Android 16.
- **Expedited work** suits "short tasks that start immediately and complete within a few minutes", and
  has its own quota in the background ([define work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)).
  Periodic work can't be expedited: `PeriodicWorkRequest.Builder` throws "PeriodicWorkRequests cannot
  be expedited" ([`PeriodicWorkRequest.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/java/androidx/work/PeriodicWorkRequest.kt)).
- **Retries.** When a worker is stopped, WorkManager puts the same work back in the queue for the same
  period and runs it again later. A periodic worker that returns success or failure moves to the next
  period ([`WorkerWrapper.kt`](https://raw.githubusercontent.com/androidx/androidx/androidx-main/work/work-runtime/src/main/java/androidx/work/impl/WorkerWrapper.kt):
  `resetWorkerStatus`, `resetPeriodic`). A retry has the same work id.

## The choice: a regular worker with an 8-minute budget

Scheduled runs stay regular (not expedited, not foreground) periodic workers, and each run has a
wall-clock budget of **8 minutes**, on top of its step limit.

- **Not expedited:** periodic work can't be. Re-enqueueing a one-time expedited request from each
  period would add a second scheduling path for a few minutes of quota that runs out in the background
  anyway.
- **Not long-running:** a scheduled run starts in the background almost every time, where
  `setForeground` throws on Android 12+ unless the user turned off battery optimisation for Locus. A
  foreground run would then be the exception, and on Android 16 it still counts against the job quota.
  Two kinds of run with different limits would be harder to explain than one predictable one.
- **8 minutes:** it leaves two minutes before WorkManager's ten to record the outcome, notify and
  return, so the run normally ends on Locus's terms. When it does, the run is recorded as stopped
  ("it took longer than 8 minutes, the limit for a scheduled run"), the chat keeps what it found, and
  the user is told.

A task that needs longer is a normal chat: those run in `AgentService` (`specialUse`, no time limit).

## How a run ends

`TaskWorker` is the Android side; the run itself is `ScheduledRun`, so it can be tested. Every ending
is recorded on the task (`lastOutcome`: done, failed or stopped, plus the summary) and notified:

| Ending | Recorded as | Notified |
|---|---|---|
| An answer | Done, the answer | The task's title |
| An error (no provider, a refused request, an exception) | Failed: the error | "Title: failed" |
| Past the 8-minute budget | Stopped: took longer than 8 minutes | "Title: stopped" |
| Stopped by Android | Stopped by Android (network lost / background work quota used up / ...) | "Title: stopped" |
| The process died mid-run | Stopped: Locus was closed before the run finished (found by the retry) | "Title: stopped" |
| The user turned the task off or deleted it | Stopped: the task was turned off | No |

A stop is recorded under `NonCancellable` in the cancelled worker, then the cancellation is rethrown.
Tasks shows "Last run failed" or "Last run stopped" in red on the task's row and over its last result.

### Following a run

- **The notification opens the run's chat.** `TaskWorker` wraps the app's own launcher intent
  (`getLaunchIntentForPackage`, so it is `dev.locus` or `dev.locus.debug` as built) with
  `OpenChatLink`'s chat id and token, the same way `AgentService`'s notifications do; `MainActivity`
  opens the chat only when the token matches. Each task has one notification and one PendingIntent
  (request code from the task id, `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`): a newer run replaces the
  older notification and points it at the newer chat, and two tasks never share an intent
  (`TaskResultLink`).
- **Tasks opens it too.** The task keeps `lastConversationId`; an opened row has "Open last run".
- **Run now shows where it is.** The row says "Queued" (Run now waits for a network connection) or
  "Running now" from the unique run-now work's state. When a Run now started from the screen ends, a
  message says it ran (with Open chat), didn't run because another run was going (the worker returns
  `ScheduledRun.Result` as output data, so a skipped run isn't reported as done), or didn't finish.
- **Editing.** Name, prompt, interval and conditions can be changed from the same sheet that creates a
  task. A scheduled workflow's plan is hidden from the prompt field and kept on save. An enabled task is
  rescheduled with `ExistingPeriodicWorkPolicy.UPDATE`. The intervals are `ScheduleIntervals`, shared with
  Library → Workflows.
- **Not yet:** a per-task run history. `task_runs` records tasks per chat, not per scheduled task, so
  listing a task's earlier runs needs a schema change; the notification and "Open last run" cover the
  latest one.

## Never sending a prompt twice

Each task has a run marker: the work id, the chat and the start time, written to the task's row just
before the prompt goes out and cleared when the worker returns. When a worker starts:

- **No marker:** send the prompt.
- **The marker is its own work id:** this is WorkManager retrying a run whose prompt already went out
  (Android stopped it, or the process died). It doesn't send it again. If the stop wasn't recorded
  (the process died), it records and notifies it now; then it clears the marker and returns success,
  so a periodic task moves on to its next period.
- **Another work's marker, under 15 minutes old:** Run now and the schedule overlap; this one is
  skipped.
- **Another work's marker, older:** no worker lives that long, so the run that left it died. It is
  recorded as stopped if it wasn't, and this run goes ahead.

A stop keeps the marker on purpose: the retry must find it. Turning a task off clears it, since nothing
retries cancelled work.

### One run at a time: the claim

Reading the marker and then writing it left a window in which the schedule's worker and a Run now
worker could both find the task free and both send the prompt. The rules above are now only a first
look; what decides is a claim made in the database before anything else happens (`CLAIM_RUN` in
Daos.kt): one `UPDATE … WHERE runWorkId IS NULL OR <stale> OR <its own claim without a chat>`, run in a
transaction that also reads the row it replaced. Exactly one of two racing workers changes the row; the
other changes nothing and is skipped. The claim has no chat until the run creates one and names it,
just before the prompt goes out, so a claim without a chat means nothing was sent and the same work may
take it again. Naming the chat and releasing the claim are also `… WHERE runWorkId = <this work>`, so a
run never releases one it doesn't hold. Edits, pausing and recording a run's outcome update their own
columns instead of writing the whole row back, so none of them can overwrite a claim made meanwhile.

## Chats continuing in the background

A chat continues on its own in two cases: a background helper finishes and its report is added
(`continueWithHelperReports`), and a message queued while the agent worked is sent when the turn ends.
Both call `AgentRunner.start()`, which starts `AgentService` as a foreground service. If the app is in
the background by then and the service had already stopped (it stops itself when nothing runs), Android
12+ throws `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`, from
`startForegroundService` ([background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)).
Before, nothing caught it, so it would have reached the app's coroutine scope, which then had no exception
handler, and taken the process down (inferred from the code, not reproduced). The scope now records
such errors under Diagnostics instead (docs/design/diagnostics.md).

Two options were considered:

1. **Keep the service alive while any helper of the chat runs**, so a later start finds it running.
   The service already counts background helpers (`activeCount`), but the gap between a helper
   finishing and the continuation registering is a race, and the queued-message case has no helper at
   all. Closing it means the service outliving its work by some margin, and still needing a catch for
   whatever slips through.
2. **Catch the refusal** and, for a start Locus made on its own, don't run the turn: mark the chat
   "Continue?" and post a notification, "Helper finished: tap to continue" (or "Your message is
   waiting"). Tapping opens the chat; a notification tap is one of the exemptions, and the app is in
   front anyway, so Continue can start the service.

Locus does **2**. It covers every path that starts the service, can't crash, and doesn't run an agent
turn in a background process that Android may freeze or kill at any moment, with nothing to show the
user it's working. A turn the user started (Send, Edit, Regenerate, Continue) is started from the
screen, so the refusal shouldn't happen; if it does, the turn still runs, as before.

Continue with only helper reports waiting adds the reports and runs once, instead of first asking the
model again with nothing new.

## Proposals

A scheduled or triggered run is still read-only, but a call to `email_send`, `calendar_create` or a workspace `write_file` that would be refused is recorded as a proposal instead, for the user to approve or decline in Needs you on home; the run is told it was proposed and says so in its answer. Approving runs exactly that call once, later, through the normal checks. Everything else is refused as before. See [needs you](needs-you.md).

## Taking over a run

A scheduled or triggered run happens in a chat of its own, which the user can open while it works (from Needs you, the task's row or the chat list). Before, a message sent there waited in the queue until the run ended, and Stop did nothing (the worker owns the run's end). Now the user can take over:

- **Send** in that chat takes it over. The message is queued with a hidden note (`ModeNote.TAKEN_OVER`: the user opened the chat while the scheduled task ran, the background rules no longer apply, answer this message), and the chat's progress line says "Stopping the scheduled run…". **Stop** takes it over the same way, without a message. Typing a draft does nothing: only Send and Stop reach the runner.
- **Where it stops.** `AgentConfig.stopAtBoundary` is asked at each step boundary, before the next request, and once a reply has arrived, before its tool calls run. Either way the loop ends with `Outcome.Interrupted` and every call has a result: a call already running finishes (scheduled runs' calls are read-only and short), and a reply's calls that hadn't started are answered "Not run: the user took over this chat". Nothing is cut off mid-call, and the transcript stays valid to continue. A request in flight isn't cancelled; its reply is kept.
- **The record.** The run ends as `TaskOutcome.STOPPED` with the detail "you took over the chat" (`BackgroundRun.takenOver`); `ScheduledRun` records it as stopped, "Stopped: you took over in the chat, so the run stopped after its last step. It runs again at its next time." It isn't sent to reach-me channels. The notification is skipped while Locus is on screen (the chat says it); if the user has left Locus by the time the run ends, "Title: stopped" says so.
- **The claim and the marker.** The worker returns normally, so the marker is cleared as for any finished run (`clearMarker(taskId, workId)`, only if this work still holds it): the prompt went out once, a retry of the same work would find no marker and nothing is ever re-sent. The user's own turn holds no claim: it runs in the chat like any message. The next scheduled time runs as usual.
- **The user's message runs once, as a normal turn.** When `runInBackground` has freed the chat's slot, `continueAfterTakeover` starts a turn only if something is still queued, and that turn's block adds the queued messages and runs only if it took any, so neither a Stop-only takeover nor a race with the queue's own pickup asks the model again with nothing new. It is an ordinary turn: not read-only, the chat's own approvals and remembered answers, the reviewer, the completion check and steering apply; outward calls ask instead of becoming proposals. Taint carries over from the transcript as for any turn (a run that read a web page leaves the chat tainted). It starts as a queued message does (`BackgroundStart.QUEUED`), so if Android refuses the foreground service the chat offers Continue. The chat shows "You took over: the scheduled run stopped, and your message runs as a normal chat turn." (or, after Stop, "… stopped here. Send a message to carry on as a normal chat.").
- **Prompt cache.** The chat's system prompt and tools are fixed when the run creates it and don't change; the note rides on the user's message.

## Run conditions: unmetered only

A task's conditions (Tasks → edit → Conditions) are WorkManager constraints, set by `TaskConstraints.of`: a run always needs a network (`NetworkType.CONNECTED`); **On Wi-Fi** narrows it to an unmetered network (`NetworkType.UNMETERED`, which also covers unmetered Ethernet and a Wi-Fi the user didn't mark metered); **While charging** and **Battery not low** add theirs. WorkManager holds the run until all hold, and stops a running worker if one stops holding (recorded as "Stopped by Android (network lost)" and so on, above). Run now ignores them (`TaskConstraints.runNow`: any network). Stored in `scheduled_tasks.unmeteredOnly` since database v16. Tested in `TaskConstraintsTest` (on Wi-Fi maps to `UNMETERED`, the default to `CONNECTED`) and `TaskRepositoryTest`.

## Starting at a time of day

A new scheduled task's sheet has a **Starting at** row: "Right after saving" by default, or a time of day picked on a `TimeSheet` (Material's `TimePicker`, in the phone's 12- or 24-hour format). Under the row the sheet lists the next three runs with their weekday and the phone's time zone ("Fri 07:30, Sat 07:30, Sun 07:30 (Nepal Time)", `nextRuns`), and says Android may run it a little later while the phone is idle. The morning and news digests start at 07:00 this way. The editor, with its draft, survives a rotation or a theme change (`SheetTargetSaver`, `TaskDraftSaver`).

**Whole-day periods keep their wall-clock time (#314).** A task that starts at a time of day and repeats every N whole days (daily, weekly) isn't a periodic request: a fixed 1,440-minute period keeps its 24 hours when the clocks change, so "every morning at 7" would run at 8:00 for good after the spring change, and `UPDATE` keeps a periodic request's schedule, so it can't be realigned either. Instead it is one one-time `TaskWorker` request at a time (unique name `task-<id>`), due at `TaskTiming.first` (the next `HH:MM` in the phone's zone, from `ZonedDateTime`). When a run ends (finished, failed or skipped; not when Android stops it for WorkManager to retry, and not on a hot-phone retry), `TaskWorker.enqueueNextDaily` enqueues the next one for `TaskTiming.next`: N local days after the date this one was due, at the same `HH:MM`, and never at or before now, so a run Android held for a day doesn't make the next late too. A task turned off or deleted meanwhile isn't enqueued again, and the conditions and period come from the task as it is then.
- A time that doesn't exist on a day (02:30 when the clocks jump from 02:00 to 03:00) runs after the jump (03:30) and the next day is at 02:30 again. A time that happens twice runs the first time.
- The time of day isn't a database column (no schema change). `TaskTimes`, a small preferences file, keeps it with the next planned run. It is what lets a task set up again without a time (a changed condition or period, turning it on again) keep it, and a weekly task keep its day: `TaskTiming.resume` reuses the planned run while it is still ahead and still at that time here. A restored backup comes with no such file: restored tasks get a plain period of the same length.
- Scheduling it again (new conditions, an edit) replaces the waiting request and, as any cancel does, stops a run of this task that is going at that moment; before, `UPDATE` left it alone.
- Not realigned yet: moving to another time zone. The run already waiting stays at its instant (once, at the old zone's time); the one enqueued after it is back on the wall clock.
- A period that isn't whole days (hourly, every 90 minutes) stays a periodic request, as before: they don't depend on the hour of day.

## Event-triggered runs

A task can also start when an event arrives ([event triggers](event-triggers.md)). Such a run is a one-time `TaskWorker` carrying the event's id. It goes through the same marker, claim, budget, receipt, notification and hot-phone hold described here. The event's history row records whether the claim was taken ("Already running") and how the run ended.

## Tests

- `TaskTimingTest`: the next run across the spring and autumn changes (New York and Lisbon), a time inside the skipped hour and one inside the repeated hour, a run held for a day, a weekly task through the change, and a later extra trigger check not replacing a nearer one.
- `ScheduledRunTest`: a finished run; a stop by Android recorded and notified with its reason, and its
  retry not sending the prompt again; a retry after the process died; the budget; a failure; Run now
  next to a live or stale marker; the start rule and stop reason texts.
- `TaskSheetStateTest`: a draft round-trips through the saved state; the next runs from a start time or from now. `TaskSheetRotationTest`: a half-written task survives a recreated activity.
- `BackgroundStartTest`: a refused automatic start doesn't run, offers Continue and posts the
  notification, and Continue then runs; a refused user-started turn still runs.
- `TakeOverTest`: a message mid-run stops it at the step boundary after its call finished, records
  "you took over", releases the marker, sends no notification while Locus is on screen, and runs the
  message once as a normal turn whose memory write isn't refused; Stop alone sends nothing more and
  notifies when Locus isn't on screen; a reply that arrives after the takeover has its calls answered,
  not run; without a send the run carries on.

## Not covered

- Runs aren't kept as a history; the task keeps its last outcome and each run's chat.
- `JobScheduler#getPendingJobReasonsHistory` (Android 16) could say why a task hasn't run at all.
  Not used yet.
