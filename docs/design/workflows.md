# Reusable workflows

Status: save, run and scheduling built 2026-11-01. Roadmap: "Reusable workflows" (P1) under "Recommended next (Nov 2026)".

A workflow is a task that went well, saved so it can run again with new inputs. It keeps:

- what to do, with the inputs as slots,
- the plan the agent followed,
- the tools it may use without asking,
- the steps that needed the user's approval,
- how to tell the run worked.

A skill is a how-to the agent may read. A workflow is a whole task the user starts, with typed inputs, limits that Locus enforces, and checks that Locus judges.

## How others represent a reusable run (checked 2026-11-01)

| | Parameters | Steps | Approvals | Failure | Expected output |
|---|---|---|---|---|---|
| Claude Code commands and skills ([1]) | Untyped strings: `$ARGUMENTS`, `$0`, named `arguments:`; `argument-hint` for autocomplete; no defaults | Prose the model follows; `context: fork` runs it in a subagent | `allowed-tools` run without asking during the turn; `disable-model-invocation` makes it user-only | A failing `` !`cmd` `` preprocessing step aborts the invocation; no retry syntax | Free-form; the skill enters the chat as one message |
| OpenAI Codex scheduled tasks ("automations") and skills ([2], [3]) | None: "the saved prompt determines what each run does" | The prompt; it can call skills | Runs unattended with the default sandbox and approvals off where allowed | A call the sandbox blocks fails; no documented retry | Results land in a Scheduled view with unread markers |
| n8n AI Agent ([4], [5], [6], [7]) | Sub-workflow inputs: named, typed fields, or a JSON example | A node graph; the agent node picks among connected tools; Max Iterations (10) | Per tool: the run pauses, a person sees the tool and the arguments the AI chose, and approves or denies (the AI is told) | Retry On Fail, On Error (stop / continue / error output), error workflows | Output parsers (structured, list, auto-fixing) |
| Zapier Agents and Zaps ([8], [9], [10], [11]) | The Run Agent step's input message, mapped from earlier steps | Trigger then actions; agents follow instructions in order | Human-in-the-loop steps: Request Approval, Collect Data, with timeouts | Autoreplay retries failed steps; a Zap that fails 95% of runs over 7 days pauses | Agent Output Fields, each with a name and description |
| Apple Shortcuts ([12], [13], [14], [15]) | Shortcut Input with content types, and what to do without input; Ask for Input with a default | A linear list of actions; If / Otherwise; Repeat | Personal automations: Run Immediately or Run After Confirmation ([16], third-party) | None beyond Stop and Output or Stop and Respond | Stop and Output returns content to the caller |
| GitHub Actions ([17], [18], [19]) | `workflow_dispatch` inputs: `string`, `boolean`, `choice`, `number`, `environment`, each with `required`, `default`, `options` and `description` | Jobs of steps; `needs`; `if:` | Environments with required reviewers and a wait timer | `if: failure()`, `continue-on-error`, `timeout-minutes` | `jobs.<id>.outputs` |

What this means for Locus:

- **Parameters.** The LLM-native formats (skills) take untyped strings. The deterministic ones (Actions, n8n, Shortcuts) type their inputs. A phone form needs types to show the right control and to refuse a bad value before a run starts. So Locus types its inputs the way Actions does, and adds URL and path, the inputs agents use most.
- **Approvals.** These attach to the risky step, not to the whole run: n8n attaches them per tool, and Actions per environment. Locus does the same. Each tool is either free to run or an approval point.
- **Outputs.** Zapier's named output fields and n8n's parsers make results checkable. Locus keeps one expected output for the agent to aim at, plus success checks it can verify itself.
- **Unattended runs.** Codex runs scheduled tasks with approvals off and reviews them afterwards. Locus's scheduled runs are read-only and refuse whatever would need a person (docs/design/scheduled-runs.md), so a scheduled workflow can't include its approval points. The UI says so.

## The choice: agent-guided, not replay

There are two ways to "do it again":

1. **Exact replay.** Record the tool calls and run them again with the inputs swapped in.
2. **Agent-guided.** Give the agent a structured plan: the goal, the inputs, the steps in words, the allowed tools, the approval points and the success checks. It carries out the plan and adapts each step to what it finds.

Locus uses agent-guided runs:

- **Replay is brittle exactly where agents are useful.** Record/replay tests of web apps are "quite brittle": one study built a taxonomy from 1,065 breakages across 453 versions ([20]). A recorded `edit_file` holds the old file's text, and a recorded `run_command` holds the old failure. Once the input changes (another folder, another site), most recorded calls are wrong.
- **Locus's tools need judgement.** Editing a file needs a fresh read, and a test failure needs a fix written for that failure. Anthropic's guidance is to use fixed "predefined code paths" when the task is well defined, and an agent when it needs "flexibility and model-driven decision-making", grounded in "ground truth" at each step ([21]). A saved task is the second kind: it worked once, and the next run will differ.
- **The limits don't depend on replay.** The parts that should be fixed (which tools, which approvals, what counts as done) are fixed, and enforced by Locus rather than the model (below). Only the way the agent gets there is left to it.

