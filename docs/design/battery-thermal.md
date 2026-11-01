# Battery- and thermal-aware execution

Roadmap item 8 (P2): use less on-device compute, hold non-urgent work, and adapt to the network,
the battery and heat. Long agent runs and on-device models heat the phone and drain it, and
Android slows a hot app down anyway.

Sources were checked on 2026-11-01. Each claim below links to the page it comes from.

## What Android offers

### Heat

- [`PowerManager.getCurrentThermalStatus()`](https://developer.android.com/reference/android/os/PowerManager)
  (API 29) returns one of these, with the meanings from the reference:

  | Status | Meaning |
  |---|---|
  | `THERMAL_STATUS_NONE` (0) | not throttling |
  | `LIGHT` (1) | "UX is not impacted" |
  | `MODERATE` (2) | "UX is not largely impacted" |
  | `SEVERE` (3) | "UX is largely impacted" |
  | `CRITICAL` (4) | "Platform has done everything to reduce power" |
  | `EMERGENCY` (5) | key components shutting down |
  | `SHUTDOWN` (6) | "Need shutdown immediately" |

  `addThermalStatusListener(Executor, OnThermalStatusChangedListener)` reports changes.
- [`getThermalHeadroom(forecastSeconds)`](https://developer.android.com/reference/android/os/PowerManager)
  (API 30) forecasts throttling:
  - 1.0 means the phone "is (or will be) throttled at THERMAL_STATUS_SEVERE".
  - It returns NaN where it isn't supported, or when it is called too often.
  - It follows slow sensors such as skin temperature, and needs a few samples before it forecasts.
  - API 35 adds `getThermalHeadroomThresholds()`. API 36 adds a headroom listener that fires only
    when a threshold is crossed, and the docs say it
    ["may not be used to fully replace"](https://developer.android.com/reference/android/os/PowerManager.OnThermalHeadroomChangedListener)
    polling.
- The [ADPF thermal guide](https://developer.android.com/games/optimize/adpf/thermal) says:
  - Lower the workload *before* throttling starts.
  - Above 1.0, "reduce workload immediately". 0.95 is likely MODERATE, and 0.85 likely LIGHT.
  - Some devices always report NONE, so headroom is the better signal.
  - Call `getThermalHeadroom` no more than once every 10 seconds. The API reference says about once
    a second; Locus uses the stricter figure.
- ADPF targets games but
  ["you can also use the features for other performance-intensive apps"](https://developer.android.com/games/optimize/adpf).
  - [`PerformanceHintManager`](https://developer.android.com/reference/android/os/PerformanceHintManager)
    needs the thread ids of the work. LiteRT-LM's native worker threads aren't exposed to Kotlin, so
    Locus can't use it. This is our inference, not something the docs say.
  - We found no Android page with ADPF or thermal guidance specific to on-device ML inference.

### Battery and battery saver

- [`BatteryManager`](https://developer.android.com/reference/android/os/BatteryManager):
  - `BATTERY_PROPERTY_CAPACITY` gives the remaining charge in percent.
  - `isCharging()` (API 23) means plugged in with the level rising, or full.
- `ACTION_BATTERY_CHANGED` is sticky. It
  ["cannot be received through components declared in manifests"](https://developer.android.com/reference/android/content/Intent),
  only by registering at runtime. [Reading it once](https://developer.android.com/training/monitoring-device-state/battery-monitoring)
  gives `EXTRA_LEVEL`/`EXTRA_SCALE`, `EXTRA_STATUS` and `EXTRA_PLUGGED`.
- `ACTION_BATTERY_LOW` matches the system's low-battery warning. The docs give no fixed percentage.
  The battery guide points to WorkManager's battery-not-low constraint instead of a manifest
  receiver.
- [`isPowerSaveMode()`](https://developer.android.com/reference/android/os/PowerManager): when it's
  on, "applications should reduce their functionality in order to conserve battery as much as
  possible". `ACTION_POWER_SAVE_MODE_CHANGED` "is only sent to registered receivers".

### Networks

- [`ConnectivityManager`](https://developer.android.com/reference/android/net/ConnectivityManager):
  - `registerDefaultNetworkCallback` (API 24) reports the default network. It calls back at once
    with the current one.
  - A metered network is one where "the user is sensitive to heavy data usage". Apps should "check
    this before doing large data transfers, and warn the user or delay".
- [`NET_CAPABILITY_NOT_METERED`](https://developer.android.com/reference/android/net/NetworkCapabilities)
  is the capability to check. `TEMPORARILY_NOT_METERED` can disappear at any moment, and
  "most apps should use NET_CAPABILITY_NOT_METERED instead".

### WorkManager

- [`Constraints.Builder`](https://developer.android.com/reference/androidx/work/Constraints.Builder)
  has `setRequiresCharging`, `setRequiresBatteryNotLow`, `setRequiredNetworkType(UNMETERED / CONNECTED)`
  and `setRequiresDeviceIdle`. Locus uses WorkManager 2.12.0, the
  [current stable release](https://developer.android.com/jetpack/androidx/releases/work).
- [Defining work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work):
  - Work runs only when all its constraints hold.
  - If a constraint stops holding mid-run, "WorkManager will stop your worker", and retries it later.
  - A periodic run may be "delayed, or even skipped".
- Expedited jobs
  ["may only set network, storage-not-low, and persistence constraints"](https://developer.android.com/reference/android/app/job/JobInfo.Builder).
  Scheduled tasks aren't expedited (docs/design/scheduled-runs.md), so charging and battery-not-low
  are allowed.

### LiteRT-LM (0.19.0)

- [`Config.kt`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.0/kotlin/java/com/google/ai/edge/litertlm/Config.kt):
  - The backends are `Backend.CPU(threadCount: Int? = null)`, `Backend.GPU()`, `Backend.NPU(...)`
    and `GOOGLE_TENSOR`. **The CPU thread count is set from Kotlin**, when the engine is created.
  - `ConversationConfig` has `maxOutputToken`. Both were checked against the 0.19.0 jar.
- [`Conversation.cancelProcess()`](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt)
  cancels inference in progress. The conversation's state isn't rolled back after a cancel (an open
  feature request), so a cancelled conversation isn't reused.
- [Google's published numbers](https://developers.google.com/edge/litert-lm/overview) for
  Gemma-4-E2B on an S26 Ultra:

  | Backend | Prefill (tokens/s) | Decode (tokens/s) | First token | Peak CPU memory |
  |---|---|---|---|---|
  | CPU | 557 | 47 | 1.8 s | 1733 MB |
  | GPU | 3808 | 52 | 0.3 s | 676 MB |

  The page has no energy or thermal data. We found no primary source showing that CPU uses less
  energy per token than GPU, or the reverse.

## What Locus does

### Conditions

- `DeviceConditions` (core/llm, an interface) is a `StateFlow<DeviceState>`. The phone's
  implementation, `AndroidDeviceConditions` (core/data), registers once for the life of the process:
  - a thermal status listener (API 29+; older phones report NONE);
  - a runtime receiver for `ACTION_BATTERY_CHANGED` and `ACTION_POWER_SAVE_MODE_CHANGED`;
  - a default-network callback, checking `NET_CAPABILITY_NOT_METERED`;
  - `onTrimMemory` and `onLowMemory`. Since API 34 only `UI_HIDDEN` and `BACKGROUND` are delivered,
    and `BACKGROUND` or above counts as low memory. The flag clears once
    `ActivityManager.MemoryInfo.lowMemory` is false again, at least 3 minutes later.
- `headroom()` calls `getThermalHeadroom(10)` at most every 10 seconds, and only before an
  on-device reply.
- Every decision is a pure function in `PowerPolicy` (core/model), unit-tested in `PowerPolicyTest`.

### Decisions

| Condition | What happens |
|---|---|
| SEVERE or above, or battery saver | Non-urgent work waits (below). The chat shows "Paused: phone is hot" or "Paused: battery saver" with Run anyway. |
| SEVERE or above, or a headroom forecast of 1.0 or more | Before an on-device reply: light mode, send anyway, or not now. |
| Battery saver | The same question before an on-device reply. |
| CRITICAL or above | An on-device reply stops at once. The partial text is kept, with a note saying why. Running Linux services are suggested for stopping. |
| SEVERE or above | Scheduled runs return `Result.retry()` before sending anything, so WorkManager tries again later with backoff. Run now isn't held. |
| Low memory / battery saver or hot / otherwise | An idle on-device model is unloaded after 20 s / 2 min / 10 min. |

A low battery alone isn't a reason to hold anything in the app: WorkManager's battery-not-low
constraint covers scheduled tasks, and the user can see their own battery.

### Non-urgent work (`DeferredWork`)

- These wait: the chat title and follow-up suggestions (`afterTurn`), the lesson-saving reflection,
  and the background refresh of the models.dev catalogue.
- The user's own turn, approvals, helpers the agent started, and a model list the user opened
  never wait. Locus never stops a task the user is watching without saying so.
- Held work is keyed per chat and kind, so a newer turn's side jobs replace older ones.
- It runs by itself once the phone is back to normal, or at once on Run anyway.
- It's kept in memory only. After a restart there is nothing to catch up on.

### On-device inference

- **Light mode** is what the user can pick in the warning sheet:
  - A reply is capped at 512 output tokens (`maxOutputToken`). Less decoding is less work on any
    backend.
  - A CPU engine is created again with `Backend.CPU(threadCount = 2)`. Fewer busy cores draw less
    power at once, so the phone heats more slowly, at the cost of speed.
  - A GPU engine stays on GPU. Its prefill is about 7 times faster (table above), and no source shows
    CPU to be more efficient. Moving a hot phone to CPU would most likely make it work longer.
  - Light mode turns off by itself once the phone is neither hot nor in battery saver.
- **Not now** puts the message back in the box.
- **The stop at critical heat:**
  - A watcher on the conditions calls `cancelProcess()` and ends the stream itself. It doesn't wait
    on the runtime's callback.
  - The turn completes with the text so far and a bracketed note (`HeatStop.note`) that the user
    sees and the model reads next turn.
  - A tool call cut off this way isn't run. The conversation isn't reused.
- **Idle unload** (`IdleUnloader`) counts from when the engine last finished work. A change of
  conditions shortens the wait without restarting it. `unload()` refuses while a reply is in
  progress. Before this, a 2.6–3.7 GB model stayed loaded until the process ended. Tested with
  virtual time in `IdleUnloaderTest`.

### Scheduled tasks and downloads

- Each task stores three conditions (database v16): charging, unmetered network and battery not low.
  `TaskConstraints` maps them to WorkManager constraints:
  - On Wi-Fi means `NetworkType.UNMETERED`; otherwise `CONNECTED`.
  - Charging means `setRequiresCharging`.
  - Battery not low means `setRequiresBatteryNotLow`.
  - Tested in `TaskConstraintsTest`.
- New tasks take the defaults from Settings → Battery & heat. Out of the box that's battery not low.
- Tasks created before this keep no conditions, as they were scheduled. Their stored values match
  the WorkManager request they already have.
- Changing a task's conditions reschedules it with `UPDATE`, which keeps its period. Run now needs
  only a network.
- On-device model downloads use `UNMETERED` unless the user turns that off. A waiting download offers
  "Use mobile data", which re-enqueues it with `CONNECTED` and resumes the partial file.

### Linux

- At CRITICAL, with services running, the chat and the workspace show "Phone is very hot" with the
  services' names, plus Stop services and Not now.
- Nothing is stopped automatically. A service runs a command the user or the agent chose, and the
  agent can start it again.

## Not done

- **`PerformanceHintManager` and `setPreferPowerEfficiency`:** LiteRT-LM's threads aren't reachable
  from Kotlin.
- **The headroom listener (API 36):** the docs say it doesn't replace polling, and we poll only
  before a reply.
- **NPU backends:** Google says they are power-efficient but publishes no numbers, and they need
  per-device libraries.
- **Measuring energy on device:** worth doing before changing which backend light mode uses.
