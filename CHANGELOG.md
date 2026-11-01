# Changelog

All notable changes to Locus are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Security
- **Linux could read Locus's own files through `/proc`.** Linux commands run as the app's own user. Any command, package script or local MCP server could open `/proc/<Locus pid>/fd/<n>` and read, and probably write, the files Locus had open: the chat database, and the settings that hold the tool permissions. Confirmed on a phone. Now:
  - Locus's processes are non-dumpable, so the kernel refuses their open files, memory and environment to any other process, including ones with the same user.
  - proot also hides `/proc/<pid>` of every Locus process and thread, and the binder devices. Linux programs can't call Android services (the Keystore among them) as Locus.
  - Tool permissions, the AI reviewer's settings and connector approvals are sealed with an HMAC key in the Android Keystore. If they're changed outside Locus, Allow settings are dropped, the reviewer is turned off, changed connectors go back to drafts for review, and Locus tells you. A restored backup is sealed with this phone's key.

  `/workspace` and `/tmp` are still shared with Linux on purpose, and Linux commands can still see each other. SECURITY.md and docs/design/linux-isolation.md cover what is and isn't isolated. `tools/verify-linux-isolation.sh` checks it on a phone.
- **Taint.** Once a run has read outside content (a web page, an email, an MCP or connector reply, command output, a helper's report), these need your OK:
  - anything that sends or changes something outside the phone;
  - opening or fetching a site you haven't named or set up in Locus;
  - saving to memory, creating a skill, or creating a connector.

  The approval card (and the safety reviewer, when it's on) says why. Scheduled read-only runs refuse these calls instead, and the after-task reflection saves nothing after such a run. The system prompt and tool list don't change, so the prompt cache still hits. See docs/design/taint.md.
- `fetch_url`, `mcp_call`, `connector_call`, `run_command` and `ssh_exec` results are now marked as untrusted, like web pages and email.
- `fetch_url` changes:
  - It refuses localhost, link-local and private network addresses, unless you set up that host in Locus (a model provider, MCP server or connector).
  - It checks every redirect, doesn't downgrade https to http, and doesn't pass your headers to another host.
  - It reads at most 2 MB of a response instead of the whole body, and gives up after 60 s.
- Memory notes record who saved them (you, Locus, or the after-task reflection), when, and in which chat. Library shows this, and flags a note saved after Locus read outside content so you can check it.
- The browser's confirmation for logins, payments and submits failed open: when the page didn't answer the check, the click went ahead. It now asks. The page helper is injected before the page's own scripts and can no longer be replaced by the page.
- Releases published a debuggable APK signed with Locus's release key, under the same app id. It installed over Locus and kept its data, so anyone with adb could read that data (chats, settings, the vault's keyset) with `run-as`. Debug builds are now never signed with the release key, and are a separate app (`dev.locus.debug`, named "Locus Debug") with their own storage, providers and sign-in return link. Releases no longer include a debug APK; the R8 mapping still makes crash reports readable. A debug build from source now installs next to Locus instead of over it.
- "Share to Locus" took `file://` links, so another app could make Locus copy its own private files (its database, its keys) into the workspace. Only `content://` shares from other apps are taken now, never ones that point back into Locus, and a shared file is limited to 200 MB. A shared file that isn't imported is named with the reason, instead of being dropped silently.
- Any app could open Locus on a chat of its choosing with the extra Locus's notifications use. Only Locus's own notifications can open a chat now: they carry a random token that's checked when Locus opens.
- Restoring a backup that included the vault replaced your saved keys before the restart, so if the rest of the restore then failed and was rolled back, the old keys were already gone while Locus said "your data is as it was before". The vault is now staged, encrypted the way the vault stores it, and swapped in at the start together with everything else, or rolled back with it. See docs/design/backup.md.

### Added
- Instrumentation tests that run the real app on a phone or emulator (`./gradlew :app:connectedDebugAndroidTest`), with a scripted model, so no key or network is needed: every screen opens, a 64-step task, approvals in the chat and from the notification, Home, rotation and process death mid-task, mid-task notes and `/btw`, restore at startup and its rollback, scheduled runs through WorkManager, and undo. They use the debug app (`dev.locus.debug`) and never touch the release app. A nightly and on-demand GitHub Actions job runs them on an emulator. See docs/TESTING.md.
- Long tasks no longer run out of context:
  - **Summaries during a task.** A chat or helper that has used 80% of its model's context window now summarises the work so far between two steps and carries on. Before, this only happened before your next message, so a long task on OpenAI, Gemini, Ollama or another OpenAI-compatible model filled the window and failed. The summary request reuses the prompt cache. After it the agent sees the summary, your latest messages word for word (about 20k tokens), its checklist, and where to find the rest.
  - **Recovery from "too long" errors.** When a provider rejects a request as too long for the model (Anthropic, OpenAI, Gemini, OpenRouter, DeepSeek, Groq, vLLM, llama.cpp, LM Studio, Mistral), Locus summarises and tries the step once more. If it still doesn't fit, the chat says so and suggests a new chat or a model with a larger window.
  - **`chat_history_read`** numbers entries and takes `offset` (read forward from an entry) and `include_tool_results` (tool output marked as untrusted, with secrets hidden), so the agent can look up what a summary left out.
  - Ollama drops old messages itself without an error, so set its context window to match `num_ctx`. See docs/design/compaction.md.
- **Verification cards.** When the agent runs a build or test (Gradle, npm/pnpm/yarn, pytest, cargo, go, Maven, make, CMake, .NET, RSpec, PHPUnit and the commands `project_info` suggests), the chat shows a compact card. It says whether the run passed or failed and how long it took, gives the runner's tally, and lists the failing tests or the first error lines. **Output** shows the raw output. `run_command` results now include the duration.
- **Changes card and undo.** Below a turn that changed files, a card lists them ("Changed 3 files") with +/− line counts. Tap a file for its diff, with added and removed lines tinted. **Undo** puts the file back as it was before the turn, and **Undo all** does the whole turn. Both ask first, and warn if the file changed since the agent edited it. What's there now goes to Workspace → Trash, so an undo can be undone. The agent is told which files you undid, and has to read them again before changing them. Records reuse the trash's own copies; each chat keeps its latest 30 turns and all chats about 8 MB of diffs. Changes made by shell commands aren't tracked. See docs/design/change-tracking.md.
- Backup models and backup keys:
  - **Backup keys.** A provider can hold several API keys (Settings → Models → provider → Backup keys). When a key runs out of quota, is refused or is rate limited, the next key takes over at once. Locus stays on a key that works, so the prompt cache keeps hitting.
  - **Backup models.** Settings → Models → Backup models is an ordered list of models on any of your providers. When a chat's model is out of quota or its key is refused, or it's still rate limited or overloaded after its retries, the same request goes to the next model on the list for the rest of that turn. A partial reply is thrown away, never continued by another model. The reply notes which model answered ("Answered by … · … couldn't answer").
  - **Retry-After.** Retries wait as long as the provider asks, up to a minute. A longer wait moves to the next backup model, or the chat says how long the provider asked to wait.
  - Quota, rate-limit and overload errors are told apart for Anthropic, OpenAI, Gemini, OpenRouter, DeepSeek and Groq. Switching to another provider starts its prompt cache from zero. Design and research: docs/design/fallbacks.md.
- Safer, smoother code edits:
  - **Syntax check.** After the agent writes or edits a Python, JavaScript, TypeScript, Go, shell, Ruby or PHP file, a quick check runs in Linux, and any errors are added to the result. It runs only if Linux and the checker are already installed, gives up after a few seconds, and never fails the edit.
  - **Read before editing.** A chat has to read a file before it can edit or replace it, and the edit is refused before you're asked to approve it.
  - **Small mismatches.** An edit that is off only by trailing spaces, a consistent indentation difference or curly quotes is made anyway when it fits exactly one place, and the result says so.
  - **Long output.** When a command prints more than fits in the reply, all of it is saved to `.locus/out/` for the agent to search or read.
  - **`git_commit`.** A new tool that commits changes, but only after you approve. It doesn't run the repository's hooks and never pushes.
  - See docs/design/coding-harness.md.
- "Use the key saved for <Integration>" on an MCP template: when you add GitHub, Supabase, Render, Cloudflare, Firecrawl, Tavily or Exa and its built-in key is already saved, one tap reuses that key instead of pasting it. The sheet says which host it will also be sent to; Locus copies it inside the encrypted vault, so neither you nor the AI sees it. It's offered only where the server takes that kind of key, and only for the template's own host.
- If you have both a built-in integration and that vendor's MCP server connected (GitHub, Supabase, Render, Cloudflare, Netlify), new chats use the MCP server and leave the overlapping built-in tools out, so the agent doesn't get two tool sets for one service. Workspace deploys (`netlify_deploy`, `cloudflare_deploy_static`, `supabase_function_deploy`) and web search stay. Settings → Integrations shows "Using the MCP server in new chats", with "Use the built-in tools instead" per integration. Chats you already have keep their tools. Decision and trade-offs: docs/design/mcp-connectors.md.

- **Diagnostics** (Settings → About & privacy → Diagnostics): a local log of recent crashes, times Android closed Locus (ANRs, native crashes, low memory), errors that would have ended the app, and storage problems. It stays on the phone. Keys and passwords are removed before anything is shown, and Share (only when you tap it) sends the same scrubbed text. Clear empties it. See docs/design/diagnostics.md.

### Changed
- Setup offers Telegram again, with the same sheet as Settings → Integrations (bot token, Find my chat, Send a test), and SSH machines can be added there without authentication ("None"), with the same choices and wording as Settings.
- Shared row pieces (the add row, the expandable row, the row chevron and the divider inset) live in the design system instead of being copied into Settings, Library and Tasks. They look the same.
- "Summarise the conversation" in the composer's + sheet has its own icon instead of the restore arrow.
- Releases are one APK per ABI instead of one universal APK: `locus-v<version>-arm64-v8a.apk` (about 17 MB, for nearly every phone) and `locus-v<version>-x86_64.apk` (about 18 MB, for Chromebooks and emulators), down from 30 MB. Each has its own versionCode, `versionCode * 10 + 3` or `+ 4`, so F-Droid can build them with `VercodeOperation`; existing installs update normally. The 32-bit SQLite, DataStore and graphics libraries are gone: Locus's Linux runtime and on-device models only exist for 64-bit, so it never ran on 32-bit devices. See docs/RELEASING.md.
- docs/RELEASING.md and docs/FDROID.md have a checklist for registering `dev.locus` for Google's Android developer verification, and docs/FDROID.md lists what still blocks an IzzyOnDroid submission.

### Fixed
- Scheduled tasks no longer fail silently. A run that Android stops (its 10-minute limit for background work, a quota, the network going away) used to leave a half-finished chat with no record and no notification. Now every run ends with a notification and an entry on the task: done, failed, or "Stopped by Android (network lost)". Runs have an 8-minute budget, so they normally finish on Locus's terms. Tasks shows "Last run failed" or "Last run stopped". See docs/design/scheduled-runs.md.
- A scheduled run that Android retried after stopping it could send its prompt a second time. Each run now leaves a marker before sending, and a retry that finds it doesn't send again.
- When a background helper finished, or a queued message was due, while Locus was in the background, starting the agent's foreground service could be refused by Android 12+ and crash Locus. Locus now leaves the chat waiting with Continue and posts "Helper finished: tap to continue" (or "Your message is waiting").
- An error in Locus's background work (startup recovery, a helper's report, resuming a chat) ended the whole app. It's now recorded under Diagnostics and the app keeps running.
- An unreadable settings or saved-keys file made every read fail. Locus now keeps a copy (`.corrupt`), starts that file over, and tells you once.
- If Android invalidated the key that protects your saved keys (it happens after some system updates), Locus crashed on every launch. Now it doesn't, and it never throws your saved keys away by itself:
  - When the key fails, saved keys are locked and nothing is changed. Features that need a key say "Locus can't open your saved keys" instead of crashing or saying there's no key. Before you first unlock the phone after a restart, Locus doesn't even try.
  - Only when the key is gone for good (a changed lock screen, a restored phone), or after it failed on three separate starts, does Locus ask: "Reset and re-enter keys?" with Reset and Not now.
  - Reset keeps a copy of the old keys and their Keystore key, at most the last three. At each start Locus tries the copies again, and if one opens, its keys come back (keys you entered since are kept).

  See docs/design/diagnostics.md.
