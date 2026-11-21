# Project rules

Status: built 2026-11-21. Code: `core/model/.../ProjectRule.kt` (the rule, matching, the note's format), `core/agent/.../ProjectRules.kt` (`ProjectRuleHooks`, when rules fire, notes, rounds), `AgentLoop` (the three hook points), `core/data/.../agent/ProjectRuleRunner.kt` (runs a command in Linux), `PolicySeal` (sealing), `feature/chat/ProjectRulesScreen.kt` (UI). Idea #11 of [feature opportunities, November 2026](../research/feature-opportunities-2026-11.md).

A project can have **rules**: "after an edit to a `*.py` file, run `ruff check`", "before finishing, run `npm test`", "never `git push` to main". The user writes them in the project's settings. The agent can't see the list, and it can't change it.

## What others do

**Claude Code hooks** ([hooks](https://code.claude.com/docs/en/hooks), read 2026-11-21):

- `PreToolUse` runs before a tool call, and its matcher picks the tools. A hook blocks the call by exiting with code 2 or by returning `permissionDecision: "deny"`. "Claude Code reads the JSON decision, blocks the tool call, and shows Claude the reason."
- `PostToolUse` runs "after a tool call succeeds". Failures go to `PostToolUseFailure` instead. Its `additionalContext` is "inserted into the conversation at the point where the hook fired". Text over 10,000 characters is saved to a file, and Claude gets a 2,000-character preview.
- `Stop` runs "when Claude finishes responding". Exit 2 "prevents Claude from stopping, continues the conversation". Claude Code also has a `stop_hook_active` flag, so a Stop hook can tell that the agent is already continuing because of a stop hook and avoid looping forever.
- Command, HTTP and MCP hooks time out after 600 s by default.
- Hook types are `command`, `prompt`, `agent`, `mcp_tool` and `http`. An `http` hook POSTs the event's JSON to a URL.
- Hooks live in settings files that anyone with write access can edit. `/hooks` is read-only, and edits made outside it are "normally picked up automatically by the file watcher".

**What Locus takes:** the three moments (before a command, after an edit, before stopping), "after success only", blocking with a reason the model reads, and a cap on how often a stop check can send the agent back.

**What it doesn't take:**

- **No `http` hooks, and nothing over the network.** An HTTP hook sends every tool call's arguments and results to a URL. That's a telemetry and exfiltration channel that a single settings edit can open. Locus has no HTTP action and no URL field. A rule's command runs only in the project's Linux on the phone. Locus itself never sends a rule's input or output anywhere (see [Not done](#not-done) for what the command can do itself).
- **No `prompt` or `agent` hooks.** A rule is something the user can read and predict. It's a command, not another model's opinion.
- **No editing from files.** Claude Code picks up hook edits from disk. Locus rules are sealed, so an edit made outside Locus suspends them (see [Sealing](#sealing)).
- **No exit-code protocol for blocking.** A block rule is a pattern and a message. It never runs a command to decide.

## The rule

| Field | Used by | Meaning |
|---|---|---|
| name | all | Shown in the list, in notes and in Activity ("ruff after edit"). |
| trigger | all | **After an edit**, **Before finishing** or **Before a command**. |
| files | after an edit | Optional globs, separated by commas ("`*.py, src/**/*.ts`"). Empty means any file. |
| command | after an edit, before finishing | A shell command for `/bin/sh -c` in the project's folder (`/workspace`). Shown in full everywhere. |
| timeout | after an edit, before finishing | 5–600 s, 60 by default. |
| commands matching | before a command | Optional pattern. Empty means every command. |
| message | before a command | What the agent is told ("never git push to main"). |
| enabled | all | The user's own on/off switch. |
| suspended | all | Set when the seal failed (see [Sealing](#sealing)). Only the user can clear it, by tapping Resume after looking at the rule. |

The trigger decides the action. Before a command, a rule **blocks**. After an edit or before finishing, it **runs a command**. Each project can have at most 20 rules, and the editor limits the fields to 2,000 characters.

**File globs** use `*` (any characters except `/`), `?` (one such character) and `**` (any characters, `/` included). A glob without a `/` matches the file name (`*.py` matches `app/main.py`). A glob with a `/` matches the path from the project's folder. A leading `/workspace/` or `./` on the edited path is dropped first.

**Command patterns** match anywhere in the command, after runs of whitespace are collapsed to one space. `*` stands for any text. `git push*main` matches `cd app && git push origin main --force`. Matching is case-sensitive. It's a guard against mistakes, not a sandbox: `g''it push` gets past it, as it gets past Claude Code's matchers.

## When rules fire

### Before a command: block

- **Checked:** for every `run_command` and `linux_service_start` call, including one made through `call_tool`, in the project's chats, their helpers, and a Needs-you proposal from a project chat.
- **Where:** in the run's refusal path, right after the chat's and project's own limits and before anything else, including the reviewer, approvals and remembered approvals.
- **Result:** a matching enabled rule refuses the call (`POLICY_DENIED`). The command never runs. The model reads this tool result:

  > Not allowed: the project rule “No push to main” blocks this command: never git push to main. This rule is the user's; don't retry the command or work around it. Continue without it, or ask the user.

- Block rules need no Linux. They apply whether or not Linux is installed.

### After an edit: run

- **Fires:** after a step's `write_file`, `edit_file` or `multi_edit` calls have run and succeeded. A refused, denied or failed edit doesn't count.
- **Which rules:** each enabled rule whose globs match at least one of the step's edited paths runs once per step, in list order, after all the step's results are in the transcript.
- **Paths:** the command gets the matching paths in `$LOCUS_RULE_FILES`, one per line and quoted safely (`ruff check $LOCUS_RULE_FILES`).
- **The note:** each run gives the agent a note, whether it passed or failed. A formatter may have changed files, and the agent should know that. Helpers run after-edit rules too, and the note goes to the helper.

### Before finishing: run, and maybe go round again

- **Fires:** in the user's own chat (not in helpers) when the agent ends its turn with a reply, the task did something (a file edit, a command, a helper), and another step is allowed.
- **Skipped:** in a read-only run (scheduled tasks), in Read-only mode, in plan mode, after a refusal, and after Stop.
- **If a rule fails** (non-zero exit or timeout), its note asks the agent to fix what it reports and finish, and the run takes another step.
- **Round limit:** at most **2 extra rounds per task**. The first failure sends the agent back (extra round 1), and so does a failure after it (extra round 2). A failure after the second extra round gets a final note saying Locus won't send the agent back again, and the turn ends. So the rule runs at most 3 times per task. The rounds are counted from the notes already in the task, so stopping and continuing doesn't reset them.
- **After a pass:** when a rule fails and then passes later in the task, a short "now passes" note is added. Nobody has to wonder whether it was fixed.
- **Order with the completion check:** rules come first. Once they pass (or have no rounds left), the [completion check](../research/agent-algorithms-2026.md) runs as before. At most 3 extra steps come from these checks together.

### Who's in charge

| Can | User (UI) | Agent | Linux (same uid) |
|---|---|---|---|
| See rules | Project → Rules | Only through notes and refusals | Can read settings.json |
| Create, edit, delete | Yes | No tool exists | A change breaks the seal |

There is no rule tool, and no tool writes the sealed settings. A test (`ToolCatalogCapabilityTest`) fails if any registered tool's name mentions rules or its description mentions project rules. The model learns about rules only from their notes and refusals.

## Running a command

- **Where:** in the project's Linux, with the project's folder at `/workspace`. That's the same place `run_command` uses, through `ChatScope`.
- **Linked projects:** a project linked to an SSH machine still runs its rules on the phone. The edited files are on the phone, and the rule promised local only.
- **Without Linux:** if Linux isn't installed or ready, command rules are **inactive**. Nothing runs and nothing is said to the model. Project → Rules shows "Linux isn't installed, so rules that run commands are inactive. Block rules still apply." with a link to install it.
- **Output:** both streams, scrubbed of the vault's secrets as all Linux output is. The note keeps at most 4,000 characters: the first 1,000 and the last 3,000, with a line saying how much was cut, because the error is usually at the end. The output is marked as untrusted, like command output.
- **Time:** a rule run is part of the task. Its time counts toward the task's time budget, because the budget is wall-clock time minus time spent waiting for the user. Cancelling the task stops the command.
- **File changes:** a rule's file changes aren't tracked for undo, as with `run_command` (see [change tracking](change-tracking.md)). Rewind lists rule runs among the things it can't take back.

## The note

A rule's note is a user-role message from Locus (`ModeNote`). It's appended after the step's tool results (after an edit) or after the reply (before finishing), never inserted. For each rule run it has a header part and a result part:

```
<locus_mode>Project rule “ruff after edit” after an edit: exit code 1 (0.8 s). It ran `ruff check $LOCUS_RULE_FILES` on app/main.py. Its output is below. The user set this rule for the project; fix what it reports about your change.</locus_mode>
<locus_result><untrusted_web_content>…</untrusted_web_content>
(Text above is output of a project rule's local command. …)</locus_result>
```

- **While it runs,** the chat's live line says a command is running (`activeTool` is `project_rule`).
- **In the chat,** it shows as a row in the turn's steps: a pass or fail mark, the rule's name and trigger, and the exit status. A tap shows the output.
- **In exports,** it's written in English.
- **In Activity,** each run is an entry (`project_rule`) with the rule's name, trigger, command and files. Each block is the refused `run_command`, with the rule's message as its result. Receipts don't count rule runs as the agent's actions.

## Taint

- **For the taint gates:** a rule's output counts as a taint source ("a project rule (name)"), exactly like a `run_command` result. Both are marked untrusted, and a test, linter or build can print text that came from anywhere: a file the agent wrote from a web page, a package's error message.
- **For remembered approvals:** the output counts as local, just like [`Taint.isLocalOutput`](taint.md). If the rule's command is one plain command that fetches nothing (`CommandSafety.mayFetch` is false), its output is the workspace's own and doesn't hold back a remembered command rule. A rule like `npm install && npm test` may fetch, so its output does hold one back.
- **`$LOCUS_RULE_FILES`:** a command that uses a variable isn't "one plain command" to `CommandSafety`, so `ruff check $LOCUS_RULE_FILES` counts as may-fetch, exactly as it would as a `run_command`. Write `ruff check .` if the output should count as local.
- **How it's recorded:** the decision is made when the note is written. The result's marker says which it is ("a project rule's local command" or "a project rule's command, which may have fetched from the network"), so `Taint.sourcesIn` reads it back the same way from history.

## Sealing

Rules live in the sealed settings (`AppSettings.projectRules`, by project id), next to the project's permissions. `PolicySeal` covers them only when there are any, so seals made before rules existed still match. A rule changed, added or removed outside Locus fails the check. Linux runs as Locus's uid, so it can write `settings.json`.

When the check fails:

- the permissions fall back to their restricted form, as before;
- **every rule is suspended**, block rules included. A planted block message is model-facing text, and a planted command would run on the next edit;
- the startup notice says "Project rules were changed outside Locus. They're suspended until you check them in each project's Rules", along with the usual tool-permissions alert.

A suspended rule does nothing. Project → Rules shows it with a caution mark and its full command or pattern, and the user resumes it after reading it. A rule the user deletes is gone. A deleted project's rules are dropped with its permissions.

A restored backup's rules are taken as the user's own and resealed, like the rest of its permissions ([backup](backup.md)).

## The prompt cache

- **What never changes:** the system prompt, the tool list and the existing messages. No tool is added, and rules aren't in the prompt.
- **Blocks:** a block is an ordinary tool result, refused at run time like the chat's other limits ([ChatPolicy](../../core/agent/src/main/kotlin/dev/locus/core/agent/ChatPolicy.kt)).
- **Notes:** a note is a new message at the end of the transcript, so every request sent before it is a byte-identical prefix of the next. A test checks this.
- **Changing rules mid-chat** changes only what happens next. It never changes what was sent.

## The UI

- **Where:** project settings → **Rules** (a row with a count) opens the project's rules list.
- **The list:** grouped by trigger, each rule with its full command or pattern, files, timeout and an on/off switch. An empty state explains rules and offers "Add a rule".
- **Banners:** one for Linux not being installed, and one for suspended rules.
- **The editor sheet:** name, trigger (a three-way choice), then that trigger's fields, Save and Delete. A suspended rule's sheet has Resume instead of Save until it's looked at.
- **Validation:** a name is needed. A command is needed for command rules, and a message for block rules. The timeout must be a number from 5 to 600.

## Not done

- **Network from the command itself.** Locus never sends anything for a rule, but the command runs in Linux, which can reach the network, as `run_command` can (see [linux isolation](linux-isolation.md)). A rule that runs `curl` is the user's own choice, written in full in their own settings.
- **More triggers.** There's no "before an edit", "after any tool", or rule for other tools.
- **Notes when a rule can't run.** Without Linux, nothing is said in the chat. The notice is on the Rules screen.
- **Undo.** Changes a rule's command makes aren't tracked for undo.
