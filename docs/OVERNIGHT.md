# Overnight log

Autonomous work while the maintainer sleeps. Each entry: what was done, pull requests, and what's blocked. Nothing here is merged automatically; every change waits as a pull request with CI.

Ground rules: no merges, secrets, force-pushes or releases without the maintainer; no driving the phones (installs only when Locus is idle and closed); no keys or personal data in commits.

## Open before tonight
- #14 Open search results at the matching message (CI passed, waiting for review)
- #15 Supabase and Render integrations (CI passed, waiting for review)

## Log

### 1. Start
- Opened this log.
- Waiting for the comparison of OpenMinis, Operit, AndroidHarness and nanoMuse (research agent running).
- Started: editable helper prompts (roadmap item), in its own branch.

### 2. More parallel work (maintainer asked for more agents)
- Started, each in its own branch: Telegram "reach me" channel; `agent_send` to continue a helper; exact per-message analytics (owns the next database migration, v9).
- Still running: editable helper prompts; the comparison of OpenMinis, Operit, AndroidHarness and nanoMuse.

### 3. Comparison done, two more ideas started
- #17: research on OpenMinis, Operit, AndroidHarness and nanoMuse, and a roadmap section "Ideas from comparable projects" (26 ideas, things to improve, mistakes to avoid, and ideas that conflict with our principles).
- Started from it: an `ask_user` tool (questions with options, answerable from the notification); reliability fixes from competitors' bug reports (system-prompt size cap, stable cache keys, reasoning-token counts, key isolation test, retrying dropped streams).
- Running now: helper prompts, Telegram, `agent_send`, exact analytics (database v9), `ask_user`, reliability.

### 4. Helper prompts done
- #18: editable prompts for titles, suggestions and long-chat summaries (the safety reviewer's prompt stays locked).
- Started: encrypted backup and restore to a file you pick (vault excluded unless you opt in).

### 5. Telegram done
- #19: Telegram as a reach-me channel (`notify_me` and task results go to ntfy and/or Telegram; Find my chat setup; token scrubbed everywhere). Inbound commands left as a follow-up on purpose.
- Started: read replies aloud, and share/export a chat as Markdown or PDF.

### 6. Exact Insights done
- #20: each reply stores its model, provider and timing; side requests (titles, suggestions, summaries, reviewer, lessons) are counted in a new "Where tokens went" section. Database v9 (owns the schema tonight). Totals in Insights will go up because side requests now count.
- Started: skill import from a GitHub link or zip (as drafts); per-chat modes (Read-only, Workspace, Full).

### 7. Usage limit hit, then resumed
- #21: `agent_send`, continue a finished helper with a follow-up (same limits, approvals and cache-friendly transcript as spawning).
- The account's 5-hour usage limit was reached with eight agents running; five stopped part-way (work saved). After the reset: resumed reliability fixes and read-aloud/export; backup, skill import and per-chat modes resume as slots free up. Running fewer agents at once from here to stay under the limit.

### 8. Ask-user done, wider research started
- #22: `ask_user`, questions the agent waits on (card in chat, answer from the notification or lock screen).
- Closed issue #1 (fixed by #8 in v0.1.1; reopen with screenshots if it returns).
- Resumed: encrypted backup. Started: wider research (more open-source Android AI apps such as ChatterUI, PocketPal, Maid, AI Edge Gallery; Android 16/17 platform features; new model-API and MCP features; F-Droid requirements).
- Queued: skill import, per-chat modes.

### 9. More tests (maintainer asked)
- Started: a coverage report (Kover) across modules and targeted tests for the riskiest code: secrets and token scrubbing, approval policies, ViewModels, repositories, streaming adapters, recovery. Results and how to run them will go in docs/TESTING.md.

### 10. Read aloud and export done
- #23: read replies aloud (system voice; skips code, tables, thinking) and share a chat as Markdown or PDF (active branch only, secrets redacted, or saved to the workspace).
- Resumed: skill import. Running: reliability, backup, coverage and tests, wider research. Queued: per-chat modes.

### 11. Reliability done
- #24: system-prompt size cap (on-device friendly), stable `prompt_cache_key`, reasoning tokens counted, a key-isolation test across five providers, retry of dropped streams, Linux services shown as stopped after a restart.
- CI: every pull request that has finished checking is green (#14–#22); #23 and #24 are running.
- Resumed: per-chat modes. Running: backup, tests and coverage, skill import, wider research.

### 12. Second research round
- Added to #17: `docs/research/more-apps-and-platform.md` (PocketPal, AI Edge Gallery, ChatterUI, Maid, SmolChat, llama.cpp Android, MLC, Off Grid, Kelivo; Android 16/17; new model-API features; MCP; F-Droid) and the new ideas in the roadmap.
- **Two real gaps found and confirmed in the code:** Anthropic prompt caching was never requested (no `cache_control`), so Anthropic chats weren't cached; and the Android 17 local network permission is missing, which would block LAN providers, SSH and MCP. Started a fix for both, plus newer compaction models and not retrying spend-limit errors.

### 13. Encrypted backup done
- #25: Settings → Backup & restore. One `.locusbackup` file (PBKDF2 600k + Tink streaming AES-GCM), chats, settings, memory and skills; workspace and API keys only if you opt in. Restore verifies the whole file first, then swaps data on restart. Needs a device check, including the minified release build.
- Running: critical fixes (Anthropic caching, local network permission), tests and coverage, skill import, per-chat modes.

### 14. Per-chat modes done
- #26: Read-only / Workspace / Full per chat; every tool classified (a test fails if a new one isn't); modes only narrow; helpers follow the parent; prompt and tools unchanged so the cache holds.
- Started: a "what was sent" viewer (exact request per turn, secrets scrubbed); on-device backend truth (GPU or CPU actually used, tokens/s, RAM checks, unload).

### 15. Skill import done
- #27: import a skill from a GitHub link or a zip, always as a draft to review (zip-slip and size limits, symlinks refused, provenance recorded).
- Started: LaTeX maths and Mermaid diagrams in replies (bundled locally, no network, rendered only once complete).

### 16. Merging (maintainer approved merging ready PRs)
- Merged: #14, #15, #17, #18. A merge agent is now merging the rest in order (#19 to #28), bringing main into each branch, fixing conflicts, and waiting for CI before every merge. This log (#16) stays open until the end.
- #28: Anthropic prompt caching turned on (breakpoints on the system prompt and the last message), up-to-date compaction models via the Models API, the Android 17 local network permission with a rationale before LAN connections, and no retries on spend-limit errors.

### 17. "What was sent" done, F-Droid started
- #29: see the exact request behind any reply (kept in memory only, rebuilt with the adapter's own code, secrets and base64 scrubbed). Added to the merge queue.
- Started: F-Droid readiness (versions in source, no dependency-info block, opt-in Linux download, fastlane metadata, a draft fdroiddata recipe that builds proot from source).

### 18. On-device truth done
- #30: on-device Gemma shows the backend it really runs on (GPU or CPU, with the fallback reason), per-reply speed, an optional benchmark, RAM warnings from Gallery's minimums, and Unload. Added to the merge queue.
- Holding new feature branches until the merge queue settles, to avoid piling up more conflicts. Running: merge agent (on #19), tests and coverage, maths and diagrams, F-Droid readiness.
