# Change tracking with undo

The agent changes files in the workspace, and until now the only record was the tool calls themselves and the workspace trash. You couldn't see at a glance what a turn changed, read the change as a diff, or take it back without digging through Workspace → Trash. This note looks at how other coding agents present per-turn changes and undo, picks what fits a phone, and describes what Locus does.

## What others do

Sources were read on 2026-11-01.

### Claude Code: checkpoints and `/rewind` ([checkpointing](https://code.claude.com/docs/en/checkpointing))

- A checkpoint is taken before each prompt that starts a turn. Only changes made by the file editing tools are tracked.
- File snapshots are kept for the 100 most recent checkpoints of a session, saved with the conversation, and swept about 30 days after the session last saved one (`cleanupPeriodDays`).
- `/rewind` (or Esc twice) lists your prompts. You can restore the code, the conversation, or both, or summarise from or up to a point. Code restore is offered only when that checkpoint has tracked file changes.
- Documented limits: a file changed only by Bash isn't tracked; subagent edits usually aren't captured; manual and concurrent edits aren't either, unless they touch files the session already tracks; a message queued mid-turn gets no checkpoint; links are skipped. Checkpoints don't replace git.

### Cursor: checkpoints and review ([checkpoints](https://cursor.com/docs/agent/chat/checkpoints), [agent overview](https://cursor.com/docs/agent/overview), [review](https://cursor.com/docs/agent/review))

- Checkpoints are made automatically before significant changes and capture the modified files. They're stored locally, apart from git.
- Hover a previous message and choose **Restore Checkpoint** to roll back everything the agent changed after it. Restoring reverts files only; the conversation stays.
- Edits are applied as the agent works, and shown in a diff view where you can reject what you don't want.
- Retention, and whether terminal commands' changes are tracked, aren't documented.

### OpenAI Codex ([slash commands](https://learn.chatgpt.com/docs/developer-commands?surface=cli), [review pane](https://learn.chatgpt.com/docs/code-review?surface=app), [PR #19481](https://github.com/openai/codex/pull/19481), [discussion #9618](https://github.com/openai/codex/discussions/9618))

- In the CLI, `/diff` shows the git working tree, untracked files included. It isn't per turn.
- The CLI's `/undo` used per-turn "ghost commits". It was removed in April 2026 (PR #19481): a maintainer said it "didn't get much use, and its design caused problems for many users". The suggested alternatives are git, or asking Codex to undo.
- The desktop app's review pane has a **Last turn** scope beside Unstaged, Staged, Commit and Branch. It can stage, unstage or revert the whole diff, one file or one hunk, and takes inline comments as feedback. It needs a git repository, and it shows every uncommitted change, without saying which came from Codex.

### Aider ([git integration](https://aider.chat/docs/git.html), [commands.py](https://raw.githubusercontent.com/Aider-AI/aider/main/aider/commands.py))

- Aider commits every edit it makes, after first committing any dirty changes of yours so they stay separate.
- `/diff` shows the changes since your last message. `/undo` undoes the last aider commit.
- The source shows `/undo` refusing when: the last commit isn't aider's from this session, it's a merge, a changed file has uncommitted changes ("Please stash them before undoing"), a file didn't exist in the parent commit, or the commit was pushed.

## What fits a phone

| Idea | Taken? | Why |
| --- | --- | --- |
| Per-turn grouping (Claude Code, Cursor, Codex "Last turn", Aider `/diff`) | Yes | A turn is what you asked for, so it's the natural unit to review and take back. |
| Files only, conversation kept (Cursor) | Yes | Locus's transcript is append-only, because providers bind cached and signed prefixes to it. Rewinding the conversation is what branches are for. |
| Per-file undo (Codex app) and undo all (Cursor, Claude Code) | Yes | A small screen wants one tap per file and one for the turn. |
| Per-hunk accept/reject (Codex app, Cursor) | No | Hunk controls are fiddly on a phone, and an undone hunk leaves a file the agent never wrote or tested. Per file is the honest unit. |
| Git as the store (Aider, Codex) | No | Most workspaces aren't git repositories, and commits in your repo are yours to make (`git_commit` asks first). |
| Refuse when the file changed since (Aider) | Warn instead | Refusing strands you. Locus warns, and moves what's there now to the trash, so nothing is lost either way. |
| Track only the file tools (Claude Code) | Yes | Changes made by `run_command` can't be attributed reliably without snapshotting the whole workspace before every command. |