- After a restore, scheduled tasks are set up again even when the restore was rolled back (they were cancelled before the restart and stayed off). If setting them up fails, Locus says so once instead of failing silently, and tries again at the next start.
- In the SSH machine editor, a password typed before switching to a private key (or the other way round) carried over to the other field. Each method now has its own field.
- Telegram showed as Connected when what was saved couldn't be used to send anything.
- A reply's ⋯ menu offered "What was sent" even when that request is no longer kept (after a restart, or for older replies). It now says "What was sent (not kept)", and the sheet explains why.
- Deleting a file or folder in Workspace had no Undo. The "Moved to trash" message now has one, which restores it from the trash.
- `git_inspect` could run a filter program named in a repository's own `.git/config`. It now refuses such a repository and says why.
- Restoring a backup did nothing: after the restart, the confirmed backup was never swapped in (the step that applies it failed at every app start), so only the vault, when included, was restored. It's applied now. A restore confirmed in 0.1.2 or 0.1.3 that never applied is thrown away instead of being applied late over newer data; restore that backup again if you still want it.
- A restore that failed part-way at startup could leave some data restored and some not, and was retried (and failed) at every start without telling you. Restoring is now all-or-nothing: if any part can't be put in place, or Locus stops in the middle, everything goes back to how it was, the restore isn't retried by itself, and the next time you open Locus it says so ("Restoring the backup didn't finish; your data and saved keys are as they were before"). See docs/design/backup.md.

