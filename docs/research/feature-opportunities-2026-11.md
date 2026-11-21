# Feature opportunities, November 2026

What mobile and desktop AI agents shipped or were asked for between 2026-10-31 and 2026-11-21, what Locus could borrow, and where it can stand out. It builds on [comparable apps](comparable-apps.md), [more apps and platform](more-apps-and-platform.md), [product and platform](product-platform-2026.md) and [agent algorithms](agent-algorithms-2026.md), and doesn't repeat them. Researched 2026-11-21 from release notes, docs and issue trackers. Dates are publication or release dates. Reddit couldn't be read, so demand signals come from GitHub issues, sorted by reactions. Ideas only; no code was copied.

Checked before writing: Locus has no app lock (no biometric code); `memory_save` asks only in a tainted run, otherwise it's allowed ([taint](../design/taint.md)); a saved workflow can be started from the composer, but there are no saved prompts or `/` shortcuts; the AI reviewer gives a reason when it denies, a person can't.

## What changed since 2026-10-31

**Commercial apps**
- **Claude Duet** (Android and iOS, 2026-10-21, [product page](https://claude.com/product/claude-duet)): a morning brief, watches connected apps, drafts replies for approval, asks before sending, paying or deleting. US-only on Pro, Max and Ultra, behind a waitlist; its page warns it "can miss something that arrives between checking in with you". Computer use reached Claude mobile for Team and Enterprise on 2026-11-20 ([release notes](https://support.claude.com/en/articles/12138966-release-notes)).
- **ChatGPT** ([release notes](https://help.openai.com/en/articles/6825453-chatgpt-release-notes)): ChatGPT Work (2026-07-09) runs in OpenAI's cloud with event-triggered tasks, approvals with auto-review, and docs, sheets and slides as output. Dots, always-on agents on their own cloud computer: launched 09-29, RCS texting 10-28, Wallet purchases 11-13. Locked chats behind PIN or biometrics: 10-20. Health on Android through Health Connect: 11-19.
- **Gemini Intelligence** ([Google](https://blog.google/products-and-platforms/platforms/android/gemini-intelligence/), 2026-05-12; Pixel 10 in the November Pixel Drop): drives other apps' screens, progress in live notifications, custom widgets.
- **Android platform** ([Android Dev Summit](https://developer.android.com/blog/posts/build-it-your-way-a-next-era-of-android-development), 2026-10-28): AppFunctions beta (calling other apps' functions needs `EXECUTE_APP_FUNCTIONS`, granted only to system assistants, so Locus can't be a caller), A2UI for Compose alpha, ML Kit Agentic API on Gemini Nano 4 alpha.

**Open-source apps**
- **OpenMinis 1.16-beta.1 to beta.8** (11-01 to 11-20, [releases](https://github.com/OpenMinis/OpenMinis/releases)): a "questions waiting" banner, sub-agent resume, full-screen browser takeover, widgets (Chats, Quick Actions, Usage & Balance). **beta.6 (11-15) added anonymous usage statistics to official builds**, on by default with a first-launch notice and an opt-out; it sends launch counts, usage time, chat activity and crash counts, never conversations, files or keys.
- **RikkaHub 3.2.0 and 3.3.0** (11-10, 11-16, [releases](https://github.com/rikkahub/rikkahub/releases)): "Phone Use" (the AI operates the phone; mechanism not documented), shared storage in the Linux workspace, automatic compression of long chats. 3.0/3.1: file-based memory library, a "dreaming" memory consolidation pass.
- **PocketPal** ([releases](https://github.com/a-ghorbani/pocketpal-ai/releases)): local OpenAI-compatible server (v1.23.0, 11-10), on-device Whisper and hands-free voice (v1.21.0), speech with the screen off (v1.24.0).
- **Operit2 2.0.0-preview.41** (11-20): full-database backup, llama.cpp 1.0. **ChatterUI 0.11.0-beta1** (10-30): automatic chat summaries, a background-task list. **Google AI Edge Gallery 1.0.21** (10-28): on-device speech in and out.
- **New:** [Mika](https://github.com/wangzan101/Mika) (Apache-2.0, ~9.3k stars) drives apps on a hidden screen after wireless-debugging pairing and lets desktop agents hand tasks to the phone over MCP. Google Artemis (ADB plus accessibility, an MCP server; from a secondary article, not opened).

**Desktop and CLI agents** (docs read 2026-11-21; most pages undated)
- Claude Code: `/deep-research` workflow, [checkpointing](https://code.claude.com/docs/en/checkpointing) (rewind code and conversation), [hooks](https://code.claude.com/docs/en/hooks), [plugins](https://code.claude.com/docs/en/plugins) with a context-cost estimate, [output styles](https://code.claude.com/docs/en/output-styles).
- Gemini CLI [checkpointing](https://geminicli.com/docs/cli/checkpointing/) and Cline [checkpoints](https://docs.cline.bot/features/checkpoints): snapshots that include shell changes.
- Open WebUI: saved prompts with `/` and variables, multi-model chats and an arena (read through search summaries only).
- LibreChat: [import](https://www.librechat.ai/docs/features/import_convos) of ChatGPT and Claude exports. Cursor: shareable canvases (10-16) and live apps (11-05) ([changelog](https://cursor.com/changelog)). Manus: [Wide Research](https://manus.im/blog/introducing-wide-research).

**What users ask for** (GitHub, by reactions): F-Droid builds (PocketPal [#55](https://github.com/a-ghorbani/pocketpal-ai/issues/55), Edge Gallery [#430](https://github.com/google-ai-edge/gallery/issues/430), OpenMinis [#501](https://github.com/OpenMinis/OpenMinis/issues/501)); a local API server for the phone's model (Edge Gallery [#552](https://github.com/google-ai-edge/gallery/issues/552), its top issue); approving the model's memory changes (RikkaHub [#1927](https://github.com/rikkahub/rikkahub/issues/1927)); several models at once (RikkaHub [#332](https://github.com/rikkahub/rikkahub/issues/332), [#1582](https://github.com/rikkahub/rikkahub/issues/1582)). Scheduled tasks, token pricing and prompt-injection handling are open requests there and already in Locus.

## Ideas to borrow, ranked by value over effort

| # | Idea | From | In Locus | Effort |
|---|---|---|---|---|
| 1 | **App lock and locked chats** | ChatGPT (10-20) | Biometric or device-credential gate on opening Locus and on chosen chats; the vault and SSH keys deserve it | S |
| 2 | **Saved prompts with variables** | Open WebUI | Type `/` in the composer, pick a saved prompt; `{{clipboard}}`, `{{date}}` and fields open a small form | S |
| 3 | **Approve memory writes, review consolidation** | RikkaHub #1927, RikkaHub 3.1 | A setting: every `memory_save` asks (today only tainted runs ask); a periodic tidy-up proposed as a diff to accept. Policy and UI only, the tool list is unchanged | S |
| 4 | **Rewind here, summarise from here** | Claude Code, Cline | Long-press a message: Rewind (Undo turn for later turns plus a branch) or Summarise from here (replaces only the tail, the cached prefix stays). Closes the roadmap's "editable summary" | S–M |
| 5 | **"Needs you" feed with drafts to approve** | Claude Duet, ChatGPT Dots | One place for trigger and scheduled results and the drafts waiting for a yes, built on the roadmap item "let a run propose a write as an approval card" | M |
| 6 | **Deep-research workflow** | Claude Code, Claude Research, Manus | A built-in workflow: 3 researcher helpers, a verifier pass, a cited report as a file, unverifiable claims marked as such; a task budget applies | M |
| 7 | **Import ChatGPT and Claude exports** | LibreChat | Library → Import parses the export on the phone into read-only chats, tainted with provenance; optional memory drafts to review | M |
| 8 | **Local OpenAI-compatible endpoint** | PocketPal (11-10); top requests | Already on the roadmap; serve the on-device model on localhost with a token from day one | M |
| 9 | **Office files** | ChatGPT Work, Duet | Bundled skills that write docx, xlsx and pptx in Linux, and viewers for them; check the packages against the F-Droid build | M |
| 10 | **Snapshots that include shell changes** | Gemini CLI, Cline | Opt-in per project: snapshot the folder before a writing `run_command`, capped, so Undo turn also reverts shell edits | M |
| 11 | **Rules (hooks)** | Claude Code | "After an edit run `ruff`", "never `git push` to main": local commands only, sealed like skills, output added as a tool note | M |
| 12 | **Compare models** | Open WebUI, RikkaHub requests | One prompt on 2 models as sibling branches, with a cost estimate first; picks feed a local "your best models" list | M |
| 13 | **Publish to your own host** | Cursor, Amp | Publish an artifact or chat as a static page through the existing Netlify, Cloudflare or Vercel tools, scrubbed, always confirmed | S–M |
| 14 | **Desktop agents reach the phone** | Mika | A token-guarded local MCP endpoint offering approvals, notifications, calendar and Linux to your own desktop agent; no control of other apps | M–L |
| 15 | **Health Connect, read-only** | ChatGPT (11-19) | Steps, sleep, heart rate; Ask by default, treated as personal data | M |

Smaller: a usage and budget widget and a "questions waiting" banner (OpenMinis), a reason when the user denies an action, response-style presets fixed when a chat starts.

## Where Locus can stand out

1. **A proactive agent with no vendor cloud and no waitlist.** Duet, Dots and ChatGPT Work run in their vendors' clouds, on top plans, some US-only. Locus can run triggers, briefs and drafts on the phone with any model, including on-device ones.
2. **Verifiably no telemetry.** Its closest open-source rival added opt-out usage statistics on 11-15. Locus has none, with reproducible builds and attested APKs; say "verifiable" on the README and the F-Droid page.
3. **It can't tap your banking app.** Gemini, RikkaHub's Phone Use, Mika and Artemis drive the screen. Locus uses hand-offs instead of screen control, taint tracking and secrets the model never sees.
4. **Your own compute.** A real Linux on the phone, SSH to your machines, self-hosted connectors (Home Assistant, Nextcloud, Paperless-ngx); cloud agents use their own sandboxes.
5. **Model freedom.** API keys, the official ChatGPT-plan sign-in, local servers and GGUF models with fallback chains; none of the commercial agents let you change the model.

## Don't copy

- **Screen and app automation** through accessibility or ADB (Gemini, RikkaHub, Mika, Artemis): against Locus's no-AccessibilityService rule and the reason point 3 above is true.
- **Agents on a vendor's cloud, or remote control through a vendor relay** (Duet, Dots, ChatGPT Work, Claude Code Remote Control, cloud coding agents): not local-first. SSH to the user's own machines is the equivalent.
- **Texting the agent over SMS or RCS** (Dots): needs a relay or SMS permissions; Telegram already covers it.
- **Permission prompts off by default** (Amp, [2026-05-06](https://ampcode.com/news/neo)): removes the guarantee that scheduled and tainted runs can't act unseen.
- **Large fan-out by default** (up to 100 subagents at Manus): bill shock on BYOK, heat and battery on a phone. Keep depth 1, 3 at once, and budgets.
- **Usage statistics**, even opt-out ones.

## Unverified

- Reddit wasn't readable. Comet and Copilot: no Android agent change since October confirmed from a primary source.
- How RikkaHub's Phone Use and Mika control the phone (accessibility, virtual display or Shizuku) isn't documented. Artemis comes from a secondary article.
- Open WebUI prompts and arena, Msty, Goose, Claude Research and memory import, and Manus were read through search summaries; check their pages before quoting details.
- Efforts are estimates from docs and greps, not from a design.
