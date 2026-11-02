# Security policy

Locus runs an AI agent that can act on a user's phone, files, servers and accounts, so security reports matter a lot.

## Reporting a vulnerability

Please don't open a public issue. Use GitHub's private reporting instead: **Security → Report a vulnerability** on this repository. Include steps to reproduce, the affected version or commit, and the impact you expect.

You'll get an acknowledgement within a few days. Please give us reasonable time to fix the problem before you disclose it.

## In scope

- Secrets reaching the model, logs, backups or other apps: API keys, OAuth tokens, vault entries.
- Bypassing approvals, tool policies, read-only mode for scheduled tasks, or the sensitive-action checks in the browser.
- Prompt injection that leads to actions the user didn't approve.
- Problems in the OAuth sign-in flows (ChatGPT, MCP), the loopback redirect listener, or the DocumentsProvider.
- Escaping the connector script sandbox.
- Backup files (`.locusbackup`, docs/design/backup.md). In scope:
  - Reading or changing a backup without its password.
  - A restore that accepts a changed, cut-short or crafted file.
  - Paths in a backup that escape the restore folder.
  - Plaintext of a backup reaching shared storage or other apps.
  - Keys or passwords getting into a backup when "Include keys and passwords" is off.
  - Guessing a weak backup password offline is a known limit of password-based encryption, not a vulnerability.

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

Reports in scope: a Linux program reaching Locus's private files, memory or Keystore keys; getting past a seal without the user being told; or reaching Android services through binder.

The long-term fix is to run Linux under a different uid. The design doc compares a companion APK, an isolated process and the Android Virtualization Framework.

## Out of scope

- Problems in the model providers themselves.
- Actions the user explicitly approved, or tools the user set to Allow.
