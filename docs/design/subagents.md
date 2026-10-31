# Sub-agents: design

Status: designed 2026-10-31; built 2026-10-31 (code and unit tests in `SubagentsTest`; not yet device-tested). See "Implementation" at the end.

Sources reviewed: Claude Code sub-agents and prompt-caching docs; the Claude Agent SDK sub-agents docs; Anthropic's multi-agent research system post and the Opus 5 prompting guide (controlling sub-agent spawning); the OpenAI Agents SDK (agents-as-tools, handoffs); Codex sub-agents; OpenClaw `sessions_spawn`.

## Facts in our code that shape this
- Every tool is always declared, and a denied tool refuses at run time. `reflect()` already runs a sub-loop with the full tool list and DENY policies, so children can keep the parent's exact tools block, which keeps cache prefixes identical.
- `executeAll` runs a turn's tool calls one at a time (approvals arrive in order). Parallelism therefore comes from background spawns plus a wait tool, not from parallel tool calls.
- `RunState.pendingApproval` is a single request, which has to become a labelled queue.
- `AgentRunner.jobs` is keyed by conversation, and `recoverInterrupted()` scans every conversation.

## Tools (in the fixed registry)
- `agent_spawn(type, task, context = "fresh"|"fork", model = "same"|"helper", background = false)`
  - Returns `{agentId, status: completed|partial|failed|cancelled, report}`.
  - The report is capped at about 6k chars and wrapped as `<subagent_report agent="…">`, untrusted.
  - `task` must state the objective and the expected output format (enforced in the schema description).
  - `fork` reuses the parent transcript plus one instruction message (a full cache hit) and forces `model: same`.
- `agent_wait(ids, timeoutSec)`: how parallel work completes.
- `agent_status(id)`, `agent_cancel(id)`.
- v2: `agent_send(id, message)` to continue a partial or finished child.

## Limits (enforced in code)
- Depth 1: children have `agent_*` set to DENY, and the conversation carries `parentId`.
- At most 3 concurrent children, at most 6 spawns per parent turn, `maxSteps` 25 per child.
- A per-child token budget (summed `usage`) ends the child as `partial`.
- A wall-clock timeout per child, and a per-run spend cap that refuses new spawns.
- The parent's system prompt gets delegation guidance: delegate only large, genuinely independent work; scale the number of children with complexity; never use children to verify; prefer one agent for simple tasks.

## Profiles
- Built-in:
  - `researcher`: web_search, web_read/fetch, memory_read, no WebView.
  - `coder`: workspace and linux.
  - `explorer`: read, list and search only.
  - `ops`: ssh, where read-style commands are allowed and everything else asks. Don't call it read-only in the UI.
  - `browser`: holds the WebView lease.
- User and agent defined: `agent-home/agents/<name>/AGENT.md` (frontmatter: name, description, tools, model inherit|helper|id, maxSteps, effort, background; the body is the system prompt). These are drafts the user approves in Library → Agents. Only the name and description catalogue goes into the parent prompt.
- Definitions can only narrow permissions. Effective policy = min(user policy, profile). Tools outside the profile are DENY, a user Deny always wins, `mustConfirm` still applies, and read-only mode propagates.

## Approvals and shared resources
- The child's ApprovalGate forwards to the parent conversation's queue with `origin {agentId, label}`, shown as "Researcher #2 wants to…" (the notification names it too). A denial goes back to that child only.
- Leases:
  - `BrowserLease` mutex: one agent drives the WebView at a time; others wait or get "browser busy".
  - One concurrent child with write tools, optionally with per-path write locks.
  - At most 2 concurrent proot shells for children.
  - On-device Gemma is serialised and not offered as a child model by default.

## Persistence and UI
- A child is a hidden Conversation row: `parentConversationId`, `parentCallId`, `agentType`, `model`, `status`, `hidden`. It uses the same messages and audit tables, so AgentLoop and TranscriptStore are reused (the SDK's `parent_tool_use_id` pattern).
- Foreground children run inside the parent job; cancelling the parent cancels them. Background children go in `children[parentId]` and stop on parent cancel.
- Recovery skips hidden children for the "Continue?" notice and marks them `interrupted`. The parent's dangling `agent_spawn` gets a synthetic result with the agentId.
- A background child that finishes after the parent turn ended appends a `[sub-agent report]` message and continues the parent (append-only; a setting).
- UI: nested rows in the steps card (label, model, live step count, current tool, tokens, status, Stop); tap to open a read-only transcript. The notification reads "Working · 2 helpers".

## Caching and cost
- Same tools block everywhere. The child's system prompt is fixed per profile (no timestamps or task text; the task is the first user message). On OpenAI, a per-profile `prompt_cache_key`.
- Helper model for cheap research and extraction; child effort defaults to low or medium.
- A fixed report format (findings, evidence paths/URLs, open questions). Large outputs are written to the workspace and passed back as paths.

## Pitfalls to design against
- Over-spawning on simple tasks; vague delegations that duplicate work; parallel writers conflicting.
- Roughly 15× the tokens of a plain chat; stalling on the slowest child (use timeouts).
- The parent paraphrasing a report and losing detail; losing partial output on errors.
- "Completed" not meaning the task is done; children lacking context (the task must carry paths, errors and decisions).
- Prompt injection relayed through child reports; using children to verify.

## Implementation (2026-10-31)
- `core/agent/tools/SubagentEngine.kt`: limits, leases, budget, reports, the child `AgentLoop`. `SubagentProfiles.kt`: built-in profiles, `SubagentPolicy`, `ReadCommands` (ops). `SubagentTools.kt`: `agent_spawn/wait/status/cancel/create`.
- `core/data/agent/SubagentRunner.kt` implements `SubagentBackend` (hidden chats, models, workspace report files); `AgentRunner` owns the approval queue (`RunState.approvals`), late reports and recovery.
- Tools learn their chat and call through `ToolCallContext` (a coroutine context element set by `AgentLoop` around `execute`), which also carries read-only, depth, chat limits and the reviewer (with the parent's trusted user messages) to children.
- Room v6: `conversations` gained `parentConversationId`, `parentCallId`, `agentType`, `agentStatus`, `hidden` (indexed by parent and call). `observeVisible`, search and empty-chat queries skip hidden rows; `observeAll` still includes them for analytics.
- Differences from the design: the writer limit refuses a second writer (instead of queueing); the browser lease covers helpers only (the parent isn't leased); the wall-clock limit (30 min) includes time waiting for approvals; `agent_send` (v2) and per-profile `prompt_cache_key` are not built. User-defined helper types appear in the prompt as `<available_agents>`; the built-in ones are described in the fixed `agent_spawn` description.

