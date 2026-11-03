# Linux health and inventory

Status: built 2026-11-02, not yet tried on a phone. Code: `core/linux` (`LinuxProbe`, `LinuxInventory`, `LinuxHealth`) and `feature/workspace/.../linux/health`. It builds on the Workspace's Linux card from #118 ([linux-setup.md](linux-setup.md)), which owns install, repair, reinstall and remove.

## Goal

The user should be able to see, at a glance, whether Locus's Linux works, what's in it, what it costs the phone, and what's wrong with it. Every problem it reports should come with a fix that really works.

## What comparable tools show, and what we took

| Tool | What it shows | What we took |
|---|---|---|
| `neofetch` / `fastfetch` | One block of identity: OS and version, kernel, arch, package count, memory, disk | The top bar's identity line (Alpine version, arch, proot version) and the package count |
| `htop` | Processes ranked by memory (RSS) and CPU, with load | Locus's Linux processes ranked by RSS, read from the host's `/proc` |
| Docker Desktop (Resources, Troubleshoot) | The VM's memory and disk use; "Clean / Purge data"; "Reset to factory defaults" | Storage and memory meters, a cache clean that's safe, and Repair / Reinstall as the escalation |
| VS Code Remote | A one-word connection state in the status bar; actions one click away | A verdict chip (OK / Warning / Problem) with a headline, and a fix button on each check |
| Termux (`termux-info`, `pkg list-installed`, About) | Environment facts for bug reports, installed packages, and on Android 12+ whether the phantom process monitor is on | The package list, and reading the phantom flag where Android allows it |
| UserLAnd, Andronix | Session state; failures surface as raw errors in a terminal | The thing to avoid: a broken session should be explained, not shown as a stack of shell errors |

