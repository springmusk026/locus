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

**Event-triggered tasks.** Some runs are started by an event: a calendar entry, an ntfy message, new mail in the agent's mailbox, or another app's `dev.locus.action.EVENT`. Such a run starts tainted.

- The event's text is marked as untrusted and isn't the user's words.
- Every vault value and anything shaped like a credential is removed from it before the model sees it.
- An ntfy trigger's access token is kept in the vault.
- A trigger can't watch the topic Locus sends results to.

See docs/design/event-triggers.md, "Security model".

**Private data in a link.** A run that has read the user's own data (files, memory, chat history, mail, calendar, a contact, git diffs, deploy settings) but nothing from outside could still send a PIN or a line of a file in a short address such as `https://site.example/4821`. So once a run has read private data, opening a new host with anything in its path, query or fragment asks, whatever its length and in every chat mode, and background runs refuse it. Search engines, Wikipedia, hosts the user named or set up, local servers, bare hosts and hosts the task already opened are unaffected. The card can allow that one host for the rest of the task, never saved. See docs/design/taint.md, "Disclosure".

**Remembered approvals** ("Don't ask again" for a chat or 30 days) only turn an Ask into an Allow for one tool and one site, folder, recipient or command prefix. They never apply to payments, forced confirmations, calls the reviewer flagged, targets from outside content, dangerous commands, or, after outside content was read, commands. A folder rule never covers a path through a symbolic link. Getting a remembered rule to cover more than that is in scope (docs/design/remembered-approvals.md).

The system prompt and tool list don't change; it's a decision at run time. `fetch_url` refuses private and loopback addresses unless the host is set up in Locus, checks every redirect and caps the response. Known limits, documented in docs/design/taint.md: taint is per run, not per value; `run_command` can reach the network by itself and isn't gated by taint; MCP and connector reads and web search queries aren't gated, because the user chose those destinations; and `fetch_url`'s private-address check is pinned to the connection: its HTTP client's own DNS lookup refuses any private answer, so DNS rebinding fails at connect time (docs/design/network.md). The browser is not covered: it opens local pages on purpose.

## Skill manifests

A skill can declare in its SKILL.md frontmatter the tool groups it needs, the network hosts it contacts, its dependencies, its version and a hash of its files. Import shows this in plain words before anything is saved. Once a declared skill is loaded, any call it doesn't declare asks first ("Not declared by skill X"), and scheduled runs refuse it. An update that asks for more needs the new permissions accepted first, and a content change without a new version is flagged. Skills without a manifest keep the user's normal permissions and are marked "Undeclared". The host list isn't enforced on `run_command`'s own network access. See docs/design/skill-manifests.md.

## Update check

The update check is off by default. When the user turns it on (in setup or Settings → About & privacy), Locus makes one unauthenticated request about once a day on Wi-Fi to `https://api.github.com/repos/springmusk026/locus/releases/latest`, with a fixed `User-Agent: Locus-update-check` and no token, cookies, version or device details. GitHub sees the IP address, as for any request. **Check now** makes the same request on demand. The address is a constant in code, not a setting.

