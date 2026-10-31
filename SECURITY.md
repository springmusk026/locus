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

## Out of scope

- Problems in the model providers themselves.
- Actions the user explicitly approved, or tools the user set to Allow.
