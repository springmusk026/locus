# Using a Claude subscription in Locus

Research round of 2026-11-03. The question: can Locus let people sign in with their Claude Pro, Max or Ultra plan and use its quota instead of an Anthropic API key, the way it already does for ChatGPT? All pages were fetched on 2026-11-03 unless another date is given. Anthropic's docs pages carry no "last updated" date, so the fetch date is the date of the claim. Each finding is marked **Stated** (Anthropic or OpenAI says so in its own pages) or **Inferred** (our reading).

## Verdict

**Locus may not do its own "Sign in with Claude".** Anthropic's Claude Code legal page says third-party developers may not offer Claude.ai login, may not send requests through a user's Free, Pro, Max or Ultra credentials, and may not "collect, store, or intermediate Claude.ai credentials or session tokens". Sign-in "must complete through Anthropic's own flow". No public OAuth client or "Sign in with Claude" program exists for third parties. Anthropic has enforced this: technical blocks and account bans in January 2026, a legal request that made OpenCode remove its Claude login in February and March 2026, and tighter subscription billing for third-party tools in April 2026.

**There is one sanctioned route.** The same page says the rules don't stop "an end user from signing in to the unmodified Claude Code binary with their own Claude subscription". Anthropic's help centre also says Agent SDK, `claude -p` and third-party app usage still draw from the plan's limits. So Locus could let the user install the official Claude Code in Locus's Linux workspace, sign in through Claude Code's own login, and have Locus run it. Locus would never touch the token. OpenCode, Zed and OpenClaw work this way today. Three caveats remain:

- **A grey zone.** The Agent SDK page still says "unless previously approved" third parties may not offer claude.ai login or rate limits. Anthropic hasn't publicly approved any of these apps.
- **Unproven on Android.** Claude Code has no Android build, and under proot it is unproven.
- **Not bundled.** Claude Code is proprietary, so Locus can't ship it and the user has to install it.

**The ChatGPT route Locus already has is different.** OpenAI runs an official "Sign in with ChatGPT" program for open-source apps, and Locus uses it with its own registered client. Anthropic has no equivalent.

## Evidence

### What Locus does for ChatGPT today (from the code)

- `core/agent/.../auth/ChatGptOAuth.kt` follows OpenAI's open-source token-sharing guide (developers.openai.com/siwc/token-sharing-open-source). The sign-in flow:
  - The first sign-in registers Locus's own client with `client_id=dynamic_agent_client`, `agent_name_hint` and a per-install `ext_agent_host_id`. The redirect returns an issued `oaiapp_…` id.
  - It uses the authorization code flow with PKCE S256, `state`, an OIDC `nonce` and `resource=https://api.openai.com/v1`, with a `127.0.0.1` loopback redirect.
  - The ID token is verified against OpenAI's JWKS. Plan use needs the `chatgpt.tokens.use.direct` scope.
- `core/model/.../Provider.kt` defines the preset "ChatGPT (your plan)" with `ApiFormat.CHATGPT` at `https://api.openai.com/v1`. `OpenAiResponsesModel` sends the bearer token to the public Responses API.
- `docs/STATE.md`, "Subscriptions", researched 2026-10-31, records the decisions:
  - The ChatGPT route is allowed.
  - Reusing Codex CLI's client id or `chatgpt.com/backend-api` is not allowed, and Locus doesn't do it.
  - A paid build would need OpenAI's interest form.
  - Google Antigravity was rejected, because its terms ban third-party OAuth use.
- Earlier research takes the same line:
  - `docs/ROADMAP.md` lists "subscription sign-in that imitates official clients" as a non-goal.
  - `docs/research/comparable-apps.md` calls such sign-in "a terms-of-service and account-ban risk".
  - `docs/FDROID.md` already declares NonFreeNet, partly because of ChatGPT sign-in.

### 1. Is there a sanctioned way for a third-party app to use a Claude plan?

