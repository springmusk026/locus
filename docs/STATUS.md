# Development status (handoff notes)

Last updated 2026-10-31. Read this first when resuming work.

## Environment
- JDK: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- SDK: `~/Library/Android/sdk` (platform 37, NDK r29 at `ndk/29.0.14206865`)
- Device: Galaxy S25 Ultra (SM-S938B), Android 17 / API 37, adb serial R5CY74C3K6B, 720x1560 px.
- On-device UI helpers are in the session scratchpad `ui.sh`. They are guarded so they only act when Locus is the top window and refuse shell metacharacters, because `adb shell input text` goes through the device shell. The user also uses the phone, so never tap when Locus isn't in front.
- Native: the user runs `tools/build-proot.sh` themselves (the auto-mode classifier blocks it). Its output lands in `core/linux/src/main/jniLibs`. The current binaries link talloc dynamically; `LinuxEnvironment.linkSharedLibraries()` aliases it to `libtalloc.so.2`. The script now builds talloc statically for the next rebuild.

## Done and verified on device
Chat and streaming:
- Providers: OpenAI-compatible (OpenAI, OpenRouter, Groq, Gemini, Ollama, LM Studio) and Anthropic.
- Markdown rendering.

Agent loop:
- Approvals in-app and from notifications.
- Read-only mode allows only observation calls (`Tool.isObservation`).
- Per-call forced confirmation (`Tool.mustConfirm`).
- Per-tool default policy (`Tool.defaultPolicy`).

Workspace:
- File tools, trash and restore.
- `DocumentsProvider`, so the workspace appears in the Files app.

Browser (`core/browser`):
- WebView with a separate profile and an injected `locus-agent.js` that produces ref snapshots.
- Live view, handoff to the user, and sensitive-click confirmation.

Linux (`core/linux`):
- Alpine 3.24.3 under proot; `run_command`; `/workspace` bind; TZ set from the phone.

Learning (`core/agent/home/AgentHome`):
- Memory index plus topics; Agent Skills (`SKILL.md`) with drafts that need approval.
- HTTP connectors and MCP connectors (`kind:"mcp"`), with vault secrets.
- Library screen with Memory / Skills / Connectors tabs.

SSH (ConnectBot sshlib 0.6.6, JVM Tink excluded):
- Pinned host keys, `ssh_exec`, `sftp_*`, a Settings section, and a fingerprint probe.

Scheduled tasks:
- WorkManager with Hilt workers; read-only; result notification. Room schema v2.

