# Changelog

All notable changes to Locus are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions will follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once releases start.

## [Unreleased]

The first public version of the source. Highlights:

### Models
- Anthropic Messages, OpenAI Responses and OpenAI-compatible providers (OpenRouter, Groq, Google Gemini, Ollama, LM Studio, llama.cpp, vLLM), with streaming, prompt caching, token accounting and compaction.
- Continue with ChatGPT: use a ChatGPT Plus or Pro plan through OpenAI's Sign in with ChatGPT program.
- On-device Gemma 4 through LiteRT-LM, with image input and KV-cache reuse.
- On-device honesty: Settings → Models says which backend the engine was really created on ("Running on GPU · 18 tok/s", or "Running on CPU (GPU failed: …)" with the trimmed reason), and on-device replies show backend and speed in their token line. Per-reply speeds are estimates from the stream and are marked "≈".
- On-device model sheet: whether the model is loaded, its load time and approximate memory, an Unload button (not while a reply is being generated), and an optional local benchmark (fixed prompt, 256 tokens in and 128 out, through LiteRT-LM's benchmark) reporting load time and prefill and decode speed. Results stay on the phone.
- Memory warnings: each on-device model has a minimum phone RAM (Gemma 4 E2B 8 GB, E4B 12 GB, the same as Google AI Edge Gallery). Below it, or when Android reports low memory, Locus warns before download and load that the model may be slow or fail; it never blocks. Checks use total RAM, never device names.
- A helper model for titles, follow-up suggestions, summaries and the optional safety reviewer.
- Editable helper prompts: Settings → Helper model & extras → Prompts lets you view, edit and reset the prompts for chat titles, follow-up suggestions and summaries of long chats. Each lists its placeholders (`{chat}` for the chat excerpt); a prompt missing a required placeholder can't be saved, and one that is somehow stored without it falls back to the default, so a request always includes the chat. A summary prompt only changes the request's last message, so compaction still reuses the chat's prompt cache. The safety reviewer's prompt is deliberately not editable. Title and suggestion parsing now copes with labels, lists, markdown, code fences, reasoning blocks and JSON in the reply.

### Chat
- Message branching: regenerate a reply or edit a message as a new branch, and switch between versions with ‹ 1/2 ›; only the shown branch is sent to the model.
- Fork a chat from any reply or message into a new chat with the same settings.
- What was sent: from a reply's ⋯ menu or the context sheet, see exactly what Locus sent to the model for that turn: provider, model, endpoint host and settings (effort, max tokens), the system prompt, the tools with their schemas, and the messages with tool calls and results folded, with rough token counts per section and Copy JSON. API keys and auth headers are never part of it, saved secrets show as [secret] and attachments by size ("[image, 1.2 MB]"). On-device chats show the LiteRT-LM conversation text. Locus keeps the last 6 requests of the 8 most recent chats in memory only, never on disk, and rebuilds the body with the adapter's own code when you open it, so sending costs nothing extra.
- Full-text search over messages (SQLite FTS5, database v8): words match from their start ("kotl" finds "Kotlin"), "quoted phrases" match exactly, accents and case are ignored. Results show each chat once, newest match first, with the matching words highlighted. Only what you'd search is indexed: your messages, attached file names and replies, not tool output or reasoning. `chat_history_search` uses the same index. Existing chats are indexed when the app updates.
- Read aloud: a reply's ⋯ menu reads it with the system text-to-speech voice, in the reply's language when Android can tell it. Code blocks and tables are announced rather than read, links read their text, and thinking and tool steps are skipped. Stop from the Reading chip, by tapping again, by sending a message or by leaving the chat.
- Share chat from the chat's top-bar menu, as Markdown or PDF, to any app or into the workspace's `exports` folder. Only the branch on screen is included, with tool steps as one-line labels and attachments by name; reasoning, tool details, helper transcripts and anything shaped like a key are left out.

### Backup
- Encrypted backup and restore (Settings → Backup & restore). One `.locusbackup` file, saved wherever you pick, holds chats and activity, settings, and memory, skills, helpers and persona files. The workspace is optional, with its size shown.
- Keys and passwords go in only if you turn on a second switch, which is off by default. The file is encrypted with your password: Tink Streaming AEAD (AES-256-GCM, 1 MB segments) under a PBKDF2-HMAC-SHA256 key (600,000 iterations).
- Restoring checks the whole file before changing anything. A wrong password and a damaged or changed file each get a clear message.
- You see what's in the backup before you confirm. A backup from a newer Locus is refused; an older database is upgraded by the usual migrations.
- Restoring waits while a task is running, then restarts the app. Format and research: docs/design/backup.md.

### Agent
- Agent loop with per-tool Allow / Ask / Deny policies, approvals in the app or from notifications, and recovery after the app is killed.
- Live Updates (Android 16+): while a task you started runs, the "Locus is working" notification is promoted to the top of the shade and the lock screen, with a chip in the status bar: "Working", the step ("Step 4"), or "Asks" when an approval or question is waiting. It says what the agent is doing ("Using browser", "Working · 2 helpers") and, while Locus is in the background, carries Deny / Allow (Allow still needs the phone unlocked) or the question's answers. Scheduled tasks never use it, it updates no more often than before, and with Live Updates turned off for Locus in system settings, or below Android 16, the notification stays as it was.
- Plan mode, a todo checklist, and thinking shown live and kept afterwards.
- Per-chat modes: **Read-only** (only reads and looks), **Workspace** (changes files and runs Linux, but never sends, posts, deploys or opens other apps) or **Full**. Pick one from the composer's + sheet, also on home for the next chat; a narrower mode shows as a chip. Modes only narrow: Settings → Permissions, the tools menu and confirmations still apply. A refused call tells the agent to ask you to switch, helpers follow the chat's mode, and switching keeps the prompt cache. Stored with the chat's switched-off tools, so the database is unchanged.
- `ask_user`: the agent can ask you a question mid-run instead of guessing: options (one or several), a yes/no confirmation, or free text. Answer on the card above the composer, by typing a normal message, or from the notification (option buttons and Reply, no unlock needed). It waits up to 30 minutes like an approval; background runs are told you aren't available, and a question left open when Locus is killed is answered as unanswered, with Continue offered. A helper's question shows in the parent chat with its name. The steps show "Answered: …".
- Sub-agents: researcher, coder, explorer, ops and browser helpers that run in parallel, plus your own definitions.
- `agent_send` continues a helper that finished or stopped early, with a follow-up message. The helper keeps everything it already did and runs with the same type, tools, limits and approvals, then sends back a new report. Its transcript shows a "Continued" divider. Each continuation counts toward the helper limits, and a helper that is still working is refused.
- Memory, Agent Skills, HTTP connectors with sandboxed scripts, MCP servers with OAuth, and drafts you approve.
- Skill import: Library → Skills → Import takes a GitHub link (a skill folder, a SKILL.md, or a repository with SKILL.md at the top; branches with slashes work) or a zip from the file picker. GitHub downloads use the contents API, without a token or with the GitHub integration's token. The skill is checked against the Agent Skills spec and limits (100 files, 10 MB, 5 MB per file; no symlinks, submodules or paths outside the folder) and always lands as a draft. Review shows SKILL.md as written, every file, and where it came from (link, branch and commit, or zip name, kept in `.locus-source.json`). A name clash offers rename or replace (a new version once approved).

### Tools
- Linux workspace (Alpine under proot), SSH and SFTP with detached jobs, phone actions through intents.
- Agent browser: structured snapshots with stable refs, form filling, waits, tabs, dialogs, downloads and uploads through the workspace, page saving and logs, with confirmations for logins, payments and submits.
- Integrations: Firecrawl, Tavily, Brave, Exa, GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, email, ntfy and Telegram.
- Telegram reach-me channel: Settings → Integrations → Telegram takes a bot token from @BotFather, and Find my chat reads the bot's messages once to fill in your chat id (or type it). The token stays in the vault and is scrubbed from every error. `notify_me` and scheduled task results now go to every channel you set up (ntfy and/or Telegram). Messages are sent as escaped HTML, long text is split at line breaks into up to five messages, and short 429 waits are honoured. Telegram doesn't send anything to Locus: what you write to the bot is not read, except once during setup.
- Supabase: list projects, functions, branches and API key names; read data with read-only SQL (allowed by default); run changes with `supabase_sql` and deploy Edge Functions from the workspace, which always ask first. Key values never reach the model. Use a scoped personal access token.
- Render: list services and deploys, read logs, list environment variable names; set variables (asks), start deploys, and suspend or resume services (always ask).
- `cloudflare_deploy_static` publishes a workspace folder (such as `dist`) as a Cloudflare Workers static site on workers.dev, with single-page-app routing and missing-file-only uploads. It always asks first. The token needs Account → Workers Scripts → Edit.

### App
- Onboarding with a persona questionnaire, a settings hub, Library, scheduled tasks, a workspace with file viewers, Activity runs with search and filters, and Insights.
- One design system across every screen, with real provider, model and file-type icons.
- Insights counts exactly (database v9): every reply stores the provider and model that answered and how long it took (time to first token and to the finished reply), so "By model" and reply speed no longer guess from the chat's current settings. Replies from before the update still fall back to the old estimate. Titles, suggestions, summaries, safety reviews and reflection are recorded too (token counts and timing only, deleted with their chat), and a new "Where tokens went" section splits tokens by chat replies, helpers and these side jobs.

### F-Droid preparation
- Linux is an explicit opt-in. The setup step says installing downloads third-party programs from alpinelinux.org that F-Droid hasn't checked, with **Download and install Linux** and **Continue without Linux** side by side. Locus no longer installs Linux the first time the agent runs a command: `run_command` and `linux_service_start` tell the agent to ask you to install it from Settings → About & privacy → Run setup again.
- The version is set in `app/build.gradle.kts` (0.1.2, code 3) instead of by CI; the release workflow checks that the tag is `v<versionName>`. See [docs/RELEASING.md](docs/RELEASING.md).
- The APK no longer contains AGP's dependency-info block.
- Fastlane metadata (descriptions, changelog, icon, screenshots) in `fastlane/metadata/android/en-US`; `tools/render-icon.py` renders the icon.
- `tools/build-proot.sh` verifies the talloc download, accepts existing proot/talloc sources (`PROOT_SRC`, `TALLOC_SRC`) and lists its inputs; a manual **Native libs** workflow rebuilds the libraries on Linux and compares them with the committed ones.
- [docs/FDROID.md](docs/FDROID.md): requirements, a draft fdroiddata recipe, the reproducible-build plan and what's still open.

### Model picker
- One search across every provider's models, favourites (star a model) and recent picks on top.
- Rows show the model's logo, its display name, and chips for context window, vision, tools and reasoning where known.
- Optional model details from the public [models.dev](https://models.dev) catalogue: off until you turn it on, fetched at most once a day, cached for offline use.

### Project
- Debug APKs are published to GitHub Releases from version tags (`.github/workflows/release.yml`).

### Changed
- The system prompt tells the agent never to ask for API keys or tokens in the chat and to point to the missing setting instead (new chats only).
- `cloudflare_pages_deploy` now says plainly that it can't upload files.
- New chats' system prompts have a hard token budget per part (persona files, memory index, skills, connectors, helper types, recent chats, your instructions) and a cap of a fifth of the model's context window. Models with 32K tokens or less, such as on-device Gemma, get a compact prompt. Anything cut leaves a note pointing the agent at `memory_search`, `skill_use` and `chat_history_search`, and `skill_use` and `connector_call` list every active name when given an unknown one. The prompt is still built once per chat. Estimates count characters, not bytes, so non-Latin text isn't undercounted.
- OpenAI (API key and ChatGPT plan) requests carry a stable `prompt_cache_key`: one per chat (shared by its compaction, reflection and forked helpers) and one per helper profile. It never depends on message text or time. If a server refuses the field, the request is sent once more without it.
- Token usage records the reasoning (thinking) tokens a provider reports (Responses `output_tokens_details`, Chat Completions `completion_tokens_details`) as part of output, and Insights shows how much of the output was thinking.

- A test checks that a chat switching between providers only ever sends each provider its own key or sign-in.

### Fixed (reliability)
- A reply whose stream broke mid-way (connection reset, unexpected end, timeout) failed the run at once. It's now retried like a busy provider (up to 3 times, with backoff), with the partial text discarded and "Connection dropped, retrying" shown. A stream that breaks after a tool call began is not retried.
- After Locus restarted, `linux_service_list` said there were no background services, even though the agent had started some (a restart ends them). They're now listed as stopped, with their last output and how to start them again; `linux_service_stop` forgets them. Removing Linux stops running services first.
- OpenAI-compatible servers that leave hidden reasoning out of `completion_tokens` (while `total_tokens` includes it) no longer undercount output and context.

- Android 17 local network permission: Locus targets API 37, where connections to LAN hosts are blocked unless the app holds `ACCESS_LOCAL_NETWORK` (Nearby devices). Locus now declares it and asks, after a short explanation, when you save or use a target on a private, link-local or `.local` address: a provider's base URL (Settings and onboarding, before loading models or saving), an SSH machine (before checking its host key or saving), an MCP server (before adding it), and before sending to such a provider from a chat. Saving never depends on the answer. If a request to a LAN provider fails without the permission, the error says how to allow it. localhost is not affected.

### Fixed
- Opening the browser full screen crashed the app when the page had focus.
- The terminal and browser buttons now show only when their panel has something to show.
- A chat that ends on your message (after an error was dismissed) offers Run again.
- The model sheet could show no tick while the chat header named a model: when the chosen model later became the provider's default, or when the provider's list didn't include it. Header, sheet and requests now resolve the model the same way (#1).
- A model picked on home just before sending could miss that chat and carry over to the next one.
- Anthropic prompt caching was never requested, so every turn paid full price for the whole prompt. Requests now carry two `cache_control` breakpoints: one after the frozen system prompt (or the last tool when there is none), one on the last cacheable block of the conversation, moving forward each turn (5-minute TTL). Local compaction reads the chat's cache; helper prompts (titles, suggestions, reviewer) stay uncached because they are never repeated.
- Server-side compaction was only sent for seven hard-coded Anthropic models, missing Claude Fable 5.1, Mythos 5.1, Sonnet 5.5, Sonnet 5 and Haiku 5.5 (whose long chats then fell back to Locus's own summaries). Locus now reads `capabilities.context_management.compact_20260112` from the Models API (once per model, with a 5-second limit, kept for the session) and falls back to the supported-models list from the compaction docs.
- A 429 for an exhausted balance, quota or spend limit (OpenAI `insufficient_quota`, `credit_balance_exhausted`, `*_spend_limit_exceeded`, `organization_usage_limit_exceeded`; Anthropic `enforced_spend_limit_reached`) was retried like a rate limit. It now fails at once with a message saying it's a billing limit; rate limits, `slow_down`, 5xx and overload are still retried with backoff.

### Fixed (before the first push)
- The full-screen browser showed a black page after opening it from a chat.
- Browser sign-ins failed with "Unable to resolve host" when finishing in the background.
- ChatGPT replies disappeared after streaming.
