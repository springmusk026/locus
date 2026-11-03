# Linux isolation: what Linux programs can and can't reach

Status: layers 1–3 shipped in 0.1.4; layer 4 (links Linux plants) added 2026-11-03. Code: `core/linux/.../ProcIsolation.kt` (`ProcIsolation`, `AppProcesses`, `ProotArgs`), `LinuxEnvironment.prootArgs`, `LocusApplication.attachBaseContext`; sealed permissions in `core/model/.../Integrity.kt`, `core/datastore/.../Integrity.kt` (`SealedSettingsStore`, `KeystoreIntegrityKey`), `core/agent/.../home/ConnectorSeal.kt`, `core/data/.../integrity/PolicyIntegrity.kt`; link-safe file operations in `core/model/.../FileTrees.kt` and bind checks in `core/linux/.../BindSources.kt`. Tests: `ProcIsolationTest`, `PolicySealTest`, `SealedSettingsStoreTest`, `ConnectorSealTest`, `FileTreesTest`, `WorkspaceToolsTest`, `PendingRestoreTest`, `BindSourcesTest`. Manual check: `tools/verify-linux-isolation.sh`.

## Threat model: what a Linux program can reach today

The attacker is any code running in Locus's Linux: a package from `apk`/`npm`/`pip` with a hostile install script, a cloned repository's build, a local MCP server, or a command the model was talked into running by a web page. It runs as Locus's uid with root inside proot (`-0`). Its goals are to read Locus's private data (chats, settings, provider keys, memory), to change what the agent does (permissions, skills, persona), or to destroy data. The table was checked by reading the code on 2026-11-03, not assumed.

| Route | What Linux gets | How it was checked |
|---|---|---|
| Naming host paths | Nothing outside the rootfs and the binds. `/` is `files/linux/alpine`; binds are `/dev`, `/proc`, `/sys`, `/dev/urandom`→`/dev/random`, `/proc/self/fd`→`/dev/fd`, four fake `/proc` files (`files/linux/proc-fakes/*`), an empty shadow over Locus's pids and binder, `files/linux/tmp`→`/tmp`, and the workspace (or a project's folder)→`/workspace`. `databases/`, `files/datastore/` (settings and the vault), `shared_prefs/`, `files/agent-home/`, `files/workspace-trash/`, `files/linux/{logs,spool,services.json}` aren't bound. | `ProotArgs.build`, `LinuxEnvironment` paths |
| Locus's processes | Nothing (layers 1 and 2). | `ProcIsolation`, device test of 0.1.4 |
| Binder (Keystore and every system service) | Nothing: the device nodes are shadowed. | `ProcIsolation.BINDER_DEVICES` |
| Environment | A fixed list of 11 variables (`PROOT_LOADER`, `PROOT_LOADER_32`, `PROOT_TMP_DIR`, `LD_LIBRARY_PATH`, `HOME`, `USER`, `PATH`, `LANG`, `TERM`, `TMPDIR`, `TZ`); the environment is cleared first, so nothing of Locus's own leaks. The terminal adds `TERM`/`COLORTERM`. A local MCP server additionally gets the vault secrets its connector names, on purpose. | `processFor`, `startPiped`, `GuestLaunch.terminalEnvironment` |
| Inherited file descriptors | Only stdin/stdout/stderr. Java's `ProcessBuilder` closes every fd above 2 in the child (OpenJDK `childproc.c`, `closeDescriptors`); the terminal's `termux.c` does the same by walking `/proc/self/fd` (lines 87–93). `run` closes stdin; a service's output goes to a log Linux can't name. | Source of both |
| `/proc/self`, `/proc/<proot pid>` | Its own process and proot's: the same fixed environment and no Locus files. | Above; `tools/verify-linux-isolation.sh` check 6 |
| Other Linux processes | Everything (same uid, dumpable). A local MCP server's secrets are readable by any other Linux command through `/proc/<pid>/environ`. | By design, see below |
| Network | The app's: the internet, the LAN and loopback listeners such as the OAuth redirect. | By design |
| **Links Linux plants, followed by Locus** | **Before layer 4: write, copy and delete anywhere in Locus's data.** See layer 4. | Code reading, the proot source and JVM tests with real symlinks |

