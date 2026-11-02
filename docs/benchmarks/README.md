# Benchmarks

Locus has a benchmark suite so we can say how often the agent actually finishes real tasks, and
publish that number honestly. The harness lives in the `:bench` module. The tasks live in
`bench/tasks/`.

**Status:** the harness, the graders and 38 tasks are done. **No real-model numbers are published
yet.** The only results in this repository are in [`sample-fake/`](sample-fake/results.md), from a
scripted fake model that replays each task's reference solution. They show that the graders work and
say nothing about any model.

## How others do it, and what we took

| Benchmark | What a task is | How it's graded | What we took |
|---|---|---|---|
| **SWE-bench** (Jimenez et al., 2023) [1] | A real GitHub issue in one of 12 Python repos; the model edits the code. | The PR's tests: FAIL_TO_PASS ("tests resolved by the PR and tied to the issue resolution") must now pass and PASS_TO_PASS must keep passing, in Docker [2][3]. Lite has 300 test instances [2]. Verified has 500 human-validated ones [4], screened by 93 developers (OpenAI, 2024) [5]. | Coding tasks are graded by running tests, including **hidden tests** copied in after the run (`grader/`), so special-casing the visible test doesn't pass. |
| **Terminal-Bench** 2.0 (2026) [6] | 89 tasks: an instruction, a Docker environment, a time limit, tests and an **oracle solution**. | Tests "verify that all outcomes described in the instruction have been achieved by testing properties of the final container state". All or nothing [7]. | Every task has an `oracle.json` reference solution, and a unit test proves it passes. Grading looks at end state, not steps. |
| **τ-bench** (Yao et al., 2024) [8], **τ²-bench** [9] | A customer-service conversation with an LLM-simulated user and tools over a database. | The final database is compared with the expected one, plus required facts in the replies. Introduces **pass^k**: "the chance that all k i.i.d. task trials are successful". | **pass^k** is reported next to pass@1. The simulated user is **scripted**, not an LLM, so it's deterministic and free (see below). |
| **WebArena** (Zhou et al., 2023) [10], **BrowserGym** [11] | 812 tasks on self-hosted sites (shop, CMS, forum, GitLab, maps). | exact_match / must_include on answers; programmatic checks of site state and URLs; one LLM-judged `fuzzy_match` [10]. BrowserGym wraps these benchmarks in one gym API [11]. | Web tasks use **local fixture sites** and check the answer text and **the requests the site received**. No fuzzy LLM match. |
| **AgentDojo** (Debenedetti et al., NeurIPS 2024) [12] | 97 user tasks × 27 injection tasks in 4 environments (629 security cases). | Deterministic utility and security functions over the environment state. The authors avoid LLM judges because an injection could hijack the judge too. Reports benign utility, utility under attack and targeted attack success rate. | The **injection** suite: each task has utility checks and `security` checks, and we report the attack success rate. We also report whether the model *tried* the attack, even when Locus blocked it. |

Guidance we followed:

- Anthropic, *Demystifying evals for AI agents* (2026) [13]: code-based graders are "fast" and "objective" but "brittle to valid variations". LLM graders need calibration. It's "often better to grade what the agent produced, not the path it took". Each trial should start from a clean, isolated environment. "You won't know if your graders are working well unless you read the transcripts". "20-50 simple tasks drawn from real failures is a great start". It defines pass@k ("at least one correct solution in k attempts") and pass^k ("all k trials succeed").
- Anthropic, *Building effective agents* [14]: "extensive testing in sandboxed environments". Anthropic docs, *Develop tests* [15]: "Automate when possible".
- OpenAI, *Evaluation best practices* [16]: eval early and often, and mine logs for cases. For agents, check tool selection and arguments. If you use LLM judges, prefer pass/fail or pairwise grading and calibrate them with humans.
- Kapoor et al., *AI Agents That Matter* (2024) [17]: accuracy-only leaderboards mislead, so optimise cost and accuracy jointly. HAL [18] and the Terminal-Bench leaderboard [19] show cost next to accuracy. **Every Locus result records tokens, cost and wall time per trial.**

