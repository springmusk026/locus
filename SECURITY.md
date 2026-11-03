# Security policy

Locus runs an AI agent that can act on a user's phone, files, servers and accounts, so security reports matter a lot.

## Reporting a vulnerability

Please don't open a public issue. Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository. Include steps to reproduce, the affected version or commit, and the impact you expect.

You'll get an acknowledgement within a few days. Please give us reasonable time to fix the problem before you disclose it.

## In scope

- Secrets reaching the model, logs, backups or other apps: API keys, OAuth tokens, vault entries.
- Bypassing approvals, tool policies, read-only mode for scheduled tasks, or the sensitive-action checks in the browser.
- Prompt injection that leads to actions the user didn't approve, including getting past taint tracking (below).
- A loaded skill making a call its manifest doesn't declare without the user being asked, or an update that asks for more without showing the difference.
- A helper (sub-agent) doing something the run that started it couldn't.
- Problems in the OAuth sign-in flows (ChatGPT, MCP), the loopback redirect listener, or the DocumentsProvider.
- Escaping the connector script sandbox.
- Backup files (`.locusbackup`, docs/design/backup.md). In scope:
  - Reading or changing a backup without its password.
  - A restore that accepts a changed, cut-short or crafted file.
  - Paths in a backup that escape the restore folder.
  - Plaintext of a backup reaching shared storage or other apps.
  - Keys or passwords getting into a backup when "Include keys and passwords" is off.
  - Guessing a weak backup password offline is a known limit of password-based encryption, not a vulnerability.

## Taint and egress

Once a run has read outside content (a web page, an email, an MCP or connector reply, command or SSH output, a helper's report), these ask the user, and scheduled runs refuse them:

- anything that sends or changes something outside the phone;
- opening or fetching a host the user didn't name in the chat or set up in Locus;
- saving to memory, creating a skill or creating a connector.

The system prompt and tool list don't change; it's a decision at run time. `fetch_url` refuses private and loopback addresses unless the host is set up in Locus, checks every redirect and caps the response. Known limits, documented in docs/design/taint.md: taint is per run, not per value; `run_command` can reach the network by itself and isn't gated by taint; MCP and connector reads and web search queries aren't gated, because the user chose those destinations; and `fetch_url`'s private-address check is pinned to the connection: its HTTP client's own DNS lookup refuses any private answer, so DNS rebinding fails at connect time (docs/design/network.md). The browser is not covered: it opens local pages on purpose.

## Skill manifests

A skill can declare in its SKILL.md frontmatter the tool groups it needs, the network hosts it contacts, its dependencies, its version and a hash of its files. Import shows this in plain words before anything is saved. Once a declared skill is loaded, any call it doesn't declare asks first ("Not declared by skill X"), and scheduled runs refuse it. An update that asks for more needs the new permissions accepted first, and a content change without a new version is flagged. Skills without a manifest keep the user's normal permissions and are marked "Undeclared". The host list isn't enforced on `run_command`'s own network access. See docs/design/skill-manifests.md.

## Linux commands and the app's uid

Locus's Linux system (Alpine under proot) runs as **the app's own Linux user**. proot translates paths; it isn't a sandbox. So Linux commands, packages they install and local MCP servers run with the app's privileges, except where the measures below stand in the way. docs/design/linux-isolation.md has the details.

What's blocked:

- Locus's processes are non-dumpable. The kernel then refuses same-uid access to their `/proc/<pid>` entries: open files, memory, environment, `cwd`/`root`/`exe`. It also refuses `ptrace` and `process_vm_readv`.
- proot additionally hides `/proc/<pid>` of every Locus process and thread with an empty directory, and hides the binder devices. Without binder, Linux programs can't call Android services (the Keystore among them) as Locus.
- Tool policies, the AI reviewer's settings and connector approvals are sealed with an HMAC key kept in the Android Keystore. If they're changed outside Locus, Allow overrides are dropped, the reviewer is turned off, changed connectors go back to drafts, and the user is told.

What isn't, by design:

- `/workspace` and `/tmp` are shared read-write with Linux.
- All Linux processes share one uid, so they can see each other. A local MCP server's environment, its secrets included, is readable by other Linux commands.
- Linux has the app's network access.
- The seal detects changes to permissions; it doesn't encrypt anything.

### Known limitation: one user for Locus and its Linux

The measures above make it hard for a Linux program to reach Locus, but they are not a boundary the kernel enforces between two users. Locus and every Linux program run as the same Android uid, so:

- Anything that gets past proot's path translation, a kernel or proot bug, or a route we haven't thought of (an fd, a socket, a file Locus shares into `/workspace`) acts with Locus's full privileges: its files, its database, its network access.
- Locus's own state files can't be named from Linux today, but nothing below proot stops a same-uid process from opening them. These include settings, the database, agent-home and the Telegram inbox. The seals only detect changes to permissions, connectors and the Telegram inbox; they don't hide anything.
- Races are possible. Locus checks a workspace path before it writes (for example, a remembered folder approval refuses a path through a symbolic link). A Linux program working at the same moment could still swap the path between the check and the write.

Treat Linux as code you chose to run with Locus's rights, and install only what you trust. Running Linux under a separate uid is on the roadmap (docs/ROADMAP.md, "Known limitations").

Reports in scope: a Linux program reaching Locus's private files, memory or Keystore keys; getting past a seal without the user being told; or reaching Android services through binder.

The long-term fix is to run Linux under a different uid. The design doc compares a companion APK, an isolated process and the Android Virtualization Framework.

## Out of scope

- Problems in the model providers themselves.
- Actions the user explicitly approved, or tools the user set to Allow.
