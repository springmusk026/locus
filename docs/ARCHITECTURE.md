# Architecture

## Principles

1. **Local first.** The agent loop, tools, storage and secrets all live on the phone. The only network traffic is to endpoints the user configured or switched on (such as the optional models.dev catalogue, see "Choosing a model").
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
core/database        Room: conversations, messages, audit log, usage events
core/datastore       Settings and encrypted secrets (DataStore + Tink)
core/agent           Agent loop, policies, reviewer, sub-agents, tools (pure Kotlin)
core/llm             Provider adapters over HTTP + SSE (pure Kotlin)
core/ondevice        On-device models (LiteRT-LM)
core/browser         Agent WebView, page script, browser tools
core/linux           Alpine-under-proot environment
core/model           Shared data types (pure Kotlin)
core/designsystem    Theme, components, logos and file icons
core/markdown        Maths and Mermaid for Markdown in chat and the workspace
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
- Chats have no step limit: they run until done, stopped, or stopped by the task budget the user sets. Scheduled runs (nobody watching) and helpers keep a step limit as a backstop.
- A task budget (`RunBudget`, `TaskBudgetLoopTest`) is checked only between steps, after every call of the last step has its result: reaching a limit asks `BudgetGate` (Continue adds one more increment of the same size; Stop ends with `Outcome.BudgetStopped`).

Task budgets: `AgentRunner` makes one `RunBudget` per task (the job a user message starts, helper-report continuations included) from the chat's own budget (keys in its restriction set) or the default in Settings. Every request is recorded by the `AgentLoop` that made it, the chat's and its helpers' (they get the budget through `ToolCallContext.budget`). Budget tokens are input plus output with cache reads at 0.1; cost uses models.dev prices and applies only when the chat model's are known; time excludes waits for the user (approvals and the pause itself). Helpers never ask: once the budget is reached they stop after their current step with a partial report, and `SubagentEngine` refuses new ones, so the parent pauses at its next step. Anthropic chats on listed models also send `output_config.task_budget` with the configured token budget, unchanged by Continue so the cached prefix holds.

The full tool set is declared on every request, even tools the user set to Deny (they refuse at run time). Changing the declared tools mid-conversation would invalidate the provider's cached and signed prefix.

### Steer and queue (`AgentConfig.atStepBoundary`)

The user can keep typing while a run works. A message sent then is queued, not sent, and joins the transcript at the next **step boundary**: after a step's tool results, before the next model request. It is appended as an ordinary user turn at the end, so the system prompt, the tool list and every earlier message stay byte for byte the same and the provider's cache prefix holds (`StepBoundaryTest`, and `SteerAndQueueTest` on the wire). There are no mid-conversation system messages. Nothing is taken after the last allowed step, after a refusal, or after a proposed plan; if the run ends first, the runner starts the next turn with the queue. The safety reviewer counts a queued message as something the user asked for.

- **Order.** An open `ask_user` question takes typed text first; an approval card doesn't block queueing. Messages keep their order, and each can be edited or cancelled until it's taken.
- **Durable.** `MessageQueue` keeps each chat's queue as a small JSON file in `noBackupFilesDir/queued` (no database change; a restored backup never brings back a stale queue). A taken message stays on disk until it is stored in the chat under the same id, so a crash in between neither loses nor duplicates it. After Stop, an error or a restart, the chat offers Send or Discard; queued messages never start a run on their own after one of those.
- **Helpers.** Steer on a working helper's row (`SubagentEngine.steer`) uses the same hook: the note joins the helper's own transcript at its next step, with a hidden line saying it came from the user. A note the helper never read (it finished first) is queued in the parent chat, prefixed with the helper's name, so nothing typed is lost. Helper notes live in memory only; helpers cut off by a restart aren't resumed anyway.

### Side questions (`/btw`, `SideQuestions`)

Claude Code's `/btw` is a side question, not a note to the task: it is answered "from what's already in the conversation", with no tools, and "the question and answer never enter the conversation history" (code.claude.com/docs/en/interactive-mode, "Side questions with /btw"). Locus does the same. `/btw <question>` in the composer, or the composer's Aside switch (shown while a task runs), asks one; `/btw` alone shows the last answer again. Instructions for the task still go through the queue above.

- **Read-only copy.** `sideQuestionRequest` sends the chat's frozen system prompt, its tool list and its history exactly as a step sends them, then one user message marked as a side question (with the last few earlier side questions replayed inside it). The prefix is the task's own, so the request reads the chat's prompt cache (`SideQuestionTest` compares it with the held step on the wire). Calls still running get a placeholder result in the copy only; a copy ending on the user's message gets the question in that message.
- **Apart from the task.** Nothing is stored in the chat or queued, the run is never stopped, and the agent never sees the question. Tool calls the model writes anyway are not run; the card says so. Exchanges live in memory, 20 per chat, and Clear forgets them.
- **On-device chats** ask the helper model with a text excerpt instead, since the phone's model is busy with the task.
- Tokens are recorded under "Side questions" in Insights.

### Per-chat limits (`ChatPolicy`)

