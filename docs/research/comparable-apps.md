# Comparable Android agent apps

How Locus compares with four open-source agent apps, and what to learn from them. Researched 2026-10-31 from each project's README, docs, release notes and issues. Only features and lessons are taken; no code was copied or adapted (several of these projects are GPL, LGPL or AGPL). The roadmap section "Ideas from comparable projects" turns this into work items.

## The projects

| Project | Licence | Stars | Platforms | In one line |
|---|---|---|---|---|
| [OpenMinis](https://github.com/OpenMinis/OpenMinis) | GPL-3.0 | ~5.6k | Android, iOS | A polished, fast-moving agent app with an Alpine sandbox, a serious network stack (Tailscale, proxies) and deep device integration. The public repo mirrors a private one and takes issues only. |
| [Operit](https://github.com/AAswordman/Operit) | LGPL-3.0 | ~9.1k | Android (arm64) | The oldest and broadest Android agent: phone automation, Ubuntu under proot, local models, workflows, a plugin marketplace. Its successor [Operit2](https://github.com/AAswordman/Operit2) (AGPL, preview) is a Rust core with Flutter clients and a layered permission model. |
| [AndroidHarness](https://github.com/Sanuu7/AndroidHarness) | MIT | ~130 | Android (Play) | A coding agent first: fallback chains across models, task budgets, change tracking with undo, verification cards, Telegram remote control. Young and releasing daily. |
| [nanoMuse](https://github.com/nano-muse/nanoMuse) | GPL-3.0+ | ~2.9k | Android, iOS, desktop, web | One named agent across your devices, with a strong safety layer ("Sentinel"), proactive triggers and a cloud relay. The Android app is a fork of OpenMinis. |

## What they do well

- **OpenMinis:** assistant questions as answerable cards (also from a notification); per-scope network routes that fail closed; usage with quota and balance; sub-agent cards with Steer and Stop; encrypted backup to rclone remotes; in-app updates with a verified download; sharing a chat as an image or PDF.
- **Operit:** many ways in (floating window, widget, default assistant, Tasker and intent triggers); multiple terminal sessions; local models (llama.cpp, MNN); separate models per job; MCP servers started locally with npx or uvx; file rollback when a message is resent.
- **AndroidHarness:** model fallback chains that switch instantly on quota errors and never replay a partial reply; task-wide budgets for tokens, cost and time; plan on one model and execute on another; change tracking with per-file undo; a `/doctor` self-test and a diagnostic export with no secrets; scheduled runs that never send a prompt twice; memory receipts showing which facts were used.
- **nanoMuse:** risk levels per tool with argument rules, redacted results and tiered remembered approvals (once, this chat, always for one folder or site), where payments always ask and need the screen lock; triggers (new mail, calendar, webhook) and a morning feed; chat channels over outbound connections.

## What goes wrong for them (lessons)

- **Prompt size and caching:** OpenMinis injects memory files without a cap (a 32K model gets a 30.8K prompt, [#510](https://github.com/OpenMinis/OpenMinis/issues/510)), puts the time in the cached prefix ([#503](https://github.com/OpenMinis/OpenMinis/issues/503)) and derives the cache key from the first message ([#504](https://github.com/OpenMinis/OpenMinis/issues/504)).
- **Token accounting:** reasoning tokens left out of counts ([OpenMinis#168](https://github.com/OpenMinis/OpenMinis/issues/168)); estimates from byte counts that undercount Chinese and cause a compaction loop ([nanoMuse#719](https://github.com/nano-muse/nanoMuse/issues/719)).
- **Long chats:** rebuilding the whole request from the database every turn (50 to 140 s to first token, [Operit#1363](https://github.com/AAswordman/Operit/issues/1363)); out-of-memory crashes with long sessions and tables ([Operit#1430](https://github.com/AAswordman/Operit/issues/1430), [OpenMinis#483](https://github.com/OpenMinis/OpenMinis/issues/483)).
- **Silent failures:** a dropped privileged executor stopped every shell and file tool with nothing on screen ([Operit#1359](https://github.com/AAswordman/Operit/issues/1359)); MCP servers that never start ([Operit#1436](https://github.com/AAswordman/Operit/issues/1436)); a failed scheduled run showing yesterday's results ([nanoMuse#637](https://github.com/nano-muse/nanoMuse/issues/637)); background services not restarted after the sandbox restarts ([OpenMinis#511](https://github.com/OpenMinis/OpenMinis/issues/511)).
- **Sub-agents:** partial results lost on timeout ([OpenMinis#473](https://github.com/OpenMinis/OpenMinis/issues/473)); a browser helper hanging with no circuit breaker ([OpenMinis#456](https://github.com/OpenMinis/OpenMinis/issues/456)).
- **Security:** a local HTTP API that returned its token unauthenticated ([Operit#1322](https://github.com/AAswordman/Operit/issues/1322)); one key slot shared by every provider ([nanoMuse#580](https://github.com/nano-muse/nanoMuse/issues/580)); subscription sign-in that imitates official CLI clients (OpenMinis source), a terms-of-service and account-ban risk.
- **Release quality:** regressions from shipping daily ([AndroidHarness#56](https://github.com/Sanuu7/AndroidHarness/issues/56), [nanoMuse#701](https://github.com/nano-muse/nanoMuse/issues/701)); half-translated UI ([Operit#312](https://github.com/AAswordman/Operit/issues/312)).
- **Approval fatigue:** nanoMuse users ask for an AI reviewer of approvals ([#724](https://github.com/nano-muse/nanoMuse/issues/724)), which Locus already has.

## Where Locus stands

Ahead on: official subscription sign-in (OpenMinis users ask for it, [#474](https://github.com/OpenMinis/OpenMinis/issues/474)); prompt-cache discipline (frozen system prompt, fixed tool list); the AI approval reviewer; untrusted-content tagging; helpers and skills that wait for approval; no control of other apps.

Behind on: backup and restore; a general ask-user tool; fallback chains and key pools; task budgets and cost; event triggers; remote channels beyond ntfy; read-aloud; inline Mermaid, LaTeX and previews; change tracking with undo; network settings (proxy, custom CA, headers); translations; ways in from other apps.

## Feature matrix

OM = OpenMinis (Android), Op = Operit v1, AH = AndroidHarness, nM = nanoMuse (Android). "?" = not verified.

| Capability | Locus | OM | Op | AH | nM |
|---|---|---|---|---|---|
| Bring your own key | yes | yes | yes | yes | yes |
| Subscription sign-in | yes (official) | yes (some imitate clients) | ? | yes (method ?) | yes |
| On-device model | yes | ? | yes | yes | ? |
| Fallback chains or key pools | partial | partial | yes | yes | ? |
| Usage with cost or quota | tokens only | yes | partial | yes | yes |
| Task budgets | no | no | ? | yes | yes |
| Linux sandbox | yes | yes | yes | yes | yes |
| Agent-driven browser | yes | yes | yes | yes | yes |
| Controls other apps | no (by design) | yes | yes | partial | yes |
| MCP over HTTP / OAuth | yes / yes | yes / yes | yes / ? | yes / yes | yes / yes |
| Local MCP (stdio) | yes (in Linux) | ? | yes | yes | ? |
| Sub-agents | yes | yes (steer) | ? | yes | yes |
| Plan mode | yes | ? | ? | yes | partial |
| Ask-user tool | browser only | yes | ? | yes | yes |
| AI approval reviewer | yes | no | no | no | no |
| Event triggers | no | partial | yes | no | yes |
| Reach-me or remote channel | ntfy | ? | LAN web | Telegram | 4 apps |
| Branching / full-text search | yes / yes | ? / yes | yes / ? | partial / yes | ? / ? |
| Backup and restore | no | yes | yes | yes | yes |
| Read aloud | no | yes | yes | yes | partial |
| Mermaid, LaTeX, inline UI | no | partial | partial | yes | ? |
| Change tracking with undo | trash only | no | partial | yes | no |
| Translated UI | no | yes | yes | yes | yes |
| CI and published builds | yes | yes | yes | yes | yes |

Not verified: OpenMinis's on-device model, branching and plan mode on Android; Operit's sub-agents and caching behaviour; AndroidHarness's ChatGPT sign-in method; nanoMuse's local MCP.
