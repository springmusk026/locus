# Roadmap

Locus aims to give you what Meta's Muse and OpenAI's Dots offer (an agent that keeps working, acts for you and asks before sensitive actions) without a cloud account in the middle. Each phase ends with a usable app.

## Phase 1: Foundation ✅ (current)

- [x] Multi-module MVVM + Compose project (AGP 9.5, Navigation 3, Hilt, Room 3, DataStore)
- [x] Providers: Anthropic Messages, OpenAI-compatible (OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp, vLLM), streaming
- [x] Agent loop: tool calling, retries, step limit, schema validation, append-only history
- [x] Approval gate (Allow / Ask / Deny per tool), in-app and from notifications
- [x] Audit log of every tool call
- [x] Workspace tools with trash and restore; workspace visible in the Files app
- [x] Encrypted API keys (Tink + Android Keystore)
- [x] Foreground service for background runs
- [x] Recovery after the app is killed mid-run: dangling tool calls answered, "Continue" offered
- [x] First run on a real device (Galaxy S25 Ultra, Android 17): chat, tool calls, in-app and notification approvals, deny, Files app access, trash restore
- [x] Markdown rendering in chat (GitHub-flavoured)
- [x] Agent browser (WebView) with live view, takeover, and confirmation of sensitive clicks
- [x] Gemini via its OpenAI-compatible endpoint (preset)
- [x] Per-chat model switcher (tap the provider name under the chat title)
- [x] Share to Locus from any app (text, links, files → new chat draft; files land in `inbox/`)
- [x] Voice input with Android's own speech recognizer
- [~] The OpenAI-compatible adapter keeps any `extra_content` a server attaches to a tool call and sends it back on that call (stored in `Message.rawPayload`). It's meant for Gemini 3's thought signatures on its OpenAI endpoint. Google's docs (2026-10-31) say Gemini 3 supports this but link to a section that no longer exists, so the exact field is unverified. Servers that don't send the field are unaffected.
- [x] **OpenAI Responses adapter** (`ApiFormat.OPENAI_RESPONSES`, `OpenAiResponsesModel`), now the default for the OpenAI preset.
  - Requests are stateless (`store: false`). Each turn's output items, including reasoning items with `encrypted_content`, are stored in `rawPayload` and replayed untouched, so reasoning carries across tool calls.
  - Reasoning summaries stream as progress. `reasoning.effort` comes from the provider's effort setting, and if a model rejects the reasoning settings, the request is retried once without them.
  - Field and event names come from the official openai-python types; mock-tested. OpenAI's guide says its newest models only support function calling here.
  - Existing providers keep the format they were saved with. *Not yet tried with a real key.*
- [ ] Native Gemini `generateContent` adapter (thought signatures without the compatibility layer). *Deferred:* Google's docs now document the Interactions API, and the generateContent shapes for thinkingConfig and streamed signatures couldn't be verified (2026-10-31). Build it with a live key to test against.

## Phase 2: Reach

- [x] **SSH machines.** Hosts with a key or password and a host key pinned on first use. Tools: `ssh_exec`, `sftp_download` and `sftp_upload` (ConnectBot sshlib 0.6.6).
- [x] **MCP servers** over Streamable HTTP via the official Kotlin SDK, with a token or **OAuth sign-in**.
  - OAuth follows MCP authorization spec 2026-07-28 (`core/agent/mcp/McpOAuth.kt`, unit-tested):
    - Discovery: the 401 `WWW-Authenticate` `resource_metadata` and the RFC 9728 well-known fallbacks, then RFC 8414/OIDC metadata with the issuer checked.
    - Client: dynamic client registration as a native public client, one registration per issuer.
    - Sign-in: PKCE S256, `resource`, and state and RFC 9207 `iss` checks; offline_access when the server offers it.
    - Tokens: refresh tokens rotate, and Locus refreshes on expiry or on a failed connect.
  - Android side (`McpSignIn`): the system browser plus a one-shot 127.0.0.1:33418 loopback listener. The spec requires localhost or HTTPS redirects. Sessions live in the encrypted vault.
  - Adding a server without a token checks whether it needs sign-in and starts it.
  - Device-tested against Linear: discovery, dynamic registration and the consent page ("Locus is requesting access", redirect 127.0.0.1:33418/callback) all work. The listener answers forged or stray requests with 404 and keeps waiting, and Cancel closes it. A completed sign-in wasn't done, to avoid authorising the user's workspace.
  - Not done: Client ID Metadata Documents, which need a hosted HTTPS document; they're the spec's preferred option over the deprecated dynamic registration. Also not done: step-up on `insufficient_scope`.