Before the user's policy, `AgentConfig.restrict` asks `ChatPolicy` whether the chat's own limits refuse the call, read fresh for every call so a change applies mid-run. They only ever refuse; what they let through still meets Allow / Ask / Deny and `Tool.mustConfirm`. Helpers get the same `restrict` through `ToolCallContext`, so they follow the chat. In order:

1. **Tools switched off** in the composer's tools menu (`ToolGroup` keys and connectors).
2. **The chat's mode** (`ChatMode`). Every tool has a capability in `ToolCapability.BY_TOOL` (`core/model`): READ, LOCAL (workspace, Linux, Locus's own memory, skills and helpers) or OUTWARD (network writes, deploys, mail, SSH, browser actions, other apps). A tool missing from the map counts as OUTWARD, and `ToolCatalogCapabilityTest` fails until it's added.
   - *Read-only* allows a call only when `Tool.isObservation(args)` says it just looks, the same rule read-only background runs use (so `fetch_url` with GET is a read, and `notify_me`, which reaches only the user's own topic, is allowed).
   - *Workspace* also allows LOCAL tools. Shell commands in Linux are allowed, including any network use from inside them.
   - *Full* adds nothing.
3. **Plan mode**: observations only until the user approves a plan.

The mode and the switched-off tools are kept in the same per-chat set (the `disabledTools` column; the mode as `mode:read_only` or `mode:workspace`), so neither needed a schema change. Neither changes the system prompt or the tool list.

`ask_user` (`AskUser`, `QuestionDesk` in `core/agent`) waits on the user like an approval does, but it is a tool, not a gate: always allowed and counted as observation (so it works in plan mode), its answer is the tool result. `QuestionDesk` holds the open questions app-wide (`UserQuestions` in `core/data`), times out after 30 minutes with "no answer", and routes a helper's question to its parent chat with the helper's label. Read-only runs get an immediate "the user isn't available". `AgentRunner` mirrors open questions into `RunState.questions`; `AgentService` posts them with option buttons and a RemoteInput reply that needs no unlock (it's information, not a permission). A question left open when the process dies gets "the user didn't answer (Locus was restarted)" from `recoverTranscript`, and the chat offers Continue. The user's answer is their own words, so it is not marked untrusted.

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

### Choosing a model

- **One rule.** `ProviderConfig.effectiveModel(override)` (`core/model/ModelChoice.kt`) is how a chat's model is resolved: the runner's requests, the chat header, the composer and the model sheet's tick all use it, so they can't disagree.
- **Lists.** `ModelDirectory` (`core/data`) fetches every provider's model list when a model sheet opens and shares it between home and chats for 30 minutes. Favourites and recent picks are `ModelRef` lists in `AppSettings` (DataStore).
- **Model details (optional).** `ModelCatalog` (`core/llm`) reads the [models.dev](https://models.dev) catalogue, `https://models.dev/api.json` ([repo](https://github.com/anomalyco/models.dev), MIT). Checked in October 2026: about 6 MB of JSON (0.6 MB gzipped) of providers keyed by id, each with an optional `api` base URL and `models` keyed by id with `name`, `tool_call`, `reasoning`, `modalities.input` and `limit.context`. Locus keeps only those fields in `cacheDir`, revalidates at most once a day with `If-None-Match`, waits an hour after a failed fetch, and works from the cached copy (or without details) offline. It's off until the user turns it on, because it's the one request to a host they didn't configure; the request carries no key, chat content or identifier. A model is matched by the endpoint's own catalogue entry (known hosts, or the catalogue's `api` URL), else by the same id from the model's maker, else by the id without a `vendor/` prefix; local servers get no endpoint match. What the provider's own list reports (display name, context window) takes precedence. No new library: the fetch uses the app's Ktor client and kotlinx.serialization.

## Storage

| What | Where | Why |
|---|---|---|
| Conversations, messages, audit log | Room 3 (`androidx.room3`) with the bundled SQLite driver | Current Room major, coroutine-first. Schemas are exported to `core/database/schemas` for migrations. The bundled SQLite (3.50) includes FTS5, used for message search ([design/search.md](design/search.md)). |
| Settings | DataStore 1.3 (JSON) | Recommended replacement for SharedPreferences. |
| Secrets | DataStore + `datastore-tink` `AeadSerializer`, keyset wrapped by an Android Keystore key | `androidx.security:security-crypto` is deprecated "in favour of existing platform APIs and direct use of Android Keystore" ([release notes](https://developer.android.com/jetpack/androidx/releases/security)). |
| Backups | One `.locusbackup` file the user saves with the system picker: a zip encrypted with Tink Streaming AEAD (AES256_GCM_HKDF_1MB) under a PBKDF2-HMAC-SHA256 key (600,000 iterations). The database is copied with `VACUUM INTO`. A restore is verified, staged in `noBackupFilesDir`, and swapped in at the next start. The vault is left out unless the user includes it. | No server, and `allowBackup` stays off. See [design/backup.md](design/backup.md). |
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
  - **Import** (`SkillImport`, `GitHubSkillFetcher`) also writes a draft, from a GitHub link (contents API, unauthenticated or with the GitHub integration's token; refs with slashes are resolved by trying each split) or a zip (`java.util.zip`). Every path is normalised and must stay inside the skill folder (zip slip); symlinks and submodules are refused; files, folder depth and bytes are limited while downloading or inflating. `SKILL.md` is validated against the spec (name, description, compatibility, no XML tags). Provenance (link, ref, path, commit or zip name) is stored beside the skill as `.locus-source.json`. Approving over an active skill of the same name keeps the old one in `history/`.
- **Connectors.**
  - HTTP API definitions in JSON, plus MCP servers over Streamable HTTP (official Kotlin SDK 0.16.0).
  - Local MCP servers (stdio): a connector with a `local` block (command, args, env naming vault secrets) runs under proot via `LinuxEnvironment.startPiped` and talks over its stdin/stdout with the SDK's `StdioClientTransport`. `LocalMcpServers` (`core/data/mcp/LocalMcp.kt`) starts it on first use, keeps it while calls are in flight, stops it after 10 idle minutes, retries a failed start once, reports crashes with the stderr tail, and publishes health for Library. Shutdown follows the stdio spec: close stdin, wait, then force. Same `mcp_list_tools` / `mcp_call` tools, so the tool list stays fixed.
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

## Rich replies (`core/markdown`)

Chat replies, kept reasoning and the workspace Markdown preview typeset maths and draw Mermaid diagrams. Everything runs on the phone; nothing is fetched.

- **Maths detection** (`MathSyntax.kt`) runs before the Markdown parser. It finds `$…$` and `\(…\)` inline, `$$…$$` and `\[…\]` display, outside code fences and code spans. Single dollars follow Pandoc's rules (no space inside the delimiters, no digit after the closer), plus: the opener may not follow a letter or digit, and the first `$` after an opener must close it. So "$5 and $10", "$5-$10" and "US$5" stay text.
- **Inline formulas** become one private-use character each. The renderer's annotator swaps it for inline content measured by the LaTeX library, with the source as the alternate text, so copying gives LaTeX back. A formula that can't be measured shows as its source.
- **Display formulas** on lines of their own become ```` ```math ```` fences (GitHub's syntax, also accepted from the model) and render as a centred block that scrolls sideways. A formula with parse errors shows as a code card.
- **Mermaid** fences render through `MermaidRenderer`: one offscreen WebView, laid out like the agent browser's, loading the bundled page through `WebViewAssetLoader`. All other requests get an empty 403, navigation is refused, file and content access and storage are off, a CSP allows only same-origin scripts, and there is no JavaScript interface: the app calls `locusRender` and polls `locusTake` with `evaluateJavascript`. Mermaid runs with `securityLevel: strict` and SVG labels (HTML labels would taint the canvas), and the page returns a PNG.
  - One render at a time; a request whose card scrolls away is cancelled while it waits. Pictures are cached by a SHA-256 of renderer version, theme and source (24 MB LRU); failures are remembered for the process. The WebView is destroyed after 45 s idle, on memory pressure, after a 12 s hang or when its renderer dies.
  - The card shows the picture at natural size (sideways scroll, tap to zoom), with View source and copy. Unsupported or broken diagrams show the code card.
- **Streaming.** `$`-style maths shows as source until the reply is saved, so delimiters arriving mid-stream never flicker. Mermaid and math fences render once their closing fence arrives; the saved reply then reuses the cached picture.

Why these dependencies (checked 2026-10-31):

- **LaTeX: `io.github.huarangmeng:latex-renderer` 1.5.6** (MIT, native Compose, KaTeX fonts). It measures formulas synchronously for `InlineTextContent`, which inline maths in a Compose `Text` needs, and covers `aligned`, `cases`, matrices, `\text` and the like. 1.5.6 is the newest release older than two weeks; 1.6.2 adds faster inline drawing and is worth taking later. Alternatives: `ru.noties:jlatexmath-android` 0.2.0 is unmaintained since 2020 and GPL-2.0 with a linking exception; KotlinTeX (`io.github.darriousliu:katex-core`) is archived; KaTeX in a WebView can't sit inline in text and costs a WebView per formula. The Markdown renderer (mikepenz 0.46.0) parses GFM math nodes but only shows their source, and has no math extension; its annotator and inline-content hooks are enough.
- **Mermaid: `@mermaid-js/tiny` 12.2.0** (MIT), bundled as an asset. Mermaid needs a DOM to measure text, so a WebView is the only faithful option. The tiny build is 2.8 MB (about 0.75 MB compressed) against 5.5 MB for the full one; it leaves out mindmap and architecture diagrams.
- Licences and hashes are in `core/markdown/NOTICE.md`.

## Known limits and deliberate omissions

- **MCP OAuth** isn't implemented yet; MCP servers authenticate with a bearer token.
- **No phone control (Accessibility) in the Play build.** Google Play's Accessibility policy bars apps that "autonomously initiate, plan, and execute actions" ([policy](https://support.google.com/googleplay/android-developer/answer/10964491)). It can only ship in an F-Droid/GitHub flavor.
- **Mermaid** uses the tiny build, so mindmap and architecture diagrams (and KaTeX inside diagram labels) show as source.