**Stated: Claude Code legal and compliance page, "Authentication and credential use"** ([code.claude.com/docs/en/legal-and-compliance](https://code.claude.com/docs/en/legal-and-compliance), fetched 2026-11-03):
- "OAuth authentication is intended exclusively for purchasers of Claude Free, Pro, Max, Ultra, Team, and Enterprise subscription plans and is designed to support ordinary use of Claude Code and other native Anthropic applications."
- "Developers building products or services that interact with Claude's capabilities, including those using the Agent SDK, should use API key authentication through Claude Console or a supported cloud provider. Anthropic does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests through Free, Pro, Max, or Ultra plan credentials on behalf of their users. Moreover, developers may not collect, store, or intermediate Claude.ai credentials or session tokens — sign-in to a Claude account must complete through Anthropic's own flow."
- The carve-out: "Nor does it prevent an end user from signing in to the unmodified Claude Code binary with their own Claude subscription, including where a platform hosts Claude Code as described under *Can customers offer Claude Code in their products?*"
- On running Claude Code inside a product: "preinstalling or running Claude Code in your products or services (e.g. in hosted sandboxes or other agent infrastructure) requires agreeing to our Commercial Terms of Service" and two conditions:
  - "The Claude Code binary must not be modified … customers may not remove, disable, or restrict any authentication method built into it."
  - Customers may not "pay for, resell, or intermediate Claude usage". Each end user authenticates with "their own Anthropic API key, Claude subscription plan credentials, or 3P inference provider credential".
- On names: you may say in plain text that a product "runs Claude Code", but you may not use "the Claude Code or Anthropic names or logos as part of your own product, feature, or company name … or in a way that suggests Anthropic built, endorses, or is partnered with your product".
- "Anthropic reserves the right to take measures to enforce these restrictions and may do so without prior notice."
- "Advertised usage limits for Pro, Max, and Ultra plans assume ordinary, individual usage of Claude Code and the Agent SDK."

**Stated: Agent SDK overview** ([code.claude.com/docs/en/agent-sdk/overview](https://code.claude.com/docs/en/agent-sdk/overview), fetched 2026-11-03):
- "Unless previously approved, Anthropic does not allow third party developers to offer claude.ai login or rate limits for their products, including agents built on the Claude Agent SDK. Use the API key authentication methods described in the Quickstart instead."
- Branding: "Claude Agent" or "{YourAgentName} Powered by Claude" are allowed. "Claude Code" or "Claude Code Agent" are not. The product must "not appear to be Claude Code or any Anthropic product".
- The SDK is governed by the Commercial Terms, and it "runs the Claude Code binary".

**Stated: Help centre, "Log in to your Claude account"** ([support.claude.com/en/articles/13189465](https://support.claude.com/en/articles/13189465-log-in-to-your-claude-account), fetched 2026-11-03; the page says "Updated this week"):
- "If you're building a product, application, or tool for others, use API key authentication" through Claude Console or a supported cloud provider.
- Using third-party tools that "misrepresent their identity to Anthropic's servers" is prohibited.
- Plan usage "is designed to support ordinary use of native Anthropic applications". Anthropic "may at its discretion allow paid subscribers who have enabled usage credits to use certain third-party tools".

**Stated: Help centre, "Use the Claude Agent SDK with your Claude plan"** ([support.claude.com/en/articles/15036540](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan), fetched 2026-11-03):
- June 15, 2026 notice: the changes announced for Agent SDK usage are paused. "Claude Agent SDK, `claude -p`, and third-party app usage still draw from your subscription limits."
- October 7, 2026 notice: Max, Ultra and Team plans now include monthly API credits. Those credits cover the API, Managed Agents, and the Agent SDK or `claude -p` when run with an API key from a linked Console organization. For signed-in plan usage, "Nothing changes for your plan."
- The article doesn't say how third-party apps may authenticate.

**Stated: Consumer Terms** ([anthropic.com/legal/consumer-terms](https://www.anthropic.com/legal/consumer-terms), effective Oct 8, 2025):
- §3 bars access "through automated or non-human means, whether through a bot, script, or otherwise" except "via an Anthropic API Key" or where Anthropic "otherwise explicitly permit[s] it".
- §2: "You may not share your Account login information … or Account credentials with anyone else."
- §12: Anthropic may "suspend or terminate your access to the Services (including any Subscriptions) at any time without notice".

**Stated: Claude Code authentication** ([code.claude.com/docs/en/authentication](https://code.claude.com/docs/en/authentication), fetched 2026-11-03):
- Pro, Max and Ultra users log in through the browser. If the redirect fails, the user pastes the code into the terminal.
- On Linux, credentials live in `~/.claude/.credentials.json` with file mode 0600.
- `claude setup-token` makes a one-year `CLAUDE_CODE_OAUTH_TOKEN` "for CI pipelines and scripts". It can only make model requests.

**Not found.** There is no public "Sign in with Claude" OAuth program, partner programme or third-party client-ID registration. The searches turned up only community projects that reuse Claude Code's client ([imzodev/openaidy#567](https://github.com/imzodev/openaidy/pull/567) says it relies on an OAuth client that community tools reverse-engineered). The only formal opening is the "unless previously approved" clause, which points to [Anthropic sales](https://www.anthropic.com/contact-sales).

**Inferred: Anthropic's pages pull in two directions.**
- The Agent SDK page bars third parties from offering "claude.ai login or rate limits" without approval.
- The legal page carves out end users signing in to the unmodified binary.
- The help centre says "third-party app usage" draws from plan limits.

Read together: apps that drive the official `claude` binary, where the user signed in through Claude Code's own flow, are tolerated and billed to the plan. Apps that run their own Claude OAuth, or handle the tokens, are banned. Being "tolerated" is not the same as written permission for a third-party product.

**The recollection checked.**
- "Third-party apps must not offer claude.ai login or rate limits": **confirmed**. That is the Agent SDK page's wording today.
- "Anthropic blocked OpenCode and similar tools from Claude Code subscription OAuth": **confirmed**. The details follow in the next section.

### 2. Which apps do this, how, and what happened to them

| App | How it used or uses a Claude plan | What happened | Sources |
|---|---|---|---|
| **Third-party harnesses in general** | Reused Claude Code's OAuth and sent Claude Code-like requests | **Jan 8, 2026:** Anthropic "tightened our safeguards against spoofing the Claude Code harness". Accounts were banned by abuse filters, then reinstated: "lifted all bans we're aware of that were caused by this issue". Anthropic's line is that harnesses using subscriptions "are prohibited by our Terms of Service" and that the supported route is the API. | Thariq Shihipar (Anthropic), X thread of Jan 9, 2026 ([mirror](https://threadreaderapp.com/thread/2009689809875591565.html)); [The Register, 2026-02-20](https://www.theregister.com/2026/02/20/anthropic_clarifies_ban_third_party_claude_access) |
| **Anthropic docs** | — | **~Feb 19, 2026:** Anthropic added the "Authentication and credential use" section. The wording then said OAuth from Free, Pro or Max "in any other product, tool, or service — including the Agent SDK — is not permitted". It has since been reworded to the text quoted above. The Register adds that Anthropic banned accounts for "gaming" pricing. | [The Register, 2026-02-20](https://www.theregister.com/2026/02/20/anthropic_clarifies_ban_third_party_claude_access) |
| **OpenCode** | A bundled `opencode-anthropic-auth` plugin that used Claude Code's OAuth, plus an Anthropic-specific "Claude Code" system prompt file | Removed after legal requests: PR [anomalyco/opencode#18186](https://github.com/anomalyco/opencode/pull/18186), "anthropic legal requests", merged 2026-03-19. It removed `anthropic-20250930.txt` and the `opencode-anthropic-auth` built-in plugin. The Register dates the first such commit to ~Feb 19. **2026-10-30:** OpenCode added a "Claude Agent" provider ([#58782](https://github.com/anomalyco/opencode/pull/58782), merged 2026-10-30): "It runs Claude models through the user's own Claude Code login, using the Claude Agent SDK. To use it, install Claude Code, run `claude auth login`". No public Anthropic approval is known. | GitHub PRs (checked with `gh`); [cellcog.ai, 2026-10-30](https://cellcog.ai/blog/opencode-claude-subscription/) |
| **OpenClaw (formerly Clawdbot)** | First Claude subscription OAuth or setup-tokens; now a "Claude CLI" backend that runs the installed `claude` binary | **Apr 4, 2026:** plans no longer covered third-party harnesses such as OpenClaw, and users had to turn on "extra usage" (pay as you go). Boris Cherny (Anthropic): "Our subscriptions weren't built for the usage patterns of these third-party tools." **May 13, 2026:** Anthropic announced separate Agent SDK credits that would cover OpenClaw and similar tools. **June 15, 2026:** that change was paused, and usage still draws from plan limits. OpenClaw's docs say it "never reads, persists, refreshes, selects, or forwards the native login tokens", and recommend an API key for shared automation. | [TechRadar](https://www.techradar.com/pro/bad-news-claude-users-anthropic-says-youll-need-to-pay-to-use-openclaw-now), [VentureBeat, 2026-05-13](https://venturebeat.com/technology/anthropic-reinstates-openclaw-and-third-party-agent-usage-on-claude-subscriptions-with-a-catch), [support article 15036540](https://support.claude.com/en/articles/15036540-use-the-claude-agent-sdk-with-your-claude-plan), [OpenClaw docs](https://docs.openclaw.ai/providers/anthropic) |
| **Kilo Code** | A "Claude Code" provider that ran the local `claude` CLI | Kilo's notice: "In January 2026, Anthropic decided to restrict Claude Code CLI to official Claude Code clients. Claude Code credentials cannot be used in Kilo Code or other third-party harnesses." It now points users to an API key. | [Kilo docs (legacy repo)](https://github.com/Kilo-Org/kilocode-legacy/blob/main/docs/legacy-ides/ai-providers/claude-code.md) |
| **Roo Code** | Same design: runs the `claude` CLI and parses its JSON (added June 2025, v3.21.4) | No current notice found. Status unverified. | [Roo docs](https://docs.roocode.com/providers/claude-code) |
| **Cline** | No built-in subscription route confirmed | Unverified | — |
| **Crush (Charm)** | Added "claude code max" support with an internal OAuth package (commit [191a6c8](https://git.secluded.site/crush/commit/191a6c803e51093faa45af6c1352411dc48bc1db)). Its README later said it doesn't "support or endorse" methods that rely on personal Claude Max accounts or OAuth workarounds, which "may violate" Anthropic's terms. | Removal date not confirmed from the repository | [commit 7b95fc5](https://git.secluded.site/crush/commit/7b95fc504a779680732e1a4c37f6ecc3450aaa96) |
| **Zed** | Runs the official Claude Code through ACP, plus the `claude` CLI in its terminal | Zed's blog of May 14, 2026 (updated June 16): ACP usage keeps working on plans, because the billing change "is not taking effect yet". | [zed.dev blog](https://zed.dev/blog/anthropic-subscription-changes) |
| **Mastra** | Signed users in with Claude Code's OAuth client, stored claude.ai tokens and injected "You are Claude Code, Anthropic's official CLI for Claude." with Claude Code beta headers | Issue [mastra-ai/mastra#29198](https://github.com/mastra-ai/mastra/issues/29198) (opened 2026-11-02, untriaged) proposes switching to the unmodified binary through the Agent SDK, citing the legal page. This is the pattern Anthropic forbids. | GitHub |
| **Android apps** | Locus's earlier research found OpenMinis imitating official CLI clients for subscription sign-in. RikkaHub signs in to ChatGPT and Grok. No Android app was found offering Claude subscription login through a sanctioned route. | — | `docs/research/comparable-apps.md`, `docs/research/product-platform-2026.md` |

**Two patterns.**
- **Spoofing.** Reusing Claude Code's OAuth client ID, holding the tokens, and sending Claude Code's system prompt and beta headers. This is what drew the blocks, bans and legal requests, and it matches "misrepresent their identity to Anthropic's servers". It is banned.
- **Driving the official binary.** Running the user's own installed `claude` (CLI or Agent SDK) and never touching the credential. Today this matches the legal page's carve-out and the help centre's billing notice. It still lacks the "previously approved" status the Agent SDK page asks for.

### 3. Risks

| Risk | Spoofing (own Claude OAuth in Locus) | Driving the official `claude` binary in the workspace |
|---|---|---|
| **Account suspension (user)** | **High.** Bans happened in January 2026 (later lifted), and The Register reports bans. Consumer Terms §12 allows termination without notice. | **Low to medium.** It is the official client with Anthropic's own telemetry. Agentic use may hit plan limits, or count as "not ordinary use". |
| **Terms violation** | **Yes, explicitly** (legal page, Agent SDK page, help centre). | **Inferred: mostly compliant.** The user signs in to the unmodified binary. Open points: (a) the Agent SDK page's "unless previously approved" line; (b) the legal page says "running Claude Code in your products" requires the Commercial Terms. A user who installs it and a free local app may not fall under that, but this is unconfirmed. |
| **Breakage** | **Certain and repeated.** Anthropic changes its checks "without prior notice" and has already done so. | **Medium.** Billing for third-party usage changed three times between April and October 2026. Under proot the binary can break. Claude Code updates itself. |
| **Impersonating Claude Code** | Inherent: requests must claim to come from Claude Code. Explicitly prohibited. | None in requests. Naming rules still apply: it may be described as "runs Claude Code", but no Locus feature may be called "Claude Code". |
| **AGPL distribution** | Shipping another vendor's OAuth client ID and spoofing code in an AGPL app is a legal and reputational problem, and invites a legal request like OpenCode's. | Claude Code's licence is "© Anthropic PBC. All rights reserved" ([LICENSE.md in the npm package](https://unpkg.com/@anthropic-ai/claude-code/LICENSE.md)). **Locus can't bundle or redistribute it.** The user must download it from Anthropic, through `apk` or `curl`. Invoking a separately installed program doesn't affect Locus's AGPL licence. |
| **F-Droid** | It would make the NonFreeNet labelling worse, and F-Droid reviewers may refuse code built to get around a service's terms (inferred). | It is an optional feature that needs a non-free program the user installs. NonFreeNet is already declared. If promoted in the UI, **NonFreeAdd** ("promote other Non-Free applications") may apply. Inferred: declare it, as `docs/FDROID.md` already plans to do if asked. ([F-Droid anti-features](https://f-droid.org/docs/Anti-Features/)) |
| **Google Play** | The Device and Network Abuse policy lists as a violation "Apps that access or use a service or API in a manner that violates its terms of service" ([Play policy](https://support.google.com/googleplay/android-developer/answer/9888379)). That is grounds for removal. | No policy conflict found: it's an official client used under its own terms. |
| **Security (Locus-specific)** | Locus would hold claude.ai tokens. That is the "collect, store, or intermediate" Anthropic forbids. | `~/.claude/.credentials.json` sits in the Linux guest, where the agent's tools and any package can read it. Locus must not read it. It should be fenced off from the agent's file tools so a model can't leak it by mistake. |

### 4. Comparison with the ChatGPT route

- **Stated (OpenAI):**
  - "ChatGPT plan usage is available to all open-source partners and selected private clients." "Using a ChatGPT plan requires separate Responses API scopes that the user must authorize." ([developers.openai.com/siwc/quickstart](https://developers.openai.com/siwc/quickstart), fetched 2026-11-03)
  - The open-source guide covers "open-source and locally hosted apps". Paid or remotely hosted apps should "complete the interest form". ([token-sharing-open-source](https://developers.openai.com/siwc/token-sharing-open-source))
- **Reported:** OpenAI's Thibault Sottiaux publicly endorsed using Codex subscriptions in third-party harnesses ([The Register, 2026-02-20](https://www.theregister.com/2026/02/20/anthropic_clarifies_ban_third_party_claude_access)).
- **The difference.** OpenAI documents a third-party OAuth program: own client registration, scopes, and the public API. Locus is a sanctioned participant. Anthropic has no such program, and its docs forbid what that program allows. The only Claude route for the user's plan goes through Anthropic's own binary.

### 5. Legitimate alternatives

**a. Anthropic API key from the Console.**
- Explicitly recommended for third-party apps (legal page; help centre 13189465). Governed by the Commercial Terms. Billing is pay per token.
- Locus already has it: the "Anthropic" preset with `ANTHROPIC_MESSAGES`.
- Stated: since 2026-10-07, Max, Ultra and Team plans include monthly API credits for a linked Console organization (support article 15036540). A Max subscriber can create a Console API key and use those credits **in Locus legitimately**. This is the cleanest way to get value from a plan. Credit amounts and how to link them weren't checked.

**b. Bedrock, Vertex (Google Cloud's Agent Platform), Foundry or OpenRouter.**
- Anthropic names cloud providers as a supported route.
- OpenRouter is a reseller with its own terms (inferred to be fine; Locus already has an OpenRouter preset).
- Bedrock needs SigV4 request signing. Vertex needs Google OAuth service credentials. Both are real work on Android and of limited interest to consumers.

**c. The official Claude Code CLI in Locus's Linux workspace.**
- **Compatibility, stated** ([code.claude.com/docs/en/setup](https://code.claude.com/docs/en/setup)):
  - Supported systems include "Alpine Linux 3.19+" on "x64 or ARM64".
  - npm builds include `linux-arm64-musl`, and there is an official signed `apk` repository.
  - On Alpine: `apk add bash curl libgcc libstdc++ ripgrep` and `USE_BUILTIN_RIPGREP=0`.
  - Hardware: "4 GB+ RAM".
  - Locus's guest is Alpine 3.24 under termux/proot (`docs/design/linux-isolation.md`), so the platform is on the supported list.
- **Android and proot, not verified:**
  - Android itself isn't supported. [anthropics/claude-code#50270](https://github.com/anthropics/claude-code/issues/50270) ("v2.1.113+ broken on Termux/Android: native binary requires glibc", opened 2026-04-18) is still open. It concerns native Termux, not a proot guest.
  - [#86798](https://github.com/anthropics/claude-code/issues/86798) (opened 2026-08-14) reported that the linux-arm64 binary "enters infinite recursive self-relaunch loop when run under proot". It was closed as inactive on 2026-09-12, not fixed.
  - Community guides report that it works in proot-distro Ubuntu (e.g. [claude-code-android](https://github.com/ferrumclaudepilgrim/claude-code-android)).
  - **Must be tested on the phone before any commitment.**
- **Login:** run `claude` or `claude auth login` in Locus's terminal and open the URL in the phone browser. If the loopback redirect can't reach the guest, paste the code shown, which the docs support.
- **Terms:**
  - Matches the legal page's end-user carve-out if (i) the user installs the unmodified binary from Anthropic, (ii) the user signs in through Claude Code's own flow, and (iii) Locus never reads, stores, refreshes or proxies the credential.
  - Agent SDK, `claude -p` and third-party app usage draw from plan limits (help centre, June 15 notice).
  - The remaining grey area is the "unless previously approved" line.
- **Two integration designs:**
  1. **As a tool or helper:** the Locus agent hands a coding task to `claude -p --output-format json` in the workspace, under Locus's approval rules. Least ambiguous: the user's Claude Code does the work, and Locus starts it like any other CLI.
  2. **As a model provider:** OpenCode's approach. Claude Code's built-in tools are turned off and Locus's tools are exposed to it, so Claude Code becomes the model behind Locus's chat. This is closer to "offering claude.ai login or rate limits for their products", so it is more exposed to the approval clause. It also has more moving parts: Locus's tools would need to reach the guest over MCP.

**d. Reverse direction: the Claude app uses Locus.**
- Claude.ai custom connectors (remote MCP) are available on Free through Enterprise, with Free limited to one. Claude connects from Anthropic's cloud, so the server must be reachable from Anthropic's IP ranges over the public internet ([support article 11175166](https://support.claude.com/en/articles/11175166-get-started-with-custom-connectors-using-remote-mcp); wording varies between versions of the page).
- Fully sanctioned. The usage runs inside Anthropic's own app on the user's plan.
- Exposing a phone's agent with shell and file access to the internet needs a tunnel, OAuth on the MCP server and careful scoping. It turns Locus into a tool server rather than letting Locus's own agent use Claude. Large effort and a large security surface (inferred).
- Claude Desktop with a local MCP server would need a desktop, not the phone.

## Options

| Option | Feasibility | Terms status | User risk | Effort |
|---|---|---|---|---|
| Built-in "Sign in with Claude" (Claude Code's OAuth client, tokens in Locus) | Technically easy, breaks often | **Prohibited (stated)** | High: bans, sudden breakage | S to M, with permanent upkeep |
| `CLAUDE_CODE_OAUTH_TOKEN` (`setup-token`) pasted into Locus, which calls the API itself | Easy | **Prohibited (stated):** Locus would store and intermediate the token, and only Claude Code may use it | High | S |
| Anthropic API key (current preset) | Done | **Allowed (stated)** | None beyond cost | — |
| API key on Max, Ultra or Team plan credits (since 2026-10-07) | Works today with the existing preset | **Allowed (stated)** | None; credit amounts unchecked | XS: copy and a link in setup |
| OpenRouter / Bedrock / Vertex | OpenRouter done; Bedrock and Vertex need signing and auth work | Allowed | None beyond cost | OpenRouter none; Bedrock or Vertex M |
| Official Claude Code in the Linux workspace as a **helper tool** (`claude -p`) | Probable; **proot untested** | **Mostly allowed (inferred)**: user-installed, unmodified, own login; grey line on "previously approved" | Low to medium: plan limits, policy changes | M |
| Official Claude Code as a **model provider** (OpenCode-style) | Probable; more moving parts | Grey (inferred): closer to "offering claude.ai login or rate limits" | Medium | L |
| Claude app → Locus over a remote MCP connector | Possible; needs a public tunnel and OAuth | Allowed | Security exposure of the phone | L |
| Ask Anthropic for approval ("unless previously approved") | Unknown | Would settle the grey zone | — | Ask by email or form |

## Recommendation for Locus

1. **Don't build Claude subscription OAuth, token paste or anything that imitates Claude Code.** It is explicitly prohibited, it puts users' accounts at risk, and it would conflict with Play policy and probably F-Droid review. This matches the existing non-goal in `docs/ROADMAP.md` and the Antigravity decision in `docs/STATE.md`.
2. **Now:** keep the Anthropic API key as the Claude route, and improve the setup text. Say that Claude Pro and Max can't be used directly. Say that Max, Ultra and Team plans include monthly API credits usable through a Console key. Link to the Console. Small and fully compliant.
3. **Next, after a device spike:** offer the official Claude Code as a user-installed **workspace tool**. An "Install Claude Code" template runs Anthropic's signed `apk` repository or `curl` installer. The user signs in in the terminal. The agent gets a `claude -p` helper under the normal approval rules.
   - **Spike first:** does `claude` run in Locus's Alpine 3.24 arm64 proot guest? Does login complete with the paste-code flow? Watch for the #86798 relaunch loop and for memory use against the 4 GB minimum.
   - **Hard rules:**
     - Never bundle or download the binary from anywhere but Anthropic.
     - Never read, copy, back up or sync `~/.claude/.credentials.json`. Deny the agent's file tools access to it, and keep it out of workspace export.
     - Never set `CLAUDE_CODE_OAUTH_TOKEN` from Locus's vault.
     - Never change Claude Code's requests.
     - Label it by what it is ("Runs Claude Code, installed from Anthropic, signed in with your Claude account"), never as a Locus feature called "Claude Code".
     - Show a notice that plan use follows Anthropic's terms and limits, which can change.
   - Declare NonFreeAdd on F-Droid if the template promotes it.
4. **Defer** the OpenCode-style "Claude Code as the model provider" until Anthropic approves it or clarifies the "unless previously approved" line. Before shipping either step 3 or step 4, consider asking Anthropic through the [sales/contact form](https://www.anthropic.com/contact-sales) the legal page names. Anthropic's Thariq also invited harness maintainers to get in touch in January 2026.
5. **Not now:** the reverse MCP connector. It is sanctioned, but the security and tunnel work outweigh the benefit for a phone agent.

## Unverified or open

- Whether Claude Code runs under Locus's proot. No primary source covers it, and community reports conflict.
- Whether "running Claude Code in your products" in the legal page covers a free, local app that starts a binary the user installed. Inferred no, not confirmed.
- Whether any third-party app holds the Agent SDK page's "previous approval". None is public.
- The amounts of the Max, Ultra and Team API credits, and how to link a Console organization.
- Roo Code's and Cline's current state. Crush's removal date.
- The exact date of the February 2026 docs change (Feb 18–20 in different reports).
