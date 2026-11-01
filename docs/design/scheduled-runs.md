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

## Tests

- `ScheduledRunTest`: a finished run; a stop by Android recorded and notified with its reason, and its
  retry not sending the prompt again; a retry after the process died; the budget; a failure; Run now
  next to a live or stale marker; the start rule and stop reason texts.
- `BackgroundStartTest`: a refused automatic start doesn't run, offers Continue and posts the
  notification, and Continue then runs; a refused user-started turn still runs.

## Not covered

- Runs aren't kept as a history; the task keeps its last outcome and each run's chat.
- `JobScheduler#getPendingJobReasonsHistory` (Android 16) could say why a task hasn't run at all.
  Not used yet.
