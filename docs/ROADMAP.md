# Roadmap

Locus aims to give you what Meta's Muse and OpenAI's Dots offer (an agent that keeps working, acts for you and asks before sensitive actions) without a cloud account in the middle. Each phase ends with a usable app.

## Phase 1: Foundation ✅ (current)

- [x] Multi-module MVVM + Compose project (AGP 9.5, Navigation 3, Hilt, Room 3, DataStore)
- [x] Providers: Anthropic Messages, OpenAI-compatible (OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp, vLLM), streaming
- [x] Agent loop: tool calling, retries, schema validation, append-only history. No step limit in chats since #41; scheduled runs stop at 40 steps and helpers keep their own limit.
- [x] Approval gate (Allow / Ask / Deny per tool), in-app and from notifications
- [x] Audit log of every tool call
- [x] Workspace tools with trash and restore; workspace visible in the Files app
- [x] Encrypted API keys (Tink + Android Keystore)
- [x] Foreground service for background runs
- [x] Recovery after the app is killed mid-run: dangling tool calls answered, "Continue" offered
- [x] First run on a real device (Galaxy S25 Ultra, Android 17): chat, tool calls, in-app and notification approvals, deny, Files app access, trash restore
- [x] Markdown rendering in chat (GitHub-flavoured)
- [x] Rich replies: typeset LaTeX maths and Mermaid diagrams in chat and the workspace Markdown preview (`core/markdown`). *Not yet checked on a device.*
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
- [x] **Web**: `fetch_url` (HTML → readable text) and searching through the browser. Search APIs: Firecrawl, Tavily, Brave and Exa (see Phase 2c). SearXNG is not done.
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
  - [x] **Supabase** (Management API, personal access token): `supabase_projects`, `supabase_query` (one read-only statement through `/database/query/read-only`, which runs as `supabase_read_only_user`, behind a client-side check that refuses writes, several statements, dollar quotes, side-effect functions and the vault schemas; allowed by default), `supabase_sql` (full rights, always confirms, names DROP/DELETE/… in the confirmation), `supabase_functions`, `supabase_function_deploy` (multipart source deploy of a workspace folder; always confirms), `supabase_api_keys` (names and types only, never values), `supabase_branches`. *Not yet tried with a real token.*
  - [x] **Render** (API v1, API key): `render_services`, `render_deploys`, `render_logs` (last hour, app/build/request), `render_env` (names only), `render_env_set` (asks; value never echoed), `render_deploy` and `render_suspend` (suspend or resume; both always confirm). Lists follow Render's cursor pagination. *Not yet tried with a real key.*
- [ ] Places search (needs a decision, see below).
  - *Places needs a decision.* The public Nominatim policy (operations.osmfoundation.org/policies/nominatim) sets 1 request/s summed across all of an app's users and requires caching. Apps must be able to switch servers without an update. Autocomplete is forbidden, and an LLM-suggested integration has to point users to the policy.
  - Options: a user-configured Nominatim server URL (self-hosted or a provider), or a keyed provider. A hard-coded public default is not appropriate.
- [x] **UI/UX pass 1:** redesigned composer (attach, voice, send in one bar), tappable example tasks, consecutive tool calls grouped ("Used N tools"), copy on replies.
- [x] **Automatic model lists:** providers' models are fetched (`/models`, Anthropic `/v1/models`) and offered as a searchable picker; typing an ID still works.
- [x] **Provider identity:** coloured monogram badges per provider in chats, the home screen and settings. *Superseded by real logos (Round 2 below).*
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
    - Inside a running task too, for chats and helpers: at a step boundary once 80% is used, and after a context-overflow error (compact, then retry the step once). The model then sees the summary, the user's latest messages word for word (about 20k tokens), the checklist and the chat's id for `chat_history_read` (now with `offset` and `include_tool_results`). See docs/design/compaction.md. Unit-tested; not yet tried on the phone.
  - **Live side panel** in chat (header Terminal/Globe toggles): a terminal log of every Linux and SSH command with its output, or the live browser. It sits above the chat on phones and beside it at 720dp and wider.
  - **Sheets instead of dialogs** everywhere (`LocusSheet`): provider editor, provider presets, integration keys, SSH machines, helper model, MCP add/key, memory edit, new task, and delete/clear confirmations.
  - Device-tested: auto title, suggestions, the token line and sheet, "Summarise now" plus the checkpoint card, the terminal and browser panels, and the sheets.
