# Remote execution: a project's commands on your own machine

Status: designed and built 2026-11-01 (v1). Roadmap: "Optional remote execution" (P2) under "Recommended next (Nov 2026)". Code: `core/data/.../remote/` (`RemoteExecution`, `RemoteSync`, `SyncRules`, `RemoteFs`), `core/agent/.../tools/RemoteBackend.kt` and `RemoteJobs.kt`, the routing in `core/linux/.../LinuxTools.kt` and `CodingTools.kt`, and the UI in `feature/chat/.../RemoteUi.kt`.

The phone is a poor place for a Gradle build or a test suite that runs for ten minutes. Many users have a PC or a server they can reach over SSH, and Locus already has SSH machines (`ssh_exec`, `ssh_job_*`, keyless login since #50). This feature lets a project run its commands on one of those machines:

- the project folder is synced there;
- `run_command`, services and logs, and verification run there;
- the phone keeps control, approvals and results.

It's opt-in per project, because the project's files and commands leave the phone. There is no Locus server in between: the phone talks to the user's machine over SSH, and nothing else.

## What others do (checked 2026-11-01)

| | Where files live | What runs remotely | Getting there, getting back | Disconnects |
|---|---|---|---|---|
| **VS Code Remote-SSH** [1] | On the host only; not synced. Local tools need SSHFS or rsync | VS Code Server, most extensions, terminals, debugging. UI extensions stay local | A server installed per connection in `~/.vscode-server`, through the local OpenSSH client and `~/.ssh/config`. Host needs glibc ≥ 2.28; Alpine/musl isn't supported [2] | Not documented; the documented fix is "Kill VS Code Server on Host" |
| **Zed remote development** [3] | On the host. UI, LLM calls, highlighting and unsaved buffers stay local | Language servers, tasks, terminals, on a headless server in `~/.zed_server` | One ControlMaster per project; server version must match the client | Each reconnect starts the daemon or reattaches to it, so work survives a dropped connection; unsaved changes are restored locally |
| **OpenAI Codex cloud** [4] | A container that checks out the repo at a branch or SHA | Setup script, then the agent; internet off by default in the agent phase [5] | Clone in; results as a diff to review, then a commit or PR [6] | Containers cached up to 12 h |
| **Claude Code on the web** [7] | A VM that clones the GitHub remote (or an uploaded git bundle, credential-like files left out) | Everything | Clone in; a branch, a diff view, a PR, or `--teleport` back to the local checkout | Conversation restored; running shell commands aren't |
| **Claude Code Desktop over SSH** [8] | On the host | Claude Code itself, under `~/.claude/remote/`; needs SFTP on the host | Host keys confirmed by fingerprint | The task keeps running under a helper on the host; approvals wait there with no time limit until a client reconnects |
| **Claude Code Remote Control** [9] | On your own machine | Everything; the phone steers | Outbound HTTPS only, through Anthropic's API | Reconnects on its own; docs say to run it in tmux or screen over SSH |
| **Dev Containers** [10][11] | Bind-mounted, or cloned into a volume on a remote Docker host (a remote host can't bind-mount local files) | The container; lifecycle commands (`postCreateCommand` …) | Remote Docker over SSH (`DOCKER_HOST=ssh://…`); for local access, SSHFS (slow) or rsync | n/a |

Sync tools:

- **rsync** sends only differences and checks size and mtime, but it "must be installed on both the source and destination machines" [12].
- **Mutagen** does a three-way merge against the last agreed state. Its default "two-way-safe" mode resolves a conflict on its own only when nothing is lost [13]. It installs an agent binary on the remote over SSH [14], and it advises against syncing `.git`, keeping the VCS folder on one side only [15].

Keeping jobs alive:

- `nohup` ignores hangups [16]. A tmux session survives disconnection [17], but tmux isn't always installed.
- `systemd-run --user` makes a transient unit [18]. Without `loginctl enable-linger`, user units can die at logout [19], and macOS has no systemd.

Android: the Doze page lists "Suspends network access" with no foreground-service exception [20]. The power-details table says a foreground service has no network restrictions, but its own Doze row says "restricted" [21]. So Locus can't count on keeping a connection open while the phone sleeps.

Sources:

- [1] https://code.visualstudio.com/docs/remote/ssh
- [2] https://code.visualstudio.com/docs/remote/linux
- [3] https://zed.dev/docs/remote-development
- [4] https://learn.chatgpt.com/docs/environments/cloud-environment (developers.openai.com/codex now redirects here)
- [5] https://learn.chatgpt.com/docs/cloud/internet-access
- [6] https://learn.chatgpt.com/docs/cloud
- [7] https://code.claude.com/docs/en/claude-code-on-the-web
- [8] https://code.claude.com/docs/en/desktop
- [9] https://code.claude.com/docs/en/remote-control
- [10] https://containers.dev/implementors/json_reference/
- [11] https://code.visualstudio.com/remote/advancedcontainers/develop-remote-host
- [12] https://download.samba.org/pub/rsync/rsync.1
- [13] https://mutagen.io/documentation/synchronization
- [14] https://mutagen.io/documentation/transports/ssh
- [15] https://mutagen.io/documentation/synchronization/version-control-systems
- [16] https://man7.org/linux/man-pages/man1/nohup.1.html
- [17] https://man7.org/linux/man-pages/man1/tmux.1.html
- [18] https://www.freedesktop.org/software/systemd/man/latest/systemd-run.html
- [19] https://www.freedesktop.org/software/systemd/man/latest/loginctl.html
- [20] https://developer.android.com/training/monitoring-device-state/doze-standby
- [21] https://developer.android.com/topic/performance/power/power-details

What Locus takes from this:

1. **Sync instead of "files live remotely".** VS Code and Zed keep the files on the host, because their whole editor runs against them. Locus's file tools, change tracking, undo, the Workspace screen and the Files app all work on the phone's workspace. So the files stay on the phone, and a copy is kept in sync on the machine. That's the devcontainer-on-a-remote-host shape (code on the host, synced) with Mutagen's safety rules.
2. **A detached job on the host that the client reattaches to.** This is how Zed, Claude Desktop SSH and tmux all survive a dropped connection. Locus already had one in `RemoteJobs` (nohup, `~/.locus-jobs`), so remote commands run as jobs and Locus follows them.
3. **Approvals stay on the client.** In Claude Desktop SSH and Remote Control, the decision waits for the user's device. Here the approval happens on the phone before anything is copied or started.
4. **Nothing installed on the host.** VS Code, Zed, Mutagen and Claude Desktop all install a server or agent binary. Locus needs only sshd with SFTP (which Claude Desktop SSH also requires) and a POSIX `sh`. musl, macOS and old glibc hosts all work.
5. **Git stays on one side.** Mutagen's advice: `.git` isn't synced.

## The design

### Per-project execution backend

Each project has a backend: **This phone (Linux)**, the default, or **Run on \<SSH machine\>** with a folder on it. The setting is `AppSettings.projectRemotes[projectId] = ProjectRemote(hostId, folder, linkedAt)`.

It lives in the sealed settings, next to the project's permissions (docs/design/projects.md, "Sealing"):

- Anything that could write Locus's files outside Locus could otherwise point a project at a machine and send its files off the phone.
- The seal covers it, and it enters the sealed payload only when one exists, so older seals still match.
- A broken seal drops every remote (`PolicySeal.restricted`), and the project runs on the phone again.

Deleting a project, or switching it back to the phone, removes the setting. Files on the machine stay there.

### Choosing sync: SFTP with a three-way state on the phone

| Option | For | Against |
|---|---|---|
| rsync over SSH | Delta transfers, mature | Needs rsync on **both** ends [12]. On the phone that means Alpine's rsync inside proot, driving `ssh` from Linux. That would hand the user's SSH key to Linux, which Locus keeps out of its reach (docs/design/linux-isolation.md), and it would bypass the host-key pinning in `SshService`. It also needs Linux installed, which remote execution doesn't. |
| git-based (commit, push or bundle, pull) | Codex and Claude Code cloud do this | Needs the project to be a git repository, and makes commits in the user's repository or a shadow one. Most Locus workspaces aren't repositories, and change tracking already chose not to use git (docs/design/change-tracking.md). |
| **SFTP, incremental, three-way** | Pure Kotlin over the existing sshlib connection: the same host-key pinning, and key, password and keyless login. Works on any host with sshd's SFTP subsystem | Whole-file transfers, no deltas. Fine for source trees; large outputs stay on the machine (below). |

**Choice: SFTP.** `RemoteSync` keeps the last agreed state per project on the phone (`files/remote-sync/<hash>.json`). For each file it records the content hash, the phone's size and mtime, and the machine's size and mtime. That is Mutagen's "last agreed state" [13]:

- **Up**, before each command. A file whose phone stamps changed is hashed. If its hash differs from the agreed one, it's uploaded: written to a temporary name, given its executable bit, then renamed into place. A file deleted on the phone is deleted on the machine, but only if Locus copied it there and it hasn't changed there since.
- **Down**, after each command. The machine's folder is listed over SFTP. A file whose remote stamps changed is downloaded and hashed. A changed or new file is written on the phone, and the old version goes to the workspace trash first. A file deleted on the machine is moved to the trash on the phone, unless the phone's copy changed since.
- **Both sides changed** (rare, since the phone syncs right before each command): the machine's version is kept, the phone's is in the trash, and the result names the file. Nothing is lost, which is the guarantee of Mutagen's two-way-safe mode.

### What is never copied

`SyncRules`:

- **Secrets, never, either way.**
  - By name: `.env` and `.env.*` (not `.env.example` and other templates), `*.pem`, `*.key`, `*.p12`, `*.jks`, `*.keystore`, `*.tfvars`, `id_rsa` and the other private key names, `.netrc`, `.npmrc`, `.pypirc`, `.git-credentials`, `local.properties`, `credentials.json`, `secrets.*`.
  - Folders: `.ssh`, `.gnupg`, `.aws`, `.kube`, `.docker`, and the like.
  - **Any file holding a value from Locus's vault** (an API key, a password, a token the user saved). An agent that writes a key into `config.js` can't send it to the machine.
- **Stays where it is:**
  - `.git` (git stays on the phone, per [15]; commit with `git_commit` as before);
  - dependency and build folders each side makes for itself (`node_modules`, `.gradle`, `build`, `target`, `.venv`, `__pycache__`, `.next`, …);
  - Locus's `.locus/`;
  - files over 100 MB;
  - anything the project's `.locusignore` names (gitignore-style: `*.log`, `data/raw/`, `/docs/big.pdf`).

So build outputs stay on the machine. The tool result says so and points at `sftp_download` to fetch one (an APK, say). Copying `build/` back after every command would move thousands of intermediate files for nothing.

### Preview-first link-up

In the project editor, "Where commands run" opens a sheet:

1. **Choose.** This phone, or one of the user's machines with a confirmed host key.
2. **Folder.** Locus reads the machine's `$HOME` and offers `~/locus/<project folder>`. A caution banner says files leave the phone, which secrets never do, and that approvals stay on the phone. The folder must be absolute, without `..`, and not `/` or the home folder itself (`ProjectRemote.cleanFolder`).
3. **Preview.** Nothing is copied yet. The sheet shows:
   - what will be copied (count, size, the first paths);
   - files already in that folder that will come to the phone;
   - the secrets that will never be copied;
   - the folders that stay where they are.
4. **"Link and copy N files".** Copies both ways with a progress bar. Only then is the setting saved.

### Routing

The tool list doesn't change, and neither does the system prompt. The backend is a routing decision inside the existing tools, made per call:

- `RemoteBackend.target()` reads the call's project from `ChatScope` and the project's current setting.
- With no target, everything runs in Linux on the phone exactly as before.

| Tool | On a linked project |
|---|---|
| `run_command` | Sync up, run in the synced folder as a detached job, follow its log, sync back. Works without Linux installed on the phone. |
| `linux_service_start` / `_stop` / `_list` | A detached job per service on the machine; stop syncs back. |
| `linux_service_log` | Reads the job's log over SFTP; once the job has finished, syncs back. Also reads a long `run_command`'s job by its id. |
| Verification card | Unchanged: `run_command`'s result keeps its status line (`Exit code 1 (61.0 s).`). The card adds "On build-server". |
| File tools, `git_inspect`, `git_commit` | Stay on the phone; edits reach the machine at the next sync. |

**Why per call, not frozen per chat.** The other option was freezing the backend into the chat at creation, as projects freeze instructions. That would need a database column and would leave existing chats on the phone after linking. A chat's cached prefix doesn't depend on the backend, so the choice can be made per call. Every routed result says where it ran, on the line after the status:

```
Exit code 0 (84.2 s).
[Ran on build-server in /home/me/locus/app, not on the phone: it's the user's own machine with its own tools (not Alpine; don't use apk). The project's files were synced there first and changes come back after. Copied 2 files there. Brought back 1 file: app/src/Gen.kt (changed). Build outputs and dependency folders (build/, node_modules/, …) stay on build-server; copy one back with sftp_download.]
<untrusted_web_content …>…</untrusted_web_content>
```

That corrects `run_command`'s description (Alpine on the phone, `/workspace`) for this call without touching the cached prefix. `cwd` keeps its meaning: `/workspace` and `/workspace/x` map into the synced folder, and a relative path is taken under it.

### Long builds: a detached job, followed and reattached

Every remote command runs as a `RemoteJobs` job: `nohup sh` in `~/.locus-jobs/<id>.{sh,log,pid,exit,start}`.

- **Why nohup.** It's POSIX and works on Linux and macOS alike. tmux isn't always installed, and `systemd-run --user` needs systemd and linger [18][19].
- **The job script** sources `~/.profile` (so the user's PATH and toolchains are there, as in their own terminal), then `cd`s into the folder and runs the command.

Following a job:

- Locus keeps one connection open and checks the job every second. Each check reads the log bytes added since the last one over SFTP, which streams the log into a head-and-tail buffer and a spool file. The whole log is saved to `.locus/out/` when long, as locally.
- **Dropped connection** (Doze, a network change): Locus reconnects every 3 s until the call's timeout. The job runs on regardless. A changed host key is never retried.
- **Timeout reached**: the result starts "Still running on build-server after 120s, as job …". It isn't an exit code, so no verification card yet. It tells the agent how to wait for the job.
- **Reattach.** A job's id is derived from the project, `cwd` and the command. Calling `run_command` again with the same command (in a later turn, after the phone slept, or after Locus restarted) **attaches to the running job instead of starting it again**. A job that finished but whose result was never reported is reported then, with its exit code, so the verification card appears. Once reported, the job is marked seen, and the same command starts afresh next time.
- **Stop.** If the user stops the task while a command runs, the job on the machine is stopped too.

Jobs are prefixed per project (`p<hash>-rc-…`, `p<hash>-s-<service>`), so one machine can serve several projects and `ssh_job_status` can still list them all.

### Change tracking

Files that sync-back changes on the phone are recorded like the file tools' own changes (`ChangeRecorder`, tool name `run_command` or the service tool), under the call's turn. The old version is the trash copy sync-back made first. So **edits made on the machine by commands show on the turn's changes card, with diffs and undo**. Locally, `run_command`'s own changes still aren't tracked (docs/design/change-tracking.md), because only a sync knows exactly what changed. `FileReads` sees the new hash, so the agent must read a changed file again before editing it.

Gaps:

- Changes from a job that outlived its call are attributed to the call that brings them back (a later `run_command` or `linux_service_log`).
- Folders that stay on the machine (`build/`, `node_modules/`) aren't tracked, by design.

### Rules

- **Explicit opt-in per project**, preview-first, with a caution notice of what leaves the phone. Nothing is linked from a chat or by the agent.
- **Secrets are never synced** (above), including files that hold a vault value. Command output and logs are scrubbed of vault values before the agent sees them.
- **Taint.**
  - A new `Tool.sendsOffPhone(args)` returns the machine's alias when a call would be routed. `Taint.reason` then treats the call as outward. In a run that has read outside content, a remote `run_command` or `linux_service_start` asks first: "This runs on build-server and copies the project's files there first". It's still a local tool, so an untainted run follows the user's normal Allow / Ask.
  - **Workspace mode** ("sends nothing") refuses routed calls with a reason.
  - Read-only background runs never route: those tools aren't observations.
- **Approvals happen on the phone**, before anything is copied or started, by the same policy as always. The project's permission overlay applies.
- **Key, password and keyless logins** all work: every connection goes through `SshService.withClient`.
- **Host keys are verified**: a machine presenting another key is refused with "SECURITY: …", never retried.

### Cache safety

- No tool is added, removed or redescribed.
- The system prompt doesn't mention the backend.
- Routing happens at run time, and the result explains itself.
- Switching a project between the phone and a machine mid-chat changes only what later results say.

### UI

- **Project editor.** A "Where commands run" row (This phone (Linux), or "On build-server" with the folder) opens the sheet above. Choosing the phone again asks first, in a sheet, and leaves the copy on the machine.
- **Chat header.** Next to the project chip: "Runs on build-server". It becomes "Syncing with build-server" or "Running on build-server" with a live dot while that's happening. Tapping it opens a sheet explaining what runs there, with a link to the project's settings.

All confirmations are sheets.

## Tests

`RemoteExecutionTest` (core/data) runs the real data layer, tools and `SshService` against an in-process Apache MINA SSHD server with SFTP (`TestSshServer`), the approach of the benchmark's `FixtureSsh`. Its commands run on the test machine. The tests cover:

- **Preview and secrets.** The preview lists files and copies nothing. Linking copies files with their executable bit. `.env`, `id_rsa`, a file holding a vault value, `node_modules` and `.git` never reach the machine.
- **Routing and sync.** An edit on the phone reaches the machine before the command. The command's created, changed and deleted files come back, and each is on the changes card. The result reads as a verification result with the host.
- **Long jobs.** A command past its timeout keeps running. The same call attaches and returns its exit code. After that, it starts afresh.
- **Reconnection.** The SSH server stops mid-wait and comes back on the same port and key. The run follows the job to its end.
- **Services** start, log, list and stop on the machine.
- **Taint and mode.** A tainted run gets a reason for a routed `run_command` and none before linking or outside the project. Workspace mode refuses it.
- **Logins and host keys.** Keyless and key logins work; a changed host key is refused.

Also:

- `SyncRulesTest`: secret names, folders, `.locusignore`, vault values, job ids and `cwd` mapping.
- `ProjectRemoteTest` (core/model): folder checks, the seal over `projectRemotes`, and the verification card's host.

## Not done (later)

- **Deltas.** Whole files are sent. A large binary that changes often is re-sent each time.
- **Watching for changes** (Mutagen, Zed) instead of syncing around each command. Locus syncs only when a command runs, so edits made on the machine by hand come back after the next command.
- **Port forwarding** for a dev server on the machine, so the agent browser could open it. sshlib supports `localPortForward`; the browser would need to know about it.
- **Picking which machine per chat.** It's per project.
- **systemd-run** for hosts that want jobs as units. nohup covers Linux and macOS today.
- **Verified on a phone.** Not yet. Doze behaviour with a remote job running needs a device check (see the phone checklist in the PR).
