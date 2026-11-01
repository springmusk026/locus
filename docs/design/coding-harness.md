# Coding harness

Locus could already write code. It had file tools, a Linux shell and background services. On real projects it still stumbled: an edit failed with nothing more than "not found", it had no way to find files by name, search skipped nothing and gave no context, and nothing told it how a project builds or tests. This note sets out what good coding harnesses give their agents, what Locus was missing, and what we added.

## What the leading harnesses do

Sources were read in November 2026.

### Claude Code ([tools reference](https://code.claude.com/docs/en/tools-reference), [best practices](https://code.claude.com/docs/en/best-practices))

- **Read** returns numbered lines and pages through big files with `offset`/`limit`. It caps output and says so when it shows only part of a file. An offset past the end of the file returns the line count.
- **Edit** replaces an exact string, with no regex or fuzzy matching. The string must be unique unless `replace_all` is set, and the file must have been read first.
- **Grep** runs on ripgrep. It has three output modes (`files_with_matches`, `content`, `count`), takes `glob`/`type` filters and context lines, and respects `.gitignore`.
- **Glob** matches file names.
- **Bash** can run in the background, and its long output is saved to a file.
- The best-practices page puts it this way: *give Claude a check to run* (tests, a type checker, a linter), and keep the exact commands in CLAUDE.md.

### Anthropic's other guidance