## [0.1.3] - 2026-11-01

### Added
- Better coding: new tools `find_files` (glob), `multi_edit` (several exact edits, all or nothing), `code_outline` (a map of a project's classes and functions), `project_info` (how to build and test a project and which Alpine packages it needs), `linux_service_log` (a server's recent output, filtered) and `git_inspect` (status, diff, log, show, blame without asking). `edit_file` gains `replace_all`, shows the changed lines and, when it can't find the text, shows what the file really says. `search_files` gains globs, context lines, file and count output, and skips `node_modules`, build folders and `.gitignore`d files. New chats get guidance on reading before editing and checking work with tests or a build. See docs/design/coding-harness.md.
- MCP server templates: Library → Connectors → Add an MCP server opens a catalogue of 33 official servers (GitHub, GitLab, Linear, Sentry, Notion, Atlassian, Stripe, Supabase, Neon, Cloudflare, Google Stitch, Context7, DeepWiki, Microsoft Learn, Hugging Face and more, plus reference servers that run in Linux), each with its logo, sign-in or key, "Get a token" link and the scopes it needs. Research and sources: docs/design/mcp-connectors.md.
- Settings → Integrations points to the vendor's official MCP server where one works from a phone (GitHub, Supabase, Render, Cloudflare, Netlify; Firecrawl, Tavily and Exa as an extra). "Use the official MCP server" opens its template; the built-in tools keep working.
- Logos for Telegram, Supabase, Render and ntfy, and a mail icon for Email.
- A task paused at its budget shows in the notification: the chip says "Paused", the title says "Locus needs you", and Stop and Continue work from there (Continue needs the phone unlocked, like Allow).
- Tapping the status, approval or question notification opens that chat.
- Removing a connector, local MCP server, memory, skill or helper, deleting an on-device model or a scheduled task, deleting from Trash permanently, and signing out of ChatGPT now ask first.
- A paused on-device model download shows Paused with its progress and a Resume button.
- Activity, Insights, Tasks and Workspace show a loading indicator when loading takes longer than 300 ms.
- While a task runs, what you type goes into that task: the box says "Tell Locus while it works…", and the agent reads your message after its current step. It answers a question briefly, adjusts to a correction, or wraps up with a summary if you ask it to stop, all without stopping the task. In the chat, such a message shows as a smaller bubble labelled "Told while working", and the task's checklist stays in place.
- Side questions, like Claude Code's `/btw`: type `/btw` and a question, or turn on Aside in the composer while a task runs. The answer comes from what's already in the chat, shows in a card you can close, and is never added to the chat; the task keeps going and never sees it. `/btw` alone shows the last answer again.
- SSH machines can use "No authentication", for sandbox machines that accept a login without a password or key. Host keys are still pinned.