## Known gaps and next steps
1. Done: MCP discoverability. There is now a floating "Add MCP server" button on Library → Connectors, plus Settings → Connectors & MCP servers. `connector_create` and the system prompt know about `kind:"mcp"`.
2. **No AccessibilityService, by the user's decision** ("minimal control, nothing shady"). That module was removed. Phone actions go through standard intents instead (`core/data/device/DeviceTools.kt`, `device_*`): the other app opens prefilled and the user confirms there.
2b. Interrupted runs: `recoverTranscript` (core/agent/Recovery.kt) answers dangling tool calls when the app starts, and Chat shows "Continue" (`AgentRunner.resume`). Unit-tested; device test still pending (the user was using the phone).
2c. Linux background services (`linux_service_start/stop/list`) for dev servers, previewed via browser_open at http://localhost:PORT. Built; not yet device-tested.
**Device etiquette:** never reinstall or force-stop while the user is using Locus or a run is in progress. Debug builds are their own app, `dev.locus.debug` ("Locus Debug"), installed next to the release one; use that id for a debug build. Detect a run with `adb shell dumpsys activity services dev.locus.debug | grep isForeground=true`. Don't grep notification text: plain `dumpsys notification` redacts it, so a "Locus is working" grep always returns 0. That broken check let an install interrupt a live run on 2026-10-31. Check immediately before `adb install` (scratchpad `ui.sh` → `safeinstall`), and don't install while Locus is the top app.
2d. On-device models (`core/ondevice`): `OnDeviceModels` (catalog, resumable SHA-256-verified downloads to filesDir/models) and `OnDeviceEngines` (one LiteRT-LM engine, GPU→CPU fallback). `OnDeviceChatModel` rebuilds a LiteRT conversation from history each step and uses a focused tool set. ApiFormat.ON_DEVICE. The APK grew to ~47 MB (liblitertlm_jni.so).
2e. Per-chat provider switcher (ChatViewModel.setProvider → conversations.providerId).
2f. Verified on device: Gemma 4 E2B on-device chat and tool calling (write_file), including in the R8 release build (`consumer-rules.pro` keeps LiteRT-LM). Model downloads now run in `ModelDownloadWorker`, a dataSync foreground job.
2g. Share to Locus (`ShareViewModel`, launchMode singleTask, ChatRoute.draft) and voice input (RecognizerIntent). Built, not yet device-tested; adb went "unauthorized".
Pending device tests: Continue after a mid-run kill, Share to Locus, voice input, device_* intents.
2h. Attachments (`Part.Attachment`, `WorkspaceRepository.importAttachment`, adapters in core/llm/Attachments.kt) and UI pass 1 (composer, Suggestions, ToolGroup, copy). Firecrawl integration (`core/agent/integrations`, Settings → Integrations, secret name `integration-firecrawl`). Firecrawl was verified live: the user searched "nepalcloud" with their own key. Weather and Wikipedia were verified against the live APIs with curl and in unit tests. The composer is now a BasicTextField, so device scripts must tap it by position (the "Message Locus" placeholder is not a findable node).
2i. Design system: `core/designsystem/Accents.kt` (Accent, accentFor, AccentTile, ProviderBadge, ThinkingDots). Model lists: `core/llm/ModelLister.kt` plus ModelField in the provider editor. Home, chat tool rows, settings permissions, activity log and workspace were restyled. Home was seen on the device; the rest still needs checking.
2j. Design system pass (2026-10-31). Every screen now uses `core/designsystem` (Components.kt; Theme.kt `Spacing`/`Radius`/`Locus.colors` status colours; `MonoCaption`). Rules: colour encodes meaning (live, caution, critical; brand for the user's choices); category hues only on tool tiles; only a live dot animates. `toolLabel`/`toolActivity` moved to `core/model/ToolLabels.kt`, so Activity uses the same wording as Chat. Reviewed on the device in dark and light.
   Fixed along the way: Back on the home screen emptied the Nav3 back stack and crashed ("NavDisplay backstack cannot be empty"). `pop()` in MainActivity now finishes the activity instead. Also: Gemini's OpenAI-compatible URL was badged as OpenAI; blank assistant messages rendered as a lone copy icon; and the on-device adapter now reads LiteRT-LM `Message.channels` as a fallback and raises a retryable error on an empty reply.
   Device-automation note: the live dot's infinite animation can keep uiautomator from going idle, so `tapdesc` may report "not found". Retry, or tap by bounds.
2k. Built while the user was using the phone (code only; all unit-tested; none device-tested yet):
   - Chat search and pinning (Room v3).
   - "Try again" after errors, and long-press to edit a message as a new one.
   - The latest task result shows on home.
   - Readable width on tablets.
   - Reflection step ("Learn after tasks" switch).
   - On-device image input.
   - SSH background jobs (`ssh_job_*`, `RemoteJobs`).
   - Tavily, Brave and Exa search backends.
   - GitHub tools.
   - MCP OAuth (`McpOAuth` and `McpSignIn`).
   Device-tested 2026-10-31:
   - Search, and pin/unpin.
   - Long-press → copy, and edit as a new message.
   - The approval card, and Activity labels.
   - Settings: integrations, and the "Learn after tasks" switch.
   - SSH jobs on the real server.
   - MCP OAuth up to consent, plus Cancel and the listener's rejection of forged requests.
   - Share to Locus (text).
   - On-device image input.
   Still untested: reflection (needs a 6+ tool task), the Responses adapter (no OpenAI key), GitHub, Tavily/Brave/Exa and ntfy with real keys, and Share with an image from a real app (adb can't grant media URI access).
   An independent code review (2026-10-31) found these, all now fixed:
   - Connector `validate()` rejected `auth.type = "oauth"`, so OAuth servers could never be saved. Oauth is now allowed for MCP only.
   - Cached MCP clients kept expired tokens. Locus now reconnects when the token changes, and retries once after refreshing, on auth failures only, so writes never run twice.
   - The loopback listener could be aborted by any stray connection, or hijacked by a page hitting 127.0.0.1:33418/callback. It now ignores bad connections, accepts only this sign-in's `state`, says "Signed in" only after the token exchange, and caps the request at 8 KB.
   - `ssh_job_stop` left grandchildren running. It now freezes and terminates the whole process tree; a test checks a grandchild dies.
   - Job snippets assumed a POSIX login shell. They're now sent as `echo <b64> | base64 -d | sh`.
   - Reflection ignored the user's memory_save policy and kept the chat "running". It now honours the policy (Ask counts as no), runs after the chat is done, and its prompt says to ignore instructions from tool output.
   - OAuth sessions were reachable through `connector:` secret names, so a drafted connector could exfiltrate the refresh token. They now live under `mcp-oauth:`.
   - Hardening:
     - HTTPS is required for every OAuth endpoint.
     - The PRM `resource` must match the server.
     - offline_access is only added alongside real scopes.
     - A dead client registration is dropped.
     - Discovery errors are shown instead of silently saving the server without auth.
     - GitHub `owner/..` is rejected.
     - The on-device stream buffer is now unlimited.
     - Search no longer matches JSON keys and no longer flashes every chat while typing.
2l. 2026-10-31 parallel round 1 (five agents, merged and device-tested):
   - `:feature:onboarding` (9 steps; persona files IDENTITY/SOUL/USER/AGENTS.md in agent-home, read by `SystemPrompt`).
   - Settings hub with sub-screens.
   - Chat streaming via the renderer's streaming state; `TranscriptFollower` auto-scroll; one turn shown as one answer (`ChatItem.Note`, `TurnUsage`).
   - Email (`Email.kt`, Angus Mail).
   - On-device: `StreamCoalescer`, a dedicated `locus-litert` thread, KV-cache conversation reuse (`TranscriptPrefix`), `GemmaCallRepair`.
   Round 2, in progress:
   - Real provider and model logos.
   - An onboarding questionnaire instead of raw file entry, and the step-5 state fix.
   - Developer integrations (Vercel, Netlify, Cloudflare…).
   - A home redesign for scale, and an analytics screen.
   - A composer with model, effort and tools pickers; plan mode; todos (DB v5).
   How these rounds work: each agent owns separate files and Room schema changes go to one owner per round. The main session integrates, runs the full test and lint pass, and device-tests.
2l. Composer controls, plan mode, todos, kept reasoning (code + unit tests; not device-tested). Room v5 (AutoMigration 4→5: conversations.modelOverride, effort, planMode, disabledTools).
   - Composer second row: model chip (sheet of providers and their fetched models, per-chat `modelOverride`), thinking chip (Effort Off/Low/Medium/High/Max → Anthropic `output_config.effort`, Responses `reasoning.effort`; hidden for Chat Completions/on-device), Tools chip (per-chat tool groups and connectors switched off; tools stay declared and refuse via `AgentConfig.restrict`/`ChatPolicy`, so the cached prefix is kept), Plan toggle.
   - Plan mode: user messages carry a hidden `<locus_mode>` note (`ModeNote`); only observation tools run; `plan_propose` ends the turn (`Tool.endsTurn`) and renders a plan card (Approve and run / Edit / Cancel). Approve turns plan mode off and sends `AgentControlTools.PLAN_APPROVED`.
   - `todo_write` (replace-the-whole-list) → live checklist pinned above the composer for the current turn.
   - Reasoning: `Part.Reasoning(content, durationMs)` kept on assistant turns (stripped before every request by `Message.forModel`), live "Thinking · Ns" block, "Thought for Ns" rows afterwards.
2m. 2026-10-31 round 3 (merged, full tests and lint green, installed and device-tested): code highlighting, tab-less live panel, approval reviewer, Activity runs/search/filters, the shared composer on home, `send_file`, workspace file viewers, sub-agents (Room v6, AutoMigration 5→6, verified on the real DB). Follow-ups done: a Settings switch for "continue when a helper reports back" (Helper model & extras), helper runs labelled in Activity and rolled into their parent chat in Insights, a background helper's approval no longer locks the composer, `agent_*`/`todo_write`/`plan_propose` get the Agent accent and a "Helpers & plans" filter.
   Not yet device-tested: the reviewer, helper approvals from the queue, Stop on a helper row, Library → Agents drafts.
3. Done: MCP OAuth (see 2k).
4. Done: sandboxed JS hooks for connectors (`Connector.script`, `ScriptEngine` in core:agent, `SandboxScriptEngine` in core:data). Unit-tested with a fake engine; the real sandbox is not yet device-tested.
5. A reflection step after each task.
6. On-device models (LiteRT-LM).
7. Git is not initialised; the user hasn't asked for commits.

## Conventions
- MVVM: one `uiState` StateFlow per ViewModel; features depend on `core:data` and never on `core:database` entities.
- Research and cite sources before adding a dependency, and check the version on Maven.
- The tool list is fixed per conversation (prompt caching). Dynamic catalogs go in the frozen system prompt snapshot.
