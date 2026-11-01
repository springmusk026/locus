# Product, platform and distribution

Third research round (2026-11-01), after [more-apps-and-platform.md](more-apps-and-platform.md). Background work checked against Android's limits, features competitors have that Locus lacks, Android platform features, UX patterns and distribution. Code claims were checked against the manifests, `AgentService.kt`, `AgentRunner.kt`, `TaskWorker.kt`, `TaskRepository.kt`, `LocusComposer.kt` and `LoopbackRedirect.kt`. Items already on the roadmap are named as such. Ideas only, in our own words. "Unverified" marks what couldn't be confirmed from a primary source.

## Top 12, by impact over effort

| # | Proposal | Effort | Impact |
|---|---|---|---|
| 1 | Reliable scheduled runs under WorkManager's limits; guard foreground-service starts from the background (B1, B2) | S | High: correctness, possible crash |
| 2 | Register `dev.locus` for Google's developer verification before the 2027 global rollout (D1) | S | High: GitHub and F-Droid installs depend on it |
| 3 | Ship on IzzyOnDroid now, with per-ABI APKs (D2) | S | High: reach |
| 4 | Launcher shortcuts, Direct Share targets, a quick-settings tile (P1; roadmap "Ways in") | S | High |
| 5 | Opt-in update check, plus Obtainium instructions (D4; roadmap) | S | Medium-high |
| 6 | Photo Picker and camera capture in the composer (P2) | S | Medium |
| 7 | Projects: chats grouped with shared instructions, files and memory scope (C1) | M | High |
| 8 | Diff cards and per-file undo (U1; roadmap "Change tracking with undo") | M | High |
| 9 | Inline sandboxed HTML canvas (U2; roadmap "Rich replies") | M | High |
| 10 | Hands-free voice: system speech first, then OpenAI Realtime / Gemini Live with your own key (C2) | M, then L | High |
| 11 | An interactive PTY terminal in the Linux workspace (C3) | M | Medium-high |
| 12 | Android 17 polish: Live Update semantic colours, FLAG_SECURE on secret screens, explicit URI grants on shares (P3) | S | Medium |

Next after these: a Glance widget (P4), the default-assistant role (P5), a two-pane layout (P6), Gemini Nano in a separate Play flavour (P7), AppFunctions (P8, wait) and a Play Store build (D3, L, uncertain).

## B. Background work

