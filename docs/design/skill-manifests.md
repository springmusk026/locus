# Skill security manifests

Status: shipped (Unreleased). Code: `core/agent/.../home/SkillManifest.kt` (parser, hashes), `SkillScope.kt` (enforcement), `AgentLoop.authorizeAndRun`, `home/SkillReview.kt` (import review, update diff), `home/SkillManifestInference.kt` (`skill_create`), `feature/library/SkillsTab.kt` (UI).

## The problem

Imported skills (#27) were reviewed as text. Nothing said what a skill would reach, and an update looked like any other draft: the user had to diff SKILL.md and every script by eye to see that version 1.1 now runs Linux commands and posts to a new host. While a skill was active, the agent kept the user's full permissions, so a skill's instructions could steer it to anything those permissions allow.

## What others do

- **Agent Skills spec** (https://agentskills.io/specification). Its frontmatter fields are `name`, `description`, `license`, `compatibility` (free text, "required system packages, network access, etc."), `metadata` (a string-to-string map) and `allowed-tools`. `allowed-tools` is "a space-separated string of tools that are pre-approved to run", marked experimental. The spec has no structured field for network access, dependencies or a version (its example puts `version` under `metadata`).
- **Claude Code** (https://code.claude.com/docs/en/skills). `allowed-tools` lists "tools Claude can use without asking permission during the turn that invokes this skill", and "it does not restrict which tools are available". Claude Code has `disallowed-tools` to take tools away. Outside Claude Code (claude.ai uploads, the Skills API), only the six spec fields are accepted.
- **Chrome extensions, Manifest V3** (https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions). The manifest has `permissions` ("items from a list of known strings"), `host_permissions` ("match patterns that give access to one or more hosts"), and optional variants granted at runtime. On update (https://developer.chrome.com/docs/extensions/develop/concepts/permission-warnings), if the new version adds a permission with a warning, "the extension will be disabled until the user accepts the new permission".
- **Android** (https://developer.android.com/guide/topics/permissions/overview). Apps declare permissions in the manifest. Install-time permissions are shown on the store page and granted at install. Runtime (dangerous) permissions must be requested before each use: "Don't assume that these permissions have been previously granted".
- **Lockfiles** (https://docs.npmjs.com/cli/v10/configuring-npm/package-lock-json). `package-lock.json` pins each package by `resolved` ("the place where the package was actually resolved from", a tarball URL or a git URL with the commit) and `integrity` ("a sha512 or sha1 Standard Subresource Integrity string for the artifact that was unpacked").

What Locus takes from these:

- From Chrome: named capabilities plus a separate host list, and the rule that an update which asks for more must be accepted before it replaces the old version.
- From Android: declarations are shown before install.
- From lockfiles: a pinned source plus a content hash, so "same version, different bytes" can be detected.

## The manifest

These are optional top-level fields in SKILL.md's frontmatter, next to the spec's own:

```yaml
---
name: pdf-tools
description: Fill and merge PDFs. Use when the user mentions PDFs.
version: 1.2.0
capabilities: [files.read, files.write, linux]
network:
  - api.example.com
  - "*.githubusercontent.com"
dependencies:
  apk: [poppler-utils]
  pip: [pypdf==4.2.0]
  npm: ["marked@12.0.2"]
  skills: [pdf-basics]
source: https://github.com/acme/skills/tree/v1.2.0/pdf
hash: sha256:<64 hex>
---
```

**`capabilities`** lists tool groups, single tool names (`read_file`), or scoped ids (`mcp:<connector>`, `connector:<id>`, `ssh:<host alias>`).

| Group | Tools |
|---|---|
| `files.read` | list/read/search/find files, `code_outline`, `project_info`, `git_inspect`, `send_file` |
| `files.write` | write/edit/move/copy/delete files, `make_directory`, `git_commit` |
| `linux` | `run_command`, `linux_service_*` |
| `web` | `web_search`, `web_read`, `fetch_url`, `wikipedia`, `weather` |
| `browser` | `browser_*` |
| `ssh` | `ssh_*`, `sftp_*` |
| `memory` | `memory_*` |
| `mcp` | `mcp_call`, `mcp_list_tools` (any server) |
| `connectors` | `connector_call` (any connector) |
| `library` | `skill_create`, `connector_create`, `agent_create` |
| `helpers` | `agent_spawn`, `agent_send` |
| `email`, `github`, `netlify`, `vercel`, `cloudflare`, `supabase`, `render`, `device`, `notify` | the integration's tools |

Any skill may always load skills (`skill_use`, `skill_read_file`), plan (`todo_write`, `plan_propose`), ask the user (`ask_user`), read chat history, and wait on or cancel helpers. A test fails if a new tool has no group and isn't in this baseline.

**`network`** lists hosts, not links:

- `api.example.com` matches that host exactly.
- `*.example.com` matches example.com and its subdomains.
- `*` matches any host. It's allowed, and shown as "Can contact any website".

The list applies where Locus sees the address: `fetch_url`, `web_read`, `browser_open` and `device_open_url`. Linux commands can reach any host, and the review sheet says so whenever a skill declares both `linux` and hosts.

**`dependencies`** has four kinds: `apk` (Alpine), `npm`, `pip` and `skills`. A package without an exact version (`jq` rather than `jq=1.7.1-r0`, or `pypdf>=4` rather than `pypdf==4.2.0`) is marked "not pinned".

**`version`** is a version number such as `1.2.0` (an optional leading `v` is allowed).

**`source`** is an https link to where the author publishes the skill.

**`hash`** is `sha256:` followed by 64 hex digits, computed over every file except SKILL.md (which can't contain its own hash). The input is one line per file, sorted by path: `path NUL sha256(file) LF`. An import whose files don't match the declared hash is refused.

A skill is **declared** when it has any of `capabilities`, `network` or `dependencies`. `capabilities: []` declares a skill that needs no tools. A skill with none of these fields is **undeclared**, and works exactly as before.

`allowed-tools` is shown on the review sheet but never grants anything. In Claude Code it pre-approves tools, and Locus doesn't let a third-party file widen the user's permissions.

**Strict parsing.** Unknown capabilities, links in `network`, ports, unknown dependency kinds, malformed versions, hashes or package names, more than 64 items, and a bare scalar where a list belongs (`capabilities: linux web`) each stop the import with a message that names the field and shows the right form. A manifest on disk that no longer parses (only possible if it was edited outside Locus) counts as declared with nothing. That fails closed, and Library shows the error.

**Interop.** Tools that accept only the spec's six fields (claude.ai uploads, the Skills API) may reject these top-level keys. A skill meant for both should keep its manifest in the copy it ships to Locus. Reading the same fields from `metadata` (where values must be strings) is possible later.

## Enforcement

A skill is **active** in a chat once a `skill_use` call for it has run and returned its content. These results are in the run's history, so a skill loaded in an earlier turn stays active until compaction drops it. Text that merely quotes `<skill_content …>` doesn't count: only `skill_use` results do. Helpers inherit their parent's active skills with the rest of the parent's `RunScope` (`ToolCallContext.run`, see docs/design/workflows.md), because the parent's task for them may be steered by the skill.

For each call, `AgentLoop.authorizeAndRun` asks `SkillScope.reason` whether every active declared skill covers it. Each one must cover the call, not just one of them. All their instructions are in the model's context, so any of them could be steering it. A union would let skill A borrow skill B's grants. If one or more skills don't cover the call:

- **In a chat:** the call goes to Ask. The approval card, and the reviewer's confirm reason, say "Not declared by skill “pdf”: its manifest doesn't include “linux” (run_command)." Deny still wins, and a call already set to Ask just gains the reason.
- **In a read-only background run:** the call is refused with that reason.
- **Undeclared skills** add nothing. A chat with only undeclared skills active behaves exactly as before.

Like taint (docs/design/taint.md), this is a policy decision only. The tool list and the system prompt never change, so the provider's prompt cache holds. A call that loads a skill, or that a loaded skill holds back, never runs in parallel with others, so the order of checks matches the order of calls. The `skill_use` result tells the model what the skill declares, so it can plan around it.

## Pinning and updates

- Every import records its `version` and a content hash of everything imported (SKILL.md included) in `.locus-source.json`, next to the existing source, ref and commit.
- The import sheet runs before anything is saved. It shows:
  - capabilities and hosts in plain words ("Can run Linux commands", "Can contact api.example.com"), or "This skill doesn't declare what it needs; it gets your normal permissions.";
  - dependencies, with unpinned ones marked;
  - where Locus got it and where the author publishes it;
  - the version and the hash.
- If the name is taken by an active skill or a waiting draft, the sheet compares manifests (`SkillManifestDiff`):
  - **Grown:** new capabilities, hosts or dependencies, or the update dropped its manifest (that last is growth: it would get normal permissions). The sheet lists them first ("New: can run Linux commands"), and Replace stays disabled until the user ticks "I accept the new permissions". Approving such a draft in Library asks the same question again.
  - **Shrunk:** removed permissions, or a manifest that appears for the first time. Shown, but nothing needs accepting.
  - **Content changed without a version bump:** flagged as "Changed without a new version".
  - **Different source:** noted.
  - **No change:** "No changes".

## Agent-created skills

`skill_create` drafts get a manifest inferred from their text:

- tool names mentioned (mapped to their groups);
- shell words and bundled scripts (`linux`);
- links (`network`; the host covers fetches, so `web` is dropped unless the text also searches);
- `apk add`, `pip install` and `npm install` lines (dependencies).

The frontmatter carries a comment saying it was inferred, and a `.locus-inferred` marker shows "Inferred manifest: check it" in Library. Instructions that use nothing visible stay undeclared, because an empty manifest would make every call ask while the skill is active. An import can't plant Locus's marker files.

## Not done

- Locus doesn't install dependencies itself. They're shown so the user knows what a skill will `apk add` or `pip install`.
- `run_command` network access isn't confined to the host list. That would need a network namespace or proxy in the Linux sandbox (see docs/design/linux-isolation.md).
- There's no editor for a manifest in Library. To change an inferred one, reject the draft and ask Locus to redraft it, or import an edited copy.
