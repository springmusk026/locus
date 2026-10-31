# Where Locus stands

Snapshot as of 2026-10-31. Read this first; `ROADMAP.md` has the full feature history and `STATUS.md` has handoff notes and device-testing etiquette.

## In one paragraph

Locus is a local-first, bring-your-own-key agent app for Android: 20 Gradle modules, about 36k lines of Kotlin, 348 unit tests, MVVM + Jetpack Compose. It talks to Anthropic, OpenAI (Responses and Chat Completions) and any OpenAI-compatible server, or runs Gemma 4 on the phone. The agent can work in a real Linux shell (proot Alpine), drive an in-app browser, reach your servers over SSH, use MCP servers and keyed integrations, remember things, write its own skills and connectors, plan, keep a todo list, and hand work to sub-agents. Every action is checked against your permissions and logged. The app has been tested daily on a Galaxy S25 Ultra (Android 17).

## What's built

| Area | What works |
|---|---|
| Models | Anthropic Messages (prompt caching, server compaction, effort), OpenAI Responses (stateless, encrypted reasoning replay), OpenAI-compatible (OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM…), on-device Gemma 4 E2B/E4B via LiteRT-LM (vision, KV reuse). Model lists are fetched automatically, with real provider and model logos. |
| Chat | Streaming Markdown with code highlighting, one answer per turn, live thinking ("Thought for Ns"), token line per reply, context ring, compaction, full-text search over every branch (FTS5, highlighted snippets; docs/design/search.md), pins, branches (regenerate and edit as siblings with a ‹ 1/2 › switcher, only the shown branch is sent) and forking into a new chat (docs/design/branching.md), follow-up suggestions, auto titles (both prompts and the compaction prompt editable in Settings), attachments, voice, share-to-Locus, files the agent sends as cards. |
| Composer | Shared by chat and home: model, thinking level, tools/connectors on or off, plan mode, attachments; floats over content. |
| Agent | Fixed tool list for prompt caching; Allow/Ask/Deny per tool; plan mode with `plan_propose`; `todo_write` checklist; optional safety reviewer (helper model approves or blocks Ask-level actions); reflection after long tasks; recovery after the app is killed. |
| Sub-agents | `agent_spawn/wait/status/cancel/create`; researcher, coder, explorer, ops and browser profiles plus your own (`agents/<name>/AGENT.md`); up to 3 at once, one level deep; approvals labelled by helper; transcripts you can open. |
| Linux | Alpine 3.24 under proot, `apk`, Python, git, Node; background services previewed in the browser; a live terminal panel. |
| Browser | Agent-driven WebView you can watch and take over: accessibility-style snapshots with diffs, form filling, waits, tabs for pop-ups, JS dialogs, downloads to and uploads from the workspace, page saving, console and network logs (docs/design/browser.md). Logins, payments, submits and uploads ask first. |
| Reach | SSH (keys, pinned host keys, SFTP, detached jobs), MCP over HTTP with OAuth, HTTP connectors with vault secrets and sandboxed JS hooks. |
| Integrations | Firecrawl, Tavily, Brave, Exa (search), GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, Email (SMTP/IMAP), ntfy, weather, Wikipedia. |
| Memory & skills | Visible, editable memory; Agent Skills format with drafts you approve; chat-history tools; persona files built from an onboarding questionnaire. |
| Always on | Scheduled tasks (read-only background runs), results by notification and ntfy. |
| Screens | Onboarding (9 steps), home, chat with side panels, Library (memory, skills, connectors, agents), Tasks, Workspace with file viewers (PDF, images, code, Markdown, tables, media), Activity (runs, search, filters), Insights (tokens, speed, tools, approvals), Settings hub. |
| Safety | Keys encrypted with Tink and the Android Keystore and never shown to the model; no AccessibilityService by design; untrusted content is marked; outward actions confirm. |

## Tested on the phone vs. not yet

Tested on the device: chat on Anthropic-format and on-device models, tool calls and approvals (in-app and from notifications), Linux and SSH, the browser panel, MCP OAuth up to the consent page, onboarding end to end, search and pins, compaction, file viewers, Activity, logos, sub-agents (two researchers in parallel, transcript sheet, Activity labels, the v6 database upgrade), ChatGPT sign-in with tool use, and the browser panel's full-screen handoff in both directions.