## What Locus does

### The workspace trash, reused

The trash already keeps a copy every time a tool replaces or deletes something:

- `write_file`, `edit_file` and `multi_edit` copy the old file first (`Workspace.backup`, reason `OVERWRITTEN`);
- `delete_path` and the user's deletes move the path there (`DELETED`);
- `move_path --overwrite` moves the destination there (`MOVED_OVER`).

Each entry is a folder in the app's private storage holding the payload and a small JSON record. **Nothing expires**: an entry stays until you restore it or delete it from Workspace → Trash. There is no size or age limit.

Change tracking points at these entries instead of copying anything. A tracked change stores which trash entry holds the "before" version, a hash of the "after" version, and the diff text. One addition: `move_path` of a file now keeps a copy of the file in the trash (`MOVED_AWAY`, shown as "Before a move"), so the move can be undone. Folder moves aren't tracked; a folder can be huge.

### Recording

- **Who a change belongs to.** Every tool call runs with a `ToolCallContext`. It now carries a `ChangeOwner`: the chat, and the turn's id. The turn's id is the first message after the latest message that starts a turn, which is how the chat screen already keys turns. So a regenerated turn, which starts a new branch, has its own changes. A helper gets its parent's owner, so its edits show under the parent's turn.
- **What reports.** The workspace file tools add each path they change (with its trash entry, or none for a new path) to a list, and once the call is done, `WorkspaceTool.execute` hands them to a `ChangeRecorder`. It lives in core/data (`ChangeRepository`). Recording never fails a tool; an error there is dropped.
- **One row per file per turn.** Room table `file_changes` (database v12). A second edit to the same file in the same turn updates the row: the "before" stays the trash copy from before the first edit, and the diff is recomputed from it to the file as it is now. A file created and deleted within one turn leaves no row.
- **Diffs.** `LineDiff` is Myers' O(ND) algorithm (the one `git diff` uses by default) on the lines between the common prefix and suffix, written out as a unified diff with three lines of context. Binary files, folders, files over 2 MB and diffs over 200,000 characters keep only their counts (or a "binary" mark).

### The changes card

Below a turn, after its reply, a card says "Changed 3 files · +40 −12". The summary row folds the list: a card with more than three files starts folded, one with three or fewer starts open, and the choice is kept for the session (TalkBack reads "Expanded" or "Collapsed"). Each row has the file's icon and name, then what changed under the name: line counts in the diff colours for a changed file, showing only the parts that aren't zero ("+12 −3", "+12"); "New · 121 lines" for a file the turn created and "Deleted · 40 lines" for one it deleted (the net diff of a new file only adds, so "+121 −0" read as a bug); with "Edited 2 times" when the turn changed it more than once. Its folder and when it last changed follow, and, for a file edited more than once, each edit with its own counts ("Edit 1 · +124", "Edit 2 · +11 −14"). "Undone" replaces all that once you've undone it. The summary row's total likewise leaves out a zero side and counts new and deleted files ("+129 · 2 new files").

- **Diff and history.** Tap a file for a sheet with the turn's net unified diff, in monospace, then its edit history (below). Added lines get a soft tint of the design system's live green with a green `+`, removed lines a soft tint of the critical red with a red `−`, and hunk headers stay quiet. The tokens are `Locus.diff` (core/designsystem/Diff.kt), built from the status colours, so they follow light and dark.
- **Undo.** The diff sheet's Undo opens a confirmation sheet, as all confirmations in Locus are sheets. It says the file goes back to how it was before the turn, and that what's there now moves to the trash. If the file changed since the agent left it (by you, a command or a later turn), a caution banner says so. The check compares the file's SHA-256 with the one recorded after the agent's change.
- **Undo all.** Undoes every change of the turn still standing, latest first, so a move is undone before the delete that preceded it.
- **When it can't.** If the trash copy is gone (you restored or emptied it), the row can't be undone and the sheet says why.

