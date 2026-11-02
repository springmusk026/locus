# Projects: chats with scoped context

Status: designed 2026-11-01; built in the PRs that follow this document. Roadmap: "Projects with scoped context" (P0) under "Recommended next (Nov 2026)", which also covers the older "Workspace AGENTS.md" and part of "Named personas". Research: [product C1](../research/product-platform-2026.md#c-competitor-features).

A project groups chats that share:

- a workspace folder,
- instructions (an AGENTS.md-style file),
- a memory scope,
- a default model and helper model,
- access permissions.

Projects don't replace the global settings. A chat outside any project behaves exactly as before.

## What other apps do (checked 2026-11-01)

| | Claude Projects | ChatGPT Projects | Manus Projects |
|---|---|---|---|
| Holds | Chats, knowledge files, project instructions used in every chat ([1]) | Chats, files, project instructions ([4]) | A "master instruction", knowledge files, skills, recommended connectors, tasks ([6], [7]) |
| Instructions | Per project. The model can't see the project's name or description ([2]) | Override global custom instructions, which aren't used in project chats ([4]) | Edits apply to existing tasks on their next message; file edits only to new tasks ([6], [8]) |
| Memory | The project's own memory plus account memory from chats outside projects; never another project's. A per-project "Use account memory" switch isolates it. Memory can be turned off for a chat only before its first message ([3]) | Default: the project's chats and files plus global memory. "Project-only" sees only the project. Shared projects are always project-only ([4], [5]) | Only the instructions and files; tasks don't draw on earlier tasks ([7]) |
| Cross-chat context | Chats don't see each other unless the content is added to the project's knowledge ([2]) | Project-only chats can reference other chats in the project ([5]) | None |
| Moving chats | "Add to project" moves a chat in, between projects or out; the effect on the chat's context isn't documented ([2]) | "Move to project"; reportedly picks up the project's instructions and files (unverified, search snippet only) | Tasks can be moved in, like into a folder ([6]) |
| Model per project | Not documented | Not documented | Not documented |
| Tools and connectors | Not documented | Not documented | Connectors are recommended per project; each member connects their own ([7]) |
| Execution | Cloud | Cloud | One sandbox VM per task, not per project ([9]) |

Sources:

- [1] https://support.claude.com/en/articles/9517075-what-are-projects
- [2] https://support.claude.com/en/articles/9519177
- [3] https://support.claude.com/en/articles/11817273
- [4] https://help.openai.com/en/articles/10169521-projects-in-chatgpt. help.openai.com refused automated fetches, so this was read through search-engine snippets of the article only (medium confidence).
- [5] https://openai.com/academy/projects (snippet). Whether the memory mode can be changed after creation is disputed. OpenAI's older guidance says no; one third-party guide from August 2026 says yes (unverified).
- [6] https://manus.im/docs/features/projects
- [7] https://manus.im/features/projects
- [8] https://manus.im/en/blog/manus-projects
- [9] https://manus.im/blog/manus-sandbox

What Locus takes from this:

1. **A project is instructions, files and a memory scope.** All three products agree on that core. Claude's and ChatGPT's two memory modes become Locus's two scopes.
2. **Settings are fixed when a chat starts.** Manus already keeps a task's file context as it was. Locus goes further: its system prompt is frozen per chat for prompt caching, so instruction edits apply to new chats only, and the UI says so. Manus applies instruction edits on the next message instead, which would break Locus's cached prefix.
3. **Moving a chat must not rewrite its prompt.** Neither Claude nor ChatGPT documents what a move does to a chat's context. Locus makes it explicit (see "Moving chats").
4. **Per-project model and permissions are Locus's own.** None of the three documents a per-project model. Manus's per-project connectors are the closest precedent. Locus has per-chat models and a policy system, so a project sets defaults and narrows permissions.

## Rules this design keeps

- **The prompt prefix never changes mid-chat.** `ConversationRepository.create` freezes the system prompt. The project block (instructions, the memory index for the chat's scope, the connector catalogue filtered to the project) is frozen with it. The chat also stores a frozen copy of its memory scope (`conversations.projectMemory`) next to `projectId`, the same way `mcpFirst` is stored.
- **The tool list is fixed per chat.** Projects add no tools and remove none. Connectors outside a project's allow-list, tool policies and folder rooting are all enforced at run time, the way `ChatPolicy` already refuses calls.
- **Local-first; secrets never reach the model.** A project stores names and folders only. Connectors still read their credentials from the vault at call time.
- **Taint and egress (#72) still apply.** The project overlay is evaluated before taint. Taint can only raise a decision to Ask, so a project can't switch it off. Writes to project memory are memory writes: in a tainted run they ask, like `memory_save` does today.

## Data

### Room v15

`projects` table (`ProjectEntity`):

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | UUID |
| `name` | TEXT | Shown in the UI; never sent to the model (as in Claude, [2]) |
| `folder` | TEXT | Workspace-relative folder, e.g. `projects/garden-app`. Fixed at creation |
| `providerId`, `model` | TEXT? | Default model for new chats in the project; null = the global default |
| `helperProviderId`, `helperModel` | TEXT? | Helper model for the project's chats; null = the global helper |
| `memoryScope` | TEXT | `PROJECT_AND_GLOBAL` (default) or `PROJECT_ONLY` |
| `createdAt`, `updatedAt` | INTEGER | |

`conversations` gets two columns:

- `projectId TEXT DEFAULT NULL`, not indexed: adding an index makes Room rebuild `conversations`, and dropping that table would cascade into `messages`; a project has few chats, so filtering in memory is fine. It's a plain column with no foreign key. Deleting a project moves its chats out of it (their frozen prompts stay as they are) rather than cascading.
- `projectMemory TEXT DEFAULT NULL`: the memory scope frozen at creation.

Instructions and memory aren't stored in Room. They're files in agent-home, next to the persona files they resemble:

```
agent-home/projects/<id>/AGENTS.md        the project's instructions
agent-home/projects/<id>/memory/          MEMORY.md index + <topic>.md, same layout as global memory
```

They sit outside the workspace on purpose. Like IDENTITY.md and the global AGENTS.md, the agent can't change its own instructions, and Linux can't name the files (docs/design/linux-isolation.md).

The migration is an AutoMigration 14 → 15 (v14 is task receipts, #83). `ProjectsMigrationTest` runs it on a database built from the exported v14 schema and compares the result with a fresh v15 database.

### Workspace folder

The project's folder is a subfolder of the workspace, `projects/<slug>` by default. In a project chat, every workspace path resolves inside that folder:

- **File tools.** `Workspace` resolves paths against the current call's root, which `AgentLoop` sets from the chat's `ChatScope` through a coroutine context element. `..` and symlinks that leave the project folder are refused, exactly as leaving the workspace was before. Browser downloads, `send_file`, the SSH and SFTP tools, site deploys and saved long output (`.locus/out/`) all go through the same `Workspace`, so they're rooted too.
- **Linux.** For a project chat, proot binds the project folder (not the whole workspace) at `/workspace`. The shell and the file tools then see the same paths. Services keep the binding they started with.

**The global workspace isn't reachable from a project chat.** Reasons:

1. **Scoping is the point of a project.** A project chat should see the project, like a Claude or ChatGPT project sees its own files. If everything stayed reachable, the folder would only be a starting directory.
2. **Paths must match between the file tools and Linux.** If the file tools allowed `../other`, the shell would need the whole workspace mounted. Then `/workspace/x` in the shell and `x` in `read_file` would name different files.
3. **Leaks between projects.** Instructions or untrusted content read in one project can't make the agent read or change another project's files.

To bring outside files in, the user copies them into the project folder from the Workspace screen or the Files app, or shares them to Locus. Chats outside projects still see the whole workspace, project folders included.

This is a scoping feature. It isn't a security boundary against code the agent runs. Linux is one trust zone (linux-isolation.md, "What is still shared"): the root filesystem and `/tmp` are shared between projects, and a program could leave files there for another project's shell.

### Memory scope

- **Project and global** (default). The prompt carries the project's memory index and then the global one, each held to its share of `PromptBudget.memory`. `memory_read` and `memory_search` look in the project first, then in global memory. `memory_save` and `memory_forget` change the project's memory only. A fact that belongs everywhere is moved to global memory by the user from Library → Memory.
- **Project only.** Only the project's memory, in the prompt and in the tools, as in ChatGPT's "project-only" mode ([5]). `<recent_chats>` lists only the project's chats.

Reflection after a task writes into the same scope as the chat. `AgentHome.memory(scope)` returns the store for a scope, and every memory tool asks the call's `ChatScope` which store to use.

The scope is frozen per chat (`conversations.projectMemory`). Changing a project's scope applies to new chats only, like instructions.

### Permissions overlay

A project can set:

- tool policies (Allow / Ask / Deny per tool), and
- an allow-list of connectors and MCP servers (null = every connector the user has).

It's an overlay on the global settings. `ProjectPolicy.effective(global, project, widened)` gives the policy for a tool:

1. With no project override, the global policy applies.
2. A project override can only narrow: the stricter of the two wins (Deny > Ask > Allow).
3. A tool the user explicitly widened for the project (a separate "widened" set, only written by an explicit choice in the project's permission screen behind a confirmation) takes the project's value even when it's looser.

Then the chat's own limits (plan mode, Read-only/Workspace mode, tools switched off), `Tool.mustConfirm`, taint and the reviewer apply as before. None of them can be loosened by a project.

The allow-list is enforced at run time on `mcp_list_tools`, `mcp_call` and `connector_call` (`ToolGroup.CONNECTOR_TOOLS`), with a refusal the model can act on. The `<available_connectors>` catalogue in a project chat's prompt lists only allowed connectors, frozen at creation.

**Sealing.** Since #78, tool policies live in the settings DataStore under an HMAC seal (`PolicySeal`, Keystore key). Project policies are stored there too, as `AppSettings.projectPolicies`, not in Room. Room is plaintext, and anything that could write `locus.db` could otherwise grant itself Allow in a project. With the policies in the sealed store:

- the seal covers `projectPolicies`. It's added to the sealed payload only when it isn't empty, so seals made before this change still match;
- a broken seal falls back to `PolicySeal.restricted`, which now also drops every project Allow override and every widening;
- a restore seals the restored settings with this phone's key, as before.

## Prompt

A project chat's system prompt adds one block before the user's custom instructions, frozen at creation:

```
<project>
This chat belongs to one of the user's projects. Your workspace is the project's folder: all file
paths, and /workspace in Linux, are inside it; files outside it are out of reach.
Memory: <project memory only | the project's memory first, then the user's general memory>.
<project_instructions file="AGENTS.md">
…
</project_instructions>
</project>
```

The project's name isn't sent. `PromptBudget` gets a `project` share. Instructions over budget are cut with a note, like persona files.

## UI

- **Home.** A Projects strip under the launcher row: one pill per project, "New", and "All" to the Projects screen. With no projects yet, a single row explains what a project is and starts one. "All chats" gets a project filter (All · each project · No project) when any project exists.
- **Projects screen.** A list of projects (name, folder, chat count, last activity). Opening one shows its chats and a "New chat" button that starts a chat inside it. A pencil opens the editor.
- **Project editor** (a full screen, built from design-system components):
  - name;
  - folder (pick an existing workspace folder or type a new one; fixed after creation);
  - instructions editor with the note "Changes apply to new chats. Existing chats keep the instructions they started with.";
  - default model and helper model (a provider, or "same as everywhere", plus an optional model id);
  - memory scope (segmented: Project and general / Project only), with the same "new chats" note;
  - permissions: per-tool Ask/Deny overrides (Allow only through the explicit "widen" confirmation) and the connector allow-list;
  - delete, which keeps the chats and files and moves the chats out of the project.
- **Chat header.** A project chip under the title, next to the model chip. Tapping it opens the project.

### Moving chats

A chat's prompt, memory scope and folder are fixed when it starts. Moving a chat with messages into a project would change future system prompts (lose the cache, contradict earlier turns) and its workspace paths, which would break mid-chat. So:

- **A chat with no messages yet** can be moved freely. Its prompt is rebuilt for the new project, which costs nothing because nothing has been cached.
- **A chat with messages** isn't moved. "Continue in a project…" starts a new chat in the chosen project with a draft pointing at the old one ("Continue from our chat “…” (id …)"). The agent reads it with `chat_history_read`. The menu explains why.

## Backups

The database (with `projects` and the new columns), the sealed settings (with `projectPolicies`) and agent-home (with `projects/<id>/`) were already in every backup. The workspace, with project folders, stays optional.

- `BackupManifest` gets `contentVersion` (missing = 1; now 2) and a `projects` count for the restore preview.
- Older backups (content 1, database ≤ 14) restore as before: Room migrates them to v15 with no projects.
- An older app reading a new backup ignores the unknown manifest fields and refuses the v15 database with its existing "made by a newer version" message.
- The encryption header's `format` stays 1, because the encryption hasn't changed.

## Tests

- `ProjectsMigrationTest`: 14 → 15 keeps every chat and its data, adds no project, and matches a fresh v15 schema.
- Per-chat freezing: a project chat's prompt carries the instructions as they were at creation. Editing the instructions or the memory scope afterwards changes new chats only.
- Rooting: `Workspace` under a project scope refuses `..`, absolute paths and symlinks out of the folder, and lists only the folder's files. Without a scope it's unchanged.
- Memory scoping: saves land in the project. Project-and-global reads fall back to global; project-only reads don't. One project never sees another's memory.
- Policy overlay: narrowing wins over global Allow; Allow is ignored unless widened; global Deny can only be overridden by an explicit widen; connectors outside the allow-list are refused.
- Seal: project policies are sealed, a changed policy fails the check, `restricted` drops Allow and widenings, and an old seal without projects still matches.
- Backup round-trip: a backup with a project restores its row, instructions, memory and policies; a content-1 manifest still reads.

## Not done (later)

- Project knowledge beyond the folder (Claude's RAG over project files). Local document search on the roadmap would cover it.
- Per-project skills and helper types.
- Named personas as a separate idea. A project's instructions cover most of it.
- Sharing projects. There's no server, by design.
