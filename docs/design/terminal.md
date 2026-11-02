# Interactive terminal

Status: built (Unreleased), device check pending. Item C3 in [product-platform-2026](../research/product-platform-2026.md#c-competitor-features); the roadmap's "Linux: several terminal sessions".

Code: `core/terminal` (Termux's emulator and view, vendored; `TerminalSessions`, `TabRegistry`), `core/linux/.../GuestLaunch.kt` and `LinuxEnvironment.terminalLaunch`, `feature/workspace/.../terminal/` (screen, view client, draft). Tests: `TabRegistryTest`, `LocusTerminalBehaviourTest`, `TerminalCloseGuardTest` and Termux's own emulator suite (core/terminal), `TerminalParityTest` (core/linux), `TerminalDraftTest` (feature/workspace). Native PTY: the phone checklist below.

Before this, the chat's terminal panel was only a log of the agent's commands. Now there's a real terminal: tabs of login shells on a pseudo-terminal, in the same Alpine Linux and /workspace the agent uses.

## Research

Sources read on 2026-11-02.

### Termux's terminal-emulator and terminal-view

- **Licence.** termux-app's [LICENSE.md](https://github.com/termux/termux-app/blob/master/LICENSE.md) says the repository is GPLv3-only, with exceptions: the `terminal-emulator` and `terminal-view` libraries are **Apache-2.0**. They descend from Jack Palevich's Android Terminal Emulator, which was Apache-2.0. The native part (`terminal-emulator/src/main/jni/termux.c`, with `Android.mk`) belongs to the same library. The tag we use carries the same LICENSE.md.
- **AGPL compatibility.** Apache-2.0 code can be combined into a GPLv3/AGPLv3 work; the FSF lists Apache-2.0 as compatible with GPLv3, and AGPLv3 §13 allows combining with GPLv3 works. We keep the Apache notices: `core/terminal/NOTICE` and `core/terminal/LICENSE-APACHE-2.0.txt`.
- **Publishing.** Only through JitPack. The [Termux Libraries wiki page](https://github.com/termux/termux-app/wiki/Termux-Libraries) says that from termux-app 0.116 on, the libraries are "being published on https://jitpack.io" (`com.termux.termux-app:terminal-view:0.118.0`). GitHub Packages was used for 0.109 to 0.114 and has been dropped. They aren't on Maven Central or Google Maven.
- **Maintenance.** Versions follow termux-app's releases. The newest stable release is v0.118.3 (2025-05-22); v0.119.0-beta.3 came out the same day ([releases API](https://api.github.com/repos/termux/termux-app/releases)). It's slow but alive, and it's the emulator behind the most-used Android terminal.
- **F-Droid.** The [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) lists JitPack.io among the trusted Maven repositories, but "those binaries must still be freely licensed, simply being included in one of those repositories is not enough". It also says "all binary dependencies … must originate either from source compilation or Debian repository downloads". The JitPack AAR carries a prebuilt `libtermux.so`. Locus's Gradle setup only allows Google Maven and Maven Central (`settings.gradle.kts`, `FAIL_ON_PROJECT_REPOS`). And for [reproducible builds](https://f-droid.org/docs/Reproducible_Builds/), F-Droid's build has to produce the same bytes as ours. A native library someone else compiled can't be rebuilt that way.

### Alternatives

- **JediTerm** ([JetBrains/jediterm](https://github.com/JetBrains/jediterm)) is dual-licensed LGPLv3 or Apache-2.0, so the licence is fine. But it's built for the desktop JVM: its `terminal` module is "a VT100 compatible terminal emulator and Java Swing based implementation of terminal panel UI". The README names no Maven coordinates, only GitHub releases, and a Maven Central search for `org.jetbrains.jediterm` finds nothing. Using it would mean a Compose renderer and Android input (IME, selection) written from scratch on top of its parser.
- **Our own emulator in Kotlin over Compose.** It's possible, but full-screen programs and coding CLIs (vim, htop, less, tmux, Claude Code, Codex) need a lot of xterm: scroll regions, the alternate screen, 256-colour and truecolour, DECSET modes, bracketed paste, wide characters, reflow on resize, and replies to DSR and DA. Termux's emulator has ten years of fixes for exactly these programs on Android, plus an IME-aware view with selection and pinch-zoom. Writing our own is weeks of work, and it would be less compatible.

### Opening a PTY without root

Android apps may open `/dev/ptmx`. Bionic has `posix_openpt`/`grantpt` since API 21 and `openpty`/`forkpty`/`login_tty` since API 23 ([bionic status](https://android.googlesource.com/platform/bionic/+/master/docs/status.md)). Locus's minSdk is 26. Termux's `termux.c` does it by hand:

1. `open("/dev/ptmx")`, then `grantpt`, `unlockpt` and `ptsname_r`.
2. Turns on `IUTF8`, turns off flow control, and sets the size with `TIOCSWINSZ`.
3. Calls `fork()`. The child calls `setsid()`, opens the slave as fds 0 to 2, closes every other fd, replaces the environment, `chdir`s and `execvp`s.

It also has `setPtyWindowSize` and `waitFor`. That's exactly the `forkpty` pattern, and it needs no root.

Locus already uses NDK r29 (`29.0.14206865`) for `tools/build-proot.sh`. proot is shipped as an executable, so Locus had no JNI until now.

### Decision

**Vendor Termux's terminal-emulator and terminal-view at v0.118.3 as source, in a new `core/terminal` module, and build `libtermux.so` from source with Gradle's ndk-build.**

- **Licence.** Apache-2.0, with its notices kept.
- **F-Droid.** No JitPack and no prebuilt binary. The recipe's existing `ndk: 29.0.14206865` builds the library like the rest of the source. The module pins `ndkVersion` to that NDK and passes `-ffile-prefix-map=<root>=.`, so the checkout path doesn't end up in the library. Termux's flags add `-Os`, and `Android.mk` adds 16 KB page alignment as `build-proot.sh` does. The new `.so` has to be in the Linux-vs-macOS reproducibility check that docs/FDROID.md already lists for proot.
- **Maintenance.** We own the copy. Upstream fixes are a diff away (NOTICE has the commit), and Termux's own unit tests come along and run in CI.
- **Changes to the vendored code are minimal.** Two `import ...R` lines point at this module's R class, `Android.mk` has linker flags, and `TerminalSession.finishIfRunning` kills only a started shell (pid > 0). Every change is marked `Locus:`.

## How it works

- **Launch, with the same isolation.** `LinuxEnvironment.terminalLaunch()` builds the command line with `GuestLaunch`, the same object that now builds `run_command`, services, git tools and local MCP servers. Every one gets `ProotArgs.build(...)`:
  - `--kill-on-exit`;
  - an empty directory bound over `/proc/<pid>` of every Locus process and thread (#78);
  - the binder devices shadowed;
  - the fake `/proc` files;
  - /tmp, and /workspace.

  Only the program after proot's options differs. Agent commands run `/bin/sh -c <command>`; the terminal runs `/bin/bash -l` if the user installed bash, otherwise `/bin/sh -l`. The environment is the agent's (proot's loader and library paths, HOME, PATH, TZ, LANG), plus `TERM=xterm-256color` and `COLORTERM=truecolor`. `TerminalParityTest` checks the options are identical. The terminal mounts the whole workspace, as local MCP servers do, because it belongs to no chat or project. Locus's processes are looked up when each tab starts. A Locus process started later isn't in that tab's hidden list, but the non-dumpable flag ([linux-isolation](linux-isolation.md), layer 1) still keeps its files and memory closed.
- **The PTY.** `TerminalSession` passes proot's path, argv and environment to `JNI.createSubprocess`. Reader, writer and waiter threads move the bytes, and the emulator runs on the main thread. The process starts when the view first knows its size, so the shell sees the real rows and columns from the start. A resize goes through `TIOCSWINSZ` (`setPtyWindowSize`) and reflows the emulator.
- **Sessions.** `TerminalSessions` is an app-wide singleton, and `TabRegistry` holds the tabs: at most 6, because Android 12+ limits an app's child processes (B3 in the research). Leaving the Terminal screen keeps every shell running. Coming back re-attaches the view, with the scrollback intact (5,000 lines per tab). When a shell exits, its tab shows Termux's "[Process completed (code n) - press Enter]"; Enter or the close button removes it. Closing a tab kills proot, but only once its shell has started (pid > 0): a tab whose view never got a size has pid 0, and killing pid 0 would kill Locus's own process group, a running agent task included. The shell and its jobs then die through `--kill-on-exit`, ptrace's exit-kill, and the hang-up when the PTY closes.
- **App exit and process death.**
  - Closing the app (back out of home, so `MainActivity` finishes) calls `closeAll()`.
  - If Android kills Locus's process, the shells go with it. Android kills an app's processes as a group, and proot's `--kill-on-exit` ends the guest programs once proot is gone. This hasn't been checked on a phone yet. Sessions are not restored on the next start: nothing about them is saved, not even the scrollback.
  - While Locus is in the background, Android may freeze or kill its cached processes and their children. Use a Linux service (`linux_service_start`) for long-running jobs.
- **Keyboard.** The view uses a password-style input type (Termux's "enforce char-based input"), so keyboards don't suggest, autocorrect or learn what's typed. The extra-keys row has Esc, Tab, one-shot Ctrl and Alt, the arrows, `|`, `~`, `/`, `-`, Home, End, PgUp, PgDn and Paste. Keys go through the view, so application cursor mode and Ctrl/Alt apply.
- **Other controls.**
  - Pinch, or the menu's Bigger/Smaller text, changes the font size for every tab; it's kept across restarts.
  - Long-press selects text, with Copy, Paste and More….
  - Paste uses bracketed paste when the program asks for it, and strips escape characters.
- **Ask Locus about this.** Select text, then More… → "Ask Locus about this", or the screen menu's "Ask Locus about the selection". This opens a new chat with the selected text in a code block in its composer. Up to 8,000 characters are kept, from the end. Nothing is sent until the user taps Send.

## Security

- **The terminal is the user, not the agent.** Agent tools keep their own `run_command` path through `LinuxEnvironment.run`. Nothing typed in or printed by the terminal reaches the model, Activity, the database or logcat. The session clients drop Termux's log calls, apart from native errors without terminal text. The only way terminal text reaches a chat is "Ask Locus about this", and that is only a draft.
- **No less isolated than agent commands.** The proot options are the same (see above) and the parity test guards them. The terminal does start one thing the agent can't: a process the user controls. It runs with the same uid and the same proot rules, so it can reach the same things `run_command` can.
- **R8.** `libtermux.so` finds `com.termux.terminal.JNI`'s native methods by name. `core/terminal/consumer-rules.pro` keeps them, and `app/r8-kept-names.txt` lists the class, so the release build fails if the name is ever lost.

## Phone checklist

On an arm64 phone with Linux installed:

1. Workspace → terminal icon opens a tab. `echo $TERM` prints `xterm-256color`, `pwd` prints `/workspace`, and `ls` shows the workspace files.
2. `ls /proc` shows no Locus pids. `cat /proc/<Locus pid>/cmdline` fails, and `ls /dev/binder` shows an empty directory or fails (same as `run_command`; use `tools/verify-linux-isolation.sh` for the pids).
3. Keyboard:
   - Typing works, with no suggestions bar.
   - Esc, Tab (completion), Ctrl then C (interrupts `sleep 100`), Alt then B, and the arrows (history) all work.
   - `|`, `~` and `/` type their characters.
   - Ctrl lights up and goes off after one key.
4. Full-screen programs:
   - `apk add vim htop` (the network must be allowed), then `vim` with arrows, Esc and `:q`, and `htop` in colour.
   - Opening and closing the keyboard and rotating the phone resize the screen correctly.
5. Pinch and the menu change the font size, and it's kept after restarting the app.
6. Selection:
   - Long-press, then Copy: the clipboard has the text.
   - Paste (extra key and menu) pastes it.
   - More… → "Ask Locus about this" opens a new chat with the text in the composer, unsent.
7. Tabs:
   - Open three tabs and switch between them; each keeps its own scrollback (`seq 1 3000`, then scroll up).
   - Open six, and New is disabled.
   - `exit` in a tab shows "Process completed", and Enter closes it.
8. Leave the screen while `sleep 1000` runs, open a chat, and come back: the tab is still running.
9. In a chat that ran a command, open the terminal panel. "Open terminal" opens the Terminal screen.
10. Closing a tab with `sleep 1000 &` running leaves no `sleep` behind (check with `ps` in another tab).
11. Back out of the app from home, then reopen it: no tabs, no leftover proot processes. Also: with a tab open, swipe Locus away in Recents. On reopening there are no tabs. Then `ps` in a new tab shows no old shells.
12. Release build: the terminal opens (the JNI names survived R8).
