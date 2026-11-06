<div align="center">
  <img src="docs/icon.svg" alt="Locus icon" width="96" />
  <h1>Locus</h1>

**An AI agent that lives on your Android phone and works with the model you choose.**<br>
Bring your own key, ChatGPT plan, local server or on-device model. No Locus account, no Locus server.

[![CI](https://github.com/springmusk026/locus/actions/workflows/ci.yml/badge.svg)](https://github.com/springmusk026/locus/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/springmusk026/locus?include_prereleases&label=release)](https://github.com/springmusk026/locus/releases/latest)
[![License: AGPL-3.0-or-later](https://img.shields.io/badge/license-AGPL--3.0--or--later-blue.svg)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#download-and-updates)

[![Get it on GitHub](https://img.shields.io/badge/Get%20it%20on-GitHub-181717?logo=github&logoColor=white)](https://github.com/springmusk026/locus/releases/latest)
[![Track with Obtainium](https://img.shields.io/badge/Track%20with-Obtainium-6F42C1)](#download-and-updates)
[![Sponsor](https://img.shields.io/badge/Sponsor-GitHub%20Sponsors-EA4AAA?logo=githubsponsors&logoColor=white)](https://github.com/sponsors/springmusk026)

[Download](#download-and-updates) · [Features](#features) · [Screenshots](#screenshots) · [Safety](#safety-and-privacy-model) · [Launch video](docs/media/locus-launch.mp4) · [Roadmap](docs/ROADMAP.md) · [FAQ](#faq)

</div>

<p align="center">
  <img src="docs/poster.png" alt="Three Locus screens: helpers researching in parallel, a reply with code the agent wrote and ran, and the agent's browser above a chat" width="860" />
</p>

Locus gives a capable model hands on your phone: a Linux shell, a browser you can watch, your files, your calendar and your own servers. It plans, works through many steps and asks before anything that matters. Your keys stay encrypted on the device, and requests go straight to the provider you picked, or nowhere at all if the model runs on the phone.

> [!NOTE]
> Locus is in active development, and the way data is stored can still change between versions. This README describes `main`. Items marked **(next)** are on `main` and listed under [Unreleased in the changelog](CHANGELOG.md#unreleased); they aren't in the latest release yet, and most haven't been tried on a phone yet.

## Contents

- [Why Locus](#why-locus)
- [Features](#features)
- [Screenshots](#screenshots)
- [Download and updates](#download-and-updates)
- [Safety and privacy model](#safety-and-privacy-model)
- [Supported providers and models](#supported-providers-and-models)
- [Build from source](#build-from-source)
- [Roadmap](#roadmap)
- [FAQ](#faq)
- [Contributing](#contributing)
- [Sponsors](#sponsors)
- [Star history](#star-history)
- [Contributors](#contributors)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## Why Locus

- **Your model, your keys, no middleman.** Anthropic, OpenAI, a ChatGPT plan, any OpenAI-compatible server or a model running on the phone. There is no Locus account or server, and no analytics or telemetry.
- **A real agent, not a chat box.** A Linux shell with Python, Node and git, a browser it drives while you watch, SSH to your machines, MCP servers and connectors, helpers that work in parallel, and a receipt for every task.
- **You stay in charge.** Every tool is Allow, Ask or Deny. Once a run has read outside content, anything that could send data out asks first, and secrets never reach the model.
- **Built for a phone.** Voice mode, a widget, a quick settings tile, Direct Share, Telegram approvals, background tasks that respect heat and battery, and calendar, reminders and hand-offs to your other apps.

## Features

### Models

- Anthropic and OpenAI API keys, your **ChatGPT Plus or Pro plan** through OpenAI's official sign-in, any OpenAI-compatible server, **Gemma 4** on the phone through LiteRT-LM, and GGUF models through llama.cpp ([on-device GGUF](docs/design/llamacpp.md)). Switch models per chat.
- Backup models and backup keys take over when a model is out of quota, rate limited or overloaded ([fallbacks](docs/design/fallbacks.md)). Locus checks a model's tools, vision and context window before routing work to it, and says why one was skipped ([capabilities](docs/design/model-capabilities.md)).
- Cost accounting: per-task receipts, budgets and Insights count cache reads and writes and server-side summaries, and long chats are summarised below a model's price step. Effort follows each model's own default (**(next)**, [cost](docs/design/cost.md)).

### Agent

- Plan mode, a live checklist, and helpers (sub-agents) for research and side jobs, up to three at once ([sub-agents](docs/design/subagents.md)).
- Long tasks don't run out of context: work is summarised between steps near the model's window ([compaction](docs/design/compaction.md)). Steer a running task, ask a side question with `/btw`, or set a budget for tokens, cost and time.
- Every finished task gets a receipt: how it ended, time taken, files changed, checks passed, approvals and estimated cost ([receipts](docs/design/task-receipt.md)).
- Regenerate a reply, edit a message as a new branch, or fork a chat from any point ([branching](docs/design/branching.md)).
- Memory you can see and edit, with ranked search in any language, and skills and connectors the agent drafts for you to approve ([memory search](docs/design/memory-search.md), [skill manifests](docs/design/skill-manifests.md)).
- **Temporary chats (next):** don't read or save memory, aren't listed or searched, and are deleted when you leave them ([temporary chats](docs/design/temporary-chats.md)).

### Linux, files and code

- An Alpine Linux shell on the phone, no root needed, installed only if you choose it, with a health page, repair and a package list ([Linux setup](docs/design/linux-setup.md), [health](docs/design/linux-health.md)).
- A real terminal with tabs and an extra-keys row ([terminal](docs/design/terminal.md)).
- A workspace you can open from Android's Files app, with viewers for PDF, code, Markdown, tables and media. A card under each turn lists changed files with diffs and per-file undo; builds and tests show as pass/fail cards ([change tracking](docs/design/change-tracking.md)).
- Charts, diagrams and small tools in a reply run as live previews in a sandbox with no network or storage ([artifacts](docs/design/artifacts.md)).
- SSH to your own servers, long jobs that survive disconnects, and projects that run their commands on one of your machines ([remote execution](docs/design/remote-execution.md)).

### Browser

- A browser the agent drives while you watch, with logins, payments and form submits confirmed by you ([browser](docs/design/browser.md)).
- **Take over and hand back (next):** the agent waits and sees nothing until you hand the page back, optionally with a note ([take over](docs/design/browser.md#take-over-and-hand-back)).
- **Address bar, back, forward and reload (next)** in the full-screen browser; a site you type there counts as one you named ([address bar](docs/design/browser.md#the-address-bar)).
- **Reliable dropdowns (next):** custom menus, autocompletes and long virtualised lists on MUI, Radix, react-select and similar sites ([forms and dropdowns](docs/design/browser.md#forms-and-dropdowns)).
- **Saved logins (next):** Locus fills a saved username, password and two-step code on the exact site without the model ever seeing them, and offers to save a login after you sign in yourself ([saved logins](docs/design/browser.md#saved-logins)).

### Connectors and integrations

- MCP servers over HTTP (with OAuth) or running locally in Linux, and HTTP connectors with vault secrets ([MCP connectors](docs/design/mcp-connectors.md)).
- Search (Firecrawl, Tavily, Brave, Exa), GitHub, Vercel, Netlify, Cloudflare, Supabase, Render, email, ntfy and Telegram.
- **Your own services (next):** templates for Miniflux, FreshRSS, Paperless-ngx, Immich, Mastodon and Vikunja, with Test connection, plus Home Assistant (risky devices always ask), Todoist and Nextcloud presets ([integrations](docs/design/integrations.md)).
- **Email setup form (next):** address and app password, with server settings filled in for common providers and a connection test ([email setup](docs/design/email-setup.md)).
- New chats load the everyday tools and find the rest when needed, with Anthropic's and OpenAI's own tool search where available ([tool discovery](docs/design/tool-discovery.md)).

### On the phone

- **Voice mode:** a hands-free conversation with the same tools and approvals, using Android's speech services and keeping no audio ([voice mode](docs/design/voice-mode.md)).
- **Ways in:** launcher and Direct Share shortcuts, a quick settings tile, "Ask Locus" on selected text, a home-screen widget, the default assistant role, and opt-in automation intents for Tasker and Automate ([Android bridge](docs/design/android-bridge.md)).
- **Calendar, reminders and hand-offs (next):** create, edit and delete events on one calendar you pick; reminders with snooze that survive a reboot; new contacts, Save as and clipboard handed to the system with you in the loop ([phone integrations](docs/design/phone-integrations.md)).
- **Telegram remote:** approve a background request from your bot chat, or start tasks by messaging it. Off by default ([Telegram remote](docs/design/telegram-remote.md)).

### Organising the work

- **Projects** keep chats together with their own folder, instructions, memory, permissions and default model. **Home projects (next):** a shelf on Home shows which project needs you ([projects](docs/design/projects.md)).
- **Workflows:** save a task that went well and run it again with new inputs, on demand or on a schedule ([workflows](docs/design/workflows.md)).
- **Scheduled tasks:** read-only background runs that report by notification, ntfy or Telegram. **Event triggers (next)** start them when something happens instead: a calendar entry, an ntfy message, new mail or another app, each with a history ([scheduled runs](docs/design/scheduled-runs.md), [event triggers](docs/design/event-triggers.md)).
- **Battery and heat:** non-urgent work waits while the phone is hot or in battery saver ([battery and heat](docs/design/battery-thermal.md)).
- **Encrypted backup and restore** to a file you pick, checked before anything is replaced ([backup](docs/design/backup.md)).
- **Quick start (next):** set up a model and start; the optional steps wait as cards on Home ([quick start](docs/design/onboarding-quick-start.md)).

## Screenshots

<table>
  <tr>
    <td><img src="docs/screenshots/chat-code.png" alt="A reply with highlighted code, its output and the file the agent created" width="200"></td>
    <td><img src="docs/screenshots/subagents.png" alt="Two researcher helpers working in parallel" width="200"></td>
    <td><img src="docs/screenshots/terminal.png" alt="The live terminal panel" width="200"></td>
    <td><img src="docs/screenshots/browser-panel.png" alt="The agent's browser above the chat" width="200"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/model-picker.png" alt="Choosing a model across providers, including a ChatGPT plan and on-device Gemma" width="200"></td>
    <td><img src="docs/screenshots/integrations.png" alt="Integrations and their connection state" width="200"></td>
    <td><img src="docs/screenshots/activity.png" alt="Activity grouping actions into runs" width="200"></td>
    <td><img src="docs/screenshots/insights.png" alt="Insights: tokens, speed and usage by model" width="200"></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/workspace.png" alt="The workspace's folders and files with file-type icons" width="200"></td>
  </tr>
</table>

## Download and updates

Get the latest APK from [**Releases**](https://github.com/springmusk026/locus/releases/latest). Locus needs a 64-bit phone running Android 8.0 or newer.

| File | For |
|---|---|
| `locus-v<version>-arm64-v8a.apk` | Nearly every phone |
| `locus-v<version>-x86_64.apk` | x86_64 Chromebooks and emulators |
| `SHA256SUMS.txt` | Checksums for both |

On first launch, choose a model and tap **Quick start**, or go through the full setup to name your agent and install the Linux workspace.

**Staying up to date.** Pick one:

- **In the app:** turn on **Check GitHub for new versions** (setup, or Settings → About & privacy). It's off by default. Locus then asks GitHub about once a day on Wi-Fi, verifies a new APK against `SHA256SUMS.txt` and the installed app's signing key, and hands it to Android's installer for you to confirm. **Include beta versions (next)** also offers pre-releases ([updates](docs/design/updates.md)).
- **With [Obtainium](https://github.com/ImranR98/Obtainium):** add `https://github.com/springmusk026/locus` as an app source, and filter APKs with `arm64-v8a` (or `x86_64`) if asked. When Obtainium installed Locus, Locus leaves updates to it.

**Checking a download.** The release key's SHA-256 certificate fingerprint is

```
0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b
```

Compare it with `apksigner verify --print-certs <apk>`. Release APKs since 0.1.6 also carry a build provenance attestation:

```sh
gh attestation verify locus-v<version>-arm64-v8a.apk --repo springmusk026/locus \
  --signer-workflow springmusk026/locus/.github/workflows/release.yml
```

See [docs/RELEASING.md](docs/RELEASING.md) and [docs/FDROID.md](docs/FDROID.md) for reproducible builds. F-Droid isn't available yet ([FAQ](#faq)).

## Safety and privacy model

The full model, including what is and isn't isolated, is in [SECURITY.md](SECURITY.md).

- **Approvals.** Every tool is Allow, Ask or Deny, and a chat can be limited further (Read-only, Workspace or Full). Approvals come to the chat or to a notification that needs the phone unlocked; an optional reviewer checks Ask-level actions against what you asked for ([remembered approvals](docs/design/remembered-approvals.md)).
- **Taint tracking.** Once a run has read outside content (a web page, an email, a command's output), sending anything out, opening a site you didn't name and saving to memory ask first. Once it has read your own data, a new site with anything in its address asks too ([taint](docs/design/taint.md)).
- **Prompt-injection hardening (next).** Hidden characters (Unicode tags, bidi controls, zero-width runs) are stripped from everything the model reads; MCP tools are pinned and changes need your review; links in replies that could leak data ask before opening.
- **Secrets stay secret.** API keys, tokens and saved logins are encrypted with Tink under an Android Keystore key, kept out of backups unless you include them, and never shown to the model.
- **No telemetry.** No analytics, crash reporting or Locus server. Diagnostics is a local log, scrubbed of secrets, that leaves only if you tap Share ([diagnostics](docs/design/diagnostics.md)). The only network requests go to the providers and services you configure; the model catalogue (models.dev) and the update check are off until you turn them on.
- **No AccessibilityService.** Locus can't read or control other apps. It hands things to them, and you confirm there.
- **Linux is not a sandbox.** Linux commands run as the app's own user. Locus hides its processes and seals its settings against changes from Linux, but treat Linux programs as code you chose to run with Locus's rights ([Linux isolation](docs/design/linux-isolation.md)).
- **Undo.** Activity logs every action, and deleted files go to a trash you can restore from.

Found a vulnerability? Please [report it privately](https://github.com/springmusk026/locus/security/advisories/new), as [SECURITY.md](SECURITY.md) describes.

## Supported providers and models

| Kind | What works |
|---|---|
| Anthropic | Messages API with prompt caching, server-side compaction and effort |
| OpenAI | Responses API with an API key, or a **ChatGPT Plus or Pro** plan through OpenAI's sign-in |
| OpenAI-compatible | OpenRouter, Groq, Gemini, Ollama, LM Studio, vLLM and any server that speaks Chat Completions |
| On the phone | Gemma 4 E2B and E4B through LiteRT-LM; GGUF models through llama.cpp, from a recommended list, Hugging Face search, a URL or a file |

Model lists are fetched from each provider. Per-provider network settings cover extra headers, a proxy and custom CA certificates ([network](docs/design/network.md)). Benchmark results with a real model are in [docs/benchmarks](docs/benchmarks/README.md#results).

## Build from source

You need JDK 21 (Android Studio's bundled one works) and the Android SDK.

```sh
git clone https://github.com/springmusk026/locus.git && cd locus
./gradlew :app:assembleDebug   # app/build/outputs/apk/debug/app-arm64-v8a-debug.apk (x86_64 too)
./gradlew testDebugUnitTest :core:agent:test :core:llm:test :core:model:test lintDebug
```

A debug build is a separate app, `dev.locus.debug` ("Locus Debug"), so it installs next to Locus instead of over it. The Linux workspace uses prebuilt proot binaries from `core/linux/src/main/jniLibs`; `tools/build-proot.sh` rebuilds them from pinned sources with NDK r29.

Locus is Kotlin, Jetpack Compose and MVVM across 26 Gradle modules.

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): the design and why each library was chosen
- [docs/STATE.md](docs/STATE.md): what's built, what's tested and what's next
- [docs/TESTING.md](docs/TESTING.md): unit, screenshot, instrumentation and benchmark suites
- [docs/design/](docs/design/): one note per feature, with the research behind it

## Roadmap

Near-term focus, from [docs/ROADMAP.md](docs/ROADMAP.md):

- **Phone checks** for everything marked "not yet tried on the phone" in [STATE.md](docs/STATE.md#tested-on-the-phone-vs-not-yet).
- **Distribution:** an F-Droid resubmission once Locus meets its age requirement; Google Play is researched but not started.
- **More local models:** llama.cpp on the GPU, image input for GGUF models, and Gemini Nano where supported.
- **Harder benchmarks**, so the suite can rank current models.
- **Stronger Linux isolation**, researched in [Linux isolation](docs/design/linux-isolation.md).

## FAQ

<details>
<summary><strong>What does it cost?</strong></summary>

Locus is free. You pay your model provider directly for what you use, at their prices, or nothing if you use a ChatGPT plan you already have, your own server or an on-device model. Receipts, task budgets and Insights show estimated spend per task and per model.
</details>

<details>
<summary><strong>What leaves my phone?</strong></summary>

Only requests to the providers and services you set up. There's no Locus server and no telemetry. The update check and the models.dev catalogue are off until you turn them on. See [Safety and privacy model](#safety-and-privacy-model).
</details>

<details>
<summary><strong>Do I need root?</strong></summary>

No. The Linux workspace runs Alpine Linux under proot as an ordinary app, and Locus uses no AccessibilityService.
</details>

<details>
<summary><strong>Is Locus on F-Droid or Google Play?</strong></summary>

Not yet. Releases reproduce byte for byte against their APKs, and an F-Droid submission was made, but it was closed because the project is too new. It will be resubmitted once Locus meets that requirement ([docs/FDROID.md](docs/FDROID.md)). Google Play is researched but not started. Meanwhile use [GitHub releases or Obtainium](#download-and-updates).
</details>

<details>
<summary><strong>How does the Linux workspace work?</strong></summary>

It's an Alpine Linux root filesystem run with proot, downloaded only when you choose to install it. You get `apk`, Python, Node and git, a terminal, and a health page with repairs. It shares Locus's app user, so it isn't a security boundary ([Linux setup](docs/design/linux-setup.md), [isolation](docs/design/linux-isolation.md)).
</details>

<details>
<summary><strong>Can it run without the internet?</strong></summary>

Yes, with an on-device model: Gemma 4 through LiteRT-LM or a GGUF model through llama.cpp. Small models are slower and less capable with tools than the large hosted ones, and they need a phone with enough memory; Locus warns before a model that's too big.
</details>

## Contributing

Bug reports and ideas are welcome in [Issues](https://github.com/springmusk026/locus/issues/new/choose). For code, read [CONTRIBUTING.md](CONTRIBUTING.md) and open an issue before anything large. Questions go to [SUPPORT.md](SUPPORT.md), and everyone is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## Sponsors

Locus is built in the open by one maintainer. Sponsorship pays for devices to test on, model API time for the benchmarks, and time to keep it free of ads and tracking.

- **[GitHub Sponsors](https://github.com/sponsors/springmusk026)**: one-off or monthly.
- **Crypto:** wallet addresses are coming soon.
<!-- TODO(owner): add crypto wallet addresses here (one line per coin, with the network), and the same links under `custom:` in .github/FUNDING.yml if wanted. Until then, no addresses are published anywhere. -->

### Featured sponsors

Organisations that sponsor Locus can have their logo here.

<p align="center">
  <!-- TODO(owner): replace this placeholder with sponsor logos, each linked to the sponsor's site. -->
  <a href="https://github.com/sponsors/springmusk026"><strong>Your logo here: become a sponsor</strong></a>
</p>

### Featured provider

AI providers can sponsor Locus as a featured provider. Locus stays bring-your-own-key and neutral:

- A featured provider would only appear as a clearly labelled, optional listing in the provider picker or onboarding, next to every other provider.
- No tracking, referral pings or analytics are added, and Locus's privacy guarantees don't change.
- Locus never switches anyone's default provider or model, and every provider keeps working the same way.

No in-app listing exists today. Interested providers can get in touch through [GitHub Sponsors](https://github.com/sponsors/springmusk026).

## Star history

<a href="https://www.star-history.com/#springmusk026/locus&Date">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/svg?repos=springmusk026/locus&type=Date&theme=dark" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/svg?repos=springmusk026/locus&type=Date" />
    <img alt="Star history chart for springmusk026/locus" src="https://api.star-history.com/svg?repos=springmusk026/locus&type=Date" />
  </picture>
</a>

## Contributors

<a href="https://github.com/springmusk026/locus/graphs/contributors">
  <img alt="Contributors to Locus" src="https://contrib.rocks/image?repo=springmusk026/locus" />
</a>

## Acknowledgements

Locus stands on a lot of open-source work. Thank you to the people behind:

| Project | Used for | Licence |
|---|---|---|
| [Kotlin](https://kotlinlang.org), [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | The language and its core libraries | Apache-2.0 |
| [Android Jetpack](https://developer.android.com/jetpack) (Compose, Material 3, Room, WorkManager, DataStore, Navigation 3, Glance, WebKit, JavaScriptEngine) and [Hilt](https://dagger.dev/hilt/) | UI, storage, background work, the browser and the script sandbox | Apache-2.0 |
| [Ktor](https://ktor.io) | HTTP client for every provider | Apache-2.0 |
| [Tink](https://github.com/tink-crypto/tink-java) | The encrypted vault and backups | Apache-2.0 |
| [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Gemma on the phone | Apache-2.0 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | GGUF models on the phone | MIT |
| [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) | MCP connectors | Apache-2.0 (moving from MIT) |
| [ConnectBot sshlib](https://github.com/connectbot/sshlib) | SSH and SFTP | Apache-2.0 |
| [Eclipse Angus Mail](https://github.com/eclipse-ee4j/angus-mail) | Email over IMAP and SMTP | EPL-2.0 or GPL-2.0 with Classpath Exception |
| [jsoup](https://jsoup.org) | Reading web pages | MIT |
| [Multiplatform Markdown Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer), [LaTeX renderer](https://github.com/huarangmeng/latex), [Mermaid](https://mermaid.js.org) | Rendering replies, maths and diagrams | Apache-2.0, MIT, MIT |
| [Termux terminal-emulator and terminal-view](https://github.com/termux/termux-app) | The terminal | Apache-2.0 |
| [PRoot](https://github.com/termux/proot), [talloc](https://talloc.samba.org), [Alpine Linux](https://alpinelinux.org) | The Linux workspace | GPL-2.0-or-later, LGPL-3.0-or-later, per package |
| [LobeHub Icons](https://github.com/lobehub/lobe-icons), [Simple Icons](https://github.com/simple-icons/simple-icons), [Material Icon Theme](https://github.com/material-extensions/vscode-material-icon-theme) | Provider, service and file-type icons | MIT, CC0-1.0, MIT |
| [models.dev](https://models.dev) | The optional model catalogue (an online service, off by default) | n/a |
| [Roborazzi](https://github.com/takahirom/roborazzi), [Robolectric](https://robolectric.org), [Turbine](https://github.com/cashapp/turbine), [GreenMail](https://greenmail-mail-test.github.io/greenmail/), [Kover](https://github.com/Kotlin/kotlinx-kover) | Tests and coverage | Apache-2.0, MIT, Apache-2.0, Apache-2.0, Apache-2.0 |

Bundled third-party code keeps its own licence; the details are in [core/linux/NOTICE](core/linux/NOTICE), [core/terminal/NOTICE](core/terminal/NOTICE), [core/designsystem/NOTICE.md](core/designsystem/NOTICE.md) and [core/markdown/NOTICE.md](core/markdown/NOTICE.md). Product names and logos belong to their owners and only identify the service you connect.

## License

Locus is licensed under the [GNU Affero General Public License v3.0 or later](LICENSE). If you distribute a modified Locus or offer one over a network, you must share its source under the same licence.
