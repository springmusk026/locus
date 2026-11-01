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
- The edit tool falls back through a series of more lenient matchers. One handles indentation, one whitespace and one escapes.
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

## Follow-ups (ranked)

1. **Check every edit automatically.** After each edit, run a fast syntax check for the file type (`python -m py_compile`, `node --check`, `tsc --noEmit` on the file, `kotlinc` is too heavy) and append the errors to the edit result, as SWE-agent, Aider and opencode do. It needs Linux and a per-language command table.
2. **Enforce read before edit, per chat.** Record which files and versions the chat has read, and refuse edits to files it hasn't read or that changed since it read them. Claude Code does this. It needs per-conversation tool state.
3. **Forgiving matching.** When only whitespace differs, apply the edit after stating the exact text used, rather than just showing the lines (the Codex `seek_sequence` tiers). Today we only hint.
4. **A ranked repo map.** Rank symbols by references (PageRank, as Aider does) to fit a token budget, instead of the alphabetical per-file outline.
5. **Background `run_command`.** Add a `background` flag that turns into a service, and spill output past 30k characters to a workspace file the agent can page through.
6. **More git.** Add a `git_commit` behind Ask, with a message, and a `git_checkout_file` that undoes the agent's own edits.
7. **ripgrep in Linux.** Use `rg` when it's installed, which is faster on very large trees. The in-process search covers projects of phone size.
