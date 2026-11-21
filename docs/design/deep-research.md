# Deep research: the built-in Research workflow

Status: built 2026-11-21. Roadmap: "Deep-research workflow" under "From the feature-opportunities research". Idea #6 in [feature opportunities](../research/feature-opportunities-2026-11.md). Code: `core/agent/.../workflow/ResearchWorkflow.kt` and `ResearchReport.kt`, the budget wrap-up in `AgentLoop`, `WorkflowRepository` and `AgentRunner` (core/data), `ResearchWorkflowUi.kt` (core/ui). Unit-tested; *not yet tried on the phone or with a live key.*

Research asks one question across several sources, checks the key claims and hands back a cited report as a file. Claims it couldn't confirm stay in the report, marked as unverified, instead of being dropped.

## What others do (checked 2026-11-21)

- **Claude Code `/deep-research`** ([workflows](https://code.claude.com/docs/en/workflows)): "fans out web searches across several angles, fetches and cross-checks the sources it finds, and synthesizes a cited report". Verifier agents vote on each claim. Claims that fail the cross-check are filtered out. When "the verifier agents can't check a claim, such as after a rate limit or API error, the report lists that claim as unverified instead of counting it as refuted". The run is a script with phases, a progress view, and a token-cost warning above 25 agents or 1.5M tokens.
- **Anthropic's multi-agent research system** ([engineering post](https://www.anthropic.com/engineering/multi-agent-research-system)): a lead agent plans, then starts subagents in parallel. The rule of thumb is that "simple fact-finding requires just 1 agent with 3-10 tool calls" and "direct comparisons might need 2-4 subagents". A citation agent attributes each claim at the end. Multi-agent runs "use about 15× more tokens than chats". Lessons: give each subagent an objective, an output format and boundaries; "start with short, broad queries" and then narrow; scale effort to the question, because early versions spawned too many agents.
- **Manus Wide Research** ([blog](https://manus.im/blog/introducing-wide-research), read through search summaries): many parallel agents, each on one item of a list.

What Locus takes from these: phases, a planner that sizes the fan-out, researchers that return claims with URLs and quotes, a check of each key claim, unverifiable claims marked rather than dropped, and an upfront cost estimate. What it leaves: dozens of agents (Locus's helper limits are 3 at once and 6 per turn, and phones pay per token), and a separate scripting runtime (Locus's workflows are agent-guided plans; see [workflows](workflows.md#the-choice-agent-guided-not-replay)).

## What the user sees

- **Where.** In the workflow picker (+ → Run a workflow) and in Library → Skills → Workflows, listed first with a search icon and "Built in". It is always there and can't be removed or edited. **Duplicate** opens the editor on a copy, saved as a workflow of the user's own (`research-copy`; a saved workflow never takes the id `research`). Workflows have no hide option, so neither does this one (open question below).
- **The run sheet** has three inputs:
  - the question (required, grows to a few lines);
  - scope notes (optional): time range, places, sites to prefer or avoid. Web addresses and bare domains in them count as sites the user named (below);
  - depth: Quick, Standard (default) or Thorough.

  Under the inputs: "3 researchers · about 15 sources", the four phases, the task budget with what a run usually uses ("500k tokens · a run like this usually uses about 315k tokens"), a caution line when the chosen budget is below that, **Change budget** (the standard budget editor: tokens, cost, time, or Off), and a line about outside content. With a model that can't call tools (`Conversation.chatOnly`, or for a new chat `ChatOnlyRule` on the default model), a caution banner explains and Run is off.
- **In the chat.** The bubble is the question, with the scope notes under it. The agent's checklist (todo_write) shows the sub-questions, then "Verify key claims" and "Write the report". Helpers show as the usual nested rows. At the end, a file card for `research/<slug>-<date>.md` (Open, Share, Save to device) with a note such as "13 sources · 2 unverified claims", and a short summary. The task receipt shows the workflow's checks, including Locus's own check of the report.
- **Notification.** When a research run ends while Locus isn't on screen, a notification ("Research finished", "Research stopped at its budget" or "Research didn't finish", with the question) opens the chat. None after Stop, because the user was there to tap it. Scheduled runs keep their own notification. The lines are English, like every agent notification ([localization](localization.md#still-english)).

## How it runs

It is an ordinary workflow run (`WorkflowRender`): one user message whose visible text is the question, with the plan as a `<workflow_plan>` note. There is no new tool, no new prompt text in any chat's system prompt, and no new tool in any tool list, so every chat's cached prefix is unchanged. Only the plan's wording is research-specific (`ResearchWorkflow.render`). It reuses:

| Part | Reused from |
|---|---|
| Inputs, the plan note, read-back of the plan, receipt checks | [workflows](workflows.md) |
| Tool fence: anything outside its tools that changes something asks | `WorkflowScope.escalation` |
| Researchers | `agent_spawn` with the built-in `researcher` profile, `agent_wait` ([sub-agents](subagents.md)) |
| Checklist | `todo_write` |
| Report file and card | `write_file`, `send_file` |
| Budget | `RunBudget`, `BudgetGate` ([cost](cost.md)) |
| Taint and approvals | [taint](taint.md), unchanged |

### Phases (the plan's steps, English for the model)

1. **Plan.** Break the question into sub-questions within the scope notes (2–3 for Quick, 3 for Standard, 3–6 for Thorough). Put them on the checklist, then "Verify key claims" and "Write the report".
2. **Research.** Start the depth's researchers: one in the foreground for Quick; for Standard and Thorough, three at once in the background, splitting the sub-questions with no overlap, collected with `agent_wait`. Each task carries its sub-questions, the scope notes and today's date. It asks the researcher to search broadly then narrow, read about N good sources (primary and recent first, with dates), and report numbered findings: the claim, the source's title, its URL and a short exact quote; then disagreements and gaps. The plan says how many helpers the run may start in all.
3. **Verify.** The main agent does this itself, not a helper. Locus's own delegation rule is "Never use a helper to check or verify work" (`agent_spawn`'s description and the system prompt), and that rule stays. For each key claim it checks that the quote says it and whether other sources agree. Where a claim matters and rests on one source, it reads that source again with `fetch_url`, up to the depth's re-read limit (2, 4 or 8). A claim it can't confirm stays in the report, marked unverified with why.
4. **Report.** `write_file` to `research/<slug>-<date>.md`, in the question's language: a title, the date, `## Summary`, one section per sub-question with `[n]` citations after each claim, `## Unverified claims` (or "- None."), and `## Sources` as "1. Title — URL (accessed date)". The headings may be translated. Then `send_file` with a note, and a short answer. If `write_file` is refused (a scheduled run is read-only), the whole report goes in the answer instead.

The slug is up to six ASCII words of the question without stop words (accents folded), else `report`. The path is relative to the chat's folder, so a project chat's reports land in the project.

### Depth and limits

| Depth | Researchers | Sources each | Re-reads | Usually uses | Default budget |
|---|---|---|---|---|---|
| Quick | 1 | 4 | 2 | ~133k | 200k tokens |
| Standard | 3 | 5 | 4 | ~315k | 500k tokens |
| Thorough | 3 | 10 | 8 | ~535k | 1M tokens |

- **Helper limits are never exceeded.** The engine still enforces depth 1, 3 helpers at once, 6 spawns per turn, 25 steps and 600k tokens per helper, and a 2M per-turn helper spend cap (`SubagentLimits`). No depth asks for more than 3, and a test holds every depth to those limits. The plan also names the cap, so the model doesn't try a fourth helper and get refused.
- **Estimate.** Budget tokens, with cache reads at a tenth (`TaskBudget.budgetTokens`). Per researcher: 15k plus 12k per source. For the main agent: 50k plus 10k per re-read. This is a rough model, made from a helper's per-step context growth, and should be checked against real runs (open question). The defaults sit about 1.5–2× above it, on the budget editor's presets.
- **Budget on the plan.** The chosen budget, or the depth's default, rides on the plan (`WorkflowPlan.budget`, JSON `task_budget`). `AgentRunner.sendWorkflow` starts the task with it instead of the chat's own or the Settings default. Off means no budget for this run. Helpers count toward it as always, and once it's reached no new helper starts. A scheduled run reads it from the plan note (`runInBackground`).

### When the budget runs out

The plan also carries `on_budget_reached` (`WorkflowPlan.wrapUp`). `AgentRunner` passes it to the loop as `AgentConfig.budgetWrapUp`. When the budget stops the run (the user taps Stop at the pause, the pause goes unanswered for 30 minutes, or a scheduled run reaches it), the loop:

- appends the note as a hidden message from Locus ("start no helpers, run no searches and read no more pages; write the report from what you have, with a `## What's missing` section");
- gives the run `AgentLoop.BUDGET_WRAP_UP_STEPS` (4) more steps without checking the budget. That is enough to write, send and answer. New helpers stay refused, because the budget is still reached;
- then stops as before (`BudgetStopped`) if it hasn't finished.

The completion check is skipped in those steps. Only the user's own chat (depth 0) gets the wrap-up. A workflow without `on_budget_reached`, and every other chat, stops exactly as before. Continue at the pause still grants another increment, as for any task.

### The report check

`ResearchReport.check` validates structure only:

- a Summary section;
- inline citations (`[3]` or `[1, 4]`; Markdown links don't count; code blocks are skipped);
- a numbered Sources list where every entry has a web address and every citation has an entry;
- an Unverified claims section.

It returns problems and counts (sources, citations, unverified). English, Spanish, Portuguese and Chinese headings are recognised. The plan's first check is a new kind, `research_report` (`WorkflowCheck.CheckKind.RESEARCH_REPORT`, value: the report's path). `WorkflowChecks` judges it from what the task last wrote there with a successful `write_file`, read from the task's own messages, so nothing new is stored. On the receipt it reads "3 sources, 12 citations, 1 unverified", or why it failed. A copy of the workflow has no path, so the check takes the last Markdown file written. The second check is agent-judged: "Every key claim was checked against its source".

## Taint and approvals

Nothing about taint changes.

- A helper's report and every fetched page or search result are taint sources, so a research run is tainted from its first report on.
- Outward actions (sending, posting, deploying, `memory_save`) ask, as in any tainted run. The workflow's tool fence asks for anything outside its own tools that changes something.
- **Opening a page on a site the user didn't name asks** (`Taint.reason`: "the address itself can carry data out"). It asks per helper and per site, with **Allow site** for the rest of that helper's or the chat's task. This is the biggest cost to the experience, and it is deliberate: content read earlier could choose the address.
- To reduce the questions, the scope notes are part of the visible message, so the sites named there count as named (`Taint.hostsIn`). For a helper, that means its parent chat's user messages. The run sheet says so ("Sites you name here open without asking").
- The plan tells researchers that unnamed sites ask, and to prefer fewer, better sites.
- Search queries aren't gated; they go to the user's own search provider.

## Scheduled research

Library → Workflows → Research → Schedule works like any workflow schedule: the question and depth are fixed, and it repeats hourly, every 6 hours or daily. The task is named after the question. A scheduled run is read-only with nobody to ask ([scheduled runs](scheduled-runs.md)), so:

- researchers still run. Spawning the read-only `researcher` profile is an observation;
- **pages on sites not named in the question or scope notes are refused**, not asked. Search results and named sites still work;
- **`write_file` is refused**, so the report can't be written as a file. The plan says to put the whole report in the final answer instead. It then shows in the run's chat, in Tasks ("Open last run") and in the task notification;
- the run also has the 8-minute scheduled-run limit, the 40-step backstop and the plan's budget. When the budget runs out it gets the same wrap-up.

The schedule sheet says this in one line instead of the generic list of refused tools. A weekly digest therefore works best with the sites to read named in the scope notes.

## Localisation

What the model reads stays English: the workflow's definition, the plan's steps, the wrap-up note and the check labels. The app shows the built-in workflow through `shownWorkflow` in Spanish, Portuguese (Brazil) and Simplified Chinese:

- the name and description;
- the inputs' labels and hint;
- the depth names (the choice's values stay `quick`/`standard`/`thorough`; `WorkflowInputFields` takes an `optionLabel`);
- the phases, the budget lines and the notes.

Copies and saved workflows are shown as the user wrote them.

## Limits and open questions

- **Approvals for unnamed sites** will interrupt a Standard or Thorough run several times, once per helper and site. Options to discuss: a run-sheet choice to allow sites from search results for this run (a real weakening of taint, so it needs the owner's decision), or researchers sharing an "Allow site" grant with the parent's task.
- **Hide.** Workflows can't be hidden, so neither can the built-in one. A per-user "hidden built-ins" setting would be small if wanted.
- **Estimate accuracy.** The per-source and per-helper numbers are a model, not measurements. Check them against the receipts of real runs and adjust.
- **Verification depth.** The main agent re-reads only a few sources. It doesn't vote across independent verifiers as Claude Code does, because Locus's own rule keeps helpers off verification.
- The wrap-up is triggered by the budget only. The scheduled run's 8-minute limit cancels the run without one.

## Tests

- `ResearchWorkflowTest` (core/agent): the definition validates; a saved workflow never takes the built-in id; inputs become the visible question and a plan worded for the depth (steps, path, checks, read-back); bad inputs are refused; slugs; every depth within the helper limits; default budget against the estimate, a chosen budget and Off carried; old plans unchanged; the tool fence and named hosts from the scope notes; the fixture report (`src/test/resources/research/fixture-report.md`) passes the check, each missing part is caught, translated headings pass; the receipt judges the written report; the budget wrap-up writes the report, has a fixed number of steps, and nothing changes without a note.
- `ResearchRunTest` (core/data): the whole run on the real runner, helper engine and workspace with a scripted model. A researcher's findings come back, the report is written at its path and passes the check, the run is tainted from the helper's report, the plan's budget was used, and the finished notification fired. Also: the capability gate for chat-only models, the built-in can't be removed while a copy gets its own id, and a scheduled digest is named after its question and carries the read-only fallback.
- Screenshots: the picker with Research, the run sheet (Standard, Thorough with the budget editor open, no tool calling, scheduled) in core/ui, and a run in progress (checklist, verifying) and a finished run with the report card in feature/chat (`ResearchScreenshots`).