What undo does on disk: a changed or deleted path is put back with `Workspace.restore`, which first moves whatever is there now into the trash. A created path is moved to the trash. Every undo can itself be undone from Workspace → Trash.

### Edit history

The row holds the net change; the sheet also lists each change to the file in the turn, in order.

- **What's recorded.** Each time a file tool reports a path, `ChangeRepository` also adds an entry to the turn's history: the time, the tool and call, its own +/− counts and unified diff (that call's trash copy against the file now), and a SHA-256 of the result. The file tools already keep a trash copy before every write, so no extra snapshot is taken.
- **Changes in between.** If the trash copy taken before an edit doesn't match the hash the previous edit left, something else changed the file in between (most often a command, or you). That shows as "Changed outside the file tools (a command, or you)", with no diff of its own: the net diff covers it. A change after the agent's last edit shows the same way at the end of the sheet ("Changed again since…"), from the same check Undo uses. A file only ever changed by a command still isn't tracked: that would need a snapshot of the workspace before every command.
- **Where it's kept.** `ChangeHistory`: one small JSON file per turn in the app's no-backup storage (`change-history/`), beside the `file_changes` table rather than in it, so the database schema didn't change. Files are written through `FileTrees.writeReplacing` and never read through a link. After a restore the cards come back with their net diffs and the sheet says there's no per-edit history.
- **Limits.** 50,000 characters of diff per edit and 1,000,000 per turn (later edits keep only their counts), 500 edits per turn, 16 MB for all turns (oldest first). A turn's history goes when its records are pruned or its chat is deleted.
- **Undo is unchanged.** It still puts the file back to how it was before the turn. Undoing one edit isn't offered: later edits in the turn were made on top of it, so taking it back alone would need a merge, and could leave a file the agent never wrote or tested (the same reason per-hunk undo was left out above).

### Keeping the agent's view current

An undo changes files under the agent. Two things keep it from working from a stale view.

1. **Read before edit.** `FileReads.invalidate(file)` drops the file from every chat that read it and marks it `REVERTED`. The next `edit_file`, `multi_edit` or `write_file` on it is refused, before you're asked to approve it, with "The user undid changes to X since this chat read it… Read it again." The agent reads it and carries on.
2. **A note.** The agent is told which files you undid, in a `<locus_mode>` note:
   - while it works, the note joins at the next step boundary, through the same path as messages you send mid-task (#55). It rides on your queued message if there is one, or comes as a message of its own that holds only the note;
   - otherwise it rides on your next message.

   The chat hides the note. A note-only message doesn't start a turn (`Message.startsTurn()`), so the turn and its card carry on past it.

The transcript stays append-only. The note is added at the end, never inserted, and the system prompt and tool list don't change, so the provider's cached prefix holds. The test `ChangesFlowTest` checks that each request starts with exactly the messages of the one before.

### Storage cap

- Each chat keeps the change records of its latest **30 turns** that changed files.
- All chats together keep at most **8,000,000 characters** of diff text, about 8 MB. Past that, the oldest turns go first, and the newest turn always stays.
- Records go with their chat (`ON DELETE CASCADE`).
- Pruning drops records only. The trash copies are yours, and stay in Workspace → Trash until you clear them.

## Verification cards

The coding harness tells the agent to verify its work with the project's tests or build (docs/design/coding-harness.md), and the result came back as a raw `run_command` output in the steps card. Now a build or test run shows as its own compact card in the turn.

**Recognising a run.** `Verification.detect` (core/model) looks at each `&&`, `;` or `||` step of the command and takes the last one it knows. It drops the wrappers first: `cd`, environment assignments, `timeout N`, `uv run`, `poetry run`, `npx`, `bundle exec`, pipes and redirects. That covers every command `project_info` suggests. It knows:

| Kind | Commands |
| --- | --- |
| Test | `gradle`/`./gradlew` `test`, `check`, `*Test` tasks; `npm`/`pnpm`/`yarn`/`bun` `test`; `jest`, `vitest`, `mocha`; `pytest`, `python -m pytest`, `python -m unittest`; `cargo test`; `go test`; `mvn test`/`verify`/`install`; `make test`/`check`; `ctest`; `dotnet test`; `rspec`, `rake test`; `phpunit` |
| Build | `gradle build`, `assemble*`, `compile*`; `npm run build`; `cargo build`; `go build`; `mvn package`/`compile`; `make`; `cmake --build`; `dotnet build` |
| Check | `gradle lint*`; `npm run lint`/`typecheck`; `tsc`; `eslint`; `cargo check`/`clippy`; `go vet` |

**Reading the result.**
- Pass or fail comes from the exit code; a run that hit its timeout reads "timed out".
- `run_command`'s status line now also says how long the command took ("Exit code 1 (61.0 s)."), so the card can show it. The agent sees it too, which helps it judge slow builds. Older results have no duration, and the card leaves it out.
- **Failing tests** are found by name in each runner's own format:
  - Gradle: `Class > test FAILED`
  - pytest: `FAILED path::test`
  - Jest: `● Suite › test`
  - Vitest: `×`
  - cargo: `test x ... FAILED`
  - go: `--- FAIL: TestX`
  - .NET: `Failed X [`
  - unittest: `FAIL: test (Class)`
- **The tally** is the runner's own summary line ("2 failed, 3 passed", "12 tests completed, 1 failed", "test result: …").
- **When no test names were found**, the card shows the first error lines instead: Gradle's "What went wrong", compiler `e:`/`error:` lines and `file:line:` errors, or else the last lines of output.
- Colour codes are stripped. The card shows at most 8 names and says how many more there are.

**When the exit status can't be trusted.** `run_command` doesn't set `pipefail`, so a pipeline exits with its last program's status: `npm test 2>&1 | tail -30` exits 0 when tests fail. `Verification.exitTrusted` reads the command: a check step piped into something (without `set -o pipefail`), a check followed by another step joined with `;` or a newline, and any `||` make the status untrustworthy; steps joined by `&&` keep it, and quoted text is ignored. Every recognised step counts, so `npm run build | tail; npm run lint` is untrustworthy even though lint ran last. `Outcome.verdict` is then:

| Exit | Trusted, no failing tests listed | Untrusted, or failing tests listed |
| --- | --- | --- |
| 0 | Passed | **Finished** (caution): the card shows the tally and error lines instead of claiming a pass |
| non-zero | Failed | Failed |
| timed out | Timed out | Timed out |

Receipts count a finished run as "unclear", workflow checks as not checked, and a drafted workflow doesn't take it as a success criterion. The agent's own completion check reads the exit code as before. The parser also drops the safety envelope (`Envelopes`, core/model) before reading output, and a bundler's "✗ Build failed in 812ms" is an error line, not a failing test.

**In the transcript.** Runs are part of the turn's work section, not cards of their own. All runs of one kind (tests, build, check) in a turn are one row, grouped by kind rather than by exact command, so a build-fix-rerun loop with `npm test`, `npm test -- --run` and `npx vitest run` stays one row; a chained command counts as the check it ends with. The row shows the latest result ("Tests · passed on run 5 of 5", or "Tests passed" for one run), a dot per run (`RunDots`) and the latest duration. A run still going (its call saved, no result yet) is already in the row ("Tests · running (run 5)"), so it updates in place. Only a run that didn't pass says more: its tally and its first three failing tests or error lines on a soft critical tint, then **Output**. A tap opens every run, newest first, each with its result, command, tally and selectable output (the latest open). The run stays in the terminal panel and in exports.


- Changes made by `run_command`, services, browser downloads, `sftp_download` and other non-file tools aren't tracked, as in Claude Code. The trash still keeps what those tools overwrite.
- Folder moves aren't tracked. Folder deletes and copies are tracked, without a diff.
- A pending note lives in memory. If Locus restarts first, the agent isn't told, but it also has to read every file again before changing it, since `FileReads` is in memory too.
- Backups don't include the trash. After a restore, the changes cards come back, but their files can't be undone (the sheet says the earlier version is gone).