- [x] **Web**: `fetch_url` (HTML → readable text) and searching through the browser. *Next: search APIs (Brave, Tavily, SearXNG).*
- [x] **Memory** you can see, edit and tell it to forget (Library → Memory).
- [x] **Secrets vault for tools.** Connectors reference a credential by name; the value is injected at call time and scrubbed from responses.
- [x] **Linux background services** for dev servers, previewed in the agent browser at `http://localhost:PORT`.
- [x] **Linux workspace.** Alpine 3.24.3 under proot (`run_command`, `apk add`, workspace at `/workspace`), verified on a Galaxy S25 Ultra (Android 17). The workspace becomes a small Linux system the agent works in, not just a folder: a shell with a package manager, Python, git and curl, running on the phone without root. The same folder stays visible in the Files app.
- [x] **Browser.** An in-app WebView the agent can drive (open, read, click, type, scroll, screenshot) while you watch, and that you can take over at any time. Logins, payments and form submits ask first.

## Phase 2c: Everyday polish

- [x] **Attachments.** Photos and files from the composer's +. Saved to `inbox/` in the workspace, with photos downscaled to 1568 px. Images go to vision models inline, PDFs to Claude as documents, other files as workspace references.
- [x] **Native integrations** with a key in Settings → Integrations: **Firecrawl** (`web_search`, `web_read`; free plan 1,000 credits a month).
- [x] No-key integrations: Open-Meteo `weather`, `wikipedia`.
- [x] More search services, each with its own key: **Tavily** (1,000 free searches a month, no card), **Brave Search**, **Exa** ($10 of free credit a month). `web_search` routes to the first connected service in the order Firecrawl → Tavily → Brave → Exa, so the agent keeps one tool. Endpoints and fields come from each service's docs (2026-10-31) and are covered by mock tests. *Not yet tried with real keys.*
- [x] **GitHub** integration with a fine-grained personal access token (vault `integration-github`), REST API version 2026-03-10. Tools: `github_repos`, `github_issues` (list, search or read one; PRs marked), `github_read` (file or folder, up to 1 MB). Writes ask first: `github_create_issue`, `github_comment`. Issue and file text comes back marked as untrusted. *Not yet tried with a real token.*
- [x] **Developer platforms:**
  - **Vercel:** `vercel_projects`, `vercel_deployments` (with build logs), `vercel_deploy` (deploys a workspace folder by SHA-1 plus missing-file upload, or redeploys; preview by default), `vercel_env`, `vercel_env_set` (values never echoed).
  - **Netlify:** `netlify_sites`, `netlify_deploys`, `netlify_deploy` (digest deploy, draft by default, can create the site), `netlify_env`, `netlify_env_set`.
  - **Cloudflare:** `cloudflare_zones`, `cloudflare_dns`, `cloudflare_dns_set`, `cloudflare_dns_delete`, `cloudflare_pages`, `cloudflare_pages_deploy` (Git-connected projects only), `cloudflare_deploy_static` (a workspace folder as a Workers static-assets site on workers.dev; see docs/design/cloudflare-deploy.md).
  - Policy: reads are allowed, writes ask, and deploys and deletes always confirm. Mock-tested; *not yet tried with real tokens*.
  - [x] Cloudflare Workers static-assets direct upload (`cloudflare_deploy_static`). Pages Direct Upload is left out: its asset hash is not documented. *Not yet tried with a real token.*
  - Next: Supabase, Render.
