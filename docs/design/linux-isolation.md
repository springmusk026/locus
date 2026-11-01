# Linux isolation: what Linux programs can and can't reach

Status: shipped (Unreleased). Code: `core/linux/.../ProcIsolation.kt` (`ProcIsolation`, `AppProcesses`, `ProotArgs`), `LinuxEnvironment.prootArgs`, `LocusApplication.attachBaseContext`; sealed permissions in `core/model/.../Integrity.kt`, `core/datastore/.../Integrity.kt` (`SealedSettingsStore`, `KeystoreIntegrityKey`), `core/agent/.../home/ConnectorSeal.kt`, `core/data/.../integrity/PolicyIntegrity.kt`. Tests: `ProcIsolationTest`, `PolicySealTest`, `SealedSettingsStoreTest`, `ConnectorSealTest`. Manual check: `tools/verify-linux-isolation.sh`.

## The design, and why it leaked

Locus's Linux is Alpine under proot (termux/proot at the commit pinned in `tools/build-proot.sh`). proot is path translation done in a ptrace tracer; it isn't a sandbox. Every Linux program is an ordinary process with **the app's own uid**, in the app's SELinux domain (`untrusted_app`), with no PID namespace. Whatever the kernel lets Locus do, it lets them do, unless proot's path translation stands in the way.

The audit (docs/research/reliability-security-audit-2026-11.md, item 3) suspected that `/proc` got around that, and a device test on 2026-11-01 confirmed it. Inside the guest, `/proc/<Locus pid>/fd/<n>` resolved to `/data/data/dev.locus/databases/locus.db` and returned the SQLite header. The reason is in proot's own source. `readlink_proc` (`src/path/proc.c`) returns `DONT_CANONICALIZE` for `/proc/<pid>/fd/<n>`, because those links may point at pipes and sockets. So proot passes the path to the kernel unchanged, and the kernel opens the file Locus has open. Through the same route a program could read Locus's memory (`/proc/<pid>/mem`, `process_vm_readv`). It could also write the files Locus holds open, including the settings that hold the Allow/Ask/Deny policies.