The last row was the real gap. Linux can't *name* Locus's files, but it can leave a symbolic link whose target is any host path (proot stores the target exactly as given: `PR_symlink` in [`src/syscall/enter.c`](https://github.com/termux/proot/blob/master/src/syscall/enter.c) translates only the new link's parent, "SYSARG_1 is the symlink's contents (not a path)"), and Locus's own code, running outside proot, then followed it.

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
- **Remembered approvals.** The "This chat" and "Always" rules from approval cards are part of the same seal. A broken seal drops all of them ([remembered approvals](remembered-approvals.md)).
- **Where requests and keys go.** `AppSettings.endpointSeal` is an HMAC over each provider's id, format, base URL and network settings (extra header names, replacing the credential header, proxy, trusted certificate ids), the default proxy, the imported certificates (by PEM hash), and each SSH machine's id, host, port, user and confirmed host key fingerprint (`EndpointSeal`). It has a Keystore key of its own (`EndpointIntegrityKey`, alias `locus_endpoint_hmac`), created at startup like the policy key, so an empty endpoint seal is trusted only in the run that creates that key. A rolled-back settings file can't bring that back, and the permissions seal is unchanged, so updating doesn't trip it.
- **Connector approvals.** `agent-home/connectors/.seal` lists each active connector's id and the SHA-256 of its file, with an HMAC over the list (`ConnectorSeal`). `AgentHome` checks it before every read or write of active connectors.

When a check fails, Locus falls back to safe settings and tells the user once, with a sheet when the app opens (`IntegrityNoticeViewModel`):

- **Permissions.** Allow overrides are dropped, so those tools are back to their defaults (only reads run without asking). Ask and Deny overrides are kept. The reviewer is turned off. The safe settings are written back, sealed.
- **Connectors.** A connector that isn't on the list, or whose file changed, goes back to drafts. Library offers it for review again.
- **Endpoints.** The addresses are kept, but each provider with an address is marked `addressUnconfirmed`. `ProviderAccess` then sends it nothing, neither key nor conversation, until the user checks the address and saves it in Settings → Models (the row says "Check address"). Proxies, imported certificates and "replace the credential header" are dropped, and SSH host keys are cleared, so the next connection asks the user to confirm the machine's key again.

Settings that grant nothing beyond the defaults need no seal. On the first run with this version, the Keystore key doesn't exist yet. Locus creates it at startup (`PolicyIntegrity.checkAtStart`). Whatever unsealed state it finds in that run (from an older version) is trusted once and sealed, and an empty connector list is sealed too. After that, a missing seal counts as a failure. If the Keystore can't be used, the check fails closed: permissions stay restricted, and other settings still save. A key the Keystore invalidated (it happens after some OS updates) is replaced. What it sealed then falls back to the safe settings once, with the notice, and is sealed with the new key.

A restore is the user's own choice, so its settings and connectors are sealed with this phone's key while the restore is staged (`BackupManager.restoreAndRestart`). Without that, a seal made with the other phone's key would reset them at the next start.

The key is only as strong as the guarantee that nothing else can use it. A same-uid process that can reach binder can ask the Keystore for an HMAC, the same way it could decrypt the vault's keyset. Hiding binder from Linux (layer 2) is what makes the seal mean something against Linux programs.

### 4. Locus never follows a link Linux planted (confused deputy)

Linux can write the rootfs, `/tmp` and `/workspace`, so it can put a link like `/root/x -> /data/data/dev.locus/databases` (or `/data/user/0/dev.locus/...`) in any of them. Inside the guest proot resolves that target as a guest path and finds nothing. Locus's own code, however, runs outside proot, so for it the target is the real host path. Kotlin's `File.deleteRecursively` and `File.copyRecursively` decide whether to descend with `File.isDirectory`, which follows links (`FileTreeWalk.kt` in the stdlib), and `File.writeText` follows a link at its final name. The audit of every recursive or write operation on a tree Linux can write found these:

| Where | Trigger | Effect before the fix | Severity |
|---|---|---|---|
| `run_command`'s full-output spill (`LinuxEnvironment.run`), the remote equivalent (`RemoteExecution`) and `WorkspaceOutputSpill` write `/workspace/.locus/output/<yyyyMMdd-HHmmss>-<3 hex>-<tool>.log` with `writeText` | **None from the user.** The command itself plants 4,096 links for the current second's names, then prints more than 30k characters | Locus writes the command's own (scrubbed) output through the link into any app file: an active skill or the persona in agent-home (unsealed), giving persistent instructions to the model, or the database to destroy it | High |
| `LinuxEnvironment.configure` (install, **Repair**) and `resetDns` (**Reset DNS**) write `etc/resolv.conf`, `etc/hosts`, `etc/motd` and the four `/proc` fakes (bound read-write into the guest) | The user taps Repair or Reset DNS, which a program can provoke by breaking DNS | Fixed content (`""`, the nameservers) overwrites any app file, e.g. truncates `locus.db` | Medium-high |
| `LinuxEnvironment.uninstall` and reinstall (`base`/`rootfs.deleteRecursively()`) | The user removes or reinstalls Linux | Everything the link points at is deleted: the whole app data directory, the vault and the workspace included | High (data loss) |
| Restore (`PendingRestore`, `RestoreFs.delete/copy`, `discard`) | The user restores a backup | The old workspace is deleted after the swap; a link in it to `databases/` deletes the database just restored | High (data loss) |
| Workspace trash (`purgeTrash`, `restore`, the `trash`/`backup` copy fallbacks), `move_path`'s copy fallback | The user or agent deletes a folder and the trash is emptied | Deletes what links inside the folder point at | Medium |
| `copy_path` (`from.copyRecursively(to)`) | The agent copies a folder, e.g. a cloned repository whose links point at Locus's data | Locus's private files (plaintext chats, settings, agent-home) are copied into `/workspace`, where Linux reads and uploads them. The vault's ciphertext is copied too, but its key stays in the Keystore | Medium-high (exfiltration) |
| `list_files` with `recursive`, the Home "recent files" list | Listing | Names and sizes of Locus's private files reach the model or the UI | Low |
| **Bind points** (added in review): the workspace, a project's folder, `files/linux/tmp`, the four fake `/proc` files and the shadow folder are bound into the guest, and proot resolves links in a binding's host path (`realpath2` in `new_binding`, `src/path/binding.c`) | **None from the user.** `rm -rf /workspace/app; ln -s /data/data/dev.locus /workspace/app` (or `rmdir /tmp; ln -s … /tmp`, or `rm /proc/stat; ln -s …/locus.db /proc/stat`); the next command in that project, or any next command for `/tmp` | Locus's whole data directory is bound read-write into Linux: a full escape, no confused deputy needed | Critical |
| Dangling links (added in review): `Workspace.resolve` canonicalises, but a link whose target doesn't exist yet stays as named, and `write_file`, `save_page`, `sftp_download`, helper reports, chat exports and the name pickers (`uniqueFile`, browser downloads) then create the target | The agent writes a file at a name Linux planted (`notes.md -> /data/data/dev.locus/files/agent-home/...`) | A new file with the model's content anywhere Locus can write | High |
| The spill's folders (added in review): `writeReplacing` checked only the last folder, and the output path is chosen before the command runs | The command replaces `/workspace/.locus` with a link | The command's output is written into any app folder, and `.gitignore` is written through a link | Medium |

Already safe: the agent's file tools and the Files tab (every path is canonicalised and must stay inside the workspace), `CodeFiles.walk` (search, outline), the documents provider, the backup archive (skips links), `diskUsage` and the health probe (`Files.walkFileTree` without `FOLLOW_LINKS`).

**The fix.** `FileTrees` (`core/model`) is the one place these operations go through:

- `delete` walks with `Files.walkFileTree` without `FOLLOW_LINKS`, so a link is removed as a link.
- `copy` copies with `NOFOLLOW_LINKS`, so a link is copied as a link with the same target text and nothing it points at is read. Keeping links, rather than dropping them, keeps `node_modules/.bin` and repositories working; every Locus reader canonicalises before it reads.
- `replaceFile`/`writeReplacing` refuse a folder on the way that is a link (`writeReplacing` checks every folder from the workspace down), write a new file with `CREATE_NEW` (`O_EXCL`, which never follows a link), and `rename(2)` it over the name. The rename replaces a planted link rather than writing through it.
- `copy` opens each regular file with `NOFOLLOW_LINKS` (`O_NOFOLLOW`; `Files.copy` doesn't) and creates the copy with `CREATE_NEW`.
- `Workspace.resolve` refuses a path that still goes through a link after canonicalising: that is a dangling link, which a write would follow. Name pickers count a dangling link as a taken name.
- `BindSources` (`core/linux`) runs before every proot launch: a bound folder Linux replaced with a link is removed and made a real folder again (`/tmp`, the workspace, the shadow), a fake `/proc` file that isn't a plain file is written again, and a project's folder must resolve inside the workspace; proot gets the resolved path.

Every site in the table uses them. The listings don't enter linked folders. If a managed file's folder is a link (Linux replaced `/etc` with one), Repair and Reset DNS stop with "Linux's own files are damaged or missing. Reinstall it to fix this." on the health screen, the same message a missing `busybox` gives, and reinstalling is now safe.

**Still open (same-uid races).** Every check above is a check-then-use on paths, against programs running as the same uid at the same time; Java has no `openat`/`unlinkat`. What a running Linux program can still win by swapping a folder for a link at the right moment:

- a write: `write_file` and the other file tools resolve the path once and then write, and `replaceFile` checks its folders and then writes;
- a delete: `FileTrees.delete` lists a folder by path, so a folder swapped for a link between the walk's look at it and its listing has the target's entries deleted. The trash is safe (its trees are renamed out of Linux's reach first, then deleted) and restore runs before Linux starts, but removing or reinstalling Linux while a Linux process survives isn't;
- a copy: a folder swapped the same way is entered (`copy_path`), so its files are copied into the workspace;
- a bind: the folder checked by `BindSources` could be swapped between the check and proot's own `realpath`.

Each needs a program already running in Linux that retries the swap in a loop. `android.system.Os.open` with `O_NOFOLLOW` (API 21) could close the final-name case for the file tools; the rest needs a second uid (see the plan below).

**Hard links.** A hard link to one of Locus's files would be read and copied as the file itself, and Android's `protected_hardlinks` doesn't stop it (the files have the same owner, on the same filesystem). Linux can't make one, though: proot translates `linkat`'s source as a guest path (and resolves a source link itself with `AT_SYMLINK_FOLLOW`), so it can't name Locus's files. That holds only as long as nothing else lets Linux name them.

**How it was verified.** JVM tests with real symlinks: `FileTreesTest` shows that `File.deleteRecursively` deletes through a link (the reason for all this), and that `FileTrees` doesn't; `WorkspaceToolsTest` covers `copy_path`, trash purge, `list_files` and a dangling link at a `write_file` name; `BindSourcesTest` covers linked bind points; `PendingRestoreTest` covers a restore with a link in the old workspace. The proot behaviour that makes the links possible is in its source (above). It hasn't been reproduced on the phone, because the phone is in daily use. To reproduce on a debug build with a throwaway install: in the terminal, `ln -s /data/user/0/dev.locus.debug/databases /root/x`, then Remove Linux. Before this change `databases/` is gone; after it, only the link is.

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

## Linux under its own uid: the options (researched 2026-11-03)

Locus's minSdk is 26 and targetSdk 37 (`build-logic/.../KotlinAndroid.kt`). Each option is judged on security gain, the devices it works on, what it costs users, the effort, and F-Droid and reproducible builds (docs/FDROID.md).

**(a) An `android:isolatedProcess` service.** It runs under a fresh uid from the isolated range (99000–99999) in the `isolated_app` SELinux domain, "isolated from the rest of the system and has no permissions of its own"; the only way in is binding to the service ([`<service>` reference](https://developer.android.com/guide/topics/manifest/service-element)). AOSP's policy ([`isolated_app_all.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app_all.te), [`isolated_app.te`](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/isolated_app.te)) answers the three questions:
- **Files:** `neverallow isolated_app_all app_data_file_type:file open` ("Isolated apps should not directly open app data files themselves"). It may read, write and `map` app-data fds *passed to it*, but proot opens thousands of rootfs paths by name, so the rootfs can't live anywhere it may open. In practice the rootfs would have to be one image file passed as an fd and served by a userspace filesystem: a different runtime, not proot.
- **Sockets:** creating any socket family other than `AF_UNIX` is a neverallow. It may use TCP/UDP sockets handed to it over IPC. So no network unless Locus proxies every connection: no `apk add`, `npm install`, `git clone` or dev servers.
- **Exec and ptrace:** `self:process ptrace` is allowed (for Chrome's crash reporter), but whether it may `execve` the APK's `libproot.so` and `mmap(PROT_EXEC)` the guest's binaries is unverified. Chrome's renderers are zygote-forked, not exec'd.
- **Shared isolated processes:** `allowSharedIsolatedProcess` and `Context.BIND_SHARED_ISOLATED_PROCESS` (API 34) let several isolated services share one process ([`R.attr`](https://developer.android.com/reference/android/R.attr#allowSharedIsolatedProcess), [`Context`](https://developer.android.com/reference/android/content/Context#BIND_SHARED_ISOLATED_PROCESS)). That only saves memory; the limits above still apply, and it doesn't exist below Android 14. `bindIsolatedService` (API 29) can start several instances from one declaration.
- **Verdict:** strong separation, but it costs the network and the filesystem, which are the reason Linux exists. Out.

**(b) A companion APK with its own uid** (e.g. `dev.locus.linux`) that hosts proot, the rootfs, services and terminals. Locus talks to it over a bound service (commands, PTYs as passed fds, service control) protected by a permission, and checks the caller's signing certificate.
- **Security gain:** real. A Linux program runs as the companion's uid and can't open `databases/`, the vault or agent-home whatever proot, `/proc` or a link does. The Keystore separates the two too. What stays shared is what Locus chooses to hand over: the workspace, and secrets for local MCP servers.
- **Devices:** all (API 26+). The same phantom-process limits apply (Android 12+); the companion would hold the foreground service instead of Locus.
- **The workspace is the hard part.** Either the companion owns `/workspace` and Locus's file tools, Files tab and documents provider read it over IPC (a `ContentProvider` with `openFile`, so Locus's own file tools become remote calls), or Locus owns it and the companion's Linux reaches it through a FUSE-like bridge, which proot can't do. The first is a large refactor of `Workspace` and every file tool.
- **Permissions:** a `signature` permission needs both APKs signed with the same key. F-Droid signs each app with its own key unless the build is reproducible and the developer's signature is used, so a signature permission works across F-Droid, GitHub and IzzyOnDroid only once the builds are reproducible (docs/STATE.md: not verified yet). `knownCerts` permissions (API 31) or a runtime `PackageManager` certificate check against a pinned list would accept both keys on older devices too.
- **UX cost:** a second install on first Linux setup (an extra system install dialog, "install unknown apps" for GitHub installs), two app entries in Settings, version skew between the two (the IPC protocol must be versioned), and two battery and notification settings.
- **Distribution:** a second F-Droid recipe and listing (proot moves into it; Locus loses its native code, which simplifies Locus's reproducibility), a second IzzyOnDroid entry, and a second Play listing whose Device and Network Abuse review (downloaded executables) is the companion's alone.
- **Effort:** L–XL. A versioned IPC for run/stream/kill, PTYs and services, moving `LinuxEnvironment` and `core/terminal` across, a remote workspace, migrating existing rootfs and workspace data between uids (the old uid can't hand its files over except by copying through IPC), and tests for every version pairing.
- **Prior art:** Termux's add-ons do the opposite. They share `com.termux`'s `sharedUserId`, which is why every add-on must be signed with the same key as Termux.

**(c) `sharedUserId`.** Deprecated since API 29: "Shared user IDs cause non-deterministic behavior within the package manager … Existing apps can't remove this value, as migrating off a shared user ID isn't supported" ([`<manifest>` reference](https://developer.android.com/guide/topics/manifest/manifest-element)). It also does the opposite of what we need: it puts two apps *into* one uid. Adding it to Locus would be permanent. Out.

**(d) Hardening in the app's uid.** This is what layers 1–4 are. Checked against the list in the brief:
- *Never bind app-private dirs*: done; only the rootfs, `tmp`, the workspace and the fakes, all under Linux's own trees (threat model above).
- *Minimal environment*: done; cleared and fixed (above).
- *Fd hygiene*: done by the platform (`ProcessBuilder`, `termux.c`); check 6 of the verify script now confirms it on the phone.
- *`/proc/self`*: shows only the guest's own process; Locus's `/proc/<pid>` is closed by layers 1 and 2.
- *Secrets out of reach*: the vault's keyset is Keystore-wrapped and binder is hidden, so a copy of the file is useless; the remaining plaintext (chats, settings, agent-home) is unnameable, and layer 4 closes the link route to it.
- *Links*: layer 4, new.
- *A seccomp filter*: Android already installs the app seccomp filter on every app process, children included, and proot installs its own filter to speed up tracing. An extra deny-list (for example `process_vm_readv` and `ptrace` attach to non-children) duplicates what layer 1 already enforces in the kernel, and would mean patching proot (the filter must be installed between `fork` and `execve`) in the reproducible build. Low gain; not now.

**(e) The Android Virtualization Framework** (pKVM, Microdroid, the Debian VM behind Android 15/16's Terminal app). The API is `android.system.virtualmachine`, gated by `MANAGE_VIRTUAL_MACHINE`, a `@SystemApi` permission whose protection level is `signature|preinstalled|development` in [AOSP's manifest](https://android.googlesource.com/platform/packages/modules/Virtualization/+/750574560/android/android.system.virtualmachine.res/AndroidManifestNext.xml), and `USE_CUSTOM_VIRTUAL_MACHINE` is "not for use by third-party applications". AVF itself is "supported only on ARM64 devices" and is optional ([source.android.com](https://source.android.com/docs/core/virtualization)). The Terminal app is a Google system app, on Pixels (7 and later) behind a developer option. No public API reaches it. Out until Google publishes one; it would then be the best option on the devices that have it.

| Option | Security gain | Devices | UX cost | Effort | F-Droid / reproducible |
|---|---|---|---|---|---|
| (a) isolatedProcess | Strong | 26+ (shared: 34+) | Linux loses network and its filesystem | XL (new runtime) | Neutral |
| (b) Companion APK | Strong: kernel and Keystore separation | 26+ | Second install, two entries, version skew | L–XL | Second recipe; signature permission needs reproducible builds or a cert check |
| (c) sharedUserId | Negative | — | — | — | — |
| (d) In-uid hardening | Moderate: closes known routes, not proot or kernel bugs | 26+ | None | S per item | Neutral (a proot patch would touch the reproducible build) |
| (e) AVF | Strongest | Some ARM64 devices, system apps only | — | — | — |

## Recommendation and plan

Stay in one uid, keep hardening it, and don't build the companion APK yet. The routes we know of are closed (layers 1–4), apart from the same-uid races listed under layer 4. What a second uid would add is protection from routes we *don't* know of: proot or kernel bugs, and the same-uid races. Paying for that with a second install for every Linux user, a remote workspace and a two-key signing problem that isn't solved until the builds are reproducible is premature. Reconsider when one of these happens: Linux becomes a default rather than opt-in, Locus starts running code it fetched without the user asking (for example, auto-installed MCP servers), the F-Droid build is verified reproducible, or a third-party AVF API appears.

1. **Now (this change):** layer 4, links. `FileTrees`, and every delete, copy and write on a tree Linux can write goes through it; fd and environment checks in `tools/verify-linux-isolation.sh`.
2. **Next (S each, in-uid):**
   - Seal what instructs the agent: active skills, the persona and helper definitions, the same way connectors are sealed (`ConnectorSeal`). They were the most valuable target of the spill bug; a seal turns any future write route into a visible reset.
   - Open workspace files for writing with `Os.open(…, O_NOFOLLOW)` in the file tools, closing the final-name race of `write_file`/`edit_file`.
   - Give local MCP servers their secrets through a pipe or a file only Locus and that server can read, instead of the environment that every Linux process can read through `/proc/<pid>/environ`. This needs a launcher inside the guest, so it's an M if the MCP spec's env convention has to be kept.
3. **Later (L–XL, if the triggers above happen):** a companion-APK spike. A bound-service prototype that runs `run_command` and one PTY in `dev.locus.linux`, with a `knownCerts` or runtime certificate check, and a measured answer to the workspace question (remote `ContentProvider` versus companion-owned files), before any migration work.
4. **Watch:** AVF's permission level in each Android release.

## Checking it on a phone

`tools/verify-linux-isolation.sh` runs inside Locus's Linux. It finds Locus's pid through proot's parent, then checks the following. `/proc/<pid>` is empty. `fd`, `cwd`, `root`, `exe`, `map_files`, `mem`, `environ`, `maps` and `task/<pid>/fd` don't resolve. No id in the following 1,500 (threads started later) exposes Locus's database, settings, keyset or agent-home, or a Locus thread's memory. The binder nodes aren't devices. The shell and proot hold no descriptor to Locus's files, sockets or binder, and neither environment has a credential-looking name. `/proc/self/fd`, `/proc/cpuinfo`, `ps` and `/tmp` still work. The kernel layer can also be checked from the host, as the app's uid (debug build):

```sh
adb shell 'pid=$(pidof dev.locus.debug); run-as dev.locus.debug ls /proc/$pid/fd; run-as dev.locus.debug stat -c %U /proc/$pid/fd'
```

Expect `Permission denied` and `root`. Before this change, the listing showed the open files and the owner was the app's user.
