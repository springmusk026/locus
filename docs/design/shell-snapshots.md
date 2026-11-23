# Snapshots that include shell changes

Status: built 2026-11-21. *Not yet tried on the phone.* Idea #10 of the
[feature research](../research/feature-opportunities-2026-11.md). Code: `ShellSnapshots` and `CommandChangeTracker`
(core/agent/tools), `ShellChangeTracker` and `ShellNotes` (core/data/repository), the "before" lookup and blob
restore in `ChangeRepository` and `Workspace.restoreCopy`, `run_command` in core/linux (`LinuxTools.kt`), the
project editor's switch and the changes card's notes (feature/chat), Settings → Storage's row (feature/settings).

Change tracking ([change-tracking.md](change-tracking.md)) records what the file tools change, because each of them
keeps a trash copy before it writes. A shell command keeps nothing: `sed -i`, `npm run format` or a build script can
rewrite a project and nothing says what, or takes it back. Rewind here says so plainly ("3 shell commands ran after
this; their effects stay"). This closes that gap for projects that ask for it.

## What others do

Sources were read on 2026-11-21.

- **Gemini CLI** ([checkpointing](https://geminicli.com/docs/cli/checkpointing/)): before a tool that changes files is
  approved, it commits the project to a **shadow git repository** in `~/.gemini/history/<project_hash>`, so the user's
  own git is never touched, and saves the conversation and the tool call. `/restore` brings back the files and the
  conversation. Off by default (`general.checkpointing.enabled`).
- **Cline** ([checkpoints](https://docs.cline.bot/features/checkpoints)): a shadow git repository, separate from the
  project's, snapshots the workspace after each tool use; per message you can restore files, the task, or both.
- **Claude Code** ([checkpointing](https://code.claude.com/docs/en/checkpointing)): tracks only its file-editing tools;
  "Bash command changes not tracked" is a documented limit.

Both shadow-git designs exist for the same reason: a command can change anything, so the only reliable "before" is a
copy of the whole tree, and git's object store makes that cheap (a file that didn't change is the same object). Locus
can't use git for it: git isn't part of the app (only of Linux, which the user may not have installed), a shadow
repository written by Linux would sit where Linux programs can plant links (docs/design/linux-isolation.md), and
change-tracking.md already ruled out git in the user's repository. The idea that matters is git's object store, and
that is small enough to write with the app's own file IO.

## Behaviour

**The setting.** Project editor → **Undo for commands** → **Snapshot before shell commands**, per project, **off by
default**. It's stored in the settings (`AppSettings.shellSnapshotProjects`, a set of project ids), not the database,
and isn't sealed: it only adds a safety net. Deleting a project turns it off. Only project chats: a project has its own
folder, the folder the command sees at `/workspace` (docs/design/projects.md). Outside a project the whole workspace
would be the folder, which is too large to copy before every command.

**Which tools.** `run_command`, on the phone and on a project's SSH machine (docs/design/remote-execution.md): a
remote run syncs its changes back into the project's folder before it returns, so the same before-and-after covers
it. Decided against:

- `linux_service_start`: a service keeps running and writing after the call returns. Diffing when the call ends would
  catch a few seconds of its writes and label them as the whole; diffing later can't tell its writes from anyone else's.
- `git_commit`: writes only `.git`, which snapshots leave out.
- `linux_service_stop`, `linux_service_list`, `linux_service_log`, `git_inspect`: don't write project files.
- SSH tools, browser downloads, `sftp_download`: not Linux-side, or not in the project's folder. They stay untracked,
  as change-tracking.md lists.

**Around each command** (`ShellChangeTracker.track`):

1. **Before.** In a project chat with the setting on, the project's folder is snapshotted on the IO dispatcher. If it
   can't be (over a cap, too slow, unreadable, or another tracked command is still running in the project), the
   command runs anyway and the turn gets a note saying why.
2. **The command runs** exactly as without the setting. Its result is not changed in any way: no note is appended,
   so the text the model reads is byte-identical, and the tool list and system prompt are untouched. The prompt
   cache holds.
3. **After** (also when the command is stopped or fails: it may have changed files all the same), the folder is
   scanned again, hashed only, and compared with the snapshot. Every added, changed (content or mode) and deleted
   file becomes a tracked change of the turn through `ChangeRepository.record`, with tool name `run_command`. A
   rename is a delete and an add. If more than 500 files changed, nothing is recorded and the turn gets a note: half a
   record would make Undo turn look complete when it isn't.

From there everything is change tracking's own: the changes card, the diff sheet and edit history ("Changed by a
command"), Undo, Undo all, Rewind here and the `<locus_mode>` note to the agent. One row per file per turn still holds:
a file a file tool edited and then a command changed (or the other way round) is one row, its "before" the state before
the first of them, and the history shows both steps with no "changed outside the file tools" gap between them.

## The store

In Locus's private no-backup storage (`no_backup/shell-snapshots/`), which Linux can't name:

| Path | What |
| --- | --- |
| `blobs/ab/cdef…` | A file's bytes, named by their SHA-256 (the first two hex characters are the folder). |
| `snapshots/<project id>/<id>.json` | One manifest per snapshot. |
| `tmp/` | Blobs being written; renamed into `blobs/` once complete, swept on retention. |

A manifest: `{v, id, projectId, conversationId, turnId, callId, createdAt, files: [{p, h, m, s}]}`, with each file's
path relative to the project's folder (`/`-separated), SHA-256, POSIX permission bits and size.

**Content-addressed.** A file is hashed first; only if no blob has that hash is it copied in, hashed again while it's
copied, and stored under the hash the copy produced (so a file that changed between the two reads still gives a blob
that matches its name). Unchanged files cost nothing in later snapshots, and ten identical files are one blob.

**How a change keeps its "before".** A tracked change stores where its earlier version is in the column that holds a
file tool's trash entry id: `shell:<sha256>:<octal mode>` (`ShellSnapshots.ref`). A trash id is digits and dashes, so
the two never collide, and no database change was needed. `ChangeRepository` looks the reference up in the store
instead of the trash.

**No links, ever.** The walk uses `Files.walkFileTree` without `FOLLOW_LINKS`, so a link (to a file or a folder) is
neither entered nor recorded, and each file is opened with `NOFOLLOW_LINKS` (`O_NOFOLLOW`): a file swapped for a link
after the walk saw it is left out instead of read. A link a command creates or changes isn't tracked; a file a command
replaced with a link reads as deleted, and its path is joined, never canonicalised, so it can't resolve to the link's
target.

## What's left out

Folders with these names, at any depth (`ShellSnapshots.EXCLUDED_DIRS`):

| Kind | Names |
| --- | --- |
| Version control and Locus's own | `.git`, `.hg`, `.svn`, `.locus` |
| Dependencies | `node_modules`, `.venv`, `venv` |
| Caches | `__pycache__`, `.pytest_cache`, `.mypy_cache`, `.tox`, `.cache`, `.gradle`, `.next` |
| Build output | `build`, `dist`, `target` |

They're what a command makes again (`npm install`, a build) and usually most of a project's bytes; `.git` has its own
history, and `.locus` holds Locus's saved command output. Also left out: links, empty folders (folders aren't tracked,
only the files in them; undoing a command that created `out/report.txt` removes the file and leaves `out/`), and
connected folders, which aren't in the project's folder on disk and which Linux can't see anyway
(docs/design/connected-folders.md).

## Caps, and when it skips

| Cap | Default | When it's passed |
| --- | --- | --- |
| Files in the folder (after the exclusions) | 5,000 | Skipped before anything is hashed. |
| Their bytes | 100 MB | Skipped before anything is hashed. |
| Time for one walk (listing and hashing) | 5 s | Skipped; the after-scan has the same budget. |
| Files a command changed | 500 | Nothing recorded (the per-turn edit cap is 500 too). |

The listing (`stat` only) runs first, so a folder over the caps costs one directory walk. A skip never blocks or fails
the command. It's recorded as a `ShellNote` (chat, turn, call, reason) in `no_backup/shell-snapshot-notes.json` (the
latest 300), and the turn's changes card says it in a line under its files: "A command's changes weren't tracked: the
project has more than 5,000 files." A turn whose only commands were skipped gets a card of its own, "Command changes
not tracked", so its missing card never reads as "nothing changed". Several commands skipped for one reason are one
line ("· 3 commands").

## Undo and conflicts

Undo is change tracking's, with the "before" from the blob:

- **Changed or deleted by the command:** whatever is at the path now goes to the workspace trash (`MOVED_OVER`, so
  the undo can itself be undone), then the blob is copied back through `NoFollowFiles` (never through a link) and
  its mode set (`Workspace.restoreCopy`). The blob is copied, not moved: other snapshots share it.
- **Created by the command:** moved to the trash, as for a file tool.
- **Changed since?** The same check as a file tool's change: the file's SHA-256 against the one recorded after the
  command. Undo of one file or a turn warns and goes ahead; **Rewind here leaves such a file exactly as it is** and
  lists it, never overwriting it (docs/design/rewind.md). Readers must read the file again (`FileReads.invalidate`).
- **Snapshot gone** (expired, or cleared in Storage): the row can't be undone; the sheet says "The earlier version was
  in a command snapshot that's no longer kept", and Rewind here lists it as can't be put back.

A mode-only change (`chmod +x`) is a change with no line counts; undo puts the old mode back. The changed-since check
compares content only, so a later `chmod` alone doesn't count as a change since.

## Retention

`ShellSnapshots.retain()` after every tracked command:

- Each project keeps the snapshots of its latest **20 turns** that have them, none older than **14 days**.
- All projects together keep at most **500 MB** of blobs; past that the oldest snapshots go first.
- A blob no kept snapshot names is deleted (garbage collection). A snapshot whose command is still running is never
  dropped.
- Change rows are pruned by change tracking's own caps (30 turns per chat, 8 MB of diff text); a row whose snapshot
  went first just can't be undone.

**Settings → About & privacy → Diagnostics → Storage** lists **Command snapshots** with its size and **Clear**
(confirmed in a sheet; the commands' changes stay on their cards but can no longer be undone). Nothing else clears
them. They're not in backups: the trash isn't either, and a restored chat's cards come back without undo.

## Performance

Measured by `ShellSnapshotsTest` on the development Mac (JVM): a project of **2,000 files, 8 MB**: first snapshot
(hash and copy everything) **466 ms**, the after-scan (hash only) **45 ms**, the next snapshot with nothing changed
(hash only, every blob already there) **51 ms**. Hashing streams through a 64 KB buffer; nothing is read into memory
whole. A phone is slower (a few times), which is what the 5-second budget is for: a folder that would take longer is
skipped with a note rather than holding up the command. Everything runs on the IO dispatcher, never the main thread.

A git-style stat cache (reuse a file's hash when its size and mtime haven't changed) would make the after-scan nearly
free, but trusting mtime can store a wrong "before" when a tool restores old timestamps (`tar`, `rsync -t`, `cp -p`),
and a wrong "before" is worse than a slow one. Not done; worth measuring on the phone first.

## Tests

`ShellSnapshotsTest` (core/agent): adds, changes, deletes, renames and mode changes; links not followed or recorded;
excluded folders at any depth; dedupe across files and snapshots; the file, byte and time caps (with a slow clock);
the busy skip; retention by turns, days and budget, with GC; a snapshot in flight kept; Clear; references; the
2,000-file bound. `ShellChangeTrackerTest` (core/data): rows for the turn with the result untouched; undo from blobs,
mode included; changed-since reported and left alone by a rewind; a rewind across a shell turn and a file-tool turn;
setting off or outside a project changes nothing (no store is even created); the cap skip and the too-many-changes
note; a cleared snapshot blocks undo with the reason; a stopped command's changes still recorded.
`ShellChangeLabelsTest` (feature/chat): the card's wording. Screenshots: the project editor with the switch on, the
changes card with command changes, with a note, a notes-only card, the diff sheet of an expired snapshot, and the
Storage page with Command snapshots and while it's cleared.

## Not done

- A stat cache for faster scans (above).
- Snapshots outside projects.
- Tracking links, empty folders and folder modes.
- `linux_service_start` (above).
