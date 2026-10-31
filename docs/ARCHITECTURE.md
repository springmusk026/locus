# Architecture

## Principles

1. **Local first.** The agent loop, tools, storage and secrets all live on the phone. The only network traffic is to endpoints the user configured.
2. **The user decides.** Every tool call passes an approval gate (Allow / Ask / Deny per tool), and every call is written to an audit log. A read-only mode (`AgentConfig.readOnly`) is ready for unattended background runs.
3. **Reversible by default.** The agent's destructive file operations go through a trash. Nothing it deletes or overwrites is lost.
4. **Append-only history.** Conversation history is never edited. Providers such as Anthropic bind reasoning blocks to the exact earlier transcript, and stable prefixes also keep prompt caches warm.

## Layers

The layering follows Google's guide (UI layer → data layer; no domain layer until it earns its place):

- **UI**: one `ViewModel` per screen exposing a single `uiState: StateFlow`, built with `stateIn(WhileSubscribed(5_000))` and collected with `collectAsStateWithLifecycle`. Screens are stateless composables that take state and callbacks.
- **Data**: repositories in `core/data` over Room, DataStore and the workspace. `AgentRunner` is an app-scoped singleton that owns agent runs, so a run outlives the screen that started it.
- **Agent and LLM**: `core/agent` and `core/llm` are pure Kotlin/JVM modules with no Android dependency, so the loop and adapters are unit-tested on the JVM.

## Modules

```
app                  Activity, Navigation 3, Hilt application, share target
feature/chat         Home, chat, composer, live terminal/browser panel, sub-agent rows
feature/onboarding   First-run setup and the agent persona questionnaire
feature/settings     Settings hub: models, permissions, integrations, SSH, about
feature/workspace    File browser and viewers, import/export, trash
feature/activity     Runs, search and filters over every tool call
feature/analytics    Insights: tokens, speed, tools, approvals
feature/browser      Full-screen live browser and takeover
feature/library      Memory, skills, connectors and helper agents
feature/tasks        Scheduled tasks
core/data            Repositories, AgentRunner, foreground service, sign-ins, DocumentsProvider
core/database        Room: conversations, messages, audit log
core/datastore       Settings and encrypted secrets (DataStore + Tink)
core/agent           Agent loop, policies, reviewer, sub-agents, tools (pure Kotlin)
core/llm             Provider adapters over HTTP + SSE (pure Kotlin)
core/ondevice        On-device models (LiteRT-LM)
core/browser         Agent WebView, page script, browser tools
core/linux           Alpine-under-proot environment
core/model           Shared data types (pure Kotlin)
core/designsystem    Theme, components, logos and file icons
```

## The agent loop (`core/agent/AgentLoop.kt`)

```
user message → [request model] → assistant turn appended
                     ↑                 │ tool calls?
                     │                 ├─ no → finished
                     │                 └─ yes → validate input → policy (Allow/Ask/Deny) → run → results appended
                     └─────────────────────────────────────────────────────────────────────────┘
```

Guarantees, each covered by a test in `AgentLoopTest`:

- Every tool call gets a result, including when the run is cancelled mid-approval, so the transcript is always valid for the next request.
- Tool input is validated against the tool's schema before running. Anthropic's eager input streaming means the API no longer validates input, so invalid JSON is returned to the model as `INVALID_JSON`.
- Calls in a turn that stopped on `max_tokens` or `refusal` are never executed.
- Transient provider errors (429, 5xx, `overloaded_error`) are retried with backoff.
- A step limit stops runaway loops.

The full tool set is declared on every request, even tools the user set to Deny (they refuse at run time). Changing the declared tools mid-conversation would invalidate the provider's cached and signed prefix.

## Providers (`core/llm`)

Each provider is a small adapter over raw HTTP + SSE, with one HTTP stack (Ktor on OkHttp) and kotlinx.serialization:

| Adapter | Covers |
|---|---|
| `OpenAiChatModel` | OpenAI Chat Completions: OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp server, vLLM |
| `AnthropicChatModel` | Anthropic Messages API, `anthropic-version: 2026-10-21` |

**Why not the vendors' SDKs?** Research in October 2026 found:

