# Scoped remembered approvals

Status: shipped (Unreleased). Code: `core/agent/.../RememberedApprovals.kt` (`ApprovalScopes`, `ApprovalMemory`, `ApprovalAnswer`, `RememberOffer`), `core/agent/.../CommandSafety.kt`, `AgentLoop.authorizeAndRun`, `core/model/.../RememberedApproval.kt`, `PolicySeal` in `core/model/.../Integrity.kt`, `core/data/.../agent/ChatApprovalMemory.kt`, the approval card in `feature/chat/.../ChatScreen.kt`, and Settings → Permissions → Remembered in `feature/settings/.../PermissionsSettings.kt`. Tests: `CommandSafetyTest`, `ApprovalScopesTest`, `RememberedApprovalsTest`, `RememberedApprovalSealTest`, `SealedSettingsStoreTest`, `ChatApprovalMemoryTest`, `RememberedRowsTest`.

## The problem

A tool set to Ask asks every time. In a long task that means the same card again and again: `./gradlew test` after each edit, another file written in `src/`, another page on the same documentation site. People then set the whole tool to Allow, which is far broader than what they meant ("tests are fine", not "any command is fine").

## What others do (researched 2026-11-02)

**Claude Code.** Permission rules have the form `Tool` or `Tool(specifier)`: `Bash(npm run test *)`, `Read(./src/**)`, `Edit(src/**)`, `WebFetch(domain:example.com)` ([permissions](https://code.claude.com/docs/en/permissions)).
- The prompt's "Yes, and don't ask again for …" saves a rule. For Bash and WebFetch it lasts permanently, per repository and per command or domain. For file edits it lasts until the session ends. Saved rules go to `.claude/settings.local.json` in the repository.
- A "don't ask again" option is only offered when the prompt can show everything the rule would allow. Otherwise only a one-time approval is offered.
- Rules are evaluated deny first, then ask, then allow, and the first match decides. Specificity doesn't change the order: a broad deny beats a narrow allow.
- Bash prefix rules match on word boundaries: `Bash(ls *)` matches `ls -la`, not `lsof`. Compound commands (`&&`, `||`, `;`, `|`, `&`, newlines) need every part allowed. Approving one saves a rule per part, never the whole string.
- The docs warn that "Bash permission patterns that try to constrain command arguments are fragile" and "not a security boundary". Their example is `curl` reached through variables, redirects, `sh -c` or full paths. They recommend scoping network access by domain (`WebFetch(domain:…)`) instead.

**OpenAI Codex.** Approval policies (`on-request`, `never`, a granular policy) sit on top of sandbox modes: `read-only`, `workspace-write` (the default, with no network) and `danger-full-access` ([approvals and security](https://learn.chatgpt.com/docs/agent-approvals-security)).
- Remembered commands are `prefix_rule(pattern=[…], decision="allow"|"prompt"|"forbidden")` in `~/.codex/rules/*.rules`. The TUI writes one when you allow-list a command ([rules](https://learn.chatgpt.com/docs/agent-configuration/rules)).
- The most restrictive decision wins: forbidden, then prompt, then allow.
- A shell script made only of plain words joined by `&&`, `||`, `;` or `|` is split, and each command is checked on its own. Anything with redirection, substitution, variables, wildcards or control flow is treated as one opaque command. That way "dangerous commands [can't be] smuggled in alongside safe ones".
- The docs ask you to "review the suggested prefix carefully". They document no expiry.

**How they treat arguments that run a program (researched 2026-11-02, after the #109 review).** A word prefix alone doesn't make a rule safe: many harmless tools have options that run another program.
- Claude Code's own matching table shows `Bash(git * main)` matching `git -c core.fsmonitor=<script> diff main`, and its rule-limits table lists `git -c push.default=current push` and `git 'push'` as forms a `git push` rule doesn't catch ([permissions](https://code.claude.com/docs/en/permissions)).
  - A prefix rule can't auto-approve exec wrappers (`watch`, `setsid`, `ionice`, `flock`) or `find` with `-exec`, `-delete`, `-fprint` or `-files0-from`.
  - Its read-only commands prompt on an unquoted glob for tools with write- or exec-capable flags (`find`, `sort`, `sed`, `git`), "because the glob could expand to a flag like `-delete`".
  - It strips `timeout`, `time`, `nice`, `nohup`, `stdbuf` and bare `xargs` before matching, so a rule for the inner command applies. Locus is stricter and never matches a wrapped command at all.
- Codex's prefix rules (`prefix_rule`) are pure token prefixes with no argument checks ([execpolicy README](https://github.com/openai/codex/blob/ba0d0e1f74ee0f41c27ef24b37ed5fc030b487e7/codex-rs/execpolicy/README.md)); Codex leans on its sandbox for the rest. Its unsandboxed "known safe" list is an allowlist with per-tool refusals ([is_safe_command.rs, rust-v0.100.0](https://github.com/openai/codex/blob/rust-v0.100.0/codex-rs/shell-command/src/command_safety/is_safe_command.rs)):
  - `git`: only `status`, `log`, `diff`, `show` and read-only `branch`. Any `-c`/`--config-env` is refused, because "global config overrides like `-c core.pager=...` can force git to execute arbitrary external commands", as are `--output`, `--ext-diff`, `--textconv`, `--exec` and `--paginate`.
  - `find`: refuses `-exec`, `-execdir`, `-ok`, `-okdir`, `-delete`, `-fls`, `-fprint`, `-fprint0`, `-fprintf`.
  - `rg`: refuses `--pre` and `--hostname-bin` (also as `--opt=value`), and `-z`/`--search-zip`.
  - `sed`: only `sed -n <N>p` / `<M,N>p` on a file. Everything else asks.
  - A quoted word such as `'git status'` is a program named `git status`, not git.

**Browsers and Android.**
- Chrome's one-time permissions are revoked "once you leave the site". Chrome's Safety Check also revokes permissions "from sites you don't visit anymore" ([Google, Sept 2024](https://blog.google/products/chrome/google-chrome-safety-update-september-2024/)). Chromium's threshold looks like 60 days of disuse (`kUnusedSitePermissionsRevocationThreshold`), but we couldn't confirm that figure from an official page.
- Firefox's "Remember this decision" saves a choice per site. Private windows only grant temporary permissions ([Mozilla support](https://support.mozilla.org/en-US/kb/how-manage-your-camera-and-microphone-permissions), [bug 1142123](https://bugzilla.mozilla.org/show_bug.cgi?id=1142123)).
- Android 11 added "Only this time" ([permissions](https://developer.android.com/training/permissions/requesting)). It also resets the runtime permissions of apps left unused "for a few months" ([app hibernation](https://developer.android.com/topic/performance/app-hibernation)).

**Lessons we took:**
1. Deny always wins, and a remembered allow never carves an exception out of a restriction.
2. Remember a scope the user can see on the card, on a stable entity (site, folder, recipient), and don't try to constrain arguments.
3. Command prefixes are word-based. Compound and expanding commands are never remembered. What follows the prefix is checked strictly, because options like `git -c`, pagers and `--to-command` turn a harmless tool into a program launcher.
4. Offer a short tier (this chat) as well as a long one.
5. Long-lived grants expire, and they're listed somewhere the user can revoke them.

## The design

### Choices on the card

When a call asks and can be remembered, the approval card keeps Deny and Allow. Below them it adds "Don't ask again for *scope*", with two buttons:

- **This chat.** The rule applies in this chat only and is deleted with the chat.
- **Always.** The rule applies in every chat of the same project (or every chat outside projects, for one made outside them) for 30 days.

The card and the "Remembered" list both show the scope. Notification actions stay Allow and Deny: a "don't ask again" choice should be made with its scope in view, so it's only on the card.

### Scopes (`ApprovalScopes.derive`)

A rule covers **one tool** and one scope taken from the call's arguments:

| Tools | Scope | Notes |
|---|---|---|
| `fetch_url` | method + origin, e.g. `GET https://api.github.com` | allowing GETs doesn't allow POSTs |
| `web_read`, `browser_open`, `device_open_url` | origin: scheme, host, port | as browsers scope site permissions; never with `user:pass@` in the URL; only http(s) |
| `write_file`, `edit_file`, `multi_edit`, `make_directory`, `copy_path` (destination) | the file's folder, relative to the workspace or project | covers that folder and below; never absolute paths, `~`, `..`, `.git` or `.locus`; a file at the top offers "This chat" only, since the folder would be the whole workspace |
| `device_email`, `device_sms` | exactly one address or number | several recipients are never remembered |
| `run_command` | a prefix of up to three plain words, e.g. `./gradlew test` | only in `/workspace`; never a dangerous command (below) |

Deleting and moving files, `email_send`, deploys, SSH, MCP calls and everything else have no scope and can't be remembered. `email_send` always confirms because it sends real mail from the agent's own mailbox.

### Dangerous commands (`CommandSafety`)

A command is never remembered, and never matched by a rule, when any of these hold:

- **It's more than one plain command.** Locus refuses `; & | \` $ > < ( ) { } \ !` and newlines anywhere, and a leading `VAR=value`. This rules out `curl … | sh`, `make && rm -rf .`, `$(…)` and redirects.
- **It's a command that is never remembered, whatever its arguments:**
  - privilege: `sudo`, `su`, `doas`, `pkexec`;
  - deleting or wiping: `rm`, `shred`, `dd`, `truncate`, `mkfs*`;
  - ownership and modes: `chmod`, `chown`, `mount`;
  - shells and wrappers that run another program: `sh`, `bash`, `busybox`, `eval`, `exec`, `source`, `env`, `xargs`, `nohup`, `nice`, `timeout`, `time`, `stdbuf`, `watch`, `flock`, `strace` and similar;
  - inline-code languages whose program is the first argument: `sed` (its `e`, `w` and `r` commands) and `awk` (`system()`);
  - pagers and editors: `less`, `more`, `man`, `vi`, `vim`, `nano` (`!cmd`, `-P pager`);
  - network clients: `curl`, `wget`, `nc`, `ssh`, `scp`, `rsync` (`-e`);
  - process and system control: `kill`, `pkill`, `reboot`, `crontab`;
  - system package managers: `apk`, `apt`.
- **It's a subcommand that rewrites history, reaches outward, installs or runs a named tool:** `git push`, `git reset`, `git clean`, `git config`, `git rebase`, `git remote`, `git difftool`, `git mergetool`, `git bisect`, `npm publish`, `npm explore`, `pip install`, `cargo publish`, `gh api`, `docker run` and a few more.
- **It's an interpreter without a script.** `python3 -c …`, `node -e …`, `python -m …`, or `npx`, `make` or `python3` on their own.
- **It has a flag that forces or goes global:** `-f`, `--force`, `--hard`, `-g`, `--global`, `--system`, `find -delete`, `--no-preserve-root`.
- **It has an option that runs a program** (`CommandSafety.EXEC_OPTIONS`), in any spelling: `--opt=value`, `--opt value`, an abbreviation getopt and git accept (`--open-files`, `--to-com`), or a letter in a short bundle (`-nO`, `-xI`, tar's dashless `xI`):
  - `git`: `-c`, `--config-env`, `--open-files-in-pager`/`-O`, `--ext-diff`, `--textconv`, `--output`, `--exec`, `--paginate`, `--git-dir`, `--work-tree`, `--template`, `--upload-pack`;
  - `tar`: `--checkpoint-action`, `--to-command`, `--use-compress-program`/`-I`, `--info-script`/`-F`, `--new-volume-script`, `--rsh-command`, `--files-from`/`-T`;
  - `zip`: `-T`, `-TT`, `--unzip-command`; `rg`: `--pre`, `--hostname-bin`, `-z`; `find`: `-exec`, `-ok`, `-fprint*`, `-fls`; `sort`: `--compress-program`;
  - build tools: Gradle `-I`/`--init-script`, `-b`, `-c`, `-p`; npm `--script-shell`, `--node-options`, `--userconfig`, `-c`; `go` `-exec`, `-toolexec`, `-vettool`; `cargo --config`, `-Z`; `make --eval`, `-E`, `-f`; `pytest -p`.
- **An argument points outside the workspace:** an absolute path, `~`, `..`, `=/…` or `host:/…`.

The same checks run when a rule is matched, so a rule for `git log` never covers `git log --output=/etc/x`. The list is deliberately broad: a false "dangerous" only means one more card.

### Matching is strict

A rule is not "anything that starts with these words". `CommandSafety.matches` accepts a command only when:

1. it passes every check above, and contains no quote at all (a quoted word can hold `sh -c '…'` or `--open-files-in-pager="…"`);
2. its first words are exactly the rule's words (`./gradlew test` doesn't cover `./gradlew testDebug`, and `git log` doesn't cover `git -c core.pager=… log`);
3. every word after them is a plain token of letters, digits and `. _ / : + @ , % -`. That means no `=` option values (`-Dfoo=bar`, `--opt=…`), no globs (`*` could expand to a file named `--checkpoint-action=exec=sh`), no `$(`, backticks or other shell syntax;
4. options after the rule's words appear only for tools Locus has vetted (`OPTIONS_VETTED`): those with a known list of program-running options above, plain readers and file tools (`ls`, `cat`, `grep`, `diff`, `cp`…), and interpreters, whose options after the script go to the script. For any other tool, only plain non-option words may follow, so `gcc main.c -wrapper ./x` asks;
5. no word after them names a never-remembered program (`env`, `xargs`, `sh`…).

This is an allowlist for what may follow a rule, with per-tool refusals for the vetted tools' options. It is closer to Codex's unsandboxed safe list than to its plain prefix rules, because Locus's Linux workspace has no per-command sandbox.

### Taint

`run_command` is a local tool, so taint doesn't make it ask by itself ([taint](taint.md)). A remembered command rule therefore gets one more check. Once the run has read outside content (a web page, mail, a Telegram message, an MCP result), a command rule never turns the Ask into an Allow: the user is asked. The card explains it, for example: "You chose to always allow `./gradlew test …`, but this task has read outside content (from web), so Locus asks before running commands that rule covers". The request carries the `TAINTED` flag, so voice and Telegram approvals treat it as needing a tap in the app. No new rule is offered on that card. Folder, site and recipient rules are unchanged: the tools they cover are already gated by taint where they could carry data out.

### Never remembered

A call gets no "don't ask again" choice, and no rule applies to it, when:

- **It must be confirmed.** The tool's `mustConfirm` gave a reason: a payment or other sensitive browser action, a URL carrying a lot of data, sending real email.
- **The reviewer had a say.** It was consulted and didn't clear the call (it said "ask", it was unavailable, or it had no request from the user to check against). A rule's call is still reviewed in "All changes" mode, and if the reviewer doesn't clear it, the user is asked without a remember option.
- **Taint made it ask** ([taint](taint.md)), or the run is tainted and the target came from untrusted content. In a run that has read outside content, a scope is only offered when the user named the target themselves: the host, folder, recipient or command prefix appears in what they typed. A page can't talk Locus into remembering anything.
- **Something else made it ask:** a loaded skill's manifest, or a workflow's declared tools and approval points.
- **It leaves the phone.** For example, `run_command` in a project linked to an SSH machine.
- **Nobody can be asked.** Background and read-only runs, and helpers (sub-agents): their config carries no `ApprovalMemory`.

### Evaluation and precedence

Remembered approvals are a policy-layer decision in `AgentLoop.authorizeAndRun`, made after every deterministic limit. The tool list and system prompt don't change, so cached prefixes stay valid. The order is:

1. The chat's and project's restrictions (`restrict`: mode, plan mode, tools switched off, connectors outside the project). A refusal here ends it.
2. Deny, and read-only (a read-only run denies everything that isn't an observation).
3. Forced confirmations, taint, skills and workflow escalations, which make the call ask.
4. **Remembered approvals.** Only when the policy is exactly Ask and nothing in step 3 applied, `ApprovalMemory.match` may turn the Ask into an Allow. The audit records `REMEMBERED_ALLOWED`, and the Activity screen shows "Remembered approval".
5. The reviewer, for what's left, exactly as for a tool set to Allow.

A rule can't widen a project's restriction. Where the chat's project sets the tool to Ask or Deny itself, no rule applies and none is offered (`ChatApprovalMemory.offers`). Rules are bound to the project they were made in, so one made outside projects never applies inside one, and the reverse.

### Storage and the seal

Both kinds of rule are stored in `AppSettings.rememberedApprovals` and covered by `PolicySeal`, which seals the id, tool, kind, value, project, chat, creation time and expiry of each rule:

- **"Always" rules** have `conversationId = null`.
- **"This chat" rules** have `conversationId` set. They live with the chat: deleting the chat deletes them (`ConversationRepository.delete`), and deleting a project deletes its rules.

They are kept in the sealed settings, not in the chat's database row, because the database is reachable through the same route the seal defends against ([linux isolation](linux-isolation.md)). A Linux program that adds, widens or extends a rule breaks the seal. Locus then falls back to `PolicySeal.restricted`, which keeps no remembered approvals at all, since each one allows something. Settings without rules seal exactly as before, so existing seals still match.

At most 200 rules are kept, oldest dropped first. Remembering the same scope again replaces the earlier rule, which also renews its expiry.

### Expiry

"Always" rules expire **30 days after they're made**. Browsers and Android expire unused grants after months of disuse: Chrome probably after 60 days, Android after "a few months". Claude Code and Codex rules don't expire at all.

We chose a fixed age, not disuse, and the shorter end of that range, for two reasons:
- These rules let an agent act (run, write, open), not just grant a passive capability.
- A fixed age needs no sealed write on every use.

Approving the same scope again renews it. "This chat" rules don't expire; they end with the chat.

### Revoke

Settings → Permissions → Remembered lists every live rule. Each row shows the tool and scope, whether it's for one chat or always, and the days left, with a Revoke button. Rules are read fresh for every call, so a revoke applies to the very next one.

## Not done

- Notification actions only allow once.
- Helpers don't use their parent chat's rules yet.

Backups carry the rules with the rest of the settings. A restore reseals them, as it does the tool policies (`PolicyIntegrity.sealRestored`).