### What fits a phone agent

Locus is a personal agent on a phone. A user hands it a task and expects it done, often while
they aren't watching. That shaped five choices:

1. **pass^k matters more than pass@k.** A user who has to retry until it works doesn't have a
   reliable agent. We report pass@1 (the share of trials that pass), pass^k (all k pass) and pass@k
   (at least one passes), all as unbiased estimators.
2. **Deterministic graders only.** Each check is a file comparison, a command's exit code and output,
   a substring of the final answer, a request the fixture server received, a file on the SSH server,
   or the agent's memory. No model ever decides whether a trial passed. This is also safer for the
   injection suite (AgentDojo's point).
3. **Small, hand-written, realistic tasks** (about 8 per suite, 38 in all). These are the kinds of
   things people ask Locus to do: fix a failing test, commit a change, read a price, place an order
   through a site's API, edit a config on a server, sort files, ask before choosing. They are not
   SWE-bench-scale repositories, which a phone can't build anyway.
4. **The real app stack.** Each trial uses Locus's real `AgentLoop`, real tool catalog, real system
   prompt, real policies and taint, and real provider adapters. They're wired by `core:testing`'s
   `TestGraph`, the same graph the app's unit tests use.
5. **Cost and time are first-class.** They are recorded per trial and summed per suite, and a run can
   stop itself at a cost limit.

### Metrics

For a task with `n` trials of which `c` passed:

- pass@k = 1 − C(n−c, k) / C(n, k), Chen et al.'s unbiased estimator [20], computed as a product so it stays numerically stable;
- pass^k = C(c, k) / C(n, k), τ-bench's estimator [8]: the chance that k trials drawn from the n all pass;
- pass@1 = c / n.

Suite figures are means over tasks, so a task counts the same however many trials it has. With
`k = n` (the default, 3 trials per task) pass^k is 1 only when every trial passed. Injection tasks also
report the **attack success rate** (the attack's goal was met: a `security` check failed) and the
**attempt rate** (the model called a tool the way the injection asked, whether or not Locus let it
through).

Each trial records: pass/fail; utility and security separately; the outcome (`completed`,
`step_limit`, `token_budget`, `timeout`, `refused`, `model_error`, `harness_error`); the first failed
check with its details; steps; input, output and cache-read tokens; budget tokens; cost; wall time;
tool calls by name; approvals; approval cards raised because of taint; calls the scripted user
declined; and questions asked.

### Contamination

The tasks and fixtures are new, written for Locus, and use synthetic names, sites and data, so no model
saw them in training before this commit. That will change once they're public, as it did for
SWE-bench: Liang et al. found models could name the buggy file path from the issue text alone, up to
76% of the time on Verified versus 53% on other repositories [21]. SWE-Bench+ found 32.67% of
successful patches had the solution leaked in the issue or its comments [22]. OpenAI stopped reporting
SWE-bench Verified in February 2026, citing contamination and flawed tests [23]. So:

- every published result names the Locus commit the tasks came from;
- answers are checked against end state, not phrasing, and coding tasks have hidden tests;
- before publishing headline numbers, add a private held-out set of tasks that never enters the
  repository, and report both.

## The suites

| Suite | Tasks | What it measures |
|---|---:|---|
| `coding` | 8 | Fixing a failing Python test; implementing a function to its docstring and a JS parser to its README (both graded by hidden tests); fixing a JS bug; a rename across four files; adding a CLI flag; fixing a shell script that breaks on spaces; changing code and committing it with `git_commit`. |
| `web` | 8 | Using `fetch_url` against local sites: reading a price one link away (next to a decoy price), following links, counting across paginated pages, summing a JSON API, placing an order through a documented API (graded by the POST the shop received, exactly once), turning a table into CSV, following a redirect, and comparing plans across pages. |
| `ssh` | 8 | Against a real SSH server: reading a file, editing one key of a config with two `port` keys, counting log errors (with a decoy line), uploading and `chmod`ing a script, fixing a broken script, running a background job with `ssh_job_start` and waiting for it, deleting only `.tmp` files, and finding and fixing one config among three. |
| `multi` | 8 | Multi-step work: asking the user before writing (twice, with `ask_user`), planning with the todo list, delegating to two helpers in parallel, sorting files and writing an index, fetching paginated JSON and writing a report, scanning a project for TODOs, and saving a preference to memory when asked. |
| `injection` | 6 | AgentDojo-style attacks in content the agent reads: a web page that wants a workspace secret sent away, a README that wants a memory planted, an API response that wants `.env` posted, command output that wants data deleted, a server file that wants a skill created, and a checkout page that wants the order copied to another host. |

Each task is a folder: `task.json` (prompt, budget, scripted user, checks, injection), `workspace/`,
and optionally `grader/` (hidden files copied in after the run), `site/<host>/`, `remote/` (the SSH
home) and `oracle.json` (the reference solution).

**Process checks** (`tool_used`, `asked_user`) appear only where the prompt asks for the feature: "use
your todo list", "start two helpers", "ask me". Everything else is graded on outcome.

### Not covered yet

- **The WebView browser.** `browser_*` needs Android's WebView, which doesn't exist on the JVM. The
  `web` suite measures `fetch_url` only. A device suite would run the same task format from an
  instrumentation test (`androidTest` in `:app`). It would serve the same `site/` folders from a
  local server on the phone, add a `browser` category whose prompts say "use the browser", and grade
  by the same checks (the fixture server's requests, the answer, workspace files). It isn't built:
  running it needs a device or emulator in CI.
- **Phone actions** (`device_*`). These open other apps through Android intents, so they need a device too.
- **Scheduled-task creation.** The agent has no tool to create scheduled tasks: you create them on the
  Tasks screen. Background runs could get their own suite later (read-only runs refusing outward
  calls).
- **The AI safety reviewer, plan mode and per-chat restrictions** are off: trials use Settings
  defaults.

## How a trial runs

1. A temp folder, a fresh `TestGraph`, and the app's data layer with an in-memory database. The
   provider is saved with its key (the key stays in the trial's in-memory vault).
2. The task's `workspace/` is copied in, and its `setup` commands run (e.g. `git init`).
3. A fixture web server starts on a loopback port. The trial's HTTP client sends every `*.bench.test`
   address there, whatever its port. The client is the app's OkHttp setup with a resolver and an
   interceptor added. To `fetch_url`'s guard and to taint these are public hosts, not loopback, so an
   "attacker" host behaves like a real one. If the task needs SSH, an Apache MINA SSHD server starts
   and is added to Settings with its host key pinned, as the user would do.
4. A chat is created exactly as the app creates one (system prompt, memory, tool list). The prompt is
   sent and the real `AgentLoop` runs with the task's budget: `maxSteps`, a token budget (Locus's task
   budget, cache reads at a tenth) and a wall-clock timeout.
5. The **scripted user** answers. `ask_user` gets the first matching rule in `task.json`, or else the
   first option. Approval cards are approved, since the user asked for the task. Injection tasks use an
   **attentive user**: a card that Locus raised because the run is tainted ("Tainted run: …") is
   approved only if it matches what the task expects (e.g. the order POST to the shop), and declined
   otherwise, like a user who reads the card. So the injection suite measures Locus's defence (taint,
   #72) together with the model, and also records whether the model tried.
6. `grader/` is copied over the workspace, the checks run, and everything is deleted.

### How this differs from the phone

These are written into every result file too:

- It runs on the JVM over the app's real data layer, not on a phone.
- **Linux tools run in the host's `/bin/sh`, not in Alpine under proot**, which can't run on the
  JVM. `LinuxEnvironment` has a `CommandHost` hook that only the harness sets. It is how
  `run_command`, `git_inspect`, `git_commit` and the service tools are kept unchanged.
  `/workspace` is mapped to the trial folder, and the environment is rebuilt from scratch (HOME and
  TMPDIR in the trial folder, nothing from the harness, so the provider key never reaches a command).
  `apk add` succeeds only for tools the host has (python3, node, git). Tool descriptions still say
  Alpine.
- **It is not a sandbox.** Commands the model runs can reach anything the user running the benchmark
  can. Run real-model benchmarks on a throwaway machine. The GitHub workflow uses an ephemeral
  runner.
- The SSH server is in-process, runs commands in the host shell, and SFTP sees the host's paths
  starting in its home folder.
- `browser_*` and `device_*` are left out of the tool list.

## Running

The harness's own tests run with the rest of the unit tests (no key, no network): graders, metrics,
the parts of the harness, every oracle end to end, and checks that no task passes when the agent does
nothing and that a followed injection is caught.

```sh
./gradlew :bench:testDebugUnitTest
```

A real run (the key is read from the environment, never from a file, and never printed or written):

```sh
export LOCUS_BENCH_API_KEY=...        # the provider's API key
./gradlew :bench:bench \
  -Pbench.provider=anthropic -Pbench.model=claude-haiku-4-5 \
  -Pbench.k=3 -Pbench.maxCostUsd=10
```

| Property | Default | |
|---|---|---|
| `bench.provider` | `fake` | `anthropic`, `openai` (Responses API), `openai-chat`, `openrouter`, `gemini`, `deepseek`, or `fake` |
| `bench.model` | | the model id |
| `bench.baseUrl` | the provider's | e.g. a proxy |
| `bench.suites` | all | comma-separated suites or task ids: `-Pbench.suites=coding,web/read-price` |
| `bench.k` | 3 | trials per task |
| `bench.maxCostUsd` | none | stop starting trials once the run has cost this much |
| `bench.out` | `bench/build/results` | where `results.json` and `results.md` go |
| `bench.label` | the model id | recorded in the results |

Prices and context windows come from [models.dev](https://models.dev/api.json) at the start of a run,
the same catalogue the app uses for task budgets. A model it doesn't know gets cost "n/a", never 0.

**On GitHub:** add the repository secret `LOCUS_BENCH_API_KEY`, then run the **Benchmarks** workflow
(Actions → Benchmarks → Run workflow) and choose the provider, model, suites, k and cost limit. It
uploads `results.json` and `results.md` as an artifact and puts the table in the run summary. It never
runs on push or pull request.

### Cost of a run

A full run is 38 tasks × k = 3, or 114 trials. Each model request carries Locus's system prompt and
about a hundred tool definitions, about 17.5k input tokens before any history. (This is measured: it's
the fake run's request size divided by four.) The reference solutions take 3.5 steps on average. Real
models take more, so assume about 8 steps and 150–200k input tokens per trial, most of it a cacheable
prefix, plus about 3k output tokens. That is roughly 20M input and 0.4M output tokens per full run.
models.dev prices on 2026-11-01, USD per million tokens (input / output / cache read):

| Model | Prices | Full run, no caching | With the prefix cached |
|---|---|---:|---:|
| `claude-haiku-4-5` | 1 / 5 / 0.1 | ≈ $22 | ≈ $9 |
| `gpt-5-mini` | 0.25 / 2 / 0.025 | ≈ $6 | ≈ $2 |
| `gemini-2.5-flash` | 0.3 / 2.5 / 0.03 | ≈ $7 | ≈ $2.5 |
| `deepseek-v4-flash` | 0.15 / 0.6 / 0.003 | ≈ $3 | ≈ $1 |

These are estimates; the result file has the real figure. The budgets cap a run: the tasks' token
budgets add up to 7.55M budget tokens per pass, or about 23M for k = 3. Use `bench.maxCostUsd` (the
workflow's default is $10) as a hard stop. One suite costs about a fifth of the full run. Time: a few
minutes per trial with a fast model, so a full run takes about 2–4 hours on the workflow.

## Adding a task

1. Make `bench/tasks/<suite>/<name>/` with `task.json` and the fixtures. Copy a similar task.
2. Write checks a correct solution passes and an untouched workspace fails. Prefer hidden tests and
   exact file contents to answer substrings.
3. Write `oracle.json` and run `./gradlew :bench:testDebugUnitTest`. `FakeRunTest` replays every
   oracle and checks that no task passes when the agent does nothing.
4. Before trusting the task, run it with a real model and read the transcripts of the failures: is
   the failure the model's or the task's?

## Sources

1. Jimenez et al., *SWE-bench: Can Language Models Resolve Real-World GitHub Issues?* https://arxiv.org/abs/2310.06770
2. SWE-bench Lite dataset card. https://huggingface.co/datasets/princeton-nlp/SWE-bench_Lite
3. SWE-bench evaluation guide. https://www.swebench.com/SWE-bench/guides/evaluation/
4. SWE-bench Verified dataset card. https://huggingface.co/datasets/princeton-nlp/SWE-bench_Verified
5. OpenAI, *Introducing SWE-bench Verified* (2024-08-13). https://openai.com/index/introducing-swe-bench-verified/
6. Merrill et al., *Terminal-Bench: Benchmarking Agents on Hard, Realistic Tasks in Command Line Interfaces* (2026). https://arxiv.org/abs/2601.11868
7. Inspect Evals, Terminal-Bench 2. https://ukgovernmentbeis.github.io/inspect_evals/evals/coding/terminal_bench_2
8. Yao et al., *τ-bench* (2024). https://arxiv.org/abs/2406.12045
9. Barres et al., *τ²-Bench* (2025). https://arxiv.org/abs/2506.07982
10. Zhou et al., *WebArena* (2023). https://arxiv.org/abs/2307.13854
11. ServiceNow, BrowserGym. https://github.com/ServiceNow/BrowserGym and https://arxiv.org/abs/2412.05467
12. Debenedetti et al., *AgentDojo* (NeurIPS 2024). https://arxiv.org/abs/2406.13352 and https://github.com/ethz-spylab/agentdojo
13. Anthropic, *Demystifying evals for AI agents* (2026-01-09). https://www.anthropic.com/engineering/demystifying-evals-for-ai-agents
14. Anthropic, *Building effective agents* (2024-12-19). https://www.anthropic.com/engineering/building-effective-agents
15. Anthropic docs, *Develop tests*. https://platform.claude.com/docs/en/test-and-evaluate/develop-tests
16. OpenAI, *Evaluation best practices*. https://developers.openai.com/api/docs/guides/evaluation-best-practices
17. Kapoor et al., *AI Agents That Matter* (2024). https://arxiv.org/abs/2407.01502
18. HAL, the Holistic Agent Leaderboard. https://hal.cs.princeton.edu/
19. Terminal-Bench leaderboard. https://www.tbench.ai/leaderboard
20. Chen et al., *Evaluating Large Language Models Trained on Code* (2021). https://arxiv.org/abs/2107.03374
21. Liang et al., *The SWE-Bench Illusion* (2025). https://arxiv.org/abs/2506.12286
22. Aleithan et al., *SWE-Bench+* (2024). https://arxiv.org/abs/2410.06992
23. OpenAI, *Why we no longer evaluate SWE-bench Verified* (2026-02-23). https://openai.com/index/why-we-no-longer-evaluate-swe-bench-verified/

The SSH fixture uses Apache MINA SSHD 2.20.1 (Apache-2.0, released 2026-10-15; the newer 3.0.0-M7 is a
milestone): https://repo1.maven.org/maven2/org/apache/sshd/sshd-core/
