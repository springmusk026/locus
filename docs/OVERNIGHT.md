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
