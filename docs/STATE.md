# Where Locus stands

Snapshot as of 2026-11-01 (after 0.1.2 and the polish pass, #41-#44). Read this first; `ROADMAP.md` has the full feature history and `STATUS.md` has handoff notes and device-testing etiquette.

## In one paragraph

Locus is a local-first, bring-your-own-key agent app for Android: 22 Gradle modules, about 49k lines of Kotlin plus 16k of tests, 876 unit tests, MVVM + Jetpack Compose. It talks to Anthropic, OpenAI (Responses and Chat Completions) and any OpenAI-compatible server, or runs Gemma 4 on the phone. The agent can work in a real Linux shell (proot Alpine, opt-in), drive an in-app browser, reach your servers over SSH, use MCP servers and keyed integrations, remember things, write its own skills and connectors, plan, keep a todo list, and hand work to sub-agents. Every action is checked against your permissions and logged. The app has been tested daily on a Galaxy S25 Ultra (Android 17).

## What's built

| Area | What works |
|---|---|
| Models | Anthropic Messages (prompt caching, server compaction read from the Models API's capabilities, effort), OpenAI Responses (stateless, encrypted reasoning replay), OpenAI-compatible (OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM…), on-device Gemma 4 E2B/E4B via LiteRT-LM (vision, KV reuse, the backend that really ran, a local benchmark, RAM warnings, unload). Per-provider network settings: extra headers, proxy, custom CA certificates. Model lists are fetched automatically, with real provider and model logos. |
| Chat | Streaming Markdown with code highlighting, one answer per turn, live thinking ("Thought for Ns"), token line per reply, context ring, compaction, full-text search over every branch (FTS5, highlighted snippets; docs/design/search.md), pins, branches (regenerate and edit as siblings with a ‹ 1/2 › switcher, only the shown branch is sent) and forking into a new chat (docs/design/branching.md), follow-up suggestions, auto titles (both prompts and the compaction prompt editable in Settings), attachments, voice, share-to-Locus, files the agent sends as cards, LaTeX maths and Mermaid diagrams, "What was sent" (the exact request for a reply, secrets scrubbed), read aloud, and sharing a chat as Markdown or PDF. |
| Composer | Shared by chat and home: model, thinking level, tools/connectors on or off, plan mode, chat mode, task budget, attachments; floats over content. Stays open while the agent works: messages queue and join after the current step (steer and queue). |
| Agent | Fixed tool list for prompt caching; Allow/Ask/Deny per tool; plan mode with `plan_propose`; `todo_write` checklist; `ask_user` questions (options, multi-select or free text) answered in the chat or from the notification; optional safety reviewer (helper model approves or blocks Ask-level actions); per-chat modes (Read-only, Workspace, Full); task budgets for tokens, estimated cost and time that pause between steps; no step limit in chats (scheduled runs stop at 40); reflection after long tasks; recovery after the app is killed. |
| Sub-agents | `agent_spawn/send/wait/status/cancel/create` (`agent_send` continues a finished helper); researcher, coder, explorer, ops and browser profiles plus your own (`agents/<name>/AGENT.md`); up to 3 at once, one level deep; approvals labelled by helper; transcripts you can open; Steer on a working helper. |
| Linux | Opt-in: downloaded only when you choose it in setup (or Settings → About & privacy → Run setup again). Alpine 3.24 under proot, `apk`, Python, git, Node; background services previewed in the browser; a live terminal panel. |
| Browser | Agent-driven WebView you can watch and take over: accessibility-style snapshots with diffs, form filling, waits, tabs for pop-ups, JS dialogs, downloads to and uploads from the workspace, page saving, console and network logs (docs/design/browser.md). Logins, payments, submits and uploads ask first. |
| Reach | SSH (keys, pinned host keys, SFTP, detached jobs), MCP over HTTP with OAuth, local stdio MCP servers that run inside the Linux workspace, HTTP connectors with vault secrets and sandboxed JS hooks. |
| Integrations | Firecrawl, Tavily, Brave, Exa (search), GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, Email (SMTP/IMAP), ntfy, Telegram, weather, Wikipedia. |
| Memory & skills | Visible, editable memory; Agent Skills format with drafts you approve; skill import from a GitHub link or a zip, as a draft; chat-history tools; persona files built from an onboarding questionnaire. |
| Always on | Scheduled tasks (read-only background runs), results by notification, ntfy and Telegram. Live Updates on Android 16+: the working notification is promoted with a status-bar chip and carries Allow / Deny, answers, or a budget pause's Continue / Stop. |
| Screens | Onboarding (9 steps), home, chat with side panels, Library (memory, skills, connectors, helpers), Tasks, Workspace with file viewers (PDF, images, code, Markdown, tables, media), Activity (runs, search, filters), Insights (tokens, speed with time to first token, by model, where tokens went, tools, approvals), Settings hub. |
| Backup | Encrypted `.locusbackup` files through the system picker: chats, settings, memory and skills, the workspace optionally, keys only when you switch them on. Restores are verified before anything changes, then previewed, and finish with a restart (docs/design/backup.md). |
| Safety | Keys encrypted with Tink and the Android Keystore and never shown to the model; no AccessibilityService by design; untrusted content is marked; outward actions confirm. |

## Tested on the phone vs. not yet

Tested on the device: chat on Anthropic-format and on-device models, tool calls and approvals (in-app and from notifications), Linux and SSH, the browser panel, MCP OAuth up to the consent page, onboarding end to end, search and pins, compaction, file viewers, Activity, logos, sub-agents (two researchers in parallel, transcript sheet, Activity labels, the v6 database upgrade), ChatGPT sign-in with tool use, and the browser panel's full-screen handoff in both directions.

Built and unit-tested, but not yet tried on the phone or with real accounts:
- `ask_user`: the question card, answering by typing, the notification's option buttons and Reply, the restart path.
- The safety reviewer, helper approvals, Stop on a helper row, helper drafts in Library → Helpers.
- The OpenAI Responses adapter (no OpenAI key yet).
- GitHub, Tavily, Brave, Exa, Vercel, Netlify, Cloudflare, Supabase, Render with real tokens; email with a real mailbox.
- Reflection after a long task; Linux background services; continuing after a mid-run kill.
- Message branches and forks (regenerate, edit, switcher, fork) and the v7 database upgrade.
- Anthropic prompt-cache breakpoints (request shape unit-tested; cache hits not yet confirmed against the live API with `cache_read_input_tokens`).
- Android 17 local network permission: the rationale and system prompt for LAN providers, SSH machines and MCP servers. Needs an Android 17 device (enforced for targetSdk 37); on an Android 16 device the opt-in can be simulated with `adb shell am compat enable RESTRICT_LOCAL_NETWORK dev.locus` and a reboot, but Android 16 gates it on `NEARBY_WIFI_DEVICES`, which Locus doesn't request, so only the blocked-connection message can be checked there. Agent tool calls to a LAN SSH machine or MCP server that was saved before this change, and the agent browser opening LAN URLs, don't prompt; they fail until the permission is allowed.
- Encrypted backup and restore: unit-tested on the JVM (round trip, wrong password, tampering, versions). Not yet run on the phone, so `VACUUM INTO` through Room, the system picker, PBKDF2 speed and the restart are untested there.
- `agent_send`: continuing a finished helper, its row, and the transcript's "Continued" divider.
- Editable helper prompts (Settings → Helper model & extras → Prompts).
- Full-text search and the v8 database upgrade (migration SQL tested on the JVM against the v7 schema with the system sqlite3, not yet on the phone's bundled SQLite).
- Exact Insights and the v9 database upgrade: model, provider and latency stored per reply, `usage_events` for side requests, "Where tokens went" (migration SQL tested on the JVM against the v8 schema with the system sqlite3, not yet on the phone).

Built since #21 and unit-tested where possible; each needs a phone check (the checklists are in the PRs):
- Android 16 Live Updates (#35): the promoted notification, its chip, and Allow / Deny and answers from it.
- Steer and queue (#38): queueing mid-task, Edit and Cancel, Steer on a helper, Send or Discard after a restart.
- Task budgets (#37): the pause, Continue and Stop, the header meter, and Anthropic's `task_budget`.
- Local MCP servers inside Linux (#36): start, idle stop, crash errors, Stop while starting.
- Skill import from GitHub and zip (#27).
- Network settings (#39): headers, proxies and custom CAs; the imported-CA chain check needs an Android 17 device.
- LaTeX and Mermaid in replies and the workspace preview (#32).
- "What was sent" (#29), read aloud and share as Markdown or PDF (#23).
- Per-chat modes (#26).
- The on-device benchmark, backend line, RAM warnings and Unload (#30).
- Encrypted backup and restore (#25), as above.
- No step limit in chats (#41): a task longer than 40 steps should finish.
- The polish pass (#42-#44): confirmations, the Helpers tab, the Settings layout, notification taps opening their chat, the budget pause in the notification, setup without Telegram and with the local network gate, and loading indicators.

## What's left (known gaps)

- No instrumented UI tests; all tests are JVM unit tests plus manual device runs. CI (`.github/workflows/ci.yml`) runs the unit tests, a Kover coverage report, lint and a debug build on every push to main and every pull request.
- F-Droid is prepared but not submitted (docs/FDROID.md): fastlane metadata is in `fastlane/`, releases are signed with Locus's own key in CI, but the build isn't verified reproducible yet and the committed `libproot.so` predates the current build script.
- Native Gemini adapter (deferred until there's a key to test against).
- MCP: Client ID Metadata Documents and step-up on `insufficient_scope`.
- Places search needs a decision (Nominatim policy).
- Accessibility: no full TalkBack walk-through yet; no real two-pane tablet layout.
- Backups: no Argon2id yet (PBKDF2 only, see docs/design/backup.md); no scheduled or automatic backups.

## What can be done next

From the roadmap, roughly in order of value:
1. Phone checks for everything listed above as built but untested.
2. F-Droid: rebuild `libproot.so` with the Native libs workflow, check the build is reproducible, and submit.
3. ChatGPT sign-in: refresh after an hour, sign-out and re-sign-in, and the usage-limit message still need a real run (see "Subscriptions" below).
4. Inbound Telegram (messages to the bot as user messages), or Matrix as another reach-me channel.
5. llama.cpp for GGUF models; Gemini Nano where supported.
6. Exposing Locus actions to other apps through AppFunctions.

## What can be improved

- **Engineering:** a few Compose UI tests for the chat and composer; coverage thresholds (Kover reports, with none set yet).
- **Analytics:** replies from before v9 still have their model and speed inferred (nothing to backfill them from). Usage events aren't exported or shown per chat yet.
- **Cost control:** sub-agents can use many tokens (a researcher reading one page used ~96k). Task budgets cap a whole task; still worth considering: a default helper model for researchers, smaller page excerpts, and showing the cost of a helper run.
- **Large chats:** paging the transcript and Activity for chats with thousands of steps.

## Subscriptions (Codex, Antigravity)

Researched 2026-10-31:
- **ChatGPT (Plus/Pro): allowed, built, and working on the phone** (sign-in, model list, chats with tools: writing and running a script, browsing). OpenAI's "Sign in with ChatGPT" program lets open-source, locally run apps use a person's ChatGPT plan: "ChatGPT plan usage is available to all open-source partners and selected private clients" (developers.openai.com/siwc/quickstart). The app registers its own client through OAuth with PKCE, then calls the public `api.openai.com/v1/responses` with the user's token. Reusing Codex CLI's client id or `chatgpt.com/backend-api` is not allowed, and Locus doesn't. A paid or commercially distributed build would need OpenAI's interest form first.
- **Google Antigravity: not allowed, so Locus won't support it.** Its terms say "Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity OAuth) is a breach of this Agreement" (antigravity.google/terms, §6), and Google suspended accounts for it in February 2026. The supported route is a Gemini API key from Google AI Studio (free tier), which Locus already offers as the Gemini preset.

## How the work is organised

- Git history starts 2026-10-31 with one commit per module, then one commit per change.
- Large features run as parallel agents on separate files; the main session merges them, runs all tests and lint, and tests on the phone.
- Never install or force-stop while Locus is in use or a run is active (see `STATUS.md`).