- anthropic-java does run on Android, but it needs Jackson keep rules and class-derived schemas fail at runtime ([report](https://github.com/voitta-ai/virgil/issues/4)).
- openai-java throws at runtime when it finds an incompatible Jackson version ([README](https://github.com/openai/openai-java)).
- Shipping several Jackson/Guava-based SDKs together invites version conflicts.
- The closest open-source app, [Agora](https://github.com/newo-ether/Agora), uses raw adapters too.

The trade-off is that we maintain the wire formats ourselves. Request shapes are pinned by tests (`AnthropicChatModelTest`, `OpenAiChatModelTest`).

Anthropic specifics implemented:

- No sampling parameters (rejected from API version 2026-10-21).
- Adaptive thinking with `display: "updates"`, so progress notes show while the model works.
- `output_config.effort`.
- `eager_input_streaming` on tools.
- Server-side `fallbacks: "default"` on the first-party API.
- Assistant turns are stored as received (thinking blocks and signatures included) and replayed verbatim. After a mid-stream fallback, blocks before the `fallback` marker are dropped except text, per the API docs.

## Storage

| What | Where | Why |
|---|---|---|
| Conversations, messages, audit log | Room 3 (`androidx.room3`) with the bundled SQLite driver | Current Room major, coroutine-first. Schemas are exported to `core/database/schemas` for migrations. |
| Settings | DataStore 1.3 (JSON) | Recommended replacement for SharedPreferences. |
| Secrets | DataStore + `datastore-tink` `AeadSerializer`, keyset wrapped by an Android Keystore key | `androidx.security:security-crypto` is deprecated "in favour of existing platform APIs and direct use of Android Keystore" ([release notes](https://developer.android.com/jetpack/androidx/releases/security)). |
| Workspace | `filesDir/workspace`, trash in `filesDir/workspace-trash` | Fast and private. Shown to other apps through `WorkspaceDocumentsProvider`. `MANAGE_EXTERNAL_STORAGE` is restricted on Play and an assistant is not a listed use. |

## Background work

`AgentService` is a foreground service of type `specialUse`:

- **Why `specialUse`.** `dataSync` is limited to 6 hours per 24 hours on Android 15+ ([docs](https://developer.android.com/develop/background-work/services/fgs/timeout)), and no predefined type describes a user-started agent task. The manifest declares the subtype justification that Play review requires.
- **Lifetime.** The service stops itself when no run is active.
- **Approvals while backgrounded.** They arrive as a high-priority notification with Allow / Deny actions. Allow needs the device unlocked (`setAuthenticationRequired`).

## Build

AGP 9.5 with built-in Kotlin, Gradle 9.8, Kotlin 2.4.21, KSP 2.3, a version catalog, and convention plugins in `build-logic` modelled on [Now in Android](https://github.com/android/nowinandroid). compileSdk and targetSdk are 37, minSdk is 26. Versions were checked against Google Maven and Maven Central on 2026-10-31.

## Browser (`core/browser`)

The agent gets a browser of its own, separate from the rest of the app. The research behind it, the tool set and what was deliberately left out are in [design/browser.md](design/browser.md).

- **Profile.** It runs in a separate WebView profile (`WebViewCompat.setProfile`, androidx.webkit 1.18), so its cookies and logins never mix with anything else.
- **Tabs.** Each tab is a WebView. Pop-ups (sign-in windows, `target=_blank`) become tabs, at most 6, and only after a tap or an agent action. The screen that last showed the browser follows the active tab.
- **Off-screen.** When it isn't on screen, it is laid out at the display size, so pages render normally while the agent works in the background.
- **Page snapshots.** An injected script (`assets/locus-agent.js`) reports the page as data, and `Snapshot.kt` formats it the way Playwright MCP does: headings and interactive elements as lines with refs like `[e12] button "Sign in"`.
  - Refs are kept in a `WeakMap`, so the page's DOM is never touched.
  - The walk covers shadow DOM and same-origin iframes. Cross-origin frames are named, not read.
  - After an action, only the lines that changed are returned.
  - Password, card and one-time-code values never leave the page.
- **Actions.**
  - Clicks scroll the element into view, wait for it to hold still, and refuse disabled or covered elements with a hint.
  - When the browser is on screen, clicks are real touch events, which pages trust. Otherwise they are dispatched from script.
  - Typing uses React-safe value setters. Enter and Tab get browser-like default actions.
  - Every script call has a timeout.
- **Files.** Downloads are saved to `downloads/` in the workspace, using the browser's cookies (data: and blob: URLs included). `browser_upload` gives workspace files to a file input, or to the picker a button opens. `browser_save` writes Markdown, MHTML or a PNG.
- **Untrusted content.** Page content returned to the model, including events, logs and tab titles, is wrapped in `<untrusted_web_content>`, and a closing tag inside page text is defused. The system prompt tells the model that this text is information, never instructions (the prompt-injection guidance from Anthropic and OpenAI).
- **Confirmations.** `SensitiveActions` makes these ask the user even when browser tools are set to Allow (`Tool.mustConfirm`):
  - Clicks on anything labelled pay, buy, order, send, delete, transfer and similar.
  - Logins and payment forms.
  - Any form submit that isn't a search, including Enter in a field.
  - Accepting an outward `confirm()`.
  - Uploads.

  Typing into password, card or one-time-code fields, or typing a card number, is refused. `browser_ask_user` hands the page to the user for logins, CAPTCHAs and payments. Read-only runs may open, read, wait and look at tabs and logs, but can't download.

## Linux workspace (`core/linux`)

Alpine Linux 3.24.3 runs under proot, with no root:

- **Why it's allowed.** Android forbids an app from `execve`-ing files in its data directory (targetSdk ≥ 29). It does allow executables shipped in the APK as `lib*.so` (extracted to the native library directory with `useLegacyPackaging`). It also allows `mmap(PROT_EXEC)` of app data, which proot's loader uses to run the distro's programs.
- **How it was built.** The design follows Kai (Apache-2.0), an app with the same targetSdk and minSdk. `tools/build-proot.sh` builds proot and talloc from a pinned commit and checks 16 KB page alignment.
- **Workspace.** The workspace is bound at `/workspace`.
- **Commands.** Output from both streams is captured and capped. On timeout, Locus sends `SIGQUIT`, which proot honours, and then force-kills the process.
- **Licensing.** proot (GPL-2.0+) is a separate executable; see `core/linux/NOTICE` for the source offer.

## Learning: memory, skills, connectors (`core/agent/home`)

Everything the agent learns is a plain file under `filesDir/agent-home`. This is deliberately outside the workspace, so the agent can change skills and connectors only through tools that create drafts:

- **Memory.**
  - `memory/MEMORY.md` is an index with one line per topic. It goes into each new chat's system prompt.
  - Topic files are read on demand (`memory_read`, `memory_search`).
  - This is the pattern Claude Code's auto-memory uses. The user can see, edit and forget everything in Library → Memory.
- **Skills.**
  - Skills use the open Agent Skills format: `skills/<name>/SKILL.md` with `name` and `description` frontmatter, plus optional files ([agentskills.io](https://agentskills.io/specification)).
  - Only the catalog of names and descriptions is in context. `skill_use` loads a body when needed (progressive disclosure).
  - `skill_create` writes a **draft** that the user approves in Library → Skills.
- **Connectors.**
  - HTTP API definitions in JSON, plus MCP servers over Streamable HTTP (official Kotlin SDK 0.16.0).
  - Credentials are referenced by name and injected at call time from the vault. They are scrubbed from responses and never shown to the model. Calls can't leave the connector's host.
  - Operations marked write or destructive, and MCP tools not marked read-only, ask the user first.
  - `connector_create` writes a draft the user approves.
- **Scripts.** A connector may carry a small JavaScript `script` with `request(op, args)` and `response(op, status, text)` hooks. It runs in `androidx.javascriptengine` (V8 in a separate sandboxed process, fresh isolate per call, 16 MB heap, 5 s limit) with no network, file or secret access. Locus makes the HTTP call and injects keys itself.
- **On-device models** (`core/ondevice`): LiteRT-LM 0.19.0 runs Gemma 4 E2B/E4B (Apache-2.0, SHA-256-pinned downloads from the LiteRT community on Hugging Face). Tool calls go through the normal agent loop (`automaticToolCalling = false`), with a focused tool set.
- **Old versions.** Replaced or removed versions are kept under `history/`.
- **Stable prompts.** The memory index and the skill and connector catalogs are snapshotted into the system prompt when a conversation starts. The tool list is fixed, so the prompt prefix (and provider caches) never change mid-conversation. Changes reach the next conversation.

## SSH (`core/data/ssh`)

SSH uses ConnectBot's Kotlin `sshlib` (0.6.6, Apache-2.0). It is coroutine-based and supports Ed25519 and post-quantum key exchange.

- **Why not sshj.** sshj's usual Android setup replaces the platform's BouncyCastle provider, which can interfere with the Android Keystore.
- **Tink.** sshlib's JVM Tink dependency is excluded, and the app's `tink-android` of the same version provides it.
- **Host keys are pinned.** When adding a machine, the user confirms its SHA-256 fingerprint. Any other key is refused, and the user is told why.
- **Credentials.** Passwords and private keys live in the vault.
- **Tested on device.** On the S25 Ultra, the host key check against `github.com` returned GitHub's published Ed25519 fingerprint.

## Scheduled tasks (`core/data/tasks`)

Scheduled tasks are WorkManager periodic jobs with Hilt workers. Each run starts a new conversation in **read-only** mode (`AgentConfig.readOnly`):

- **Allowed:** calls where `Tool.isObservation` is true, such as opening and reading pages, GET requests, and connector reads.
- **Never allowed:** clicking, typing, writing or sending anything. This is the pattern Dots uses for proactive background work.

The answer arrives as a plain-text notification.

## Known limits and deliberate omissions

- **MCP OAuth** isn't implemented yet; MCP servers authenticate with a bearer token.
- **No phone control (Accessibility) in the Play build.** Google Play's Accessibility policy bars apps that "autonomously initiate, plan, and execute actions" ([policy](https://support.google.com/googleplay/android-developer/answer/10964491)). It can only ship in an F-Droid/GitHub flavor.
- **Markdown** is rendered minimally (code fences only) for now.