What doesn't fit a phone: CPU graphs over time (a polling loop costs battery and the guest's load is mostly proot's), per-process kill buttons (the agent's services already have Stop), and kernel details (proot shares Android's kernel; it isn't the user's to change).

## What's read, and how

Most of it is read **from the host side**, so it still answers when Linux itself is broken, and it costs no proot launch:

- **Packages**: apk's own database, `/lib/apk/db/installed`. It's a text file of `X:value` records, the same in apk-tools 2 and 3 (Alpine 3.24's minirootfs ships apk-tools 3.0.8; the test fixture is that file). It gives name, version, description, arch and installed size. `/etc/apk/world` says which packages were asked for (by the user or the agent) rather than pulled in as dependencies.
- **Alpine version and arch**: `/etc/os-release` (or `/etc/alpine-release`) and `/etc/apk/arch`.
- **Integrity**: `/bin/busybox`, `/bin/sh`, `/usr/bin/env`, `/etc/alpine-release`, `/etc/apk/repositories`, the musl loader for the arch, the package database, and the install marker. Symlinks count as present (not followed). `/etc/resolv.conf` must name a nameserver.
- **Storage**: one walk of the distro (no symlinks followed, cancellable between files) gives its size, the biggest folders two levels down, and the size of each known cache. Linux's `/tmp` (kept outside the distro) is measured separately. Phone storage comes from `StatFs` on Locus's files directory.
- **Memory**: `ActivityManager.MemoryInfo` (total, available, Android's own low-memory flag), falling back to `/proc/meminfo`.
- **Processes**: every process of the app's uid in `/proc` that isn't one of Locus's own (the test `ProcIsolation` already uses). These are proot and the programs under it, which is what Android counts against its phantom process limit. RSS comes from `/proc/<pid>/status`.
- **Services**: `LinuxEnvironment.serviceStatuses()`, including ones Android killed or a restart ended.
- **Android kills**: #113's kill detection, via #118's `androidKill` notice (the last command, service or terminal Android killed).
- **The phantom process limit**: on Android 12+, the flag `settings_enable_monitor_phantom_procs`, read first from global settings and then from the developer option's system property (the order Android's `FeatureFlagUtils` uses, per agnostic-apollo's Android-Docs). Either can be closed to apps; then it's shown as unknown rather than guessed.
- **proot**: the binary and its loader are present and executable, and `--version` (run directly, with a 3 s limit).

Only three things start a process in Linux:

1. **Round trip**: one `echo` through proot, timed. It shows whether Linux runs at all and how slow a command is to start. It runs on every check, with a 20 s limit.
2. **Network**, on request: BusyBox `nslookup` and `wget --spider` to `dl-cdn.alpinelinux.org`, each with its own timeout. It reaches the internet, so it isn't automatic.
3. **Updates**, on request: `apk update` then `apk list -u`. It downloads the package index (a few MB), so it isn't automatic either. The parser also reads the older `apk version -l '<'` form.

`/proc/meminfo`, `/proc/<pid>/status`, apk's database, `world` and `apk list -u` all have parsers with fixture tests. There is no `df` parser: `StatFs` gives the same numbers without starting a process, and inside proot `df` would only report Android's own filesystem anyway.

## Health rules

`LinuxHealth.checks(report)` gives one line per check, worst first. The overall level is the worst line. The thresholds:

| Check | Problem | Warning | Fix |
|---|---|---|---|
| Runtime | proot or its loader missing or not executable | | None in the app: reinstall Locus |
| Round trip | Fails, or no answer in 20 s | Over 3 s | Repair |
| System files | Any key file missing, or apk's database unreadable | | Reinstall (Repair only rewrites what Locus manages) |
| Storage | Under 500 MB free on the phone | Under 2 GB free, or caches of 100 MB or more | Clean caches, when there are any |
| Memory | | Android's low-memory flag, or under 10% available | |
| Services | Crashed: exited with a non-zero code or a signal | Killed by Android; or set to start when Locus opens but not running | Restart (crash, kill), Start (not started) |
| Background limit (Android 12+) | | A kill was seen, or Locus has 20+ Linux processes | How to fix (a sheet) |
| DNS config | No nameserver in resolv.conf | | Reset DNS |
| Network (on request) | DNS fails | DNS works, HTTPS to the mirror doesn't | Reset DNS |
| Updates (on request) | | | Open terminal (`apk upgrade`), shown as information |

Every fix does something real:

- **Repair** and **Reinstall** call #118's `LinuxSetup`, so the Workspace card and this screen show the same operation. Reinstall goes through a red `ConfirmSheet`, and closes terminals first, as the card does.
- **Clean caches** empties apk's, pip's, npm's, Go's, Yarn's, Cargo's and Gradle's caches, after a `ConfirmSheet`. It runs from the host side, so it works even when Linux doesn't. A cache folder that's a symlink, or whose real path leaves the distro, is skipped.
- **Reset DNS** writes back the resolv.conf Locus installs with.
- **Restart** and **Start** start the service again with its command, directory and workspace mount. Service records now keep the project folder, so a project's service never gets the whole workspace on restart.

### Services and restarts

A service's proot process ends with Locus's process, so every restart of the app or the phone ends every service. That is expected, not a fault, so a service a restart ended (and one that exited with code 0) gets **no check**: it shows under Services as "Stopped by restart" (or "Finished") with a neutral chip and a **Start** button, and doesn't touch the verdict or the "need attention" count. The states (`LinuxEnvironment.ServiceState`):

| State | How it's told apart | Chip | Check |
|---|---|---|---|
| Running | The process is alive | Running (live) | none |
| Stopped by restart | Its record has no exit: Locus's process ended while it ran | Stopped by restart (neutral), Start | none, or a warning when set to start |
| Finished | Exit code 0, kept in its record | Finished (neutral), Start | none, or a warning when set to start |
| Crashed | Non-zero exit or a signal, kept in its record | Crashed, code 1 / signal 11 (SIGSEGV) (critical), Restart | **Problem** |
| Killed by Android | SIGKILL while Locus was in the background | Killed by Android (caution), Restart | Warning |

The exit is written to the service's record (`ServiceRecords`) when it happens, so after a restart a crash still reads as a crash rather than as "a restart ended it".

**Start when Locus opens** is a `SwitchRow` under each service. With it on, `LinuxAutostart` (core/data, started from the Application) starts the service again the first time Locus is on screen in a new process, once Linux is ready. It waits for the screen because Android freezes or kills an app's extra processes in the background, so a server started for a scheduled task or at boot wouldn't stay up. Only the user sets it: the agent has no tool for it, and if the agent starts a different command (or directory, or folder) under the same name, the setting is dropped. A service set to start that isn't running is a warning with Start; a crash wins over that.
- **How to fix** opens a sheet with #113's reason and fix, a button to Developer options, and the guide.
- **Open terminal** opens the terminal.

## The agent: no new tool

There's no `linux_info` tool. An earlier version of this branch added one; it was taken out because every tool's description is sent with every new chat, so a tool costs tokens in chats that never use it. The agent already gets the same facts through `run_command`: `apk info -e python3`, `apk info -vv`, `cat /etc/apk/world`, `df -h /`, `free -m`, `cat /etc/alpine-release`. The probe classes stay in `core/linux`, for the screens.

## The health page

It's opened from "Health & details" on the Workspace's Linux card. It's a summary meant to be read at a glance, so nothing long is shown in full. The first version listed every package inline, hundreds after a toolchain install; phone feedback was that this was too much, so the page now only counts them. The rules are pure functions in `HealthSummary`, with tests. From top to bottom:

1. **Top bar**: "Linux health", the identity line, and Check again (pull to refresh does the same, but TalkBack can't pull).
2. **Verdict card**: the level as a `LocusChip`, the headline, "n of m checks need attention" and when it was checked. It's a polite live region.
3. **Problems**: worst first, one row each, with a status icon (described as OK / Warning / Problem), detail, and the fix button. Passed checks fold into one "n checks passed" row that expands. The network check offers Check until it has run.
4. **Storage**: a `Meter` for phone storage, then what Linux uses, `/tmp`, the top 3 folders ("Show all" for the rest), and the caches with Clean.
5. **Memory**: a warning, not a dashboard. When the phone is low (the memory check's rule), a caution `Banner` says so; otherwise one line with what's available. Then one line for Linux's programs ("2 running · 84 MB") and the top 3 by RSS ("Show all" for the rest). The memory check isn't repeated among the problems, but it still counts toward the verdict.
6. **Services**: state chips, with Stop, Start or Restart, and a "Start when Locus opens" switch per service ([Services and restarts](#services-and-restarts)).
7. **Packages**: one row, "n you installed · m total", which opens the packages screen.

### Packages

Its own screen, because a toolchain pulls in hundreds of packages (app-manager screens and Docker Desktop's image list work the same way: a summary count, the full list one tap away).

- **"You installed" is the default**: packages in `/etc/apk/world` except the six Alpine's own image puts there (`LinuxInventory.BASE_SYSTEM`). **All** is a `LocusChip` filter beside it, and a search field filters within whichever is selected (name matches first, then descriptions).
- A `LazyColumn` with a key and content type per row; the filtering runs off the main thread, so it stays smooth with 500+ rows.
- Rows are compact: name, version and size. Tapping one expands it to its description and where it came from (you, Alpine, or a dependency). Only the user's own packages offer **Uninstall**, which runs `apk del <name>` after a red `ConfirmSheet`; names are checked against apk's name characters first, and Alpine's base is refused.
- **Check for updates** is here, on request.
- An empty "You installed" says how to install something and offers "Show all".

The states:

- **Not installed / failed install**: `EmptyState` with "Set up Linux", which goes back to the card.
- **Installing**: a `Meter` with the step.
- **Unavailable**: the reason.
- **First load**: `LoadingState`.
- **Probe failed**: `ErrorState` with Try again.
- **Package database unreadable**: the Packages row says so, and the packages screen shows `ErrorState` with Try again.
- **No services**: one line saying how they appear.
- **Search with no match**: one line saying so.

Outcomes ("Freed 150 MB.", "Repaired…", a failed restart) go through `Locus.feedback`. Only design-system tokens and components are used; `DesignGuardTest` passes with no new findings.

## Deferred

- CPU use per process. It needs two samples of `/proc/<pid>/stat` over time, and a polling loop the battery work tries to avoid.
- Showing `MAX_PHANTOM_PROCESSES`. Reading it needs `DUMP` granted over adb.
- Upgrading packages from the screen. `apk upgrade` can take minutes and prompt, so it belongs in the terminal, which the update card opens.
