# Changelog

All notable changes to Locus are recorded here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions will follow [Semantic Versioning](https://semver.org/spec/v2.0.0.html) once releases start.

## [Unreleased]

The first public version of the source. Highlights:

### Models
- Anthropic Messages, OpenAI Responses and OpenAI-compatible providers (OpenRouter, Groq, Google Gemini, Ollama, LM Studio, llama.cpp, vLLM), with streaming, prompt caching, token accounting and compaction.
- Continue with ChatGPT: use a ChatGPT Plus or Pro plan through OpenAI's Sign in with ChatGPT program.
- On-device Gemma 4 through LiteRT-LM, with image input and KV-cache reuse.
- A helper model for titles, follow-up suggestions, summaries and the optional safety reviewer.

### Chat
- Message branching: regenerate a reply or edit a message as a new branch, and switch between versions with ‹ 1/2 ›; only the shown branch is sent to the model.
- Fork a chat from any reply or message into a new chat with the same settings.
- Full-text search over messages (SQLite FTS5, database v8): words match from their start ("kotl" finds "Kotlin"), "quoted phrases" match exactly, accents and case are ignored. Results show each chat once, newest match first, with the matching words highlighted. Only what you'd search is indexed: your messages, attached file names and replies, not tool output or reasoning. `chat_history_search` uses the same index. Existing chats are indexed when the app updates.

### Agent
- Agent loop with per-tool Allow / Ask / Deny policies, approvals in the app or from notifications, and recovery after the app is killed.
- Plan mode, a todo checklist, and thinking shown live and kept afterwards.
- Sub-agents: researcher, coder, explorer, ops and browser helpers that run in parallel, plus your own definitions.
- Memory, Agent Skills, HTTP connectors with sandboxed scripts, MCP servers with OAuth, and drafts you approve.

### Tools
- Linux workspace (Alpine under proot), SSH and SFTP with detached jobs, phone actions through intents.
- Agent browser: structured snapshots with stable refs, form filling, waits, tabs, dialogs, downloads and uploads through the workspace, page saving and logs, with confirmations for logins, payments and submits.
- Integrations: Firecrawl, Tavily, Brave, Exa, GitHub, Vercel, Netlify, Cloudflare, email and ntfy.
- `cloudflare_deploy_static` publishes a workspace folder (such as `dist`) as a Cloudflare Workers static site on workers.dev, with single-page-app routing and missing-file-only uploads. It always asks first. The token needs Account → Workers Scripts → Edit.

### App
- Onboarding with a persona questionnaire, a settings hub, Library, scheduled tasks, a workspace with file viewers, Activity runs with search and filters, and Insights.
- One design system across every screen, with real provider, model and file-type icons.

### Model picker
- One search across every provider's models, favourites (star a model) and recent picks on top.
- Rows show the model's logo, its display name, and chips for context window, vision, tools and reasoning where known.
- Optional model details from the public [models.dev](https://models.dev) catalogue: off until you turn it on, fetched at most once a day, cached for offline use.

### Project
- Debug APKs are published to GitHub Releases from version tags (`.github/workflows/release.yml`).

### Changed
- The system prompt tells the agent never to ask for API keys or tokens in the chat and to point to the missing setting instead (new chats only).
- `cloudflare_pages_deploy` now says plainly that it can't upload files.
- New chats' system prompts have a hard token budget per part (persona files, memory index, skills, connectors, helper types, recent chats, your instructions) and a cap of a fifth of the model's context window. Models with 32K tokens or less, such as on-device Gemma, get a compact prompt. Anything cut leaves a note pointing the agent at `memory_search`, `skill_use` and `chat_history_search`, and `skill_use` and `connector_call` list every active name when given an unknown one. The prompt is still built once per chat. Estimates count characters, not bytes, so non-Latin text isn't undercounted.
- OpenAI (API key and ChatGPT plan) requests carry a stable `prompt_cache_key`: one per chat (shared by its compaction, reflection and forked helpers) and one per helper profile. It never depends on message text or time. If a server refuses the field, the request is sent once more without it.
- Token usage records the reasoning (thinking) tokens a provider reports (Responses `output_tokens_details`, Chat Completions `completion_tokens_details`) as part of output, and Insights shows how much of the output was thinking.

- A test checks that a chat switching between providers only ever sends each provider its own key or sign-in.

### Fixed (reliability)
- A reply whose stream broke mid-way (connection reset, unexpected end, timeout) failed the run at once. It's now retried like a busy provider (up to 3 times, with backoff), with the partial text discarded and "Connection dropped, retrying" shown. A stream that breaks after a tool call began is not retried.
- After Locus restarted, `linux_service_list` said there were no background services, even though the agent had started some (a restart ends them). They're now listed as stopped, with their last output and how to start them again; `linux_service_stop` forgets them. Removing Linux stops running services first.
- OpenAI-compatible servers that leave hidden reasoning out of `completion_tokens` (while `total_tokens` includes it) no longer undercount output and context.

### Fixed
- Opening the browser full screen crashed the app when the page had focus.
- The terminal and browser buttons now show only when their panel has something to show.
- A chat that ends on your message (after an error was dismissed) offers Run again.
- The model sheet could show no tick while the chat header named a model: when the chosen model later became the provider's default, or when the provider's list didn't include it. Header, sheet and requests now resolve the model the same way (#1).
- A model picked on home just before sending could miss that chat and carry over to the next one.

### Fixed (before the first push)
- The full-screen browser showed a black page after opening it from a chat.
- Browser sign-ins failed with "Unable to resolve host" when finishing in the background.
- ChatGPT replies disappeared after streaming.
