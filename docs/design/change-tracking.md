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

Below a turn, after its reply, a card says "Changed 3 files". Each row has the file's icon, its name and folder, and +N −M line counts in the diff colours. "Undone" replaces the counts once you've undone it.

- **Diff.** Tap a file for a sheet with the unified diff, in monospace. Added lines get a soft tint of the design system's live green with a green `+`, removed lines a soft tint of the critical red with a red `−`, and hunk headers stay quiet. The tokens are `Locus.diff` (core/designsystem/Diff.kt), built from the status colours, so they follow light and dark.
- **Undo.** The diff sheet's Undo opens a confirmation sheet, as all confirmations in Locus are sheets. It says the file goes back to how it was before the turn, and that what's there now moves to the trash. If the file changed since the agent left it (by you, a command or a later turn), a caution banner says so. The check compares the file's SHA-256 with the one recorded after the agent's change.
- **Undo all.** Undoes every change of the turn still standing, latest first, so a move is undone before the delete that preceded it.
- **When it can't.** If the trash copy is gone (you restored or emptied it), the row can't be undone and the sheet says why.

What undo does on disk: a changed or deleted path is put back with `Workspace.restore`, which first moves whatever is there now into the trash. A created path is moved to the trash. Every undo can itself be undone from Workspace → Trash.

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

## Limits

- Changes made by `run_command`, services, browser downloads, `sftp_download` and other non-file tools aren't tracked, as in Claude Code. The trash still keeps what those tools overwrite.
- Folder moves aren't tracked. Folder deletes and copies are tracked, without a diff.
- A pending note lives in memory. If Locus restarts first, the agent isn't told, but it also has to read every file again before changing it, since `FileReads` is in memory too.
- Backups don't include the trash. After a restore, the changes cards come back, but their files can't be undone (the sheet says the earlier version is gone).
