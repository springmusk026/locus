# Stream timeouts, network switches and the Linux kill reason

Three reliability fixes from the November audit and the product research: model streams that hang
after a network drop, a failed summary that went unreported, and Linux processes that Android kills
in the background without saying why. Roadmap items: "Network drops" ([Audit #11](../research/reliability-security-audit-2026-11.md#11-network-changes-mid-stream)),
"Compaction failure notice" ([Audit, smaller issues](../research/reliability-security-audit-2026-11.md#smaller-confirmed-issues))
and "Linux kill reason" ([Product B3](../research/product-platform-2026.md#b-background-work)).
Sources were checked on 2026-11-02.

## Stream idle timeout

Before: OkHttp `readTimeout(10, MINUTES)` and Ktor `requestTimeoutMillis = INFINITE`. A stream whose
socket died without a reset hung for up to 10 minutes before the agent loop's retry started.

### What the libraries and comparable agents do

| Source | Timeout | What resets it |
|---|---|---|
| OkHttp `readTimeout` | Default 10 s. "Applied to both the TCP socket and for individual read IO operations including on Source of the Response" | Any byte |
| OkHttp `callTimeout` | Default none. "Spans the entire call: resolving DNS, connecting, writing the request body, server processing, and reading the response body" | Nothing |
| Ktor `HttpTimeout` | `requestTimeoutMillis`: "a whole HTTP call, from sending a request to receiving a response"; `socketTimeoutMillis`: "the maximum time in between two data packets". A request's `timeout {}` overrides the client's | – |
| Codex | `DEFAULT_STREAM_IDLE_TIMEOUT_MS = 300_000` ("Idle timeout … to wait for activity on a streaming response before treating the connection as lost"), `DEFAULT_STREAM_MAX_RETRIES = 5` | Any stream activity |
| Claude Code | Byte-level watchdog: 180 s on the Anthropic API, 300 s elsewhere. Event-level watchdog: 300 s. Body idle timeout for other providers: 5 minutes. `CLAUDE_STREAM_IDLE_TIMEOUT_MS` values below 5 minutes are raised to 5 minutes. An abort is a mid-stream failure that's retried | Bytes "including SSE keep-alive pings" (byte-level) |

Sources:
- OkHttp: [`OkHttpClient.kt`](https://github.com/square/okhttp/blob/master/okhttp/src/commonJvmAndroid/kotlin/okhttp3/OkHttpClient.kt) (`callTimeout`, `readTimeout`)
- Ktor: [client timeouts](https://ktor.io/docs/client-timeout.html)
- Codex: [`codex-rs/model-provider-info/src/lib.rs`](https://github.com/openai/codex/blob/main/codex-rs/model-provider-info/src/lib.rs)
- Claude Code: [Streaming idle watchdogs](https://code.claude.com/docs/en/network-config#streaming-idle-watchdogs), [environment variables](https://code.claude.com/docs/en/env-vars) (`API_TIMEOUT_MS`, `API_FORCE_IDLE_TIMEOUT`)

### Keepalives

Reasoning models can think for minutes before the next token. Whether the line stays busy meanwhile
depends on the provider:

- **Anthropic** sends `ping` events: "Event streams might also include any number of `ping` events"
  ([streaming](https://platform.claude.com/docs/en/build-with-claude/streaming#ping-events)). Claude
  Code's 180 s byte watchdog on the Anthropic API relies on them.
- **OpenAI Responses** sends `keepalive` events "on long-running requests" (openai-node fixed its
  accumulator for them: [openai/openai-node#1965](https://github.com/openai/openai-node/pull/1965),
  July 2026). Locus's Responses adapter already ignores event types it doesn't know.
- **OpenRouter** "occasionally sends comments to prevent connection timeouts", e.g.
  `: OPENROUTER PROCESSING` ([streaming](https://openrouter.ai/docs/api-reference/streaming)).
- **OpenAI Chat Completions and local servers** (Ollama, llama.cpp, LM Studio, vLLM): no documented
  keepalive. A reasoning model or a long prompt on a slow machine can leave the line silent.

### Decision

`StreamIdle` (core/llm `Sse.kt`) wraps every line read of a model stream:

- **5 minutes** until the stream has sent a keepalive: Codex's default and Claude Code's floor and
  body idle timeout. Long enough for a reasoning model on a provider that sends nothing while it thinks.
- **3 minutes** once it has sent one (an Anthropic `ping`, a Responses `keepalive`, an SSE comment
  line): Claude Code's byte watchdog on the Anthropic API. A provider that keeps the line alive and
  then goes quiet for 3 minutes has lost the connection, not paused.

Any line resets the timer, pings and comments included, so a long-thinking stream that keeps the line
alive is never cut off however long it thinks. The audit suggested 90–120 s; that is shorter than
both reference agents, and nothing shows Anthropic's ping interval is that tight, so Locus follows
them instead.

On timeout the read throws `StreamIdleException`, an `IOException`, so `AgentLoop` retries it like any
dropped connection (the partial output is discarded; bounded backoff; not after a tool call started,
as before). The timer is wall-clock time on one shared daemon thread (`IdleClock.RealTime`) and
cancels only the line read, which stays on the caller's dispatcher. A coroutine `withTimeout` would
follow the caller's clock, and in tests on virtual time it fired at once while a mock engine answered
on a real thread. Tests of the timeout pass a clock on their test scheduler and run on virtual time.

OkHttp's `readTimeout` drops from 10 to **6 minutes**: a backstop for every read, including the wait
for a model's response headers, one minute above the stream limit so the stream's clearer message
wins. The Ktor request timeout stays infinite: a long answer is not a hung one.

## Network switches

"When a new network becomes the default, any new connection the app opens uses this network. At some
point later, all remaining connections on the previous default network are forcefully terminated"
([Reading network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)).
Until then a stream on the old network may hang instead of failing.

- `DefaultNetworkWatcher` (core/data) registers `registerDefaultNetworkCallback` once per process.
  `onAvailable` is called at once with the current default network, then for each new default.
- `NetworkSwitches` (core/llm) records the first network and counts each *different* default network
  as a switch. Losing the network with nothing to replace it isn't a switch: a retry then would fail
  at once and spend the retries, while the stream may still recover. The idle timeout covers that case.
- `InterruptOnNetworkSwitch` wraps every cloud model from `ProviderAccess` (chats, helpers, the
  reviewer, summaries). A stream that started before a switch ends with `NetworkSwitchedException`
  (an `IOException`), which cancels the HTTP call; the agent loop's retry sends the request again on
  the new network. The cost is the tokens already streamed for that turn; the alternative was waiting
  for the old network to be torn down or the idle timeout.
- After a switch, the pooled connections of the shared OkHttpClient and of providers' own clients
  (proxy, extra CAs) are evicted, so the retry doesn't pick up a dead socket from the pool.

## `fetch_url` and MCP

- `fetch_url` already has a 60 s total limit in real time (#72), so nothing changed there.
- The MCP client was a separate OkHttp client. It's now built from the shared `OkHttpClient` with
  `newBuilder()`, so it shares its connection pool (evicted at a switch) and dispatcher, keeping its
  own 5-minute read timeout for a server's quiet event stream. It still connects directly: the
  providers' proxy and CA settings are for model traffic only (see [network.md](network.md#scope)).

## Compaction failure notice

A summary written before a turn (the chat is near its window) ran inside `runCatching`, so a failure
was silent and the next request could overflow with a provider error that doesn't say why. A summary
inside a run (`AgentEvent.CompactionFailed`) only cleared the progress line. Both now set the chat's
notice: "Couldn't summarise earlier messages (reason). If the chat no longer fits the model, the next
reply may fail: try “Summarise the conversation” again, or start a new chat." The turn still runs.
The pre-turn path also no longer swallows cancellation (Stop during the summary used to carry on with
the turn).

## Linux kill reason

Android 12+ caps the child processes of all apps ("phantom processes") at 32 and kills children of
background apps that use a lot of CPU, with signal 9
([agnostic-apollo/Android-Docs](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md)).
Android's `java.lang.Process` reports a process killed by a signal as `0x80 + WTERMSIG`, "what all
Unix shells do" (libcore `ojluni/src/main/native/UNIXProcess_md.c`), so 137.

`AndroidKill` (core/linux): an exit code of 137 while Locus has no started activity
(`ProcessLifecycleOwner` below STARTED) is reported as "Killed by Android (phantom process limit)",
with the fix and the link above:

- Android 14+: Settings → System → Developer options → "Disable child process restrictions".
- Android 12L and 13: `adb shell settings put global settings_enable_monitor_phantom_procs false`.

`run_command` adds it to its status line. Services get a daemon thread blocked in `waitFor()` that
notes, when the service exits, whether that was such a kill; `linux_service_list` and
`linux_service_log` then say so. A service Locus stops itself is never counted (its last resort is a
SIGKILL too). A 137 in the foreground, or from `exit 137`, stays a plain exit code: the kernel's
out-of-memory killer also sends signal 9, and only the background case is put down to Android.

## Not done

- No setting to change the stream limits (Claude Code has `CLAUDE_STREAM_IDLE_TIMEOUT_MS`). If a local
  server needs more than 5 silent minutes, add a per-provider value.
- A network switch after a tool call has started streaming still ends the run with "The connection
  dropped" rather than retrying (unchanged `AgentLoop` rule).
- The Linux kill reason reaches the user through the agent (tool results). There's no notification of
  its own.

## Size limits

A stream can also fail by never ending a line or an event. A broken proxy or a hostile endpoint could send one, or a model could get stuck emitting the same tool argument. Before this change, `Sse.kt` and the provider parsers kept everything in memory until the phone ran out. `StreamLimits` caps it, well above any real reply (a long answer is under 1 MB):

| What | Limit |
|---|---|
| One SSE line (UTF-8 bytes; Ktor's `readUTF8Line(max)`) | 8 MB |
| One event's `data:` lines together | 16 MB |
| Everything one stream sends (bounds the Responses parser's list of finished items) | 64 MB |
| One tool call's streamed arguments (Anthropic `input_json_delta`, Chat Completions `arguments`) | 8 MB |
| One text, thinking or other content block (Chat Completions: the turn's text) | 16 MB |

Past a limit, the stream fails with a retryable `LlmException` ("The model's stream sent a tool call's arguments over 8 MB …"). The run retries it like other transient failures, and then reports it.
