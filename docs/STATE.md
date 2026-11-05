# Where Locus stands

Snapshot as of 2026-11-05 (release 0.1.6, through #192). Read this first; `ROADMAP.md` has the full feature history, `CHANGELOG.md` what changed in each release, and `STATUS.md` handoff notes and device-testing etiquette.

## In one paragraph

Locus is a local-first, bring-your-own-key agent app for Android: 26 Gradle modules, about 104k lines of Kotlin plus 53k of tests, MVVM + Jetpack Compose. There are about 2,810 `@Test` methods: about 2,750 JVM unit and screenshot tests, 36 in the benchmark harness and 21 instrumentation tests. It talks to Anthropic, OpenAI (Responses and Chat Completions) and any OpenAI-compatible server, or runs models on the phone (Gemma 4 through LiteRT-LM, GGUF models through llama.cpp). The agent can work in a real Linux shell (proot Alpine, opt-in), drive an in-app browser, reach your servers over SSH, use MCP servers and keyed integrations, remember things, write its own skills and connectors, plan, keep a todo list, and hand work to sub-agents. Chats can be grouped into projects, and a task that went well can be saved as a workflow. You can talk to it hands-free (voice mode), use a real terminal in its Linux, reach it from Telegram, the home screen, the launcher and other apps, and let it check GitHub for updates. Every action is checked against your permissions and the run's limits, and logged. The app has been tested daily on a Galaxy S25 Ultra (Android 17), but most of what shipped in 0.1.4, 0.1.5 and 0.1.6 hasn't been tried on it yet (see below).

## What's built

| Area | What works |
|---|---|
| Models | Anthropic Messages (prompt caching with a 1-hour cache on the tools and system prompt, server compaction read from the Models API's capabilities, effort), OpenAI Responses (stateless, encrypted reasoning replay), OpenAI-compatible (OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM…), on-device Gemma 4 E2B/E4B via LiteRT-LM (vision, KV reuse, the backend that really ran, a local benchmark, RAM warnings, unload), and GGUF models via llama.cpp: a catalogue of small tool-calling models, Hugging Face search, URL and file import, background downloads checked by SHA-256, tool calls constrained by grammar (docs/design/llamacpp.md). Backup models across providers and backup keys per provider, with `Retry-After` honoured (docs/design/fallbacks.md). Capability checks (tools, vision, reasoning, context window) from the provider's list, models.dev and built-in rules, before a chat, helper or backup is routed to a model (docs/design/model-capabilities.md). Per-provider network settings: extra headers, proxy, custom CA certificates. Model lists are fetched automatically, with real provider and model logos. |
| Chat | Streaming Markdown with code highlighting, one answer per turn, live thinking ("Thought for Ns"), token line per reply, context ring, compaction before a message and inside a running task, with recovery from "too long" errors (docs/design/compaction.md), full-text search over every branch (docs/design/search.md), pins, branches and forks (docs/design/branching.md), follow-up suggestions, auto titles, attachments, voice, share-to-Locus, files the agent sends as cards, LaTeX maths and Mermaid diagrams, live sandboxed HTML and SVG artifacts (docs/design/artifacts.md), "What was sent", read aloud, and sharing a chat as Markdown or PDF. Under each turn: a changes card with diffs and per-file undo, verification cards for build and test runs (docs/design/change-tracking.md), and a task receipt (docs/design/task-receipt.md). Voice mode: a hands-free loop on Android's speech services with barge-in (docs/design/voice-mode.md). |
| Composer | Shared by chat and home: model, thinking level, tools/connectors on or off, plan mode, chat mode, task budget, attachments, Run a workflow. Stays open while the agent works: messages queue and join after the current step (steer and queue); `/btw` or Aside asks a side question answered apart from the chat. A reviewer-blocked call you run anyway joins at the next step. |
| Agent | Allow/Ask/Deny per tool; new chats declare 47 everyday tools and find the rest with `find_tools` and `call_tool`, or the provider's own tool search on Anthropic and OpenAI Responses (docs/design/tool-discovery.md); plan mode; `todo_write`; `ask_user`; optional safety reviewer; per-chat modes (Read-only, Workspace, Full); task budgets; no step limit in chats (scheduled runs stop at 40); read-only calls in a row run together; long output of any tool saved to `.locus/out/` (docs/design/tool-output.md); reflection after long tasks; a completion check that asks once to finish open work; one run per chat at a time; remembered approvals per chat or for 30 days (docs/design/remembered-approvals.md); recovery after the app is killed. Every run-level limit (read-only, chat and project limits, a workflow's scope, taint, loaded skills' manifests) is one `RunScope`, which helpers inherit. |
| Sub-agents | `agent_spawn/send/wait/status/cancel/create`; researcher, coder, explorer, ops and browser profiles plus your own (`agents/<name>/AGENT.md`); up to 3 at once, one level deep; approvals labelled by helper; transcripts you can open; Steer on a working helper. |
| Projects | Chats grouped with their own workspace folder (file tools and `/workspace` rooted there), instructions, memory scope, permissions that only narrow, allowed connectors, and default and helper model; fixed per chat when it starts. Projects strip on home, a project page, a chip under each chat's title (docs/design/projects.md). |
| Workflows | "Save as workflow" from a receipt or a reply drafts named inputs, steps, tools, approval points and success checks for review; runs from the composer or Library, on demand or on a schedule. Tools outside the workflow and its approval points ask (docs/design/workflows.md). |
| Linux | Opt-in: downloaded only when you choose it, in setup or from the Linux card in Home → Files (install, progress, repair, reinstall, remove; docs/design/linux-setup.md). Alpine 3.24 under proot, `apk`, Python, git, Node; a health page with fixes and a package list (docs/design/linux-health.md); an interactive terminal with up to six tabs (docs/design/terminal.md); background services previewed in the browser; a live terminal panel; a syntax check after the agent edits code. Locus's own processes are non-dumpable and hidden from Linux, along with the binder devices (docs/design/linux-isolation.md). |
| Browser | Agent-driven WebView you can watch and take over, with its own tabs per chat: accessibility-style snapshots with diffs, form filling, waits, tabs for pop-ups, JS dialogs, downloads and uploads, page saving, console and network logs (docs/design/browser.md). Logins, payments, submits and uploads ask first, and the check fails closed. |
| Reach | SSH (keys, pinned host keys, SFTP, detached jobs), MCP over HTTP with OAuth and full argument schemas, local stdio MCP servers inside Linux, HTTP connectors with vault secrets and sandboxed JS hooks. A project can run its commands on one of your SSH machines, with preview-first SFTP sync and detached jobs that reattach (docs/design/remote-execution.md). |
| Integrations | Firecrawl, Tavily, Brave, Exa (search), GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, Email (SMTP/IMAP), ntfy, Telegram, weather, Wikipedia. |
| Memory & skills | Visible, editable memory with ranked full-text search in any language (docs/design/memory-search.md), project memory, and provenance (who saved a note, when, and whether the run had read outside content); Agent Skills format with drafts you approve; skill import from a GitHub link or a zip, with a review of the skill's manifest; chat-history tools; persona files built from an onboarding questionnaire. |
| Always on | Scheduled tasks (read-only background runs, 8-minute budget, conditions for charging, Wi-Fi and battery), every run ending with a notification and a recorded outcome (docs/design/scheduled-runs.md); scheduled workflows; results by notification, ntfy and Telegram. Telegram remote: approve background tasks by button, or start tasks from your bot for a set time (docs/design/telegram-remote.md). Live Updates on Android 16+. |
| Battery & heat | Thermal status, battery, battery saver, network and memory pressure read as `DeviceConditions`; non-urgent work waits while hot or in battery saver; light mode for on-device replies; idle on-device models unloaded; downloads wait for Wi-Fi (docs/design/battery-thermal.md). |
| Ways in | Launcher and Direct Share shortcuts, a quick settings tile, "Ask Locus" on selected text, a home-screen widget, the default assistant role, permission-gated automation intents, and `calendar_read`, `contacts_pick` and `photos_pick` (docs/design/android-bridge.md). |
| Screens | Onboarding (9 steps), home, chat with side panels, projects, Library (memory, skills, workflows, connectors, helpers), Tasks, Workspace with file viewers, Activity, Insights (with cache reads and writes, and estimated spend), Settings hub (including Battery & heat and Diagnostics). |
| Backup | Encrypted `.locusbackup` files through the system picker: chats, settings, memory, skills, projects and workflows, the workspace optionally, keys only when you switch them on. Restores are verified, previewed, and applied all-or-nothing at the next start, the vault included; a failed restore rolls everything back and says so (docs/design/backup.md). |
| Safety | Keys encrypted with Tink and the Android Keystore and never shown to the model, never wiped on a Keystore failure; taint tracking and egress control, and a disclosure rule: after a task reads private data, a new site with anything in its address asks (docs/design/taint.md); `fetch_url` checks every DNS answer at connect time (docs/design/network.md); provider addresses, proxies and SSH host keys sealed; `fetch_url` refuses private addresses and caps responses; skill security manifests (docs/design/skill-manifests.md); tool permissions, the reviewer's settings and connector approvals sealed with a Keystore HMAC; release and debug builds signed apart, the debug app a separate `dev.locus.debug`; share target and notification intents hardened; no AccessibilityService by design. |
| Diagnostics | A local log of crashes, Android exits (ANRs, native crashes, low memory), errors caught on the app scope and storage problems, scrubbed of secrets, shared only on request; a self-test (keys, database, storage, notifications, Linux, an opt-in provider check) and a Storage screen (docs/design/diagnostics.md). |
| Releases | One APK per ABI (arm64-v8a, x86_64), each with its own versionCode, reproducible byte for byte apart from the signature from 0.1.6 (both 0.1.6 APKs pass `apksigcopier compare`; docs/FDROID.md), signed in a job apart from the build and with a build provenance attestation (docs/RELEASING.md); a release build checks that classes looked up by name survive R8 (`app/r8-kept-names.txt`). An opt-in update check verifies a new release's checksum and signing key and hands it to Android's installer (docs/design/updates.md). |
| Testing | JVM unit tests on every PR with a Kover report; an instrumentation suite with a scripted model (nightly and on demand on an emulator); an agent benchmark of 38 tasks with deterministic graders (`:bench`, docs/benchmarks/README.md), with a first real-model run published. See docs/TESTING.md. |

## Tested on the phone vs. not yet

Tested on the device: chat on Anthropic-format and on-device models, tool calls and approvals (in-app and from notifications), Linux and SSH, Linux on the proot built on Linux (#161, confirmed on 2026-11-03 via #171: install, a command, a service, the terminal, health and Repair), the browser panel, MCP OAuth up to the consent page, onboarding end to end, search and pins, compaction before a message, file viewers, Activity, logos, sub-agents (two researchers in parallel, transcript sheet, Activity labels, the v6 database upgrade), ChatGPT sign-in with tool use, and the browser panel's full-screen handoff in both directions. The Linux `/proc` leak was confirmed on the phone before it was fixed.

Shipped in 0.1.6, unit-tested, and **not yet tried on the phone** (each PR has a phone checklist):

- GGUF models (#178, #179): import, the catalogue, Hugging Face search, URL downloads, pause and resume, tool calls. The import crash on Android's regex engine was found on the phone and fixed (#182).
- The self-test and the Storage screen (#177); helper partial results and the time limit (#175).
- Sealed skills, helpers and persona, `O_NOFOLLOW` workspace writes and hidden MCP server secrets (#174); quieter Linux health and Start when Locus opens (#173).
- The calmer chat: the work section, run dots, the bottom dock, the changes card's history (#180); ChatGPT failure details and Switch model (#181).
- The UI audit (#190, #192): each screen at 200% font and in dark theme, and the behaviour changes in #192's checklist (restore without room, a project without a name, blocked undo, Choose model, cancelling a remote link).

Shipped in 0.1.5, unit-tested, and **not yet tried on the phone**:
- Voice mode (#107); the interactive terminal (#111); Linux in the Workspace and its health page (#118 and later).
- Telegram approve-by-button and remote mode (#110, #126), also not yet against the live Bot API.
- Ways in: shortcuts, the tile, "Ask Locus" on selected text, the automation intents and phone tools (#104); the widget and the default assistant role (#108).
- Interactive artifacts (#103); remembered approvals (#109, #120, #125); the disclosure rule and Allow site.
- The completion check and estimated spend (#105); ranked memory search, provenance and Memory read (#106).
- Scheduled tasks you can follow (#117); one run per chat (#128); stream timeouts and network switches (#113); full-page browser screenshots.
- The opt-in update check and its install path (#155).
- The design-system migrations: each moved screen needs a look in light and dark.

Shipped in 0.1.4, unit-tested, and **not yet tried on the phone**:
- Compaction inside a running task and recovery from "too long" errors (#73).
- Backup models and backup keys (#67); model capability checks and the "Can't use here" chips (#88).
- The changes card and undo (#68), verification cards (#74), task receipts (#83).
- Taint and the `fetch_url` limits (#72); the browser gate failing closed.
- The Linux isolation fix and sealed permissions (#78): run `tools/verify-linux-isolation.sh` in Locus's terminal and the `run-as` check in docs/design/linux-isolation.md.
- Release signing and the separate debug app, the share target and notification intents (#69).
- The vault in the restore journal (#71), the all-or-nothing restore (#64, #65), and the Keystore reset sheet and recovery (#79).
- Scheduled-run outcomes and the background-start guard (#76); Diagnostics (#77).
- Long-output files, parallel reads, and Anthropic 1-hour cache hits in the context sheet (#81).
- Per-ABI release APKs and their versionCodes (#80).
- Projects (#86, #89) and the v15 database upgrade; workflows and scheduled workflows (#94, #95).
- Run anyway at the next step (#92).
- From #100: tool discovery and provider tool search, skill manifests and their import review, `RunScope` inheritance, Battery & heat, and remote execution (Doze with a remote job running needs a check).
- The instrumentation suite (#84) compiles; it hasn't been run on a phone yet.

Older, still untried on the phone or with real accounts:
- `ask_user` (the question card, the notification's option buttons and Reply, the restart path); the safety reviewer, helper approvals and helper drafts; `agent_send`.
- The OpenAI Responses adapter (no OpenAI key yet).
- GitHub, Tavily, Brave, Exa, Vercel, Netlify, Cloudflare, Supabase, Render with real tokens; email with a real mailbox.
- Reflection after a long task; Linux background services; continuing after a mid-run kill.
- Message branches and forks; full-text search; exact Insights; editable helper prompts.
- Anthropic prompt-cache breakpoints (cache hits not yet confirmed against the live API with `cache_read_input_tokens`).
- Android 17 local network permission. Needs an Android 17 device (enforced for targetSdk 37); on Android 16, `adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.locus` (`dev.locus.debug` for a debug build) and a reboot only shows the blocked-connection message. Agent tool calls to a LAN SSH machine or MCP server saved before that change, and the agent browser opening LAN URLs, don't prompt; they fail until the permission is allowed.
- Encrypted backup and restore on the phone (`VACUUM INTO` through Room, the system picker, PBKDF2 speed, the restart).
- Android 16 Live Updates, steer and queue, task budgets, local MCP servers, skill import, network settings, LaTeX and Mermaid, "What was sent", read aloud and share, per-chat modes, the on-device benchmark, no step limit in chats, and the polish pass (#42-#44).

## What's left (known gaps)

- **Phone checks** for everything above. Most of the November work is unit-tested only.
- F-Droid: submitted on 2026-11-05 as fdroiddata MR !55897 (https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55897) and closed the same day under Quality point 6 of the Inclusion Policy (actively developed for at least 3 months; the repo was created on 2026-10-31). Nothing was raised about the recipe, the build or the anti-features. 0.1.6 reproduces against its release APKs: `apksigcopier compare` passed for arm64-v8a and x86_64 on a fresh clone of the tag (docs/FDROID.md). Resubmit on or after **2027-01-31** with the same recipe, updated to the latest release, from the fork's `dev.locus` branch. Until then, a self-hosted F-Droid repo on GitHub Pages and Obtainium are documented but not started. Developer verification for `dev.locus` isn't registered yet; IzzyOnDroid isn't submitted and likely has a similar newness bar on top of its AI policy.
- Google Play is researched, not started, and needs the owner's decision (docs/research/google-play.md).
- Native Gemini adapter (deferred until there's a key to test against).
- MCP: Client ID Metadata Documents and step-up on `insufficient_scope`.
- Places search needs a decision (Nominatim policy).
- Accessibility: no full TalkBack walk-through yet; no real two-pane tablet layout.
- Backups: no Argon2id yet (PBKDF2 only); no scheduled or automatic backups.
- Linux: `/workspace` and `/tmp` are shared on purpose, Linux processes can see each other (local MCP servers are hidden from them), and memory isn't sealed (permissions, skills, helpers and persona files are). Locus and its Linux share one Android uid, so these protections aren't a kernel boundary and same-uid races against Locus's path checks remain possible (SECURITY.md). Real isolation would need Linux under another uid; a companion APK is the realistic option and isn't scheduled (docs/design/linux-isolation.md).
- Taint is per run, not per value, and `run_command` can reach the network itself and isn't gated by taint (docs/design/taint.md). `fetch_url`'s DNS rebinding gap is closed (docs/design/network.md).
- Skill manifests: `run_command`'s network access isn't confined to the declared hosts, Locus doesn't install declared dependencies, and there's no manifest editor (docs/design/skill-manifests.md).
- Compaction: no early background summary at about 70%, no OpenAI Responses server compaction, no reserved buffer for the summary (docs/design/compaction.md).
- Fallbacks: one default chain, no per-chat chain; a model out of quota is tried again on the next turn (docs/design/fallbacks.md).
- Tool discovery: the core set isn't based on usage yet, and replaying deferred-tool turns across models is unverified against the live APIs (docs/design/tool-discovery.md).
- Remote execution: whole files are synced (no deltas), no file watching or port forwarding, one machine per project (docs/design/remote-execution.md).
- Workflows are linear (no conditionals or loops), and chats from before receipts can't be saved as one (docs/design/workflows.md).
- Projects: no per-project skills or helper types, no project knowledge beyond the folder (docs/design/projects.md).
- Diagnostics: native crash tombstones aren't read (docs/design/diagnostics.md).
- The benchmark has one published real-model run so far (`claude-haiku-5-5`, 2026-11-05: 97.4% pass@1 at k = 3, $0.19 for all 114 trials; the one failing task was a grader bug, since fixed). The standard tier is close to saturated for a current model; a hard tier of 15 tasks is built and verified with scripted transcripts but not yet run against a real model. On the JVM it runs Linux commands in the host shell and leaves out the browser and phone intents (docs/benchmarks/README.md).
- The instrumentation suite runs nightly and on demand, not on PRs, until it has been reliably green.

## What can be done next

From the roadmap ("Recommended next (after 0.1.6)"), roughly in order of value:
1. Phone checks for everything listed above as built but untried, starting with a GGUF model (and small models' chat-only chats and loop breaker), the self-test, restore, voice mode, the terminal, the update check, and a long task on a non-Anthropic model.
2. F-Droid: resubmit on or after 2027-01-31 (MR !55897 was closed as too new); meanwhile, optionally, a self-hosted F-Droid repo or Obtainium (documented, not started); register for developer verification; IzzyOnDroid if wanted (similar newness bar).
3. Harder benchmark tasks that separate current models (the first real-model numbers are published: docs/benchmarks/README.md#results), a private held-out set, and runs of more models.
4. More local models: llama.cpp on the GPU (OpenCL on Adreno), KleidiAI, images (mmproj); Gemini Nano where supported; Matrix as another reach-me channel.
5. Linux under its own uid (a companion APK) stays deferred; the in-uid hardening shipped in 0.1.6 (docs/design/linux-isolation.md).
6. Google Play: researched, not started; the owner decides (docs/research/google-play.md).

## What can be improved

- **Engineering:** JVM Compose tests for the composer; coverage thresholds (Kover reports, with none set yet); static analysis (detekt or ktlint with Compose rules).
- **Analytics:** replies from before v9 still have their model and speed inferred. Usage events aren't exported or shown per chat yet. Insights shows estimated spend from the opt-in models.dev prices.
- **Cost control:** sub-agents can use many tokens. Task budgets cap a whole task, and receipts show a task's estimated cost; still worth considering: a default helper model for researchers and the cost of each helper run.
- **Large chats:** paging the transcript and Activity for chats with thousands of steps.

## Subscriptions (Codex, Antigravity)

Researched 2026-10-31:
- **ChatGPT (Plus/Pro): allowed, built, and working on the phone** (sign-in, model list, chats with tools: writing and running a script, browsing). OpenAI's "Sign in with ChatGPT" program lets open-source, locally run apps use a person's ChatGPT plan: "ChatGPT plan usage is available to all open-source partners and selected private clients" (developers.openai.com/siwc/quickstart). The app registers its own client through OAuth with PKCE, then calls the public `api.openai.com/v1/responses` with the user's token. Reusing Codex CLI's client id or `chatgpt.com/backend-api` is not allowed, and Locus doesn't. A paid or commercially distributed build would need OpenAI's interest form first.
- **Google Antigravity: not allowed, so Locus won't support it.** Its terms say "Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity OAuth) is a breach of this Agreement" (antigravity.google/terms, §6), and Google suspended accounts for it in February 2026. The supported route is a Gemini API key from Google AI Studio (free tier), which Locus already offers as the Gemini preset.

## How the work is organised

- Git history starts 2026-10-31 with one commit per module, then one commit per change.
- Large features run as parallel agents on separate files; the main session merges them, runs all tests and lint, and tests on the phone.
- Never install or force-stop while Locus is in use or a run is active (see `STATUS.md`).
