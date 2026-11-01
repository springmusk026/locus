# Tool discovery: finding tools when they're needed

Status: shipped (Unreleased). Code: `core/agent/.../ToolDiscovery.kt` (core set, `ToolIndex`, `FindTools`, `CallTool`), `Tool.kt` (`ToolRegistry.deferring`, `resolve`), `AgentLoop.executeAll`, `JsonSchemas.kt`, `core/data/.../mcp/McpDiscovery.kt`, `McpTools.kt`, `ToolCatalog.forChat`, `core/model/.../ToolDiscovery.kt` (`DiscoveryTools`). Research: [agent algorithms #3 and #4](../research/agent-algorithms-2026.md#3-deferred-tool-loading-on-anthropic-and-openai-m-high).

## The problem

Before this change every chat declared every built-in tool on every request: 122 tools, about 18,700 tokens of definitions by the "What was sent" count, before the system prompt or a single message. That has three costs:

- **Context.** On an 8–32k local model the tool list alone can take most of the window. On large models it is still paid on every cache write.
- **Tool choice.** Anthropic's tool search docs say "Claude's ability to pick the right tool degrades once you exceed 30–50 available tools". Its engineering post on advanced tool use reports MCP-eval accuracy going from 49% to 74% (Opus 4) and from 79.5% to 88.1% (Opus 4.5) with tool search, and context from about 77k to 8.7k tokens.
- **Scale.** Each new integration or MCP server makes it worse.

MCP servers already sat behind two fixed meta-tools (`mcp_list_tools`, `mcp_call`), which kept the cache safe but showed the model only `name: type` for each argument: descriptions, enums and nested objects were dropped, and arguments weren't checked before the call ([research #4](../research/agent-algorithms-2026.md#4-full-mcp-schemas-and-argument-checks-s-medium-high)).

## Sources (read 2026-11-01)

- **Anthropic, tool search tool** — https://platform.claude.com/docs/en/agents-and-tools/tool-use/tool-search-tool. Status *stable* (no beta header), on the Claude API, Bedrock InvokeModel (not Converse), Vertex and Foundry, for Claude Opus 4.5 and later, Sonnet 4.5 and later, Haiku 4.5 and later, the Fable and Mythos models (full list in the page's model table). Two variants: `tool_search_tool_regex_20251119` (name `tool_search_tool_regex`) and `tool_search_tool_bm25_20251119` (name `tool_search_tool_bm25`). Every tool is still sent; `defer_loading: true` keeps a tool out of context until a search returns a `tool_reference` to it. Quotes that matter here:
  - "Internally, the API excludes deferred tools from the system-prompt prefix. … The prefix is untouched, so prompt caching is preserved."
  - "At least one tool, normally the tool search tool itself, must stay non-deferred." A request with every tool deferred is a 400.
  - "A tool with `defer_loading: true` can't also carry `cache_control`: the API returns a 400. Put the cache breakpoint on a non-deferred tool."
  - The response carries `server_tool_use` and `tool_search_tool_result` blocks; "pass the assistant's content back unchanged" and never send a `tool_result` for a `srvtoolu_` id.
  - "Keep your 3–5 most frequently used tools non-deferred"; "Add a system prompt section describing available tool categories".
  - A custom client-side search can return `tool_reference` blocks in a normal `tool_result`.
- **OpenAI, tool search** — https://developers.openai.com/api/docs/guides/tools-tool-search (Markdown at the same URL plus `.md`). `{"type": "tool_search"}` in `tools`, `defer_loading: true` on functions (inside a `namespace` for the best savings; "At the start of a request, the model still sees the name and description of whatever is searchable", only the namespace's for a namespace). "Only `gpt-5.4` and later models support `tool_search`" in the Responses API. Hosted search returns `tool_search_call` and `tool_search_output` items (`execution: "server"`, `call_id: null`) before the `function_call`, which then carries a `namespace`. "When new tools are discovered by the model, they are injected at the end of the context window", which "allows the model's cache to be preserved". A client-executed mode (`execution: "client"`) also exists.
- **Anthropic, advanced tool use** (2025-11-24) — https://www.anthropic.com/engineering/advanced-tool-use: the accuracy and context numbers above; tool-use examples taking complex-parameter accuracy from 72% to 90%.

## Design

### One mechanism everywhere: `find_tools` and `call_tool`

A chat created with discovery declares, on every provider and for its whole life:

1. a small **core set** of tools (below), and
2. two stable tools: **`find_tools(query, limit?)`** and **`call_tool(name, arguments)`**.

`find_tools` ranks the chat's deferred tools (and its MCP servers' tools, see below) with Okapi BM25 (k1 1.2, b 0.75) over each tool's name (counted three times), description, and argument names and descriptions, the same fields Anthropic's server-side search reads. A query that is exactly a tool's name puts it first. It returns up to 5 tools (at most 10) with their **full** descriptions and JSON Schemas. The index is in memory: about 80 short documents, rebuilt per search in well under a millisecond, so there was no reason to bring FTS5 in.