Before an update is installed, Locus checks the downloaded APK's SHA-256 against the release's `SHA256SUMS.txt` (or GitHub's asset digest), then checks that it's `dev.locus`, newer than the installed version and signed with the same key. Android's installer still asks the user to confirm, and refuses a different key anyway. The checksums come from the same release as the APK, so they catch corruption and swapped files, not a compromised release account; the signing key is the defence against that. Builds made with `-Plocus.updateCheck=false`, debug builds and installs from an app store don't install updates themselves. See docs/design/updates.md.

In scope: getting Locus to install, or offer to install, an APK that isn't a newer Locus signed with Locus's key, or to send anything about the user or the phone with the check.

## Release builds

How a release APK is made, from 0.1.6 (details in docs/RELEASING.md):

- **The key is kept apart from the build.** One job runs the tests and builds unsigned APKs with read-only access and no secrets. A separate job, in the `release` environment, signs them with `apksigner`, writes `SHA256SUMS.txt` and publishes. It runs no project code, so a compromised dependency or Gradle plugin in the build can't read the key.
- **Dependencies are checked.** Gradle verifies the SHA-256 of every dependency, plugin and build-logic artifact against `gradle/verification-metadata.xml`, in CI and in the release build.
- **Actions are pinned** by commit SHA in every workflow; Dependabot proposes updates.
- **Build provenance.** Each release's APKs and mapping have a signed SLSA provenance attestation. `gh attestation verify <apk> --repo springmusk026/locus --signer-workflow springmusk026/locus/.github/workflows/release.yml` shows the commit and workflow run that built it. The release key's SHA-256 certificate fingerprint is `0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b`.

In scope: a way for code in the build job, a pull request or a workflow on a branch to read the signing key or publish a release.

## Linux commands and the app's uid

Locus's Linux system (Alpine under proot) runs as **the app's own Linux user**. proot translates paths; it isn't a sandbox. So Linux commands, packages they install and local MCP servers run with the app's privileges, except where the measures below stand in the way. docs/design/linux-isolation.md has the details.

What's blocked:

- Locus's processes are non-dumpable. The kernel then refuses same-uid access to their `/proc/<pid>` entries: open files, memory, environment, `cwd`/`root`/`exe`. It also refuses `ptrace` and `process_vm_readv`.
- proot additionally hides `/proc/<pid>` of every Locus process and thread with an empty directory, and hides the binder devices. Without binder, Linux programs can't call Android services (the Keystore among them) as Locus.
- Tool policies, the AI reviewer's settings and connector approvals are sealed with an HMAC key kept in the Android Keystore. If they're changed outside Locus, Allow overrides are dropped, the reviewer is turned off, changed connectors go back to drafts, and the user is told. Remembered approvals, the Telegram chat and remote mode are sealed with them.
- Active skills, helper definitions and the persona files (what tells the model what to do) are sealed with a Keystore key of their own. A skill or helper changed, added or removed outside Locus is taken out of use: the changed version waits in Library as a draft marked "Changed outside Locus", the version the user approved is put back from a kept copy, and the user is told. A changed persona file is held for review in Settings → Agent → Agent identity, and new chats keep the saved version. Nothing changed outside Locus is loaded into a prompt until the user approves it.
- Provider addresses, proxies, imported CA certificates and SSH host keys are sealed with a Keystore key of their own. If they're changed outside Locus, nothing is sent to that provider until the user saves it again, proxies and certificates are removed, and SSH host keys must be confirmed again.
- Locus checks for symbolic links a Linux program left in the rootfs, `/tmp` or `/workspace` before it deletes, copies or writes there (removing Linux, Repair, Reset DNS, a restore, the trash, `copy_path`, the file tools, saved command output), and before it binds a folder into Linux. Such a link can name any of Locus's own files, because proot keeps a link's target as written. Writes into the workspace (the file tools, helper reports, saved artifacts and command output) open the file with `O_NOFOLLOW` and, before writing, check through `/proc/self/fd` that the open file is the intended one, so swapping a folder for a link doesn't redirect them. Deletes, copies and binds are still checks on paths, so a Linux program still running and swapping a folder for a link at the right moment can win those races; the design doc lists where.
- Linux programs get a fixed, cleared environment and no file descriptors beyond stdin, stdout and stderr.

What isn't, by design:

- `/workspace` and `/tmp` are shared read-write with Linux.
- All Linux processes share one uid, so they can see each other. A local MCP server gets its secrets as environment variables, because that's what standard MCP stdio servers read. Its process tree is hidden from every Linux command, terminal and server started after it, and its secrets are scrubbed from command output and from what servers return to the model, but a Linux program started earlier, one that reads its memory by pid with `process_vm_readv`, or one that modifies the server's installed package can still get them.
- Linux has the app's network access.
- The seals detect changes to permissions, connectors, endpoints, skills, helpers and the persona; they don't encrypt anything. Memory, project instructions and custom instructions aren't sealed.

### Known limitation: one user for Locus and its Linux

The measures above make it hard for a Linux program to reach Locus, but they are not a boundary the kernel enforces between two users. Locus and every Linux program run as the same Android uid, so:

- Anything that gets past proot's path translation, a kernel or proot bug, or a route we haven't thought of (an fd, a socket, a file Locus shares into `/workspace`) acts with Locus's full privileges: its files, its database, its network access.
- Locus's own state files can't be named from Linux today, but nothing below proot stops a same-uid process from opening them. These include settings, the database, agent-home and the Telegram inbox. The seals only detect changes to permissions, connectors, provider addresses, the Telegram inbox, skills, helpers and the persona; they don't hide anything.
- Races are possible. Writes into the workspace are race-free against a swapped link on Android (`O_NOFOLLOW` and a check of the open file's real path), but deletes, copies and binds check paths first, and a Linux program working at the same moment could still swap a folder between the check and the use.

Treat Linux as code you chose to run with Locus's rights, and install only what you trust. Running Linux under a separate uid is on the roadmap (docs/ROADMAP.md, "Known limitations").

Reports in scope: a Linux program reaching Locus's private files, memory or Keystore keys; getting past a seal without the user being told; or reaching Android services through binder.

The long-term fix is to run Linux under a different uid. The design doc compares a companion APK, an isolated process, `sharedUserId`, in-uid hardening and the Android Virtualization Framework, and recommends staying in one uid for now, with the conditions that would change that.

## Out of scope

- Problems in the model providers themselves.
- Actions the user explicitly approved, or tools the user set to Allow.