### Changed
- MCP approvals follow the server's tool annotations with the spec's defaults: tools that may delete things say so, tools whose names delete always ask, and a read-only claim from a server outside the catalogue isn't believed for a tool whose name writes. Tool lists are cached, so approvals no longer ask the server again.
- Chats no longer stop after 40 steps. A task runs until the model finishes, you stop it, or a task budget pauses it. Scheduled runs keep a 40-step limit, and helpers keep their own.
- The notification says what the agent is doing in plain words ("Browsing", "Running a command") instead of the tool's name, and approval titles read like the chat ("Run `ls`?", "Researcher #2: Run `ls`?").
- Library's Agents tab is now Helpers, and "Continue when a helper reports back" moved to Settings → Agent, under Task budget.
- Settings: Network is in the Models section, Backup & restore has its own Data section, and About & privacy sits on its own at the bottom.
- Integrations: one-line rows, short key labels with help under the field, "Set up" for Telegram, ntfy and Email, and "Remove key" only when a key is saved.
- Setup no longer lists Telegram (it's set up in Settings → Integrations), refuses an ntfy topic or email setting that can't work, and asks for the local network permission before checking a LAN SSH host or adding a LAN MCP server.
- Running setup again from Settings returns to Settings when it finishes.
- A share or notification tap during first setup waits until setup is finished.
- Insights shows models by their display name when the models.dev catalogue is on, and tools in plain words. Insights and Activity format durations the same way.
- Chat: the header's model chip is gone (the composer shows the model), Share chat is a direct button, the reply menu is reordered with its own icons, Mermaid's toggle reads Source / Diagram, the budget meter shows this task's use and limits, and cards above the composer scroll instead of covering the chat. Touch targets are at least 48 dp, with screen-reader labels.
- The Terminal and Browser toggles moved into the composer, so the chat is full width again.
- Insights names models as the picker does: the provider's own name first, then the models.dev catalogue's, then the id.

### Fixed
- Task budget pauses were counted as tool calls, actions and approvals in Insights and Activity.
- Stop didn't work while a local MCP server was starting, and its form was lost when the phone rotated.
- A chat's search highlight didn't clear.
- A stopped run with queued messages showed two banners.
- The terminal and browser toggles could cover a message's buttons.
- A share could open twice after the phone rotated.
- Fixes from testing on the phone: switch rows and a reply's token line wrap instead of being cut off; "1 byte", "1 line" and "1 call" instead of plurals; the Workspace Trash button has a trash icon; Insights gives each slow tool its own title and colours only failures red; a failed call in Activity is a caution, not an alarm; Email's fallback icon is round; repeated labels and notes on the Helper model and Agent pages are gone.
- When a local MCP server crashed during a call and Locus noticed the crash first, the call failed with a bare "Connection closed" instead of being retried (for calls that are safe to repeat) or explained with the server's last output.
- Builds could produce an APK that crashed on launch or when opening a screen ("Cannot create an instance of class …ViewModel"), or fail to resolve types in KSP, at random. KSP now runs one module at a time, and the build fails if any `@HiltViewModel` lacks its generated Hilt code.

## [0.1.2] - 2026-10-31

The first release built from a version set in source. Highlights:

### Models
- Anthropic Messages, OpenAI Responses and OpenAI-compatible providers (OpenRouter, Groq, Google Gemini, Ollama, LM Studio, llama.cpp, vLLM), with streaming, prompt caching, token accounting and compaction.
- Continue with ChatGPT: use a ChatGPT Plus or Pro plan through OpenAI's Sign in with ChatGPT program.
- On-device Gemma 4 through LiteRT-LM, with image input and KV-cache reuse.
- On-device honesty: Settings → Models says which backend the engine was really created on ("Running on GPU · 18 tok/s", or "Running on CPU (GPU failed: …)" with the trimmed reason), and on-device replies show backend and speed in their token line. Per-reply speeds are estimates from the stream and are marked "≈".
- On-device model sheet: whether the model is loaded, its load time and approximate memory, an Unload button (not while a reply is being generated), and an optional local benchmark (fixed prompt, 256 tokens in and 128 out, through LiteRT-LM's benchmark) reporting load time and prefill and decode speed. Results stay on the phone.
- Memory warnings: each on-device model has a minimum phone RAM (Gemma 4 E2B 8 GB, E4B 12 GB, the same as Google AI Edge Gallery). Below it, or when Android reports low memory, Locus warns before download and load that the model may be slow or fail; it never blocks. Checks use total RAM, never device names.
- A helper model for titles, follow-up suggestions, summaries and the optional safety reviewer.
- Editable helper prompts: Settings → Helper model & extras → Prompts lets you view, edit and reset the prompts for chat titles, follow-up suggestions and summaries of long chats. Each lists its placeholders (`{chat}` for the chat excerpt); a prompt missing a required placeholder can't be saved, and one that is somehow stored without it falls back to the default, so a request always includes the chat. A summary prompt only changes the request's last message, so compaction still reuses the chat's prompt cache. The safety reviewer's prompt is deliberately not editable. Title and suggestion parsing now copes with labels, lists, markdown, code fences, reasoning blocks and JSON in the reply.
- Network settings per provider (Settings → Models → provider → Network, and Settings → Network): extra headers such as HTTP-Referer and X-Title for OpenRouter or cf-aig-* for Cloudflare AI Gateway, with values kept in the vault and never shown again; an HTTP or SOCKS5 proxy, with an optional sign-in, per provider or as a default (addresses on the phone always connect directly); and imported CA certificates (PEM, CRT or DER) trusted only by the providers that tick them, or by all of them after a warning. Header names that the connection or the key own are refused, a header may replace the key header only after a warning, and redirects aren't followed while extra headers are set. Only model requests use these settings; integrations, MCP servers and web fetches connect directly. Design and research: docs/design/network.md.

### Chat
- Message branching: regenerate a reply or edit a message as a new branch, and switch between versions with ‹ 1/2 ›; only the shown branch is sent to the model.
- Fork a chat from any reply or message into a new chat with the same settings.
- Maths and diagrams in replies: LaTeX (`$…$`, `\(…\)`, `$$…$$`, `\[…\]`, ```` ```math ````) is typeset, and ```` ```mermaid ```` blocks are drawn as diagrams with View source, copy and tap to zoom. Prices like "$5 and $10" stay text. Everything renders on the phone from bundled code, in a sandboxed WebView for diagrams. While a reply streams, maths shows as source; a diagram appears once its block is complete. Diagrams that can't be drawn (including mindmap and architecture diagrams) show as code. The workspace Markdown preview does the same.
- What was sent: from a reply's ⋯ menu or the context sheet, see exactly what Locus sent to the model for that turn: provider, model, endpoint host and settings (effort, max tokens), the system prompt, the tools with their schemas, and the messages with tool calls and results folded, with rough token counts per section and Copy JSON. API keys and auth headers are never part of it, saved secrets show as [secret] and attachments by size ("[image, 1.2 MB]"). On-device chats show the LiteRT-LM conversation text. Locus keeps the last 6 requests of the 8 most recent chats in memory only, never on disk, and rebuilds the body with the adapter's own code when you open it, so sending costs nothing extra.
- Full-text search over messages (SQLite FTS5, database v8): words match from their start ("kotl" finds "Kotlin"), "quoted phrases" match exactly, accents and case are ignored. Results show each chat once, newest match first, with the matching words highlighted. Only what you'd search is indexed: your messages, attached file names and replies, not tool output or reasoning. `chat_history_search` uses the same index. Existing chats are indexed when the app updates.
- Read aloud: a reply's ⋯ menu reads it with the system text-to-speech voice, in the reply's language when Android can tell it. Code blocks and tables are announced rather than read, links read their text, and thinking and tool steps are skipped. Stop from the Reading chip, by tapping again, by sending a message or by leaving the chat.
- Share chat from the chat's top-bar menu, as Markdown or PDF, to any app or into the workspace's `exports` folder. Only the branch on screen is included, with tool steps as one-line labels and attachments by name; reasoning, tool details, helper transcripts and anything shaped like a key are left out.
- Steer and queue: the composer stays open while the agent works ("Add to this task…"). Send queues the message as a dimmed bubble ("Queued, sent when the current step ends"), with Edit and Cancel until then. It joins the chat after the current step's tool results, before the next request, appended at the end, so the prompt cache is kept; if the turn ends first, it starts the next turn. An open question still takes what you type first, and an approval card no longer locks the box. Queued messages survive the app being closed (one small file per chat, no database change); after Stop, an error or a restart the chat offers Send or Discard.
- Steer a helper: working helper rows (and their transcript) have Steer next to Stop. Your note reaches the helper after its current step; one it never read is queued in the main chat instead.

### Backup
- Encrypted backup and restore (Settings → Backup & restore). One `.locusbackup` file, saved wherever you pick, holds chats and activity, settings, and memory, skills, helpers and persona files. The workspace is optional, with its size shown.
- Keys and passwords go in only if you turn on a second switch, which is off by default. The file is encrypted with your password: Tink Streaming AEAD (AES-256-GCM, 1 MB segments) under a PBKDF2-HMAC-SHA256 key (600,000 iterations).
- Restoring checks the whole file before changing anything. A wrong password and a damaged or changed file each get a clear message.
- You see what's in the backup before you confirm. A backup from a newer Locus is refused; an older database is upgraded by the usual migrations.
- Restoring waits while a task is running, then restarts the app. Format and research: docs/design/backup.md.

### Agent
- Agent loop with per-tool Allow / Ask / Deny policies, approvals in the app or from notifications, and recovery after the app is killed.
- Live Updates (Android 16+): while a task you started runs, the "Locus is working" notification is promoted to the top of the shade and the lock screen, with a chip in the status bar: "Working", the step ("Step 4"), or "Asks" when an approval or question is waiting. It says what the agent is doing ("Using" and the tool's name, or "Working · 2 helpers") and, while Locus is in the background, carries Deny / Allow (Allow still needs the phone unlocked) or the question's answers. Scheduled tasks never use it, it updates no more often than before, and with Live Updates turned off for Locus in system settings, or below Android 16, the notification stays as it was.
- Plan mode, a todo checklist, and thinking shown live and kept afterwards.
- Per-chat modes: **Read-only** (only reads and looks), **Workspace** (changes files and runs Linux, but never sends, posts, deploys or opens other apps) or **Full**. Pick one from the composer's + sheet, also on home for the next chat; a narrower mode shows as a chip. Modes only narrow: Settings → Permissions, the tools menu and confirmations still apply. A refused call tells the agent to ask you to switch, helpers follow the chat's mode, and switching keeps the prompt cache. Stored with the chat's switched-off tools, so the database is unchanged.
- `ask_user`: the agent can ask you a question mid-run instead of guessing: options (one or several), a yes/no confirmation, or free text. Answer on the card above the composer, by typing a normal message, or from the notification (option buttons and Reply, no unlock needed). It waits up to 30 minutes like an approval; background runs are told you aren't available, and a question left open when Locus is killed is answered as unanswered, with Continue offered. A helper's question shows in the parent chat with its name. The steps show "Answered: …".
- Sub-agents: researcher, coder, explorer, ops and browser helpers that run in parallel, plus your own definitions.
- `agent_send` continues a helper that finished or stopped early, with a follow-up message. The helper keeps everything it already did and runs with the same type, tools, limits and approvals, then sends back a new report. Its transcript shows a "Continued" divider. Each continuation counts toward the helper limits, and a helper that is still working is refused.
- Memory, Agent Skills, HTTP connectors with sandboxed scripts, MCP servers with OAuth, and drafts you approve.
- Task budgets: limits on tokens, estimated cost and time for each task (one message and the helpers it starts). Off by default; set a default in Settings → Agent, or a chat's own in the composer's options (⋯ → Task budget). At a limit the task pauses between steps, never in the middle of an action: "Paused: reached the 200k-token budget for this task", with Continue (one more of the same) and Stop. Helpers count toward the parent's budget and stop after their current step, and no new helper starts past it. Tokens are input plus output with cached input counted at a tenth; cost is estimated from models.dev prices (Settings → Models → model details) and only applies when the chat model's prices are listed; time doesn't count while Locus waits for you. While a task runs, the chat header shows "38k / 200k", and each pause and what the task used appear in Activity. On supported Claude models (first-party API) the token budget is also sent as `output_config.task_budget` (beta), so the model paces itself; the value never changes within a chat, so the prompt cache holds. No database change: a chat's own budget is kept with its tool switches.
- Skill import: Library → Skills → Import takes a GitHub link (a skill folder, a SKILL.md, or a repository with SKILL.md at the top; branches with slashes work) or a zip from the file picker. GitHub downloads use the contents API, without a token or with the GitHub integration's token. The skill is checked against the Agent Skills spec and limits (100 files, 10 MB, 5 MB per file; no symlinks, submodules or paths outside the folder) and always lands as a draft. Review shows SKILL.md as written, every file, and where it came from (link, branch and commit, or zip name, kept in `.locus-source.json`). A name clash offers rename or replace (a new version once approved).

### Tools
- Linux workspace (Alpine under proot), SSH and SFTP with detached jobs, phone actions through intents.
- Agent browser: structured snapshots with stable refs, form filling, waits, tabs, dialogs, downloads and uploads through the workspace, page saving and logs, with confirmations for logins, payments and submits.
- Integrations: Firecrawl, Tavily, Brave, Exa, GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, email, ntfy and Telegram.
- Telegram reach-me channel: Settings → Integrations → Telegram takes a bot token from @BotFather, and Find my chat reads the bot's messages once to fill in your chat id (or type it). The token stays in the vault and is scrubbed from every error. `notify_me` and scheduled task results now go to every channel you set up (ntfy and/or Telegram). Messages are sent as escaped HTML, long text is split at line breaks into up to five messages, and short 429 waits are honoured. Telegram doesn't send anything to Locus: what you write to the bot is not read, except once during setup.
- Supabase: list projects, functions, branches and API key names; read data with read-only SQL (allowed by default); run changes with `supabase_sql` and deploy Edge Functions from the workspace, which always ask first. Key values never reach the model. Use a scoped personal access token.
- Render: list services and deploys, read logs, list environment variable names; set variables (asks), start deploys, and suspend or resume services (always ask).
- Local MCP servers (stdio) that run inside the Linux workspace: Library → Connectors → Add MCP server → Local (Linux) takes a command such as `npx -y @modelcontextprotocol/server-filesystem /workspace` (or `uvx …`), optional arguments, and environment variables whose values are kept in the vault and given to the process when it starts. A server starts the first time a chat lists or calls its tools, through the same `mcp_list_tools` and `mcp_call` (the tool list doesn't change), and stops after 10 idle minutes, when it's removed or its key changes, and with Locus. A failed start is retried once; a crash is a clear error with the end of its stderr, and only listing tools is retried on a fresh process, so a write never runs twice. Library shows Running, Starting, Stopped or Failed with the reason, and Start, Stop and Try again. Tools follow the same policies (read-only runs can only use tools the server marks read-only). It needs Linux installed and says so when it isn't; nothing is installed for you (the examples say what to `apk add`). The agent can draft one with `connector_create`, which shows the exact command for your approval. Secret values are scrubbed from Linux command output while the server runs. No database change: connectors stay JSON files.
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

### Fixed
- Opening the browser full screen crashed the app when the page had focus.
- The terminal and browser buttons now show only when their panel has something to show.
- A chat that ends on your message (after an error was dismissed) offers Run again.
- The model sheet could show no tick while the chat header named a model: when the chosen model later became the provider's default, or when the provider's list didn't include it. Header, sheet and requests now resolve the model the same way (#1).
- A model picked on home just before sending could miss that chat and carry over to the next one.
- Anthropic prompt caching was never requested, so every turn paid full price for the whole prompt. Requests now carry two `cache_control` breakpoints: one after the frozen system prompt (or the last tool when there is none), one on the last cacheable block of the conversation, moving forward each turn (5-minute TTL). Local compaction reads the chat's cache; helper prompts (titles, suggestions, reviewer) stay uncached because they are never repeated.
- Server-side compaction was only sent for seven hard-coded Anthropic models, missing Claude Fable 5.1, Mythos 5.1, Sonnet 5.5, Sonnet 5 and Haiku 5.5 (whose long chats then fell back to Locus's own summaries). Locus now reads `capabilities.context_management.compact_20260112` from the Models API (once per model, with a 5-second limit, kept for the session) and falls back to the supported-models list from the compaction docs.
- A 429 for an exhausted balance, quota or spend limit (OpenAI `insufficient_quota`, `credit_balance_exhausted`, `*_spend_limit_exceeded`, `organization_usage_limit_exceeded`; Anthropic `enforced_spend_limit_reached`) was retried like a rate limit. It now fails at once with a message saying it's a billing limit; rate limits, `slow_down`, 5xx and overload are still retried with backoff.
- A reply whose stream broke mid-way (connection reset, unexpected end, timeout) failed the run at once. It's now retried like a busy provider (up to 3 times, with backoff), with the partial text discarded and "Connection dropped, retrying" shown. A stream that breaks after a tool call began is not retried.
- After Locus restarted, `linux_service_list` said there were no background services, even though the agent had started some (a restart ends them). They're now listed as stopped, with their last output and how to start them again; `linux_service_stop` forgets them. Removing Linux stops running services first.
- OpenAI-compatible servers that leave hidden reasoning out of `completion_tokens` (while `total_tokens` includes it) no longer undercount output and context.
- Android 17 local network permission: Locus targets API 37, where connections to LAN hosts are blocked unless the app holds `ACCESS_LOCAL_NETWORK` (Nearby devices). Locus now declares it and asks, after a short explanation, when you save or use a target on a private, link-local or `.local` address: a provider's base URL (Settings and onboarding, before loading models or saving), an SSH machine (before checking its host key or saving), an MCP server (before adding it), and before sending to such a provider from a chat. Saving never depends on the answer. If a request to a LAN provider fails without the permission, the error says how to allow it. localhost is not affected.
- The full-screen browser showed a black page after opening it from a chat.
- Browser sign-ins failed with "Unable to resolve host" when finishing in the background.
- ChatGPT replies disappeared after streaming.

[Unreleased]: https://github.com/springmusk026/locus/compare/v0.1.3...HEAD
[0.1.3]: https://github.com/springmusk026/locus/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/springmusk026/locus/compare/v0.1.1...v0.1.2