`call_tool` is never executed as itself. `ToolRegistry.resolve` turns it into the named tool's own call (same call id) before the agent loop looks at anything, so **everything after that sees the real tool and its arguments**:

| Check | Where | Through `call_tool` |
|---|---|---|
| Argument validation | `validateArguments(tool.spec.inputSchema, …)` | the named tool's schema |
| Chat limits (plan mode, Read-only / Workspace, tools switched off) | `config.restrict` → `ChatPolicy` | the named tool |
| Allow / Ask / Deny | `policyFor(tool)` | the named tool's policy, never call_tool's |
| Forced confirmation | `tool.mustConfirm` | the named tool's |
| Taint (#72) | `Taint.reason(tool, …)`, sources recorded by tool name | the named tool; `Taint.sourcesIn` maps stored call_tool results back to the tool that ran |
| Read-only scheduled runs | `tool.isObservation` | the named tool's |
| AI reviewer | `RunReview.covers/check` | the named tool and its arguments |
| Budgets, change tracking, long-output spill | unchanged | the same step boundary, `ToolCallContext`, `bound()` |
| Parallel reads (#81) | `runsTogether` | decided on the named tool |
| Approval card, activity log, labels | `ApprovalRequest`, `AuditEntry`, `toolLabel` | the named tool's name, arguments and wording |

Only the stored result keeps call_tool's name and id, because providers match a result to its call (Gemini and LiteRT-LM by name). Calls that can't resolve (no `name`, `arguments` not an object, an unknown tool, call_tool naming call_tool or find_tools) are answered as invalid input without running anything. A model that calls a deferred tool directly by name is also fine: it is in the registry and goes through the same path.

Tests: `ToolDiscoveryTest` (deny regardless of call_tool's own policy, Ask naming the real tool, forced confirmation, taint asking with the real source's name, a background run refusing a tainted `fetch_url` to an unnamed host, read-only scheduled runs, chat limits, schema errors, parallel reads, MCP routing, labels).

### Which tools stay loaded

The v1 core set (`ToolDiscovery.CORE_V1`, 45 tools plus find_tools and call_tool) is chosen by evidence rather than taste:

- **Every tool the frozen system prompt tells the agent to use by name for everyday work**: the coding paragraph (`project_info`, `code_outline`, `find_files`, `search_files`, `read_file`, `edit_file`, `multi_edit`, `write_file`, `run_command`, `linux_service_start`, `linux_service_log`, `git_inspect`), checklists and plans (`todo_write`, `plan_propose`), `ask_user`, helpers (`agent_spawn`, `agent_wait` and the rest of the agent_* tools a running helper needs), `memory_save` and the memory reads, `chat_history_search` and `chat_history_read`, `web_search`, `web_read`, `fetch_url`. A prompt that points at an invisible tool would mislead the model.
- **The essential set Locus already chose for on-device models** (`OnDeviceText`): files, `run_command`, `fetch_url`, `browser_open`, `browser_read`, memory, web search, chat history.
- **`browser_ask_user`**, the hand-over for passwords and payments. The prompt forbids entering secrets; the safe alternative must never be a search away.
- **The connector meta-tools** (`connector_call`, `mcp_list_tools`, `mcp_call`) the prompt's connector list refers to.
- The browser's everyday actions (`browser_open`, `browser_snapshot`, `browser_click`, `browser_type`, `browser_read`), since browsing is one of Locus's pillars.

Deferred: every integration (GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, email), SSH and SFTP, the phone's apps (`device_*`), `notify_me`, `weather`, `wikipedia`, the other 11 browser tools, and tools used once in a while (`git_commit`, `linux_service_list`/`stop`, `memory_forget`, `skill_create`, `connector_create`, `agent_create`). Anthropic's own advice is 3–5 non-deferred tools; Locus keeps more because its system prompt names them and a phone agent's everyday work is broad. The set can be tightened in a later version once Insights has real usage counts per tool.

`find_tools`' description lists what can be found ("more browser actions (…), phone apps (…), GitHub, Vercel, …, and the tools of the user's MCP servers"), built from the chat's deferred set in a fixed order, and new chats get a short "Tools on demand" note in their system prompt, as Anthropic recommends.

### Frozen per chat

The choice is made once, in `ConversationRepository.create`, and stored with the chat (`conversations.toolDiscovery`, database v14; "v1" names the core set). `ToolCatalog.forChat` builds the chat's registry from it, with `mcpFirst`, and caches one registry per choice, so every request of the chat (turns, compaction, side questions, reflection, forked helpers) sends the same list. Chats created before this keep `null` and declare every tool, exactly as before. Helpers and copies of a chat take its value. A future v2 core set would be a new value; v1 chats keep v1. Tests: `ToolDiscoveryChatsTest` (old and new chats side by side, byte-identical tool JSON on the next turn, helpers and forks), `ToolDiscoveryMigrationTest`, and the cache-prefix check in `ToolDiscoveryTest` (every request of a run, find and call included, has the same system prompt and tools, and each request's messages start with the previous request's).

### MCP servers

- **Through discovery.** `McpDiscovery` offers each connected server's tools to `find_tools` as `mcp__<connector>__<tool>`, with the server's description and a compact schema. `call_tool` on such a name resolves to `mcp_call {connector, tool, arguments}`, so mcp_call's own risk (annotations), confirmation, schema check and taint apply unchanged. Server listings are cached per connector (`McpClients.tools`); a server that fails or takes more than 15 s is skipped and named in the result. MCP descriptions are third-party text, so they come back inside `<untrusted_web_content>`, which also taints the run, as `mcp_list_tools` always did.
- **Compact schemas.** `mcp_list_tools` now shows each tool's arguments as a compact JSON Schema (`JsonSchemas.compact`): types, descriptions (300 characters each), enums, consts, nested objects, array items, `anyOf`/`oneOf`/`allOf`, `$ref` and `$defs`, formats and bounds, `additionalProperties`, two examples. Titles, `$schema` and the like go. Tool descriptions are cut to 600 characters in the list; `mcp_list_tools` with `tool` returns one tool's full description and schema.
- **Checked before the call.** `mcp_call.precheck` validates the arguments against the cached schema (`JsonSchemas.problems`: required, types including lists of types and `null`, enums, consts, nested objects, array items, unknown keys when `additionalProperties` is false, `anyOf`/`oneOf`, local `$ref`s) and, if they don't fit, answers with each problem's path and the schema, before anyone is asked and before the network call. Anything the checker doesn't understand counts as fitting; the server stays the judge. Tests: `JsonSchemasTest`, `ToolDiscoveryChatsTest`.

## Measured

`ToolTokensTest` counts the `tools` part of each adapter's real request body the way "What was sent" does (`SentView.estimate.tools`, about four characters a token):

| Provider | Every tool (122) | Discovery, v1 (47 declared, 77 deferred) | Saved |
|---|---|---|---|
| Anthropic Messages | 18,663 | 8,455 | 55% |
| OpenAI Responses | 18,724 | 8,479 | 55% |
| OpenAI-compatible chat (OpenRouter, Ollama, …) | 18,663 | 8,455 | 55% |
| Gemini (OpenAI compatibility layer) | 18,663 | 8,455 | 55% |
| On device (LiteRT-LM) | 2,971 | 2,966 | — |

On device Locus already gave the model a fixed short list (`OnDeviceText`); with discovery it also gets find_tools and call_tool, so it can reach the phone's apps, weather and Wikipedia it used to have directly, and everything else it never had.

The saving is per request, and on a cache write; on a cache hit tool tokens cost a tenth or less. The bigger gain on large models is choice: 47 tools instead of 122.

## What doesn't change

The declared list, the system prompt and the cache key stay fixed for a chat's life. Permissions, approvals, taint, the reviewer, budgets, change tracking, parallel reads, labels and activity names behave identically for a tool found and run through discovery (see the table above). Settings → Permissions still lists every tool.

## Not done

- **Usage-based core sets.** Insights doesn't count calls per tool across chats yet; a v2 set should come from that.
- A per-chat or per-provider switch. Every new chat uses discovery.