Two paths proot does translate weren't exploitable, and still aren't: `/proc/self/root` (proot substitutes the tracee's guest root) and `/proc/<pid>/root` or `cwd` of a process proot doesn't trace (proot reads the link itself and resolves the target as a guest path, inside the rootfs).

## The fix, in layers

### 1. Locus's processes are non-dumpable (kernel)

`LocusApplication.attachBaseContext` calls `prctl(PR_SET_DUMPABLE, 0)` (`android.system.Os.prctl`, public since API 21) before any file is opened, and `LinuxEnvironment` repeats the call before it starts anything. For a non-dumpable process, the kernel's ptrace access check (`ptrace(2)`, "Ptrace access mode checking", step 4) refuses every process without `CAP_SYS_PTRACE`, **even one with the same uid**. The files under `/proc/<pid>` also become owned by root (`proc(5)`). That covers:

- `fd`, `fdinfo`, `cwd`, `root`, `exe`, `environ`, `mem`, `maps`, `smaps`, `map_files`, under `/proc/<pid>` and under every `/proc/<pid>/task/<tid>`;
- `ptrace` and `process_vm_readv`/`process_vm_writev`, which don't go through paths at all;
- every thread, including threads started later. The flag belongs to the process's memory, which all threads share.

Side effects, checked:

- **proot still works.** `execve` makes a process dumpable again (`begin_new_exec` sets `SUID_DUMP_USER` when the uids match), so proot and the programs it traces are dumpable as before.
- **Locus can still read its own `/proc/self`.** Same-thread-group access is always allowed, and `proc_fd_permission` makes an exception for the owner.
- **Native crash reports still work.** Android's crash handler (`debuggerd_handler`) makes the process dumpable again while it dumps.
- **Native debugging and CPU profiling of Locus itself are blocked** (LLDB attach, `simpleperf` without root). Java debugging (JDWP), Layout Inspector, Database Inspector and heap dumps run inside the process and aren't affected.

### 2. proot hides Locus's processes and the binder devices (paths)

Before each command, `AppProcesses.find` lists `/proc` (with `hidepid`, Locus sees only its own uid). It picks every process whose command line is `dev.locus` or `dev.locus:<name>`, the names Android gives app processes, plus Locus's own pid, and all their thread ids. A thread id opens `/proc/<tid>` just as well as a pid does. `ProotArgs.build` then binds one empty directory over `/proc/<id>` for each of them. proot uses the deepest matching binding (`insort_binding` in `src/path/binding.c`), so this wins over the `/proc` binding for everything below the id, whatever the order. To the guest, `/proc/<Locus pid>` is an empty directory; `fd`, `mem`, `task/…` don't exist.

The same directory covers `/dev/binder`, `/dev/hwbinder`, `/dev/vndbinder` and `/dev/binderfs`, but only the ones that exist. Binding a missing path would make proot invent it. No Linux tool needs binder. Without binder, a Linux program can't ask Android services, the Keystore among them, for what Locus's uid is allowed. That matters for layer 3.

This layer is a backstop. It only knows the threads that existed when the command started, so a thread Locus starts during a long-running service isn't hidden by a bind. Layer 1 covers it.

**Options considered.** proot has no option to show only the guest's own processes. `--kill-on-exit` only ends the tracees when proot exits, and proot has no PID handling of its own. A PID namespace (`unshare(CLONE_NEWPID)`) needs privileges an app doesn't have, and unprivileged user namespaces are blocked for apps. A fully faked `/proc` would break `ps`, `top`, build tools and `/proc/self`, because guest PIDs are dynamic and proot binds are fixed when it starts. Patching proot to refuse `/proc/<pid>` for any pid it doesn't trace would close the path route completely. It would also mean carrying a patch on top of the reproducible build in `tools/build-proot.sh` (F-Droid rebuilds and compares it), and it still wouldn't cover `process_vm_readv` or `ptrace`. Layer 1 already does, so the patch isn't worth it for now.

### 3. Sealed permissions (defence in depth)

If something still managed to write Locus's files, it shouldn't be able to grant itself permissions silently. Three pieces of state are sealed with an HMAC-SHA256 key that's generated in and never leaves the Android Keystore (`KeystoreIntegrityKey`, alias `locus_policy_hmac`):

- **Tool policies and the reviewer settings.** `AppSettings.policySeal` is an HMAC over the policy map (sorted), `reviewerMode` and `reviewerApprovesOutward` (`PolicySeal`). `SealedSettingsStore` wraps the settings DataStore. Every read is checked, and every write is checked first and then sealed again, so an update can't seal permissions it didn't check. Nothing else needs to know about it.
- **Connector approvals.** `agent-home/connectors/.seal` lists each active connector's id and the SHA-256 of its file, with an HMAC over the list (`ConnectorSeal`). `AgentHome` checks it before every read or write of active connectors.

When a check fails, Locus falls back to safe settings and tells the user once, with a sheet when the app opens (`IntegrityNoticeViewModel`):

- **Permissions.** Allow overrides are dropped, so those tools are back to their defaults (only reads run without asking). Ask and Deny overrides are kept. The reviewer is turned off. The safe settings are written back, sealed.
- **Connectors.** A connector that isn't on the list, or whose file changed, goes back to drafts. Library offers it for review again.

Settings that grant nothing beyond the defaults need no seal. On the first run with this version, the Keystore key doesn't exist yet. Locus creates it at startup (`PolicyIntegrity.checkAtStart`). Whatever unsealed state it finds in that run (from an older version) is trusted once and sealed, and an empty connector list is sealed too. After that, a missing seal counts as a failure. If the Keystore can't be used, the check fails closed: permissions stay restricted, and other settings still save.

A restore is the user's own choice, so its settings and connectors are sealed with this phone's key while the restore is staged (`BackupManager.restoreAndRestart`). Without that, a seal made with the other phone's key would reset them at the next start.

The key is only as strong as the guarantee that nothing else can use it. A same-uid process that can reach binder can ask the Keystore for an HMAC, the same way it could decrypt the vault's keyset. Hiding binder from Linux (layer 2) is what makes the seal mean something against Linux programs.

## What is still shared, on purpose or not

Not blocked, by design:

- **`/workspace` and `/tmp`.** They are bound read-write so the agent, the shell, the file tools and the Files app share files. A Linux program can read and change everything in them: the user's projects, files the agent wrote, anything shared into Locus.
- **Linux sessions can see each other.** Every command, service and local MCP server runs under its own proot, as the same uid, and those processes aren't hidden from each other. So one Linux program can read another's environment and memory. A local MCP server's secrets, passed as environment variables, are readable by any other Linux command. Treat everything inside Linux as one trust zone.
- **The network.** Linux programs have the app's network access: the internet and the phone's LAN.
- **`/sys`, the rest of `/dev`, and `/proc` system files** (`/proc/cpuinfo`, `/proc/net`, and so on) are bound as before. They hold no Locus data, but they do describe the phone.
- **Locus's own files by path.** Linux can't name them: the rootfs is the guest's `/`, and only `/workspace` and `/tmp` are bound from app storage. That was true before this change too.

Not blocked, and known:

- **Any route we haven't thought of to an fd or a socket Locus holds.** For example, an abstract-namespace Unix socket Locus listens on, or a loopback port such as the OAuth redirect listener. Those are reachable from Linux like from any other app, and they check their callers accordingly.
- **Only permissions are sealed.** Persona files, memory, active skills and helper definitions in agent-home aren't. Layers 1 and 2 keep Linux away from them; nothing would notice if something else changed them.
- **The seal doesn't hide anything.** Settings, the database and agent-home stay plaintext on disk. The seal detects tampering with permissions; it doesn't stop anyone from reading.
- **A guest exploit of the kernel, or of proot itself.** proot runs as the app, so this design can't contain that.

## The long-term option: Linux under a different uid

The way to stop relying on path translation is to make Linux a different Linux user. The options:

- **`android:isolatedProcess` service.** It runs under a fresh, random uid with no permissions, in the `isolated_app` SELinux domain. It can't read Locus's files at all, and the app talks to it only through binder. The costs are large. An isolated process has **no network** (no `INTERNET`, so `apk add`, `npm install` and `git clone` stop working). It has no storage of its own to unpack the rootfs into, so the rootfs and the workspace would come in as file descriptors Locus passes over binder. `isolated_app` SELinux rules are strict about executing files, and it's unverified that proot's ptrace and loader would be allowed. In practice it would mean replacing proot, not moving it.
- **A companion APK.** A second app (like Termux's add-ons) gets its own uid and its own `INTERNET` permission, and shares only what Locus passes over binder, a socket or the Storage Access Framework. It is real isolation that keeps the network. The costs: a second install for users, a second F-Droid entry, keeping the two in sync, and an IPC protocol for commands, services and file access.
- **The Android Virtualization Framework** (protected VMs, the system's "Linux terminal"). It's the strongest option, but `MANAGE_VIRTUAL_MACHINE` is a signature/privileged permission, so third-party apps can't use it today.

The companion APK is the realistic next step if Linux isolation needs to be stronger than layers 1–3. It isn't scheduled.

## Checking it on a phone

`tools/verify-linux-isolation.sh` runs inside Locus's Linux. It finds Locus's pid through proot's parent, then checks the following. `/proc/<pid>` is empty. `fd`, `cwd`, `root`, `exe`, `map_files`, `mem`, `environ`, `maps` and `task/<pid>/fd` don't resolve. No id in the following 1,500 (threads started later) exposes Locus's database, settings, keyset or agent-home, or a Locus thread's memory. The binder nodes aren't devices. `/proc/self/fd`, `/proc/cpuinfo`, `ps` and `/tmp` still work. The kernel layer can also be checked from the host, as the app's uid (debug build):

```sh
adb shell 'pid=$(pidof dev.locus.debug); run-as dev.locus.debug ls /proc/$pid/fd; run-as dev.locus.debug stat -c %U /proc/$pid/fd'
```

Expect `Permission denied` and `root`. Before this change, the listing showed the open files and the owner was the app's user.
