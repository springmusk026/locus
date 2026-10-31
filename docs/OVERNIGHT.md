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