Built and unit-tested, but not yet tried on the phone or with real accounts:
- The safety reviewer, helper approvals, Stop on a helper row, helper drafts in Library → Agents.
- The OpenAI Responses adapter (no OpenAI key yet).
- GitHub, Tavily, Brave, Exa, Vercel, Netlify, Cloudflare, Supabase, Render with real tokens; email with a real mailbox.
- Reflection after a long task; Linux background services; continuing after a mid-run kill.
- Message branches and forks (regenerate, edit, switcher, fork) and the v7 database upgrade.
- Editable helper prompts (Settings → Helper model & extras → Prompts).
- Full-text search and the v8 database upgrade (migration SQL tested on the JVM against the v7 schema with the system sqlite3, not yet on the phone's bundled SQLite).

## What's left (known gaps)

- No CI yet, and no instrumented UI tests; all tests are JVM unit tests plus manual device runs.
- Release signing uses the debug key; real release signing, reproducible builds and F-Droid metadata are not set up.
- Native Gemini adapter (deferred until there's a key to test against).
- MCP: Client ID Metadata Documents and step-up on `insufficient_scope`.
- Places search needs a decision (Nominatim policy).
- Accessibility: no full TalkBack walk-through yet; no real two-pane tablet layout.
- `agent_send` (continuing a finished helper) and per-profile prompt cache keys for helpers.

## What can be done next

From the roadmap, roughly in order of value:
1. A searchable model picker with favourites, and a capability catalogue (context window, vision, tools), e.g. from models.dev.
2. Search: open a result at its message (scroll, or switch to its branch).
3. ChatGPT sign-in: refresh after an hour, sign-out and re-sign-in, and the usage-limit message still need a real run (see "Subscriptions" below).
4. More integrations: Telegram or Matrix as reach-me channels.
5. llama.cpp for GGUF models; Gemini Nano where supported.
6. Exposing Locus actions to other apps through AppFunctions.

## What can be improved

- **Engineering:** GitHub Actions running unit tests, lint and an assemble on every push; a few Compose UI tests for the chat and composer; a release build in CI.
- **Analytics accuracy:** store the model and latency per message so Insights doesn't infer them from the chat.
- **Cost control:** sub-agents can use many tokens (a researcher reading one page used ~96k). Consider a default helper model for researchers, smaller page excerpts, and showing the cost of a helper run.
- **Large chats:** paging the transcript and Activity for chats with thousands of steps.
- **Onboarding:** let people skip Linux packages and come back later from Settings.
- **Docs:** a user-facing README with screenshots and a privacy statement.

## Subscriptions (Codex, Antigravity)

Researched 2026-10-31:
- **ChatGPT (Plus/Pro): allowed, built, and working on the phone** (sign-in, model list, chats with tools: writing and running a script, browsing). OpenAI's "Sign in with ChatGPT" program lets open-source, locally run apps use a person's ChatGPT plan: "ChatGPT plan usage is available to all open-source partners and selected private clients" (developers.openai.com/siwc/quickstart). The app registers its own client through OAuth with PKCE, then calls the public `api.openai.com/v1/responses` with the user's token. Reusing Codex CLI's client id or `chatgpt.com/backend-api` is not allowed, and Locus doesn't. A paid or commercially distributed build would need OpenAI's interest form first.
- **Google Antigravity: not allowed, so Locus won't support it.** Its terms say "Using third party software, tools, or services to access the Service (e.g. using OpenClaw with Antigravity OAuth) is a breach of this Agreement" (antigravity.google/terms, §6), and Google suspended accounts for it in February 2026. The supported route is a Gemini API key from Google AI Studio (free tier), which Locus already offers as the Gemini preset.

## How the work is organised

- Git history starts 2026-10-31 with one commit per module, then one commit per change.
- Large features run as parallel agents on separate files; the main session merges them, runs all tests and lint, and tests on the phone.
- Never install or force-stop while Locus is in use or a run is active (see `STATUS.md`).