Replay would still suit a pure sequence of calls with no reads in between, such as "post this to three channels". That is a later step: "conditionals and replayed steps" in the roadmap.

## The format

`core/model/Workflow.kt`, saved as JSON:

```json
{
  "schema": 1,
  "id": "test-and-deploy",
  "name": "Test and deploy",
  "description": "Runs the tests, fixes failures and deploys.",
  "goal": "Run the tests in {folder}, fix failures, then deploy to {site}",
  "parameters": [
    {"name": "folder", "label": "Folder", "type": "path", "default": "app/", "required": true},
    {"name": "site", "type": "url", "default": "https://example.dev"}
  ],
  "steps": ["Run the tests in {folder}", "Fix failing tests", "Deploy to {site}"],
  "tools": ["read_file", "run_command", "edit_file", "connector:vercel"],
  "approvals": ["connector:vercel"],
  "checks": [{"kind": "tests_pass", "label": "Tests pass"}, {"kind": "agent", "label": "The site loads"}],
  "expectedOutput": "The live address",
  "source": {"conversationId": "…", "taskId": "…"}
}
```

- **`schema`** is the file format's version. Fields may be added with defaults but never renamed, and unknown fields are ignored, so a file from a newer Locus still loads.
- **Parameter types** are `text`, `url`, `path`, `number`, `boolean` and `choice`. A run checks each value against its type: a URL must be http(s), and a path can't contain `..`.
- **Capability keys** are a tool name, or `connector:<id>` for a connector or MCP server. These are the same keys the composer and projects use (`ToolGroup.connectorKey`), so a skill manifest can use them too.

## Storage

Workflows are files in agent-home, like skills:

- `workflows/<id>.json` for every chat;
- `projects/<project id>/workflows/<id>.json` for one project. A workflow saved from a project chat goes there by default.

Agent-home is in the app's private storage, and the agent has no tool that writes there. So the agent can't widen its own workflow's limits. Agent-home is in every backup, so workflows are too (`WorkflowsBackupTest`). Removing a project archives its folder, workflows included.

## Saving a task

"Save as workflow" is on a finished task's receipt and in the reply's ⋯ menu. Locus drafts the workflow and opens it in a review sheet. Nothing is saved until the user taps Save.

The draft (`WorkflowDraft`) comes from two sources:

- **From the task's record, as fact:**
  - The tools are the calls that ran.
  - The approval points are the calls the user approved ("Ask stays Ask"). A call the user declined isn't a tool of the workflow.
  - The checks are the verification kinds (tests, build, lint) whose last run in the task passed. A failure that was later fixed still counts.
- **From one helper-model request, for judgement:**
  - a name and a description;
  - which parts of the request are inputs, with types and defaults;
  - the steps, generalised from one-line labels of the calls;
  - the expected output;
  - up to three checks the agent confirms.

  The helper is the cheap model set in Settings, like for titles. The request has no tools and is separate from the chat, so the chat's prompt cache is untouched.

The helper's reply is validated:

- An input whose `{slot}` appears nowhere is dropped.
- A slot left without an input is turned back into words, so it's never sent as a raw `{slot}`.
- Bad names are fixed or dropped.

Without a helper model, or when its reply isn't usable JSON, the draft comes from the record alone. The goal is the request, with each web address as a `{url}` input, and there is one step per thing the agent did.

The review sheet edits everything:

- the inputs (slot, type, default, required);
- the steps, one per line;
- the tools, each with an "Ask" box;
- the checks and the expected output;
- in a project, whether to keep the workflow in the project or for all chats.

## Running one

There are two places to run one:

- **From a chat:** + → Run a workflow → pick one → fill in the inputs → Run. It runs in that chat.
- **From Library → Skills → Workflows:** Run starts a new chat, in the workflow's project if it has one.

The run sheet shows the plan, what the run will ask about, and what runs on its own.

`WorkflowRender` fills in the inputs and builds one ordinary user message:

- The **visible text** is the goal with the inputs filled in. It's what the bubble shows, what the chat is titled after, and what the safety reviewer trusts as the user's request.
- The **plan** rides on the same message as a hidden note (`ModeNote`), as one `<workflow_plan>` JSON block: inputs, steps, `allowed_tools`, `always_ask`, `success_checks` and `expected_output`. The note tells the agent that this is a plan, not a script. The agent should check what it finds, adapt or skip steps, and say what it changed. It should end with one `Check: <label> — passed|failed: why` line per check.

**Cache safety.** The plan is in the user message, never in the system prompt, and the tool list doesn't change. The cached prefix (frozen system prompt and fixed tool list) is the same as for any other message.

A workflow is a task of its own, so it isn't queued into a running one. If the chat is busy, the sheet says so.

### Limits at run time

`WorkflowScope` uses the new `AgentConfig.escalate` hook. For each call it gives a reason to ask, or nothing:

- **An approval point asks**, even if the user's settings allow that tool.
- **A call outside the declared tools asks**, unless it only looks (`Tool.isObservation`) or steers the agent itself (`todo_write`, `ask_user`, `plan_propose`).
- A workflow with no tools listed has no limit beyond the user's settings.