- [ ] Places search (needs a decision, see below).
  - *Places needs a decision.* The public Nominatim policy (operations.osmfoundation.org/policies/nominatim) sets 1 request/s summed across all of an app's users and requires caching. Apps must be able to switch servers without an update. Autocomplete is forbidden, and an LLM-suggested integration has to point users to the policy.
  - Options: a user-configured Nominatim server URL (self-hosted or a provider), or a keyed provider. A hard-coded public default is not appropriate.
- [x] **UI/UX pass 1:** redesigned composer (attach, voice, send in one bar), tappable example tasks, consecutive tool calls grouped ("Used N tools"), copy on replies.
- [x] **Automatic model lists:** providers' models are fetched (`/models`, Anthropic `/v1/models`) and offered as a searchable picker; typing an ID still works.
- [x] **Provider identity:** coloured monogram badges per provider (no trademarked logos) in chats, the home screen and settings.
- [x] **Livelier design:** category accent colours used consistently (Files blue, Web teal, Linux amber, Memory violet, Phone coral, Connectors pink); animated thinking dots; item animations; redesigned home (greeting, shortcut cards, badges, live status, soft brand gradient); grouped, collapsible permissions with bulk set; tinted activity log and workspace.
- [x] **Design system (deslopify pass).** One set of tokens and components in `core/designsystem` used by every screen:
  - Tokens: spacing 4/8/12/16/24/32, radii (chip 8, control 12, card 16, sheet 28), and colour by meaning. Brand moss is for your choices; live green means running now; amber means it needs you; red means failed. Category hues appear only on tool tiles.
  - Components: `LocusCard` (hairline and an optional corner glow), `CardGroup` + `ListRow` + `GroupDivider`, `cardSegment` for long lazy lists, `SectionLabel`/`SectionNote`, `StatusDot` (only a live dot moves), `StatusPill`, `StatusLine`, `Banner`, `Segmented` (sliding pill for tabs and Allow/Ask/Deny), `LocusTopBar`, `IconTile`, `EmptyState` with an icon, and an `entrance` stagger.
  - State-aware home: a status line ("Working on 1 chat", "1 action needs your approval", "Ready · model"), a featured card for the chat that's running or waiting, shortcuts that report state (memories, scheduled tasks, items to review), and an ask bar that starts a chat directly.
  - Chat: a model chip with a badge and a switcher, tool calls grouped in one card, live status lines, a caution-toned approval card, and a copy icon.
  - Settings, Library, Tasks, Activity (grouped by day, plain-language labels, fixed-width durations), Workspace and Browser were rebuilt on the same parts.
  - Reviewed on the device in dark and light.
- [x] **UI/UX pass 2:**
  - Chat search over titles and message text (Room LIKE query, debounced) and pinning (schema v3, AutoMigration 2→3) with Pinned/Recent sections.
  - "Try again" after an error resumes from the transcript.
  - Long-press your message to copy it or edit it as a new message (history stays append-only).
  - The latest scheduled-task answer (last 24 h) appears as a card on home.
  - Content is capped at 840dp wide on tablets and in landscape.
  - The segmented control grows with font scale, and provider badges are hidden from TalkBack because the name sits next to them.
