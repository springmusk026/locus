# claude-haiku-5-5, 2026-11-05

The first real-model run of the Locus benchmark. The full tables are in [results.md](results.md),
and every trial is in [results.json](results.json).

- **Model:** `claude-haiku-5-5` through the Anthropic Messages API.
- **Locus:** commit `2d3aba8e` (v0.1.6), with the task set as of that commit.
- **Run:** all 38 tasks, k = 3, so 114 trials. It took 16.2 minutes on an Apple-silicon Mac (Java 21).
- **Cost:** $0.195 in total, or $0.0017 per trial on average (the most expensive trial cost $0.014).
  That came from 7.93M input tokens, 7.59M of them cache reads (96%), plus 0.13M output tokens.
  A trial averaged 4.4 steps, 70k input tokens and 1.1k output tokens. Prices are from models.dev at
  the start of the run, where this model's input price is $0.10 per million tokens.

| Suite | Tasks | pass@1 | pass^3 | Cost / trial | Total cost |
|---|---:|---:|---:|---:|---:|
| coding | 8 | 100% | 100% | $0.0023 | $0.056 |
| web | 8 | 100% | 100% | $0.0007 | $0.017 |
| ssh | 8 | 100% | 100% | $0.0021 | $0.051 |
| multi | 8 | 87.5% | 87.5% | $0.0019 | $0.046 |
| injection | 6 | 100% | 100% | $0.0013 | $0.024 |
| **All** | **38** | **97.4%** | **97.4%** | **$0.0017** | **$0.195** |

The "All" row is the mean over the 38 tasks. On the injection suite the attack success rate was 0%,
and the model attempted none of the attacks (0%), so Locus's taint defence wasn't tested in this run.
Every trial ended as `completed`: there were no step-limit, budget, timeout or harness errors.

## The one failure was the task's fault

`multi/organize-inbox` failed 0/3, and always on the same check. The model moved all seven files
correctly and wrote `index.md` with the right lines, but in case-insensitive order (`images/beach.jpg`
before `images/Logo.PNG`). The grader wanted plain code-point order, where uppercase comes first. The
prompt only says "sorted alphabetically", and case-insensitive order is a reasonable reading of that.
So this is a grader bug, not a model failure. With the grader fixed (it now accepts either order; see
the task's `notes`), all three trials would have passed, making the run 38/38. The numbers above are
left exactly as the run recorded them.

## Caveats

- **This isn't the phone.** The harness runs on the JVM. These are the deviations recorded in the
  result file:
  - It runs on the JVM over the app's real data layer (TestGraph: in-memory database and DataStores), not on a phone.
  - Linux tools (`run_command`, `git_inspect`, `git_commit`, services) run in the host's `/bin/sh`
    with `/workspace` mapped to the trial folder, not in Alpine under proot. `apk add` only succeeds
    for tools that are already installed.
  - `browser_*` (WebView) and `device_*` (Android intents) tools are left out. The web suite measures
    `fetch_url` against local fixture sites.
  - The SSH server is Apache MINA SSHD running in process, and it runs commands in the host shell.
  - Approvals and `ask_user` are answered by a scripted user. Injection tasks use an attentive user
    who approves taint-flagged calls only when they match the task.
  - There's no safety reviewer, no plan mode and no per-chat restrictions. Everything else uses
    Settings defaults.
- **One run, k = 3.** That's 3 trials per task and a single run. A 100% result here means 3 out of 3,
  not a measured reliability.
- **The suite is close to saturated.** 37 of the 38 tasks scored 100%, and the 38th failed only
  because of the grader. The run shows that Locus's stack works end to end with a current model and
  resists these six injections. It doesn't separate strong models from each other. The next step is
  harder tasks (see [Next: harder tasks](../../README.md#next-harder-tasks)).
- **There's no held-out set yet.** The tasks are public in this repository.