Escalation only ever adds an approval:

- Deny, a chat's mode and plan mode, and project limits still decide first.
- A background (read-only) run refuses an escalated call instead of asking, as it does for any Ask.
- The approval card gives the reason, for example "send_email isn't one of the workflow “Test and deploy”'s tools".

When the safety reviewer is set to review Ask calls, it also reviews escalated ones. This is the same rule as for taint.

Locus doesn't keep separate state about which workflow is running. It reads the plan back from the chat: the latest message that started a task. Messages that join a task don't end the workflow: ones sent mid-task, Locus's own notes, and helpers' reports. Anything else the user sends starts a new task, which isn't the workflow's. So the limits survive a restart and "Continue", with no database change.

This is the same mechanism a skill manifest needs: declared capabilities, with everything else escalated to Ask. When skill manifests land, they can supply their own `escalate` reason through the same hook.

### Success checks

At the end, the task receipt shows the workflow's name and each check: passed, failed, or not checked, and how it was judged. `WorkflowChecks` judges them from what the task left:

| Kind | Judged by | Passes when |
|---|---|---|
| `tests_pass`, `build_pass`, `check_pass` | Locus, from the verification cards (#74) | The task's last run of that kind passed. No run means "not checked" |
| `file_changed` | Locus, from tracked changes (#68) | A changed, not undone, path ends with the value |
| `reply_contains` | Locus | The final reply contains the value, ignoring case |
| `agent` | The agent's own `Check:` line | It says passed. Shown as "Reported by the agent" |

Like the rest of the receipt, the checks are computed from stored data (`TaskReceipts.build`), so nothing new is stored. The receipt's summary line gains "2 of 3 checks passed".

## Scheduling

Library → Skills → Workflows → Schedule creates an ordinary scheduled task (Tasks) with fixed inputs and an interval: hourly, every 6 hours or daily.

- The task's prompt is the rendered goal followed by the plan note (`WorkflowRender.stored`), so no database change is needed. `ScheduledRun` splits it back (`WorkflowRender.split`). The goal becomes the user message, and the plan rides on it as a note, as in a chat. Tasks shows only the goal.
- A project's workflow runs in a new chat in that project. The plan carries the project id. If the project is gone, the run uses a chat outside any project.
- It is a normal scheduled run, so every scheduled-run rule applies: read-only, a step limit and the 8-minute budget, and the prompt is never sent twice (the #76 marker covers it like any other prompt).
- The workflow's limits still apply. An escalated call is refused, because a read-only run never asks.
- **Approval steps can't run unattended**, and the schedule sheet says so before the schedule is saved (`WorkflowScope.unattended`). It lists:
  - the approval points (they need you);
  - the declared tools that change things (a read-only run refuses them);

  or says the whole workflow only reads and can run unattended. A workflow that needs those steps should be run from a chat.

## Limits

- Helpers that a workflow run starts don't inherit its tool limits. They have their own profile's tools and the user's policy.
- "Save as workflow" needs the task's receipt, so chats from before receipts (#83) can't be saved from.
- A workflow is linear: no conditionals or loops yet. The agent handles variation within a step.
- Agent-judged checks are only as reliable as the model's report. The receipt labels them as such.

## Sources

- [1] https://code.claude.com/docs/en/skills
- [2] https://learn.chatgpt.com/docs/automations?surface=app (redirect from developers.openai.com/codex/app/automations)
- [3] https://learn.chatgpt.com/docs/build-skills
- [4] https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-base.executeworkflowtrigger/
- [5] https://docs.n8n.io/integrations/builtin/cluster-nodes/root-nodes/n8n-nodes-langchain.agent/tools-agent/
- [6] https://docs.n8n.io/advanced-ai/human-in-the-loop-tools/
- [7] https://docs.n8n.io/workflows/components/nodes/ and https://docs.n8n.io/flow-logic/error-handling/
- [8] https://help.zapier.com/hc/en-us/articles/35859160812685
- [9] https://help.zapier.com/hc/en-us/articles/41776074420493
- [10] https://zapier.com/blog/human-in-the-loop-guide.md
- [11] https://help.zapier.com/hc/en-us/articles/14167175792909-Decide-how-your-Zap-handles-errors-with-advanced-settings
- [12] https://support.apple.com/guide/shortcuts-mac/apd8195f96d6/mac
- [13] https://support.apple.com/guide/shortcuts/apd68b5c9161/ios
- [14] https://support.apple.com/guide/shortcuts/use-if-actions-apd83dcd1b51/ios
- [15] https://support.apple.com/guide/shortcuts/apda9578f70f/ios
- [16] https://matthewcassinelli.com/automations-run-immediately-shortcuts-notifications (third-party; not confirmed on apple.com)
- [17] https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- [18] https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments
- [19] https://docs.github.com/en/actions/reference/workflows-and-actions/expressions
- [20] Hammoudi, "Why Do Record/Replay Tests of Web Applications Break?", UNL 2016: https://digitalcommons.unl.edu/computerscidiss/100
- [21] Anthropic, "Building effective agents": https://www.anthropic.com/engineering/building-effective-agents