- **[Text editor tool](https://platform.claude.com/docs/en/agents-and-tools/tool-use/text-editor-tool)** offers `view` with `view_range`, `str_replace`, `create` and `insert`. It returns errors you can act on, such as "Found 3 matches… provide more context" and "No match found".
- **[Building effective agents](https://www.anthropic.com/research/building-effective-agents)** makes three points:
  - Its SWE-bench agent spent more effort on the tools than on the prompt.
  - Design arguments so they are hard to get wrong ("poka-yoke").
  - Avoid formats that need line counts written in advance.
- **[Writing tools for agents](https://www.anthropic.com/engineering/writing-tools-for-agents)** recommends pagination, filtering and truncation with sensible defaults, and error messages that say what to fix.

### OpenAI Codex CLI ([github.com/openai/codex](https://github.com/openai/codex))

- **`apply_patch`** uses a freeform envelope (`*** Begin Patch` / `*** Update File:` / `@@` hunks). Its matcher tries an exact match first, then ignores trailing whitespace, then ignores all surrounding whitespace (`codex-rs/apply-patch/src/seek_sequence.rs`).
- **The prompts** (`gpt_5_1_prompt.md`, `gpt_5_codex_prompt.md`) say to prefer `rg`, fix the root cause, keep changes minimal and in the codebase's style, and leave unrelated bugs alone. They say to test the narrowest thing first and then widen. The agent must never revert the user's changes or run `git reset --hard`.
- **`AGENTS.md`** holds per-directory instructions, and deeper files take precedence.

### opencode ([tools](https://opencode.ai/docs/tools/), `packages/opencode/src/tool/edit.ts`)

- The tools are read, edit, write, grep, glob (both grep and glob use ripgrep), apply_patch, bash, todowrite, and an experimental lsp.
- The edit tool falls back through a series of more lenient matchers. One handles indentation, one whitespace and one escapes. (The rewrite in `packages/core/src/tool/plugin/edit.ts`, checked again in November 2026, keeps fewer: exact, then typographic look-alikes such as curly quotes and dashes, then lines compared without trailing whitespace. More than one match fails unless `replaceAll` is set. A miss shows the closest lines.)
- When an edit misses, it shows the nearest numbered lines.
- It used to append LSP diagnostics to every edit result.

### Aider ([repo map](https://aider.chat/2023/10/22/repomap.html), [unified diffs](https://aider.chat/docs/unified-diffs.html), [lint and test](https://aider.chat/docs/usage/lint-test.html))

- **Repo map.** tree-sitter extracts definitions and references, and PageRank ranks them. The map has a budget of about 1k tokens and goes with every request.
- **Flexible patching.** Turning it off produced 9× more edit errors. GPT handles line numbers badly.
- **Lint and test loop.** Aider lints automatically after every edit. With `--test-cmd`/`--auto-test`, any non-zero exit goes back to the model to fix.

### OpenHands and SWE-agent ([SWE-agent ACI paper, arXiv 2405.15793](https://arxiv.org/html/2405.15793))

- **OpenHands `str_replace_editor`.** `view` works like `cat -n`, and `str_replace` must match exactly and uniquely, with 3–5 lines of context recommended. The terminal tool has a soft timeout, and servers are expected to run in the background with a log file.
- **SWE-agent ablations** (SWE-bench Lite, GPT-4 Turbo) all improve on the configurations they replace:

  | Setting | Score | Compared with |
  | --- | --- | --- |
  | Linter guard on edits | 18.0% | 15.0% without it |
  | 100-line viewer window | 18.0% | 12.7% for whole files |
  | Search capped at 50 summarised results | 18.0% | 12.0% for iterative search |

- After a single failed edit, the chance that editing eventually succeeds falls from 90.5% to 57.2%.

## What matters most, ranked

1. **A verification loop.** Run tests, the build or a linter after changing code, and feed the failures back to the model. Aider, Codex, Claude Code and SWE-agent all do this.
2. **Exact, unique-match edits with errors that help.** A failed edit is expensive, so the error has to show the real text.
3. **Checks right after an edit** (lint or LSP), as in SWE-agent, opencode and Aider.
4. **Forgiving matching, or near-miss hints**, as in Codex, opencode and Aider.
5. **Bounded reads** with line numbers and offset/limit.
6. **Capped search** with globs, context lines and output modes, skipping ignored folders.
7. **Arguments that are hard to get wrong.**
8. **Background processes with readable logs.**
9. **Repository context:** a repo map, and instruction files such as AGENTS.md or CLAUDE.md.
10. **Prompt discipline:** read first, change little, follow the house style, verify, and never revert the user's work.

## Locus before this change

| Need | Before |
| --- | --- |
| Read in ranges | `read_file` had `start_line`/`max_lines`, but no cap on line length, no message when the start was past the end, and no hint on where to continue. |
| Exact edit | `edit_file` required a unique match, but a miss said only "not found". It had no `replace_all`, didn't handle CRLF, and didn't show the result. |
| Several edits | None. |
| Find by name | None, only `list_files recursive`. |
| Content search | `search_files` took a regex and a suffix only. It had no context lines, globs or output modes, and walked into `node_modules`/`.git`. It also followed symlinks, which could leave the workspace. |
| Repo map | None. |
| Project detection and toolchain hints | None. The agent had to guess how to build and test, and what to install on Alpine (musl). |
| Background processes | `linux_service_start/stop/list` existed, but showed only the last 1,500 characters of a log, with no filter. |
| Git | Only through `run_command`, which asks every time and fails on "dubious ownership" under proot. |
| Prompt guidance | One line: "Work in small, checkable steps. Read before you edit." |

## What we added

Every tool is in `ToolCatalog`, so every chat declares the same fixed tool list, and none is switched on or off mid-chat. The system prompt is still built once per chat. Existing tool names and arguments are unchanged; only optional arguments were added. Saved chats therefore still render and replay.

| Tool | Risk / default | What it does |
| --- | --- | --- |
| `edit_file` (extended) | WRITE / Ask | Adds `replace_all`. Errors list the line of every occurrence. A miss shows the real lines when only whitespace differs, or else the closest lines, and spots copied `read_file` line-number prefixes. Keeps CRLF line endings. Returns the changed lines with numbers. Refuses binary files and anything over 10 MB. |
| `multi_edit` (new) | WRITE / Ask | Applies several exact edits to one file in order, all or nothing. A failure names the edit and leaves the file unchanged. |
| `read_file` (extended) | READ / Allow | Cuts lines over 2,000 characters, caps output at 100k characters, and gives a "continue with start_line=N" hint. Says when start_line is past the end. |
| `find_files` (new) | READ / Allow | Glob search (`**`, `{a,b}`, `[ab]`; a pattern without `/` matches names at any depth), newest first. |
| `search_files` (extended) | READ / Allow | Adds `glob`, `context`, `output` = content/files/count, `ignore_case`, `literal` and `max_results`. Output is in ripgrep's format (`path:N:` for matches, `path-N-` for context, `--` between groups). |
| `code_outline` (new) | READ / Allow | A light repo map: declarations with line numbers, by per-language patterns, for a file or a folder. Covers Kotlin, Java, C#, Python, JS/TS, Go, Rust, C/C++, Ruby, PHP, Swift, Dart, shell and Markdown. |
| `project_info` (new) | READ / Allow | Detects npm/pnpm/yarn, Python (pip/uv/poetry), Gradle (and whether it's an Android project), Maven, Cargo, Go, CMake, Make, Ruby, PHP, .NET and static sites. Gives the scripts, the Alpine `apk` packages and the build and test commands. Names AGENTS.md, CLAUDE.md and README first, and says whether the folder is a git repository. |
| `linux_service_log` (new) | READ / Allow | The last N lines of a service's log, optionally filtered by regex. Reads from the end of the file, and secrets are scrubbed. |
| `git_inspect` (new) | READ / Allow | status, diff (unstaged, staged or against a ref), log, show, branches and blame, run in Linux. Refs are validated and paths quoted. Git options that could run a program from a cloned repository (`core.fsmonitor`, external diff, textconv) are switched off, and `safe.directory` is set for proot. |

Here is how the safety measures apply across these tools:

- **Ignored folders.** Every walk skips dependency and build folders and `.gitignore`d paths, unless `include_ignored` is set.
- **Symlinks.** No walk follows a symlink, so a link inside the workspace can't expose files outside it.
- **Path escapes.** Paths outside the workspace are refused, as before.
- **Secrets.** Linux output passes through the existing `OutputRedactor`.

The system prompt gains a "Coding:" paragraph. It tells the agent to:

- orient with `project_info` and `code_outline` and follow AGENTS.md;
- read the lines it will change before changing them;
- use only libraries and APIs that exist, and never invent signatures;
- make small edits with `edit_file`/`multi_edit`;
- verify with tests, a build or a linter, and never claim success without a passing check;
- run servers through services and read them with `linux_service_log`;
- review its changes with `git_inspect`, and commit only when asked;
- use the Alpine toolchain packages (checked on pkgs.alpinelinux.org for v3.24: `openjdk21-jdk`, `uv`, `poetry`, `ruby-bundler`, `dotnet8-sdk`).

The Coder helper's instructions and tools follow the same rules.

UI coverage:

- Each new tool has a `toolLabel`, a `toolActivity` and a `ToolCapability`.
- `git_inspect` gets the Linux accent, joins the Linux tool group and appears in the terminal panel. The file tools use the Files accent.

## Second round: the follow-ups

This round worked through the first list of follow-ups. Each one was checked against its source again in November 2026 and against the code as it stands.

### Shipped

**A syntax check after every change.** After `write_file`, `edit_file` or `multi_edit` changes a file, Locus runs a fast checker for that kind of file in Linux. Any errors it finds are added to the end of the tool's result, as SWE-agent's linter guard, Aider's lint step and opencode's diagnostics do.

| File | Check |
| --- | --- |
| `.py` | `python3 -I` compiles the file (`compile()`), without running it or writing `__pycache__` |
| `.js`, `.mjs`, `.cjs` | `node --check`. A "Unexpected token '<'" error is ignored, because that's JSX node can't read, not a mistake. |
| `.ts`, `.tsx`, `.mts`, `.cts` | `tsc --noEmit --noResolve --noLib` on the one file, keeping only grammar errors (`TS1xxx`). It runs only in a project with a `tsconfig.json`, and only with a `tsc` on the system PATH. A single file can't be type-checked without its project. |
| `.go` | `gofmt -e` |
| `.sh`, `.bash` | `sh -n`, or `bash -n` when the shebang names bash |
| `.rb`, `.php` | `ruby -c`, `php -n -l` |

Kotlin and Java are left out: `kotlinc` takes too long on a phone.

- **Optional.** The check is skipped when Linux isn't installed or the checker program isn't there, and nothing is ever installed for it. It is also skipped for any other kind of file.
- **Time-boxed.** A check gets 15 s (25 s for `tsc`) in Linux, and 25 s in all. A timeout, a crash or any other error is simply left out of the result, so the edit itself never fails because of the check.
- **No code from the project runs.** Checkers come only from the system PATH, never `node_modules/.bin`. They run from `/`, so a `php.ini` or Python module in the project folder isn't picked up, and Python runs in isolated mode.
- **Wiring.** `core/agent` defines a `SyntaxCheck` interface. `core/linux`, which depends on `core/agent`, implements it as `LinuxSyntaxCheck`. `ToolCatalog` passes it to `workspaceTools`, the same way it adds the Linux tools, so the modules don't depend on each other in a cycle.

**Read before edit, per chat.** `FileReads` records which files each chat has read, with their time, size and SHA-256. It is keyed by the conversation id that `ToolCallContext` gives every tool call. A helper has its own id, so it reads for itself.

- **What counts as a read.** A `read_file` of any part of the file counts, as do the chat's own writes and edits.
- **Unread files.** An edit or rewrite of an existing file the chat hasn't read is refused, with a message telling the agent to read it first. New files are exempt.
- **Before the user is asked.** A new `Tool.precheck` hook runs before approval, so the user is never asked to allow a change that can only be refused. The tool checks again when it runs, and the audit log records such a refusal as `INVALID_INPUT`.
- **Files changed since the read.** Claude Code's [tools reference](https://code.claude.com/docs/en/tools-reference) asks for the read, and has since relaxed the rule: an edit to a file that changed after the read is still allowed if `old_string` matches the current file exactly and once, and the result says the file has other changes. Locus does the same for `edit_file` and `multi_edit`. On a changed file, only an exact match is accepted (no lenient tiers), and the result says to read the file again before any edit that depends on the lines around it. `write_file` refuses to replace a changed file, because that would throw away the other changes.
- **A touched file.** When the time changed but the contents didn't (a `touch`, or a formatter that changed nothing), it still counts as read.
- **Memory only.** The record lives in memory: up to 64 chats, each with up to 4,096 files. After Locus restarts, the agent reads a file again before changing it.

**Lenient matching.** When `old_text` isn't in the file exactly, `TextEdits` tries three more tiers, in order:

1. Typographic look-alikes. Curly quotes, dashes and special spaces count as their plain forms, as in opencode's `normalizeForMatch` and the last tier of Codex.
2. Whole lines compared without trailing whitespace, as in opencode and Codex's `rstrip`.
3. Whole lines whose indentation differs by the same amount on every line. `new_text` is then re-indented by that amount. Codex's `trim` tier ignores the indentation completely and writes the new lines as given. Locus requires the shift to be uniform and re-indents, so in Python the new code keeps its correct nesting.

A tier applies only when it finds exactly one place, and the result says which tier matched. Otherwise the miss is reported as before. That report now also says when the whitespace-blind match fits several places.

**Long command output.** When `run_command` output goes past 30,000 characters, the agent still gets the start and end. All of the output, up to 8 MB, is also saved to `.locus/out/cmd-<time>-<id>.log`, and the result names that file so the agent can search it with `search_files` or page through it with `read_file`. Claude Code does the same with long Bash output.

- **Secrets.** The raw copy is written to Locus's private storage, which Linux can't see. It goes into the workspace only after `OutputRedactor` has scrubbed it, so secrets don't reach the model this way either.
- **Clean-up.** Locus keeps the newest 20 logs. A `.locus/.gitignore` keeps them out of the user's repository, and the walks skip `.locus`.

**`git_commit`** (WRITE / Ask, capability LOCAL, Linux group and accent, terminal panel).

- **What it does.** It stages the given paths, or all changes, new files included, with `git add -A`, then commits with the message and shows the commit's stat. It never pushes.
- **Same safety as `git_inspect`.** It runs with `safe.directory` and `core.fsmonitor=false`, and adds `core.hooksPath=/dev/null`, `--no-verify` and `commit.gpgSign=false`, so hooks and signing programs from the repository don't run.
- **Paths.** Paths are resolved through the workspace, so they can't escape it, and are then quoted.
- **Filter programs.** One gap remained: a repository's own `.git/config` can name a filter program (`filter.<name>.clean`, `smudge` or `process`), and git runs it when it stages, diffs or checks out files. No `-c` option turns that off. Both `git_commit` and `git_inspect` now refuse such a repository and say why. A filter in the user's global config, such as Git LFS, is still trusted.
- **Missing identity.** If git doesn't know who to commit as, the error tells the agent to ask the user for a name and email.
- **Who can use it.** The Coder helper doesn't get it, because commits happen only when the user asks.

### Skipped, and why

- **A `background` flag on `run_command`.** `linux_service_start` already starts a command in the background under a name, and `linux_service_log` reads its output, so the flag would only add a second way to do the same thing (the opposite of poka-yoke). Instead, when `run_command` times out, it now points to the service tools.
- **`rg` in Linux.** The in-process search skips ignored folders and is quick on projects of phone size. `rg` would add a proot start-up of about half a second to every search, use Rust's regex dialect instead of the Java one the tool describes, and depend on Linux being installed. It isn't worth it until searches are slow in practice.

## Follow-ups (ranked)

1. **A ranked repo map.** Rank symbols by how often they're referenced (PageRank, as Aider does) to fit a token budget, instead of the alphabetical outline of each file.
2. **`git_checkout_file`.** Undo the agent's own edits to a file. The workspace trash already keeps each earlier version.
3. **Project checks after edits.** Run a project's own linter (`eslint`, `ruff`) when the project has one. That means running the project's tools, so it needs Ask or a setting.
4. **Read state that survives a restart**, if re-reading after a restart turns out to cost too much.
5. **ripgrep in Linux**, if searches are slow on large trees in practice.