**B0. AgentService is fine.** Only `dataSync` and `mediaProcessing` have the 6-hours-per-24 limit, and `shortService` about 3 minutes; `specialUse` has none. Play reviews the free-text `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, and apps targeting 34+ declare their service types in Play Console ([FGS timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout), [service types](https://developer.android.com/develop/background-work/services/fgs/service-types)). `AgentService.kt` uses `specialUse` with the subtype property and a defensive `onTimeout`; model downloads use `dataSync` through WorkManager (`ModelDownloadWorker.kt`), which is correct.

**B1. Scheduled runs have no foreground service (real gap).**
- WorkManager work has a roughly 10-minute execution window (exact 2026 figure unverified). On Android 16, job quotas depend on the standby bucket and count jobs running alongside a foreground service; Google advises logging `WorkInfo.getStopReason()` ([Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-all)). Android 17 adds `JobScheduler.getPendingJobReasonStats()` ([Android 17 features](https://developer.android.com/about/versions/17/features)).
- `TaskWorker.doWork()` calls `runner.runInBackground()` directly (`TaskWorker.kt:38-52`), with no `setForeground` and only a network constraint. A 40-step run can outlast the window; the worker is cancelled, `recordRun` and `notify` never run, and periodic work runs again later. This is the roadmap's "a failed scheduled run says it failed" and "never send a prompt twice".
- **Proposal (S–M, high):** a wall-clock budget below the window (~8 minutes) on top of the 40 steps; record "Stopped by Android (reason)" from `stopReason` and notify even when stopped; a per-run idempotency marker so a retried worker doesn't re-send the prompt; "last run failed / stopped" in Tasks.

**B2. Possible crash starting a foreground service from the background.**
- Since Android 12, `startForegroundService` from the background throws `ForegroundServiceStartNotAllowedException` unless an exemption applies (a notification action is one).
- `AgentRunner.start()` (line 497) calls `ContextCompat.startForegroundService` unguarded. It's reached from `continueWithHelperReports` (lines 182, 229-233) and the queued-message resume (line 493), both of which can fire after the chat's turn ended with the app backgrounded. If `AgentService` already stopped itself (`activeCount` 0), the start may throw inside a coroutine. Inferred from the code path, not reproduced.
- **Proposal (S, high if it triggers):** catch the exception and post "Helper finished: tap to continue" instead of auto-continuing, or keep the service alive while any of the chat's helpers runs.

**B3. Android may kill Linux background services.** Android 12+ caps "phantom" child processes at 32 across all apps and kills CPU-heavy children of background apps; the toggle to disable it exists only on some builds (community sources: [Android-Docs on phantom processes](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md), [Andronix](https://docs.andronix.app/android-12/andronix-on-android-12-and-beyond)). Locus already reports services as stopped after a restart. **Proposal (S, medium):** when a proot child dies with signal 9 while Locus is backgrounded, show "killed by Android (phantom process limit)" with a help link.

**B4. Android 17 checks** ([behaviour changes](https://developer.android.com/about/versions/17/behavior-changes-17)). Background audio needs a foreground service with while-in-use capability: check read-aloud with the screen off (TTS plays in the engine's process, so it may be unaffected; unverified). Certificate Transparency is on by default: check that custom CAs from #39 still work for self-hosted servers (unverified).

## C. Competitor features

What's current:
- **Claude** ([release notes](https://support.claude.com/en/articles/12138966-release-notes)): interactive apps on mobile (Mar 25); Cowork on mobile, merged into every chat with shared projects, artifacts, scheduled tasks and skills (Jul 7, Sep 16); memory as categorised editable entries with sensitive topics excluded by default (Jul 10, Aug 25); Health Connect on Android (Jan 12).
- **ChatGPT:** a new scheduled-tasks system with its own page, Pulse being retired in June 2026 ([AlternativeTo](https://alternativeto.net/news/2026/6/chatgpt-launches-new-scheduled-tasks-and-prepares-to-sunset-pulse/); secondary, OpenAI's help centre returned 403). "ChatGPT agent" reportedly replaced by "ChatGPT Work" (unverified: conflicting page versions). Codex in the mobile app since May 2026 with step approvals (secondary sources only).
- **Gemini:** "Gemini Intelligence" (May 2026) automates other apps, which conflicts with Locus's principles. A customisable Daily Brief and agent skills in testing (Android Authority, unverified).
- **Comet:** on Android since November 2025 ([TechCrunch](https://techcrunch.com/2025/11/20/perplexity-brings-its-ai-browser-comet-to-android/)): voice over open tabs, a visible action trace, multi-tab summaries.
- **Manus:** Projects on mobile, May 2026 ([AlternativeTo](https://alternativeto.net/news/2026/5/manus-adds-projects-feature-to-mobile-for-flexible-task-management/)).
- **Open source since the last round** (GitHub releases): RikkaHub 3.1.x (ChatGPT and Grok subscription sign-in, a 7/30/90-day usage page, SSH remote workspaces, a floating quick-reply window, a memory-consolidation model, per-call tool time); Operit 1.12.2 (token ledger with price overrides, database and DataStore health checks with repair, performance monitors, separate TTS/STT profiles); ChatterUI 0.11 beta ("Notices" listing background work, concurrent remote chats); PocketPal 1.22 (themed icon, Weblate); Maid 3.0 (a vote-to-report feature labelled "Google Requirement"); OpenMinis (diagnostic builds with an environment line in feedback).

**C1. Projects (M, high).** Claude, ChatGPT and Manus group chats with shared instructions and files. Locus lacks it; the nearest are workspace folders and the roadmap's "Workspace AGENTS.md" and "Named personas". Proposal: a project is a workspace subfolder plus an AGENTS.md (frozen at chat start, cache-safe), an optional default model and persona, a memory scope tag and a chat list, with a project filter on Home.

**C2. Voice mode (M, then L; high).** ChatGPT, Gemini Live and Comet have hands-free voice. OpenAI's [Realtime API](https://developers.openai.com/api/docs/guides/realtime) (`gpt-realtime-2.1`, WebSocket, tool calls) works with the user's key; Android 17 adds `USAGE_ASSISTANT` and `MODE_ASSISTANT_CONVERSATION` ([features](https://developer.android.com/about/versions/17/features)). Locus has dictation and read-aloud (`LocusComposer.kt`, `ReadAloud.kt`) but no conversation loop; on-device speech (sherpa-onnx) is on the roadmap. Step 1: a full-screen loop of system speech-to-text, the normal agent and TTS, with barge-in, approvals read aloud and spoken yes/no only for Ask-level actions. Step 2: a Realtime/Live adapter for OpenAI and Gemini keys.

**C3. Interactive terminal (M, medium-high).** Termux setups running Claude Code, Codex, opencode or OpenClaw rely on a real terminal. Locus's terminal panel is a log of agent commands; the roadmap has "several terminal sessions". Proposal: a PTY terminal using Termux's terminal-emulator and terminal-view libraries (licence unverified, likely Apache-2.0; GPL-3.0 would also be AGPL-compatible), plus a "Run a coding CLI" template that installs one through npm in Alpine with a vault key.

**C4. Smaller gaps** (S–M, mostly on the roadmap): cost in Insights with day ranges (as RikkaHub and Operit have); a diagnostics self-test and export ("Reliability tools"); a visible list of background work like ChatterUI's Notices ("Visible health"); a morning-digest task template.

## P. Android platform

**P1. Ways in (S, high).** [Launcher and sharing shortcuts](https://developer.android.com/develop/ui/views/launch/shortcuts) through `ShortcutManagerCompat`; a [quick-settings tile](https://developer.android.com/develop/ui/views/quicksettings-tiles). The manifest has only MAIN, SEND and VIEW filters. Proposal: static shortcuts (New chat, Voice, Workspace); dynamic shortcuts for pinned chats, published as Direct Share targets so a share lands in that chat; a tile that opens the ask bar.

**P2. Photo Picker and camera (S, medium).** [`PickMultipleVisualMedia`](https://developer.android.com/training/data-storage/shared/photo-picker). The composer uses `OpenMultipleDocuments` (`LocusComposer.kt:146`) and has no camera. Proposal: Photos, Camera (`TakePicture` into `inbox/`) and Files in the + sheet.

**P3. Android 17 polish (S, medium).** Live Update semantic colours via `createSemanticStyleAnnotation` (SAFE, CAUTION, DANGER, INFO; [features](https://developer.android.com/about/versions/17/features)); `FLAG_SECURE`, since Content Capture can't otherwise be turned off; explicit `FLAG_GRANT_READ_URI_PERMISSION` on `ACTION_SEND` before Android 18 ([changes](https://developer.android.com/about/versions/17/behavior-changes-all)). Live Updates are done (#35); none of these three are, and the share grant is already in the roadmap's Hardening line. Proposal: approval waiting = CAUTION, failed = DANGER, done = SAFE; FLAG_SECURE on key and vault sheets; StrictMode `detectImplicitUriPermissionGrant` in debug builds.

**P4. Glance widget (M, medium).** Android 17 enforces a widget bitmap memory limit ([changes](https://developer.android.com/about/versions/17/behavior-changes-17)). One widget: the status line, an ask button and the latest scheduled-task answer. Approvals still need the app or the unlocked notification.

**P5. Default assistant (M, medium).** `RoleManager.ROLE_ASSISTANT` needs a `VoiceInteractionService` (2026 mechanics unverified). Do it after voice mode, so a long-press of power opens Locus's voice mode.

**P6. Large screens (M, medium-low).** At target 37, orientation and resizability limits are ignored at 600dp and wider ([changes](https://developer.android.com/about/versions/17/behavior-changes-17)); Android 16 previews back on long-press in three-button navigation. Locus is edge-to-edge (`MainActivity.kt:58`) with Navigation 3 and `BackHandler`, content capped at 840dp, no two-pane layout. Proposal: a Material 3 Adaptive list-detail layout (chat list | chat | live panel), checking that side panels animate with predictive back.

**P7. Gemini Nano / ML Kit GenAI (M, low-medium).** From [ML Kit GenAI](https://developers.google.com/ml-kit/genai): Prompt API beta with function calling in preview; summarise, proofread, rewrite and image description beta; speech alpha; Galaxy S25 and later listed. **Inference only while the app is the top foreground app; a foreground service doesn't count** (`BACKGROUND_USE_BLOCKED`), with per-app quotas. Closed-source SDK (Play services dependency unverified). Proposal: only in a separate `play` flavour, for foreground helper jobs (titles, suggestions, proofreading) when no helper model is set. Not suitable for agent runs.

**P8. AppFunctions (M, low today).** Experimental, Android 16+, callers need `EXECUTE_APP_FUNCTIONS`; during the preview only a limited set of apps and system agents can use the full pipeline, and Gemini integration is private ([AppFunctions](https://developer.android.com/ai/appfunctions)). Wait; when it opens, expose `start_chat(prompt)`, `run_task(id)` and `add_memory(text)`, each confirmed in Locus.

**P9. Lower value:**
- Credential Manager / passkeys: no Locus account; providers' passkeys already work in the browser during OAuth.
- [Auth Tab](https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab) (`androidx.browser` 1.9, Chrome 137+) accepts only custom-scheme or verified HTTPS redirects, not http loopback. Locus opens the full browser (`LoopbackRedirect.kt:127`); Custom Tabs would keep sign-in inside Locus's task. S, low.
- AppSearch: low value. Handoff (Android 17): needs state on both devices, against local-first.
- Per-app language (`localeConfig`): with "Translated UI". Android 17 contact picker: use it instead of READ_CONTACTS when contacts are built.
- Wear OS / Android Auto: low value; Wear approvals already work through mirrored notification actions (unverified on a device).
- Storage Access Framework: the workspace is already a DocumentsProvider; syncing a picked `OpenDocumentTree` folder would pair with scheduled backups to a picked folder. M, low-medium.

## U. UX patterns

**U1. Diffs and undo (M, high).** Coding agents, and AndroidHarness, show per-file diffs with accept and undo. Locus shows changed lines from `edit_file` and trashes files (0.1.3). Proposal: snapshot each file before its first write in a turn; a "Changed 3 files" card with unified diffs in the code viewer, "Undo this file" and "Undo turn"; a verification card for build and test commands.

**U2. Canvas (M, high).** Claude's interactive apps on mobile (March 2026); MCP Apps. Locus has an HTML viewer in Workspace plus Mermaid and LaTeX; inline sandboxed HTML isn't done. Proposal: render `send_file` HTML or SVG inline in a WebView with JavaScript but no network or file access (`WebViewAssetLoader`, network blocked), with Open full-screen, Save to workspace and Share as image; reuse the sandbox for MCP Apps later.

**U3. Approvals and timelines.** Locus is ahead here (in-app and notification approvals with unlock, Live Update chips, the reviewer, Activity runs). Remaining, S each: scoped remembered approvals (roadmap), and a per-turn "receipts" line of memories, skills and connectors used (roadmap "show which facts were read").

**U4. Onboarding (S, medium).** Setup has 9 steps; competitors reach a first answer in one screen. Proposal: "Quick start" after the model step, skipping identity, Linux, SSH and MCP, each reappearing as a one-tap card on Home's empty state. A judgement, not measured.

## D. Distribution

**D1. Developer verification (S, high).** From [developer verification](https://developer.android.com/developer-verification): from 2026-09-30, certified devices in Brazil, Indonesia, Singapore and Thailand require apps from verified developers, going global in 2027; an "advanced flow" lets power users still install unverified apps. There's an [open-source registration guide](https://developer.android.com/developer-verification/guides/open-source-app-registration), which also covers registering F-Droid's key if F-Droid signs. Locus's plan has F-Droid publishing Locus's own signed APK, so one key covers both. Proposal: register `dev.locus` and the release key fingerprint (`0fc4ab07…0e1b`) now, and note it in FDROID.md and RELEASING.md.

**D2. IzzyOnDroid (S, high).** [Inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/): about 30 MB per APK as a rule of thumb; no proprietary components unless essential, no trackers; the same opt-in rule for downloading executables as F-Droid; reproducible-build checks through rbtlog. **The v0.1.3 release APK is 29,868,073 bytes, right at the cap** (`gh release view`). Proposal: per-ABI APKs (arm64-v8a, plus x86_64 if needed) and submit while F-Droid's LiteRT-LM question is open. The Linux opt-in already meets the consent rule.

**D3. Google Play (L, uncertain).**
- The [Device and Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/9888379) forbids downloading executable code (dex, JAR, .so) from outside Play, except code run by a virtual machine or interpreter. Alpine and `apk add` binaries under proot aren't clearly covered.
- [Termux's](https://github.com/termux/termux-app) Play build needed "extensive adjustments" and Termux still recommends F-Droid (how unverified).
- The [AI-Generated Content policy](https://support.google.com/googleplay/android-developer/answer/14094294) covers apps where chat is central. The page no longer mentions in-app reporting, but Maid 3.0 added one as a "Google Requirement", so add a report action anyway (whether it's still required is unverified).
- Play reviews the `specialUse` declaration and Data safety form. 16 KB pages are fine: proot libraries have 0x4000 LOAD alignment (`llvm-readelf`).
- Proposal, if wanted: a `play` flavour without the Linux download (SSH and remote MCP still work), with a report action, Gemini Nano allowed. Otherwise F-Droid, IzzyOnDroid and GitHub stay primary.

**D4. Update check (S, medium-high).** On the roadmap (opt-in, GitHub Releases, SHA-256), not built. First version only notifies and opens the release page, off by default and disabled in F-Droid and IzzyOnDroid builds by a build flag. Document Obtainium in the README. Later: install through `PackageInstaller` after checking `SHA256SUMS.txt` and the signing certificate.

## Unverified

The exact 2026 WorkManager window; whether the B2 crash happens; Termux's Play adjustments; whether Play's AI policy still requires in-app reporting; whether ML Kit GenAI needs Play services; the licence of Termux's terminal-view; default-assistant mechanics; Android 17's background-audio rule and TTS; Android 17's Certificate Transparency default and user-imported CAs; secondary-source items (ChatGPT scheduled tasks, Codex on mobile, Gemini agents, Manus).
