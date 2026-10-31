# Locus

An open-source personal AI agent that runs on your Android phone. You bring the model (your own API key, or a model server on your own network) and Locus does the rest locally: it plans, uses tools, works on files in its own workspace, and asks before it changes anything.

No account, no Locus server, no telemetry. Requests go straight from the phone to the provider you configure.

> Status: early development (Phase 1 of the [roadmap](docs/ROADMAP.md)). Its core logic is unit-tested and the main flows have been checked on a Galaxy S25 Ultra (Android 17).

## What works today

- **Bring your own model.** Anthropic (Claude), and any OpenAI-compatible endpoint: OpenAI, OpenRouter, Groq, Ollama, LM Studio, llama.cpp server, vLLM. Responses stream.
- **Agent loop with tools.** The model can list, read, search, write, edit, move, copy and delete files in its workspace, across many steps.
- **You stay in control.** Each tool has an Allow / Ask / Deny policy. Anything that changes files asks by default, in the chat or from a notification when the app is in the background. Approving from a notification requires unlocking the phone. Unanswered requests are denied after 30 minutes.
- **Undo for the agent.** Deleted files and the previous version of every overwritten file go to a trash you can restore from.
- **Activity log.** Every tool call is recorded: what ran, with which input, whether you approved it, and the result.
- **A workspace you can reach.** The workspace appears in Android's Files app (and other apps' file pickers) as "Locus workspace". You can also import and export files in the app.
- **Keeps working in the background.** A foreground service keeps a task running when you leave the app.
- **Uses the web like you do.** An in-app browser the agent drives (open, read, click, type, scroll) while you watch. You can take over any time, it hands over for logins and payments, and buttons like Pay, Send or Delete always ask first.
- **A Linux system to work in.** Alpine Linux running on the phone without root (`run_command`), with your workspace mounted at `/workspace`. Install tools with `apk add`. *Requires running `tools/build-proot.sh` once (see below).*
- **Learns as it goes.** It remembers your preferences and lessons across chats, and you can see, edit or forget everything in Library → Memory.
- **Skills and connectors, including ones it makes itself.** Reusable skills in the open Agent Skills format, HTTP API connectors and MCP servers. When the agent drafts a skill or connector, it waits for your approval. Connector keys go into the vault and the AI never sees them.
- **Scheduled tasks.** "Every morning, check …": runs in the background in read-only mode and notifies you with the answer.
- **Keys stay safe.** API keys are encrypted with Tink AES-256-GCM under a key held by the Android Keystore. Nothing is included in Android backups.

## Build

Requirements: JDK 17+ (Android Studio's bundled JDK works), Android SDK. Gradle installs the API 37 platform if it is missing.

```sh
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                      # unit tests (LLM adapters, agent loop, workspace tools)
./gradlew :app:assembleRelease      # R8-minified, signed with the debug key for local installs
```

The Linux workspace needs proot built once from pinned sources (needs Android NDK r29):

```sh
tools/build-proot.sh                # writes core/linux/src/main/jniLibs/<abi>/lib*.so, checks 16 KB alignment
```

## Set up

1. Open **Settings → Add provider** and pick a preset.
2. For a cloud provider, paste your API key. For Ollama or LM Studio on your computer, replace the example address with your computer's LAN IP and enter a model you have pulled.
3. Start a chat. Try: "Make a folder called trips and draft a packing list for three days in Pokhara."

## How it is built

Kotlin, Jetpack Compose and MVVM, following Google's [architecture recommendations](https://developer.android.com/topic/architecture/recommendations). [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) explains the modules and records why each library was chosen, with sources.

```
app                  Activity, Navigation 3, Hilt application
feature/chat         Conversation list and chat (streaming, tool cards, approvals)
feature/settings     Providers, tool permissions, instructions
feature/workspace    File browser, import/export, trash
feature/activity     Audit log
feature/browser      Live browser view and takeover
feature/library      Memory, skills and connectors (review and approve)
feature/tasks        Scheduled tasks
core/data            Repositories, AgentRunner, foreground service, DocumentsProvider
core/database        Room 3: conversations, messages, audit log
core/datastore       Settings and encrypted secrets (DataStore + Tink)
core/agent           Agent loop, approval gate, workspace tools (pure Kotlin)
core/llm             Provider adapters over HTTP + SSE (pure Kotlin)
core/browser         Agent WebView, injected page script, browser tools
core/linux           Alpine-under-proot environment and run_command
core/model           Shared data types (pure Kotlin)
core/designsystem    Theme, icons, shared components
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