- [x] Next from the RikkaHub review:
  - [x] Message branching (regenerate/edit as siblings, ‹1/2›), and forking a message into a new chat (#6).
  - [x] A searchable model picker sheet with favourites (#8).
  - [~] A per-message "more" sheet (select text, share). Replies have a ⋯ menu (Read aloud, Fork, What was sent) and selectable text; sharing one message isn't there.
  - [x] Message search with FTS (#12).
  - [x] Usage statistics by source (chat, title, suggestion, compaction): "Where tokens went" in Insights (#20).
  - [x] Editable helper prompts (2026-10-31): titles, suggestions and compaction summaries in Settings → Helper model & extras → Prompts, with placeholders (`{chat}`), validation, Reset, and a fallback to the default when a stored prompt lacks a required placeholder. Compaction edits change only the last message, so the cached prefix is untouched. The safety reviewer's prompt stays fixed. Unit-tested; not yet tried on the phone.
  - [x] A model capability catalogue (context, vision, tools) from models.dev, off until turned on (#8).
- [x] **Onboarding** (`:feature:onboarding`, 9 steps):
  - Intro, then privacy and consent (a required tick, optional Careful/Balanced/Autonomous permission profile).
  - Model: presets, key, model list, or on-device download.
  - Agent identity: the persona files IDENTITY.md, SOUL.md, USER.md and AGENTS.md in agent-home, after OpenClaw's workspace convention. They're loaded into every new chat's prompt, and the prompt opens "You are <name>".
  - Workspace and Linux: opt-in since #33. **Download and install Linux** (from alpinelinux.org, which the step says F-Droid hasn't checked) plus `apk add` for python3/pip, git, nodejs/npm, curl, or **Continue without Linux**.
  - SSH, connectors and MCP.
  - A live check of the model plus `uname -a`, then a summary.
  - Shown on first run; Settings → About & privacy → "Run setup again" re-runs it (and is where Linux can be installed later), and Settings → Agent → "Agent identity" opens the persona editor. Device-tested end to end.
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
  - **Sub-agents:** `agent_spawn/wait/status/cancel/create`; built-in researcher, coder, explorer, ops and browser profiles plus user-defined `agents/<name>/AGENT.md` approved in Library → Agents (now Helpers, #42); depth 1, three at once, approvals queued with the helper's name, browser lease, hidden helper chats (Room v6), nested live rows with a transcript sheet. Verified on device: two parallel researchers, correct combined answer. See `docs/design/subagents.md`.
- [x] **Per-chat capability modes:** Read-only, Workspace or Full per chat, on top of Allow / Ask / Deny (from the comparison with Operit and others). Every tool has a capability in `ToolCapability.BY_TOOL`; tests fail when a registered tool is missing.
- [x] **Next:** message branching (#6), a searchable model picker with favourites (#8), full-text search (#12), editable helper prompts (#18), a models.dev capability catalogue (#8), `agent_send` to continue a helper (#21).
- [ ] UI/UX pass 3: a full TalkBack walk-through and a real two-pane tablet layout. *Nov 2026: at target 37, orientation and resize locks are ignored at 600dp+; use a Material 3 Adaptive list-detail layout and check side panels with predictive back ([product P6](research/product-platform-2026.md#p-android-platform)).*
- [~] Image support for the on-device model. `EngineConfig.visionBackend` is set (the encoder loads on demand), and photos from the newest user message are sent as `Content.ImageFile` before the text. Earlier photos are referenced by path so each step stays fast. Verified on the device: Gemma 4 E2B loaded its vision adapter and correctly described an attached screenshot.

## Ideas from comparable projects

From the comparison in [research/comparable-apps.md](research/comparable-apps.md) (OpenMinis, Operit, AndroidHarness, nanoMuse; ideas only, no code). Effort: S, M, L.

**Features we lack**
- [x] **Ask-user tool** (S–M): option, confirmation or free-text cards the agent waits on, answerable from the notification, surviving a restart. `ask_user(question, options?, multi?, allow_other?)`, always allowed; 30-minute timeout; background runs answered "not available"; helpers' questions in the parent chat. *Not yet tried on the phone.*
- [x] **Encrypted backup and restore** (M): chats, memory, skills and settings to a file the user picks; the vault only behind a password. Done: see "Encrypted backup and restore" in Phase 2c (#25).
- [x] **Fallback chains and key pools** (M): ordered backup models across providers; switch at once on quota errors, retry on 429/5xx, never replay a partial reply. Backup keys per provider (sticky until a quota, auth or rate-limit error) and a default chain in Settings → Models; `retry-after` honoured up to 60 s; partial output discarded on a switch; `ReplyInfo.fallbackFrom` (database v11). See docs/design/fallbacks.md. *Not yet tried on the phone. Further fallback work is in progress.*
- [x] **Task budgets** (S–M): tokens, estimated cost and time per run, helpers included, pausing at a safe boundary. `RunBudget` (core/agent) is shared by a task's run and its helpers through `ToolCallContext`; the loop checks it only between steps and asks Continue / Stop. Defaults off (Settings → Agent), a chat's own in ⋯ → Task budget, kept in the chat's restriction set (no schema change). Cost uses models.dev `cost` prices, now kept in the catalogue. Anthropic `output_config.task_budget` (beta `task-budgets-2026-03-13`) is sent on listed models, first-party API only, at a constant value per chat.
- [ ] **Cost in Insights** (S): from the opt-in models.dev prices, with day windows. *Nov 2026: RikkaHub (7/30/90-day usage) and Operit (token ledger with price overrides) now ship this ([product C4](research/product-platform-2026.md#c-competitor-features)).*
- [ ] **Telegram remote channel** (M): beyond notifications, answer approvals by button; allow-listed chat only. The outbound channel is done (#19); inbound is tracked under Phase 3 as "Inbound Telegram".
- [x] **Steer and queue** (S–M): send while a run works (picked up at the next step); Steer and Stop on helper rows. Queued messages survive a restart (one file per chat, no database change); see ARCHITECTURE.md, "Steer and queue". *Not yet tried on the phone.*
- [x] **Side questions** (S): `/btw` as in Claude Code, answered apart from the chat on a read-only copy of it, in a card above the composer, never stopping the task; see ARCHITECTURE.md, "Side questions". *Not yet tried on the phone.*
- [ ] **Helper partial results** (S): return what a helper had on timeout or cancel; configurable time limit.
- [x] **Per-chat capability modes** (S): Read-only, Workspace-write or Full, on top of Allow/Ask/Deny. Done: see "Per-chat capability modes" in Phase 2c (#26).
- [ ] **Scoped remembered approvals** (M): "for this chat" or "always for this site, folder or recipient"; payments always ask; dangerous shell patterns never remembered.
- [ ] **Event triggers for tasks** (M): new mail, a calendar entry, an ntfy message; a morning digest template.
- [ ] **Ways in** (S–M): quick-settings tile, widget, default assistant, Tasker or intent trigger (these start Locus; they don't control other apps). *Nov 2026: S, high. Start with static shortcuts, pinned chats as dynamic shortcuts and Direct Share targets, and a tile; then a Glance widget, and the assistant role after voice mode ([product P1, P4, P5](research/product-platform-2026.md#p-android-platform)).*
- [~] **Rich replies** (M): Mermaid and LaTeX are done (#32, see Phase 1); sandboxed inline HTML previews with no network are not. *Nov 2026: render `send_file` HTML/SVG inline in a no-network, no-file WebView, reusable for MCP Apps; Claude ships interactive apps on mobile ([product U2](research/product-platform-2026.md#u-ux-patterns)).*
- [x] **Change tracking with undo** (M): per-chat file diffs and per-file undo, plus verification cards for build and test commands. *Changes card, diffs, per-file undo and Undo all (#68), and verification cards (docs/design/change-tracking.md). Not yet checked on a device. Nov 2026: snapshot before the first write in a turn, "Undo this file" and "Undo turn" ([product U1](research/product-platform-2026.md#u-ux-patterns)).*
- [x] **Local MCP servers** (M): npx or uvx inside Linux, kept alive while a run uses them.
- [x] **Network settings** (S–M): proxy per provider, custom CA certificates, extra headers kept in the vault (model providers only; docs/design/network.md).
- [ ] **Reliability tools** (S–M): a self-test, a diagnostic export with no secrets, a storage screen. *Started: Settings → About & privacy → Diagnostics lists recent crashes, `ApplicationExitInfo` exits and storage problems, with a scrubbed Share and Clear ([diagnostics](design/diagnostics.md)). Still to do: the self-test and storage screen.*
- [ ] **Opt-in update check** (S) against GitHub Releases with a SHA-256 check. *Nov 2026: first version only notifies, off by default and disabled in F-Droid and IzzyOnDroid builds; document Obtainium in the README ([product D4](research/product-platform-2026.md#d-distribution)).*
- [x] **Read replies aloud** (S) with the system voice, skipping code, tables and thinking.
- [x] **Share or export a chat** (S) as Markdown or PDF (an image of a range is not done yet).
- [x] **Android 16 Live Updates** (S) for running and waiting runs, with Approve and Deny (#35). *Not yet tried on the phone.*
- [ ] **Semantic memory recall** (M) with on-device embeddings and provenance per fact. *Nov 2026: first an FTS5 index ranked by `bm25()` (S); today's substring search returns nothing for non-Latin queries on the JVM. Provenance (`source_chat`, `saved_at`, `by`, `tainted`) also closes a memory-poisoning path. EmbeddingGemma is a candidate model ([algorithms #9](research/agent-algorithms-2026.md#9-ranked-memory-search-with-provenance-s-for-fts5-and-provenance-m-for-embeddings-medium)).*
- [x] **Skill import** (S) from a GitHub link or zip, as a draft to approve. Done: see "Skill import" in Phase 2b (#27).
- [ ] **Translated UI** (M, ongoing), falling back to English for missing strings.
- [ ] **Workspace AGENTS.md** (S), read when a chat starts. *Nov 2026: the base for Projects ([product C1](research/product-platform-2026.md#c-competitor-features)).*
- [ ] **Calendar and contacts read access** (S–M), read-only, Ask by default.

**Improve what we have**
- [ ] Sub-agents: a default cheaper helper model, and the cost of each helper run. (Steer is done.)
- [ ] Scheduled tasks: let a run propose a write as an approval card; pause when the user takes over; unmetered-only. (Never sending a prompt twice is done: a per-run marker stops a retried worker from re-sending it, see [scheduled runs](design/scheduled-runs.md). Run now exists.)
- [ ] Memory: show which facts were read in the steps card, with Forget in place. *Nov 2026: a per-turn "receipts" line covering memories, skills and connectors ([product U3](research/product-platform-2026.md#u-ux-patterns)).*
- [ ] Compaction: editable summary and visible progress.
- [ ] Plan mode: plan on one model, execute on another. *Nov 2026: Anthropic's advisor tool (beta) does this within one chat ([algorithms, also worth doing](research/agent-algorithms-2026.md#also-worth-doing)).*
- [ ] Browser: element picker and "see the page as it was" for a past call.
- [ ] SSH: an SFTP folder as a workspace; preview-first sync.
- [ ] Linux: several terminal sessions. *Nov 2026: start with one interactive PTY terminal (Termux's terminal libraries, licence to check) and a "Run a coding CLI" template ([product C3](research/product-platform-2026.md#c-competitor-features)).*

**Avoid (from their bug reports)**
- [x] A hard token budget for the system prompt's memory, skills and persona parts, with a small-context fallback (on-device Gemma). `PromptBudget` (core/agent): per-part budgets, the whole prompt at most a fifth of the window, compact budgets at 32K or less; a chat switched to a smaller model later keeps the prompt it started with.
- [x] Nothing volatile in the cached prefix; a stable OpenAI `prompt_cache_key` per chat and helper profile. The prefix was already stable (system prompt frozen at chat creation, with only the creation date and the frozen recent-chat list; constant tool specs in a fixed order). `PromptCacheKey` (core/llm) sends `locus-chat-<id>` for a chat, its compaction and reflection, its forks, and `locus-helper-<type>` for fresh helpers, on the Responses and ChatGPT routes; dropped once if a server refuses the field.
- [x] Token counts from provider usage, reasoning included; no byte-count estimates for non-Latin text. Compaction and the context ring already used reported usage only, and no byte-count estimate existed (the new prompt budget counts characters). Reasoning was already inside output for Anthropic, Responses and OpenAI Chat Completions; now `TokenUsage.reasoning` records the reported share, Insights shows it, and Chat Completions servers that leave thinking out of `completion_tokens` are counted from `total_tokens`.
- [x] A test that switching providers never sends the wrong key. Keys were already stored per provider id and every cloud model is built in `ProviderAccess`; `ProviderKeyIsolationTest` (core/data) switches a chat across Anthropic, OpenAI, Groq, a keyless Ollama and ChatGPT over MockEngine and checks every request's headers and body.
- [x] Retry streams that drop mid-way, not only 429 and 5xx. `AgentLoop` already retried retryable provider errors and a stream that ended without a response; it now also retries an `IOException` (reset, unexpected EOF, timeout) with the same bounded backoff, discarding the partial reply, unless that attempt had started a tool call. Tools only run after a completed turn, so nothing runs twice.
- [ ] Visible health for Linux, MCP, SSH and the browser; never fail silently. *Nov 2026: a list of background work, as ChatterUI's Notices ([product C4](research/product-platform-2026.md#c-competitor-features)); streams can hang up to 10 minutes after a network change ([audit #11](research/reliability-security-audit-2026-11.md#11-network-changes-mid-stream)).*
- [ ] Page long transcripts; don't rebuild every request from the database; watch memory with large tables. *Nov 2026: the chat query loads `rawPayload` for every message and re-runs on any chat's change; add a UI projection without it, then Room 3 paging over the active branch ([audit #12](research/reliability-security-audit-2026-11.md#12-transcript-query-and-paging)).*
- [x] Restart Linux background services when the sandbox comes back. Done as "report, don't restart": each service is its own proot process that ends with Locus, and the in-memory list was lost on restart, so `linux_service_list` said "No background services". Started services are now recorded in `linux/services.json`; after a restart they're listed as stopped with how to start them again. Automatic restart is left out on purpose (it would run a command without a fresh approval). Uninstalling Linux now stops its services first.
- [x] A failed scheduled run says it failed instead of showing old results. Every ending is recorded on the task and notified, a stop by Android with its reason ("Stopped by Android (network lost)"), within an 8-minute budget under WorkManager's 10-minute limit; Tasks shows "Last run failed" or "Last run stopped". See [scheduled runs](design/scheduled-runs.md).
- [ ] Any future local network API requires a token from day one.

**From the second research round** ([more-apps-and-platform.md](research/more-apps-and-platform.md))
- [x] **Enable Anthropic prompt caching** (S): Locus never sent `cache_control`, so Anthropic chats weren't cached. Two breakpoints since #28; cache hits not yet confirmed against the live API.
- [x] **Android 17 local network permission** (S–M): ask before connecting to LAN providers, SSH or MCP (#28; in setup too since #44). *Not yet tried on an Android 17 device.*
- [x] **Up-to-date model capabilities** (S): read them from the Anthropic Models API; refresh the server-compaction list (#28).
- [ ] **Server compaction for OpenAI Responses**, and on-demand compaction for Anthropic (S–M). The Responses request shape is verified (`context_management: [{type: "compaction", compact_threshold}]`, docs/design/compaction.md → Not done); which models support it isn't documented, and the stateless replay would have to keep the encrypted compaction item. Compaction inside a run is done (docs/design/compaction.md; [algorithms #1](research/agent-algorithms-2026.md#1-compaction-inside-a-run-plus-recovery-from-overflow-m-very-high)).
- [ ] **Start the in-run summary early in the background** (about 70%, as Codex does): needs a checkpoint that names the message it covers up to (docs/design/compaction.md).
- [x] **Server task budget** (S): Anthropic's `output_config.task_budget` with task budgets, see above. Left out for Claude Sonnet 5.5, whose migration guide advises against task budgets on interactive sessions, and for third-party gateways.
- [ ] **Cache-safe mid-chat changes** (S–M): Anthropic mid-conversation system messages and OpenAI configuration updates for steering and effort changes without losing the cache.
- [x] **"What was sent" viewer** (S–M): the exact request per turn, secrets scrubbed (#29).
- [x] **On-device backend truth** (S): show the backend that actually ran, why it fell back, and tokens/s; optional benchmark, never uploaded (#30).
- [x] **Model gating by RAM** (S): minimum RAM per model, a loaded-models view with eject; never key on device names (#30: it warns rather than blocks, and the model sheet shows whether the model is loaded, with Unload).
- [ ] **llama.cpp via Arm's Kotlin binding** (L): Hugging Face GGUF search, quantisation picker, use a file in place.
- [ ] **On-device speech** (M–L): sherpa-onnx for speech-to-text and Kokoro text-to-speech, then hands-free voice. *Nov 2026: a hands-free loop on the system recogniser and TTS can come first (voice mode, below).*
- [ ] **Local document search** (M): chunk and embed workspace PDFs on the device, a `docs_search` tool.
- [ ] **Local OpenAI-compatible endpoint** (M): serve the on-device model on localhost, with a token from day one.
- [ ] **Named personas** (M): several personas with their own model, skills and connectors, fixed when a chat starts.
- [ ] **MCP:** a Client ID Metadata Document on GitHub Pages (S); stateless MCP and elicitation through ask-user once the Kotlin SDK supports it (M); MCP Apps in a sandboxed WebView (M–L).
- [~] **F-Droid readiness** (M): proot from source, no dependency-info block, opt-in Linux download, NonFreeNet, reproducible builds, fastlane metadata. Done in #33 except reproducible builds (unverified; the committed `libproot.so` needs rebuilding) and the submission itself; see docs/FDROID.md. *Nov 2026: Google's developer verification (global in 2027) also applies to F-Droid and GitHub installs, and IzzyOnDroid is an earlier channel (see below; [product D1, D2](research/product-platform-2026.md#d-distribution)).*
- [~] **Hardening:** zip-slip and secret-free imports for skills and connectors (S; zip-slip and path checks for skill import done in #27); a 32-bit and unsupported-architecture check before installing Linux (S); streaming tests for RTL and unusual Unicode (S); `FLAG_GRANT_READ_URI_PERMISSION` on every share (S). *Nov 2026: security hardening from the audit (release signing, intents, taint, vault restore) is in progress; see below.*

**Ideas that conflict with our principles (not planned)**
- Phone control through Accessibility, screen-reading models or virtual displays; shell through Shizuku, ADB or root (controls other apps).
- A notification listener reading other apps (only ever as an opt-in allow-list, if at all).
- A Locus cloud relay, cross-device sync through a server, or free models through our servers (not local-first).
- Trimming old reasoning or tool output mid-chat, toggling tools per chat, or re-sending changing context in the system prompt (breaks prompt caching; Locus caps tool output when it's written and refuses tools at run time instead). *A cache-neutral variant, clearing only after the cache has expired, is listed below ([algorithms #6](research/agent-algorithms-2026.md#6-clear-old-tool-results-only-when-the-cache-is-cold-m-medium-high)).*
- Keyless providers or relays that hold user keys, and subscription sign-in that imitates official clients.

## From the November 2026 research

Third research round (2026-11-01): [agent algorithms](research/agent-algorithms-2026.md), [product and platform](research/product-platform-2026.md), and a [reliability and security audit](research/reliability-security-audit-2026-11.md). Only new items are listed here; where an item already existed above, it carries a note with the new evidence instead. Effort: S, M, L. `[~]` marks work in progress on another branch.

**In progress**
- [~] **Release signing** (S, high): stop signing the debuggable APK with the release key, or give it its own application id. [Audit #1](research/reliability-security-audit-2026-11.md#1-debuggable-apk-signed-with-the-release-key). *In progress.*
- [~] **Vault in the restore journal** (S–M, high): a rolled-back restore must also roll back the vault, and a failed reschedule must be reported. [Audit #2](research/reliability-security-audit-2026-11.md#2-failed-restore-leaves-the-vault-replaced). *In progress.*
- [~] **Intent hardening** (S, medium-high): the share target accepts only `content://` from other apps, with a size cap and a visible list of failed imports; `EXTRA_OPEN_CHAT` only from Locus's own pending intents. [Audit #4 and smaller issues](research/reliability-security-audit-2026-11.md#4-share-target-copies-file-uris). *In progress.*
- [~] **Taint tracking and egress control** (S–M, very high): mark every untrusted source (`fetch_url`, `mcp_call`, `connector_call`, command and SSH output), track taint per run, and after taint raise outward calls and URL-taking reads to new hosts to Ask; memory, skill and connector writes become drafts. Covers the memory-poisoning path. [Algorithms #2](research/agent-algorithms-2026.md#2-taint-tracking-and-egress-control-sm-very-high), [audit #6, #7](research/reliability-security-audit-2026-11.md#6-fetch_url-and-mcp_call). *In progress.*
- [~] Fallback chains (see "Ideas from comparable projects") and change tracking with undo (#68) are also being built now.

**Agent core**
- [ ] **Compaction inside a run, plus overflow recovery** (M, very high): compact at a step boundary at 80% of the window, keep the last ~20k tokens of the user's own messages and the todo list verbatim, treat an overflow error as "compact and retry once", and apply it to helpers. Without it, long chats on non-Anthropic providers end as failed since #41 removed the step limit. [Algorithms #1](research/agent-algorithms-2026.md#1-compaction-inside-a-run-plus-recovery-from-overflow-m-very-high)
- [ ] **Deferred tool loading** (M, high): Anthropic tool search and OpenAI `tool_search`, chosen at chat creation so the prefix stays cached; ~110 tools are declared on every request today. Measure tool tokens first with "What was sent". [Algorithms #3](research/agent-algorithms-2026.md#3-deferred-tool-loading-on-anthropic-and-openai-m-high)
- [ ] **Full MCP schemas** (S, medium-high): `mcp_list_tools` returns compact JSON Schemas; `mcp_call` validates arguments before the network call. [Algorithms #4](research/agent-algorithms-2026.md#4-full-mcp-schemas-and-argument-checks-s-medium-high)
- [ ] **Spill long output of every tool** (S, medium-high): head and tail inline, the rest in `.locus/out/`, as `run_command` already does; a tighter cap for helpers. [Algorithms #5](research/agent-algorithms-2026.md#5-spill-long-output-of-every-tool-to-a-file-s-medium-high)
- [ ] **Clear old tool results only when the cache is cold** (M, medium-high): wire view only, after the cache lifetime has passed or at compaction, so a warm cache is never broken. A cache-neutral variant of the "not planned" trimming below. [Algorithms #6](research/agent-algorithms-2026.md#6-clear-old-tool-results-only-when-the-cache-is-cold-m-medium-high)
- [ ] **1-hour cache lifetime and cache-hit tracking** (S, medium): `ttl: "1h"` on the tools-and-system breakpoint (phone gaps often exceed 5 minutes); cache reads and writes per step in Insights. [Algorithms #7](research/agent-algorithms-2026.md#7-longer-cache-lifetimes-and-measuring-hits-s-medium)
- [ ] **Parallel read-only calls** (S–M, medium): consecutive Allow observations without a lease run together; results stay in order. [Algorithms #8](research/agent-algorithms-2026.md#8-parallel-read-only-calls-sm-medium)
- [ ] **Evidence-based completion check** (S, medium): one hidden note when a turn ends with open todos, a failing last command, or edits after the last test. [Algorithms #10](research/agent-algorithms-2026.md#10-evidence-based-completion-check-s-medium)
- [ ] **Compaction failure notice** (S, low-medium): a failed compaction is swallowed today (`AgentRunner.kt:280`). [Audit, smaller issues](research/reliability-security-audit-2026-11.md#smaller-confirmed-issues)

**Security**
- [ ] **Linux `/proc` exposure** (M; L for process isolation; high): bind a filtered `/proc`, drop `/sys`, keep tool policies and approval state where same-UID processes can't change them (AEAD DataStore or a Keystore MAC), later an isolated process for Linux; document it in SECURITY.md. *Unverified on the device.* [Audit #3](research/reliability-security-audit-2026-11.md#3-proot-is-not-a-security-boundary)
- [ ] **Browser gate fails closed** (S–M, medium): a missing or failed inspection asks; helper captured at document start; native cross-checks; explicit Safe Browsing. [Audit #5](research/reliability-security-audit-2026-11.md#5-browser-sensitive-action-gate-fails-open)
- [ ] **`fetch_url` limits** (S, medium): a streamed byte cap, refusing loopback, link-local and private addresses unless allowed, https for agent requests; first call of a non-vendor MCP read tool asks. [Audit #6](research/reliability-security-audit-2026-11.md#6-fetch_url-and-mcp_call)
- [ ] **Release supply chain** (M, medium): actions pinned by SHA, keystore only in a separate signing job, Gradle dependency verification, build attestation. [Audit, smaller issues](research/reliability-security-audit-2026-11.md#smaller-confirmed-issues)
- [ ] **Dependency checks** (S): confirm the bundled SQLite version and run `PRAGMA quick_check` on restored databases; confirm the jsoup fix; weekly Dependabot security updates and `osv-scanner`. [Audit, CVEs](research/reliability-security-audit-2026-11.md#dependency-cves-checked-2026-11-01)

**Reliability and performance**
- [x] **Guard background `startForegroundService`**: when Android refuses the agent service for a turn Locus started on its own (a helper's report, a queued message), the turn waits with Continue in the chat and a "tap to continue" notification instead of crashing. See [scheduled runs](design/scheduled-runs.md#chats-continuing-in-the-background).
- [x] **Crash and exit history**: a local crash log from an uncaught-exception handler, `ApplicationExitInfo` exits since the last start, a `CoroutineExceptionHandler` on the app scope, DataStore corruption handlers, and recovery when the Keystore key is invalidated, all under Diagnostics. See [diagnostics](design/diagnostics.md).
- [ ] **Network drops** (S–M, medium): an idle timeout for streams instead of 10 minutes, cancel streams on a default-network change, a total timeout for `fetch_url`, the MCP client built from the shared HTTP client. [Audit #11](research/reliability-security-audit-2026-11.md#11-network-changes-mid-stream)
- [ ] **Startup and memory** (M, medium): baseline and startup profiles, release ABIs limited to arm64-v8a and x86_64, recovery from a "runs in flight" marker instead of scanning every chat, lazy vault init, idle unload of the on-device model. [Audit #9](research/reliability-security-audit-2026-11.md#9-startup-and-memory)
- [ ] **Linux kill reason** (S, medium): say "killed by Android (phantom process limit)" when a proot child dies from signal 9 in the background. [Product B3](research/product-platform-2026.md#b-background-work)
- [ ] **Android 17 checks** (S): read-aloud with the screen off; custom CAs under the Certificate Transparency default. [Product B4](research/product-platform-2026.md#b-background-work)
- [ ] **Measurement** (M): a macrobenchmark module (startup, long chat, streaming), Compose stability config and `contentType` on chat rows. [Audit, performance](research/reliability-security-audit-2026-11.md#performance-measurement)
- [ ] **Static analysis and coverage floors** (S–M): lint for the JVM modules, detekt or ktlint with Compose rules, a rule against `runCatching` around suspend calls, per-module Kover floors, JVM Compose tests for the composer. [Audit, engineering](research/reliability-security-audit-2026-11.md#engineering)

**Product and platform**
- [ ] **Projects** (M, high): a workspace subfolder with its own AGENTS.md (frozen at chat start), default model and persona, memory scope and chat list. Builds on "Workspace AGENTS.md". [Product C1](research/product-platform-2026.md#c-competitor-features)
- [ ] **Voice mode** (M, then L; high): a hands-free loop of system speech, the agent and TTS with barge-in, then OpenAI Realtime / Gemini Live with the user's key. Complements "On-device speech". [Product C2](research/product-platform-2026.md#c-competitor-features)
- [ ] **Photo Picker and camera** (S, medium) in the composer's +. [Product P2](research/product-platform-2026.md#p-android-platform)
- [ ] **Android 17 polish** (S, medium): Live Update semantic colours, `FLAG_SECURE` on key and vault sheets, a StrictMode check for implicit URI grants. [Product P3](research/product-platform-2026.md#p-android-platform)
- [ ] **Quick start in onboarding** (S, medium): skip identity, Linux, SSH and MCP after the model step; skipped steps return as cards on Home. [Product U4](research/product-platform-2026.md#u-ux-patterns)
- [ ] **Sign-in in a Custom Tab** (S, low) instead of the full browser. [Product P9](research/product-platform-2026.md#p-android-platform)

**Distribution**
- [ ] **Developer verification** (S, high): register `dev.locus` and the release key through the open-source registration before the 2027 global rollout; note it in FDROID.md and RELEASING.md. [Product D1](research/product-platform-2026.md#d-distribution)
- [ ] **IzzyOnDroid with per-ABI APKs** (S, high): the 0.1.3 APK is at the ~30 MB guideline. [Product D2](research/product-platform-2026.md#d-distribution)
- [ ] **A reduced Play flavour** (L, uncertain): no Linux download, a report action, Gemini Nano allowed. Only if wanted. [Product D3](research/product-platform-2026.md#d-distribution)

## Phase 2b: Learns as it goes

- [x] **Memory.** The agent saves facts and preferences as it works, recalls them in later chats, and shows you all of it to edit or forget.
- [x] **Skills.** Reusable instructions and scripts in the Agent Skills format (a folder with `SKILL.md`). Only each skill's name and description sit in context until one is needed.
- [x] **Connectors.** Plug in services through MCP servers, or as simple HTTP API connectors whose keys stay in the vault.
- [x] **Auto skills.** When asked, or after a task it had to work out, the agent drafts a new skill. You review it before it becomes active, and every version can be rolled back.
- [x] **Skill import.** Library → Skills → Import takes a GitHub link or a zip. It is checked against the Agent Skills spec and size limits and always lands as a draft you review (SKILL.md as written, every file, the source link and commit) before approving.
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
- [x] **Telegram** reach-me channel: Settings → Integrations → Telegram takes a bot token from @BotFather and a chat id.
  - Find my chat calls `getMe`, then `getUpdates` once without an offset (so nothing is confirmed) and lists the chats that messaged the bot; no bot server or polling keeps running. A webhook on the bot (409) gets a clear message.
  - The vault holds `TOKEN CHAT_ID`; the model never sees either. The token is part of the request path, so every error and exception message is scrubbed of it.
  - `notify_me` goes to every configured channel (ntfy and/or Telegram) instead of adding a tool, so the per-chat tool list stays fixed. Scheduled task results do the same. Priority 1–2 sends silently.
  - Markdown becomes Telegram HTML (only `<`, `>`, `&` need escaping there); if Telegram can't parse it, the part is resent as plain text. Text is split at lines into ≤3,500-character parts (the limit is 4,096 after entities parsing), code blocks are reopened across parts, at most five parts a second apart. A 429 with `retry_after` ≤ 30 s is waited out and retried.
  - Sources: core.telegram.org/bots/api (Making requests, sendMessage, Formatting options, getUpdates, ResponseParameters) and core.telegram.org/bots/faq (limits). Covered by MockEngine tests (`TelegramTest`); *not yet tried against the live API or on the device.*
- [ ] Inbound Telegram: treat messages to the bot as user messages, only from the configured chat id, off by default. Needs a polling design (WorkManager can't poll more often than every 15 minutes; a foreground long-poll costs battery) and a decision on what such a message may do (approvals, read-only like scheduled tasks?).
- [ ] Matrix bot as another reach-me channel.

## Phase 4: Fully local models

- [x] On-device inference with LiteRT-LM 0.19.0 (Gemma 4 E2B 2.6 GB / E4B 3.7 GB, Apache-2.0, SHA-256-verified resumable download, GPU with CPU fallback, tool calling with `automaticToolCalling = false` and a focused tool set). Downloads run as a WorkManager foreground job with progress, so they continue in the background.
- [ ] llama.cpp for GGUF models.
- [ ] Gemini Nano via the ML Kit Prompt API where the device supports it. *Nov 2026: inference runs only while Locus is the top foreground app (a foreground service doesn't count), so only for helper jobs, in a separate Play flavour ([product P7](research/product-platform-2026.md#p-android-platform)).*

## Phase 5: F-Droid extras

- [x] Minimal phone actions through standard Android intents (`device_*`): calendar events, alarms and timers, email/SMS drafts, dialer, directions, links, opening apps. The other app opens prefilled and the user confirms there. **No AccessibilityService by design**: Locus does not read or operate other apps.
- [ ] Bundled command-line tools (busybox, git) and an optional Termux bridge (`RUN_COMMAND`).
- [ ] Expose Locus actions to other apps through AppFunctions. *Nov 2026: still a limited preview; wait ([product P8](research/product-platform-2026.md#p-android-platform)).*