- [x] Recent files on home (the 3 latest, skipping hidden folders and dependency trees).
- [x] **Chat helpers and context (pass 4, after reviewing RikkaHub's design, AGPL, ideas only):**
  - A **helper model** (Settings → Helper model, a provider plus an optional cheaper model; defaults to each chat's own) does side jobs in short tool-free requests that never touch the chat's prompt cache.
    - **Auto titles** after the first reply.
    - **Follow-up suggestions** as tappable chips above the composer, cleared when a run starts.
  - **Token accounting:** every assistant turn stores `TokenUsage` (Room v4).
    - Anthropic: `message_start` + `message_delta`, with cache fields summed into input.
    - OpenAI-compatible: the `usage` chunk; `stream_options.include_usage` is sent only to OpenAI, OpenRouter and Groq.
    - Responses: `response.usage`.
    - UI: a mono line under each reply ("10.6k in · 36 out · 94% cached"); a context ring and percentage or count in the chat header; a sheet with context-now versus window plus per-chat totals.
  - **Context window** per provider, filled from model lists (Anthropic `max_input_tokens`, OpenRouter `context_length`) or entered by hand.
  - **Compaction:**
    - Anthropic models with server-side compaction (`compact_20260112`, beta `compact-2026-01-12`) have it enabled. Compaction blocks stream into `rawPayload` (unknown block types now keep all streamed fields).
    - Other providers: at 80% of the window, or via "Summarise now", the chat's own model writes a structured summary with the same prompt prefix, so it's a cache hit. It's stored as a `Part.Checkpoint` message; older messages stay visible, but only the summary and later messages are sent.
  - **Live side panel** in chat (header Terminal/Globe toggles): a terminal log of every Linux and SSH command with its output, or the live browser. It sits above the chat on phones and beside it at 720dp and wider.
  - **Sheets instead of dialogs** everywhere (`LocusSheet`): provider editor, provider presets, integration keys, SSH machines, helper model, MCP add/key, memory edit, new task, and delete/clear confirmations.
  - Device-tested: auto title, suggestions, the token line and sheet, "Summarise now" plus the checkpoint card, the terminal and browser panels, and the sheets.
- [ ] Next from the RikkaHub review:
  - Message branching (regenerate/edit as siblings, ‹1/2›), and forking a message into a new chat.
  - A searchable model picker sheet with favourites.
  - A per-message "more" sheet (select text, share).
  - Message search with FTS.
  - Usage statistics by source (chat, title, suggestion, compaction).
  - Editable helper prompts.
  - A model capability catalogue (context, vision, tools), e.g. from models.dev.
- [x] **Onboarding** (`:feature:onboarding`, 9 steps):
  - Intro, then privacy and consent (a required tick, optional Careful/Balanced/Autonomous permission profile).
  - Model: presets, key, model list, or on-device download.
  - Agent identity: the persona files IDENTITY.md, SOUL.md, USER.md and AGENTS.md in agent-home, after OpenClaw's workspace convention. They're loaded into every new chat's prompt, and the prompt opens "You are <name>".
  - Workspace and Linux: install plus `apk add` for python3/pip, git, nodejs/npm, curl.
  - SSH, connectors and MCP.
  - A live check of the model plus `uname -a`, then a summary.
  - Shown on first run; Settings → About → "Run setup again" re-runs it, and Settings → Agent → "Agent identity" opens the persona editor. Device-tested end to end.
- [x] **Encrypted backup and restore** (Settings → Backup & restore). Every comparable app has backup; Locus had only `allowBackup=false`. One `.locusbackup` file through the system picker, password-encrypted (Tink Streaming AEAD with a PBKDF2 key). The workspace and the vault are optional; the vault is off by default. Restores are verified, previewed, and finish with a restart. docs/design/backup.md. *Next: Argon2id if a vetted library is added; scheduled backups to a picked folder.*
- [x] **Settings hub** with sub-screens: Models (with on-device models), Helper & extras, Agent, Permissions, Integrations & connectors, SSH machines, About & privacy. Each row shows a one-line state summary.
- [x] **Chat streaming and layout:**
  - Saved replies use `rememberMarkdownState(retainState, immediate)`; the live reply uses the renderer's streaming state with 48 ms batching. The streamed text is held until the saved message arrives.
  - Auto-scroll: opening a chat jumps to the end; new content is followed only while you're near the bottom; a "jump to latest" button appears otherwise.
  - **One turn, one answer:** narration between tool steps becomes muted notes inside the steps card; the final reply has one copy button and a per-turn token line.
  - Terminal/Browser toggles are floating buttons on the right edge, shown only when the chat used them.
- [x] **Agent email** (`Email.kt`, Eclipse Angus Mail 2.0.5): `email_send` always confirms; `email_list` and `email_read` are read-only, keep mail unread, and mark content untrusted. TLS only. Configured via Integrations → Email (`smtps://user:pass@host:465 imaps://host:993 Name`, or JSON). Tested with GreenMail; not yet tried with a real mailbox.
- [x] **On-device performance:**
  - Streaming state is coalesced to about 15 Hz (`StreamCoalescer`), and the notification redraws only when what it shows changes.
  - LiteRT runs on a dedicated `locus-litert` thread, and Stop calls `cancelProcess()`.
  - The conversation and its KV cache are reused across agent steps (`TranscriptPrefix`). On device: first token 201 ms with reuse versus 3,243 ms without.
  - `GemmaCallRepair` recovers tool calls LiteRT fails to parse (Gemma emitting a stray `<|"|>`), deduplicated.
- [x] **Round 2 (parallel agents, merged 2026-10-31):**
  - **Real logos:** LobeHub Icons (MIT), with the model family preferred over the provider.
  - **File-type icons:** Material Icon Theme (MIT), 84 icons, in Workspace and on home.
  - **Onboarding:** questionnaire-generated persona files; step state fixed.
  - **Home redesign:** a launcher row; 3 pinned, 5 recent, "All chats" by date; empty chats hidden; long-press to pin, rename or delete.
  - **Insights (analytics):** tokens per day and per model, cache share, reply speed, tool success and slowest tools, approvals, busiest chats, task runs.
  - **Composer:** model, thinking-level, tools and Plan chips (DB v5).
  - **Plan mode:** read-only, then `plan_propose`, then Approve / Edit / Cancel.
  - **Todos:** the `todo_write` checklist above the composer.
  - **Thinking:** live "Thinking" block, then "Thought for Ns", saved as `Part.Reasoning` and never sent back.
- [x] **Round 3 (merged and device-tested 2026-10-31):**
  - **Code highlighting** in chat and in the workspace preview (highlights 1.1.0 via the markdown renderer's code module), with a language label and a copy button per block.
  - **Live panel** shows just the chosen view (no tabs).
  - **Approval reviewer** (`ToolReviewer`, off by default): a helper model reviews Ask-level actions against the user's own messages, allows clearly aligned low-risk ones, blocks off-task or dangerous ones ("Run anyway" overrides), and otherwise asks. It fails closed; outward sends always ask unless opted in.
  - **Activity:** tool calls grouped into runs (10-minute gaps), search, time range, kind and outcome filters, per-chat scope. Helper runs are labelled with their job and parent chat.
  - **Composer** shared by chat and home (model, thinking, tools, plan in sheets), and `send_file` delivers workspace files as cards in chat.
  - **File viewers:** PDF (PdfRenderer), images, highlighted code, Markdown, CSV/TSV tables, audio/video, HTML, with Open with and Share.
  - **Sub-agents:** `agent_spawn/wait/status/cancel/create`; built-in researcher, coder, explorer, ops and browser profiles plus user-defined `agents/<name>/AGENT.md` approved in Library → Agents; depth 1, three at once, approvals queued with the helper's name, browser lease, hidden helper chats (Room v6), nested live rows with a transcript sheet. Verified on device: two parallel researchers, correct combined answer. See `docs/design/subagents.md`.
- [ ] **Next:** message branching, a searchable model picker with favourites, full-text search, editable helper prompts, a models.dev capability catalogue, `agent_send` to continue a helper, Supabase and Render integrations.
- [ ] UI/UX pass 3: a full TalkBack walk-through and a real two-pane tablet layout.
- [~] Image support for the on-device model. `EngineConfig.visionBackend` is set (the encoder loads on demand), and photos from the newest user message are sent as `Content.ImageFile` before the text. Earlier photos are referenced by path so each step stays fast. Verified on the device: Gemma 4 E2B loaded its vision adapter and correctly described an attached screenshot.

## Phase 2b: Learns as it goes

- [x] **Memory.** The agent saves facts and preferences as it works, recalls them in later chats, and shows you all of it to edit or forget.
- [x] **Skills.** Reusable instructions and scripts in the Agent Skills format (a folder with `SKILL.md`). Only each skill's name and description sit in context until one is needed.
- [x] **Connectors.** Plug in services through MCP servers, or as simple HTTP API connectors whose keys stay in the vault.
- [x] **Auto skills.** When asked, or after a task it had to work out, the agent drafts a new skill. You review it before it becomes active, and every version can be rolled back.
- [x] **Auto connectors.** On request, the agent writes a connector for an API you name (endpoints, auth from the vault). It stays inactive until you approve it. Optional `script` with `request()` / `response()` hooks runs in androidx.javascriptengine (isolated V8, no network, files or secrets); Locus performs the HTTP call itself.
- [x] **Earlier chats.** Each new chat's system prompt carries `<recent_chats>`: the latest 8 titles with ids and dates, frozen at creation. `chat_history_search` (title and message text, or the most recent chats) and `chat_history_read` (a transcript of what the user asked, what Locus answered and which actions it took) let the agent look back when the user mentions a previous conversation. Read-only. Device-tested: "what was our last chat about?" read the right chat.
- [x] **Reflection.** After a completed task that used 6 or more tools, one extra call runs on the same transcript (a prompt-cache hit) with only memory tools allowed. Its turn is not stored in the chat; any memory_save shows in Activity and as "Saved 1 lesson to memory". Settings → "Learn after tasks" turns it off. Skipped for on-device models and read-only runs. *Not yet tested on the device.* After a task, the agent notes what worked or failed so the next run goes better (stored as memory, editable).

## Phase 3: Always on

- [x] Scheduled and recurring tasks (WorkManager). Background runs are read-only: they may open and read, never click, type, write or send.
- [x] A notification with each scheduled task's answer.
- [x] Long jobs on your own server over SSH: `ssh_job_start` runs a command detached under nohup in `~/.locus-jobs/<id>.{sh,log,pid,exit}`, so it survives the session ending and Android killing Locus. `ssh_job_status` (read-only, so scheduled tasks can poll it) reports running, finished with its exit code, or stopped, plus the output tail; without an id it lists jobs. `ssh_job_stop` stops a job. The snippets are POSIX sh and unit-tested against a real `/bin/sh` (`RemoteJobsTest`). Verified on the device against the user's server: start, then status running → finished with exit 0 and its output.
- [x] **ntfy** reach-me channel: Settings → Integrations → ntfy takes your topic URL, plus an optional `tk_` token for protected topics.
  - The `notify_me` tool pushes to that topic, and background tasks may use it because it only reaches you.
  - Scheduled task results are forwarded there automatically.
  - Header names come from docs.ntfy.sh; a live publish to ntfy.sh returned 200 with title, priority and markdown applied.
- [ ] More reach-me channels: Telegram or Matrix bot.

## Phase 4: Fully local models

- [x] On-device inference with LiteRT-LM 0.19.0 (Gemma 4 E2B 2.6 GB / E4B 3.7 GB, Apache-2.0, SHA-256-verified resumable download, GPU with CPU fallback, tool calling with `automaticToolCalling = false` and a focused tool set). Downloads run as a WorkManager foreground job with progress, so they continue in the background.
- [ ] llama.cpp for GGUF models.
- [ ] Gemini Nano via the ML Kit Prompt API where the device supports it.

## Phase 5: F-Droid extras

- [x] Minimal phone actions through standard Android intents (`device_*`): calendar events, alarms and timers, email/SMS drafts, dialer, directions, links, opening apps. The other app opens prefilled and the user confirms there. **No AccessibilityService by design**: Locus does not read or operate other apps.
- [ ] Bundled command-line tools (busybox, git) and an optional Termux bridge (`RUN_COMMAND`).
- [ ] Expose Locus actions to other apps through AppFunctions.
