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

### Agent
- Agent loop with per-tool Allow / Ask / Deny policies, approvals in the app or from notifications, and recovery after the app is killed.
- Plan mode, a todo checklist, and thinking shown live and kept afterwards.
- Sub-agents: researcher, coder, explorer, ops and browser helpers that run in parallel, plus your own definitions.
- Memory, Agent Skills, HTTP connectors with sandboxed scripts, MCP servers with OAuth, and drafts you approve.

### Tools
- Linux workspace (Alpine under proot), SSH and SFTP with detached jobs, phone actions through intents.
- Agent browser: structured snapshots with stable refs, form filling, waits, tabs, dialogs, downloads and uploads through the workspace, page saving and logs, with confirmations for logins, payments and submits.
- Integrations: Firecrawl, Tavily, Brave, Exa, GitHub, Vercel, Netlify, Cloudflare, email and ntfy.

### App
- Onboarding with a persona questionnaire, a settings hub, Library, scheduled tasks, a workspace with file viewers, Activity runs with search and filters, and Insights.
- One design system across every screen, with real provider, model and file-type icons.

### Fixed (before the first push)
- The full-screen browser showed a black page after opening it from a chat.
- Browser sign-ins failed with "Unable to resolve host" when finishing in the background.
- ChatGPT replies disappeared after streaming.
