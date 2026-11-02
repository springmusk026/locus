# MCP connectors: templates, official servers and tool risk

Status: templates, annotation-based risk and MCP-first integrations shipped (see
[Migration plan](#migration-plan-built-in-integrations--official-mcp-servers)); "Use my saved key" and
per-chat MCP preference shipped (steps 2 and 3, [When a user has both](#when-a-user-has-both)).
Research date: 2026-11-01. Every endpoint below was checked against the vendor's own page (linked) and,
for remote servers, with an unauthenticated MCP `initialize` request plus the server's OAuth metadata
(RFC 9728 protected-resource metadata, then RFC 8414 authorization-server metadata). "DCR" means the
authorization server advertises a `registration_endpoint` (dynamic client registration), which is what
Locus's sign-in needs today. "CIMD" means it also advertises `client_id_metadata_document_supported`.

## Why

Locus reaches services in two ways:

- **Built-in integrations** (`core/agent/.../integrations/`): hand-written REST tools for GitHub, Vercel,
  Netlify, Cloudflare, Supabase, Render, Firecrawl, Tavily, Brave, Exa, ntfy, Telegram and Email. Keys
  live in the vault; the model never sees them.
- **Connectors**: remote MCP servers over Streamable HTTP (OAuth sign-in or a static key), local stdio
  MCP servers run in the proot Linux system, and HTTP connectors the agent drafts.

Most vendors now ship an official MCP server. Those servers are maintained by the people who own the API,
cover far more of it than our hand-written tools, and pick up new features without a Locus release. The
goal is to make them easy to add (a template catalogue) and to recommend them over our own REST tools
where that is a clear win, without losing what the built-ins give us (stable tool lists, tight
approvals, no Linux needed).

## Research: official servers for the built-in integrations

| Service | Official server | Endpoint / package | Auth that works from a phone | DCR | Notes | Sources |
|---|---|---|---|---|---|---|
| GitHub | Yes (github/github-mcp-server, v2.0.3, 2026-10-21) | `https://api.githubcopilot.com/mcp/` (also `/mcp/readonly`, `/mcp/x/{toolset}[/readonly]`) | PAT as `Authorization: Bearer` | No (OAuth needs a pre-registered GitHub app) | Toolset filter headers `X-MCP-Toolsets`, `X-MCP-Readonly` | [repo](https://github.com/github/github-mcp-server), [remote docs](https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md), [GitHub Docs](https://docs.github.com/en/copilot/how-tos/provide-context/use-mcp/set-up-the-github-mcp-server) |
| Vercel | Yes | `https://mcp.vercel.com` | None usable: OAuth only, and only for clients Vercel has reviewed | Advertised, but gated | Locus would need to apply via Vercel's client review | [docs](https://vercel.com/docs/agent-resources/vercel-mcp) |
| Netlify | Yes (`@netlify/mcp` 1.21.0, 2026-10-26) | `https://netlify-mcp.netlify.app/mcp` | OAuth (DCR) | Yes | Local package takes `NETLIFY_PERSONAL_ACCESS_TOKEN` | [docs](https://docs.netlify.com/build/build-with-ai/netlify-mcp-server/), [repo](https://github.com/netlify/netlify-mcp) |
| Cloudflare | Yes (cloudflare/mcp, active) | `https://mcp.cloudflare.com/mcp` ("Code Mode": `search`, `execute`, `docs` over the whole API); product servers such as `https://docs.mcp.cloudflare.com/mcp` | OAuth (DCR), or API token as Bearer | Yes (+CIMD) | Older per-product servers (bindings, observability…) are deprecated in favour of Code Mode; `/sse` returns 410 | [docs](https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/), [repo](https://github.com/cloudflare/mcp-server-cloudflare) |
| Supabase | Yes (supabase/mcp, npm 0.15.0, 2026-10-29) | `https://mcp.supabase.com/mcp` | OAuth (DCR), or PAT as Bearer | Yes | `?project_ref=`, `?read_only=true`, `?features=` | [docs](https://supabase.com/docs/guides/getting-started/mcp) |
| Render | Yes (render-oss/render-mcp-server v0.3.0) | `https://mcp.render.com/mcp` | API key as Bearer | No | 26 tools; no read-only mode | [docs](https://render.com/docs/mcp-server), [repo](https://github.com/render-oss/render-mcp-server), [changelog](https://render.com/changelog/connect-to-the-official-render-mcp-server-over-http) |
| Firecrawl | Yes (`firecrawl-mcp` 3.36.0) | `https://mcp.firecrawl.dev/v2/mcp` (key), `/v2/mcp-oauth` (OAuth) | API key in `x-firecrawl-api-key`; keyless tier | Yes on the OAuth route | ~15 tools | [docs](https://docs.firecrawl.dev/mcp-server) |
| Tavily | Yes (`tavily-mcp` 0.2.22) | `https://mcp.tavily.com/mcp/` | OAuth (DCR), or `?tavilyApiKey=` | Yes (+CIMD) | 5 tools | [docs](https://docs.tavily.com/documentation/mcp) |
| Brave Search | Yes, local only (`@brave/brave-search-mcp-server` 2.1.4) | stdio or self-hosted HTTP | `BRAVE_API_KEY` env | n/a | No hosted server | [repo](https://github.com/brave/brave-search-mcp-server) |
| Exa | Yes (`exa-mcp-server` 3.4.2) | `https://mcp.exa.ai/mcp` | `x-api-key` header, OAuth (`?login`), or keyless | Yes | `?tools=` filter | [docs](https://docs.exa.ai/reference/exa-mcp) |
| ntfy | No (community servers only, listed on ntfy's integrations page) | — | — | — | | [ntfy integrations](https://docs.ntfy.sh/integrations/) |
| Telegram | No official server found | — | — | — | Community Bot API wrappers only | — |
| Email (SMTP/IMAP) | No (protocols, no single vendor) | — | — | — | | — |

## Research: developer servers offered as templates

| Server | Endpoint / command | Auth used by the template | Phone? | Source |
|---|---|---|---|---|
| Google Stitch | `https://stitch.googleapis.com/mcp` | API key in `X-Goog-Api-Key` (created in Stitch → Settings → API Keys). The alternative, a gcloud access token plus `X-Goog-User-Project`, expires hourly and isn't practical on a phone | Yes | [Stitch MCP setup](https://stitch.withgoogle.com/docs/mcp/setup), [Google codelab](https://codelabs.developers.google.com/design-to-code-with-antigravity-stitch) |
| Context7 (Upstash) | `https://mcp.context7.com/mcp` | Optional `Authorization: Bearer` key; anonymous works at lower limits (OAuth lives on `/mcp/oauth`) | Yes | [docs](https://context7.com/docs/resources/all-clients) |
| Sentry | `https://mcp.sentry.dev/mcp[/{org}[/{project}]]` | OAuth (DCR). Static tokens use `Authorization: Sentry-Bearer`, which our header auth can't express, so the template is sign-in only | Yes | [mcp.sentry.dev](https://mcp.sentry.dev/), [repo](https://github.com/getsentry/sentry-mcp) |
| Linear | `https://mcp.linear.app/mcp` (`/mcp/readonly`) | OAuth (DCR) or API key as Bearer | Yes | [docs](https://linear.app/docs/mcp) |
| Notion | `https://mcp.notion.com/mcp` | OAuth (DCR) | Yes | [docs](https://developers.notion.com/docs/get-started-with-mcp) |
| Atlassian (Rovo) | `https://mcp.atlassian.com/v2/mcp` | OAuth (DCR). Locus's redirect is `http://127.0.0.1:…`, which is on Atlassian's default domain allowlist | Yes | [getting started](https://support.atlassian.com/atlassian-rovo-mcp-server/docs/getting-started-with-the-atlassian-remote-mcp-server/), [domains](https://support.atlassian.com/security-and-access-policies/docs/available-atlassian-rovo-mcp-server-domains/) |
| Stripe | `https://mcp.stripe.com` | OAuth (DCR) or an agent API key as Bearer (from December 2026 other keys are refused) | Yes | [docs](https://docs.stripe.com/mcp), [agent keys](https://docs.stripe.com/keys#agent-keys) |
| Neon | `https://mcp.neon.tech/mcp` | OAuth (DCR) or API key as Bearer | Yes | [docs](https://neon.com/docs/ai/neon-mcp-server) |
| Prisma Postgres | `https://mcp.prisma.io/mcp` | OAuth (DCR) | Yes | [docs](https://www.prisma.io/docs/postgres/integrations/mcp-server) |
| MongoDB | local: `npx -y mongodb-mcp-server@latest --readOnly` | `MDB_MCP_CONNECTION_STRING` env | Yes, in Linux. The hosted Atlas server isn't usable with a static key | [repo](https://github.com/mongodb-js/mongodb-mcp-server) |
| GitLab | `https://gitlab.com/api/v4/mcp` | OAuth (DCR, scope `mcp`) or fine-grained PAT as Bearer | Yes | [docs](https://docs.gitlab.com/user/model_context_protocol/mcp_server/) |
| Hugging Face | `https://huggingface.co/mcp` | Optional token as Bearer; anonymous gets public tools | Yes | [docs](https://huggingface.co/docs/hub/hf-mcp-server), [repo](https://github.com/huggingface/hf-mcp-server) |
| Postman | `https://mcp.postman.com/minimal` (`/mcp` full) | OAuth (DCR, US) or API key as Bearer | Yes | [setup](https://learning.postman.com/docs/developer/postman-api/postman-mcp-server/set-up-postman-mcp-server/), [repo](https://github.com/postmanlabs/postman-mcp-server) |
| PostHog | `https://mcp.posthog.com/mcp` | OAuth (DCR) or personal API key (MCP preset) as Bearer | Yes | [docs](https://posthog.com/docs/model-context-protocol) |
| Grafana Cloud | `https://mcp.grafana.com/mcp` | OAuth (DCR) | Yes | [docs](https://grafana.com/docs/grafana-cloud/platform/grafana-assistant/configure/cloud-mcp/) |
| Railway | `https://mcp.railway.com` | OAuth (DCR) | Yes | [docs](https://docs.railway.com/reference/mcp-server) |
| DeepWiki | `https://mcp.deepwiki.com/mcp` | None | Yes | [docs](https://docs.devin.ai/work-with-devin/deepwiki-mcp) |
| Microsoft Learn | `https://learn.microsoft.com/api/mcp` | None | Yes | [docs](https://learn.microsoft.com/en-us/training/support/mcp) |
| AWS Knowledge | `https://knowledge-mcp.global.api.aws` | None (rate limited) | Yes | [docs](https://awslabs.github.io/mcp/servers/aws-knowledge-mcp-server) |
| Cloudflare Docs | `https://docs.mcp.cloudflare.com/mcp` | None | Yes | [docs](https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/) |
| Files, Git, Fetch, Knowledge graph | `npx -y @modelcontextprotocol/server-filesystem /workspace`, `uvx mcp-server-git --repository /workspace`, `uvx mcp-server-fetch`, `npx -y @modelcontextprotocol/server-memory` | None | Yes, in Linux | [modelcontextprotocol/servers](https://github.com/modelcontextprotocol/servers) |

### Considered and left out

- **Figma.** The remote server (`https://mcp.figma.com/mcp`) only accepts clients in Figma's MCP
  catalog; registration from other clients returns 403, and personal access tokens aren't accepted. The
  desktop server needs the Figma desktop app. Not usable from a phone until Locus is approved.
  ([remote](https://developers.figma.com/docs/figma-mcp-server/remote-server-installation/),
  [desktop](https://developers.figma.com/docs/figma-mcp-server/local-server-installation/))
- **Vercel.** Same: approved clients only, no token option. Keep the built-in.
- **Asana.** OAuth without DCR; users would have to create their own Asana app and paste a client ID and
  secret, which Locus's sign-in doesn't support yet. ([docs](https://developers.asana.com/docs/using-asanas-mcp-server))
- **Playwright / Chrome DevTools MCP.** Both drive a desktop Chromium. Playwright's bundled browsers don't
  target Alpine (musl) on arm64, and Chrome on Android only exposes DevTools over adb. Locus already has
  its own WebView browsing. ([playwright-mcp](https://github.com/microsoft/playwright-mcp),
  [chrome-devtools-mcp](https://github.com/ChromeDevTools/chrome-devtools-mcp))
- **Docker Hub.** Local only, no published npx package (clone and build), low value on a phone.
  ([docs](https://docs.docker.com/ai/mcp-catalog-and-toolkit/hub-mcp/))
- **Archived reference servers** (GitHub, GitLab, Postgres, Puppeteer, Slack, Sentry, SQLite) moved to
  `servers-archived`; only the maintained reference servers are templated.
- Also seen and worth a later look: Jam, Buildkite, Socket, Semgrep (experimental hosted server),
  Zapier, Devin (private-repo DeepWiki).

## What shipped: the template catalogue

`core/data/.../mcp/McpTemplates.kt` holds the catalogue: id, name, vendor, one-line description,
category, docs URL, endpoint (or a local command), whether browser sign-in works, and how a pasted key is
sent (`Authorization: Bearer`, a named header like `X-Goog-Api-Key`, or a URL parameter like
`tavilyApiKey`), with a "Get a token" link and the scopes it needs.

Library → Connectors → **Add an MCP server** now opens a picker grouped by category, with a "Custom
server" row for anything else. Picking a template shows its notes, an editable URL (for options such as
Supabase's `?project_ref=…&read_only=true`), and the key field when it takes one. Rules:

- A pasted key goes to the vault under `<id>-token`; the connector file keeps only the name.
- No key and the server supports sign-in: Locus starts the usual OAuth sign-in (loopback redirect, DCR).
- No key and the key is optional (DeepWiki, Exa, Context7…): added without auth.
- Local templates go through the existing local-server path; env values go to the vault.
- Adding a template again replaces its own connector; a different connector that already uses the id is
  kept and the template gets `<id>-mcp`.

Logos come from Simple Icons 16.36.0 (CC0) and LobeHub Icons 1.101.0 (MIT), converted like the
existing marks; see `core/designsystem/NOTICE.md`.

## Tool risk from MCP annotations

MCP tools carry optional `annotations` (spec 2025-03-26 and later;
[2025-11-25 schema](https://modelcontextprotocol.io/specification/2025-11-25/schema#toolannotations)):
`readOnlyHint` (default false), `destructiveHint` (default true, meaningful only when not read-only),
`idempotentHint` (default false) and `openWorldHint` (default true). The spec says they are hints:
"Clients should never make tool use decisions based on ToolAnnotations received from untrusted servers",
and the
[2025-03-26 tools page](https://modelcontextprotocol.io/specification/2025-03-26/server/tools) says
"clients **MUST** consider tool annotations to be untrusted unless they come from trusted servers".
The Kotlin SDK (0.16.0) exposes them as `Tool.annotations: ToolAnnotations?` with nullable booleans, so
the defaults have to be applied by us.

Before this change every MCP call except one marked `readOnlyHint: true` asked first with the same
message, any server's read-only claim was believed, and each approval check fetched `tools/list` again.
Now `McpToolRisk` (in `core/data/.../mcp/`) classifies each call:

| Risk | When | Effect |
|---|---|---|
| READ | `readOnlyHint: true`, not contradicted | Runs under the normal policy; counts as an observation (plan mode, read-only tasks) |
| ADDITIVE | not read-only, `destructiveHint: false` | Asks: "adds or changes something" |
| DESTRUCTIVE | `destructiveHint: true`, read-only and destructive both set, or a name with a deleting verb (`delete_`, `drop`, `purge`, `revoke`…) whatever the hints say | Asks: "may delete or overwrite" |
| UNKNOWN | no hints (spec defaults: may be destructive), or an untrusted server claiming read-only for a tool whose name writes (`create_`, `send_`, `deploy`…) | Asks |
| MISSING | tool not in the server's list | Asks |

A server is **trusted** when its connector reaches a host in the template catalogue over https (or runs
a catalogue command locally): the vendor's own server. Other servers keep the read-only fast path only
when the tool's name doesn't contradict it. Tool lists are cached per connector and refreshed by
`mcp_list_tools` and whenever the connector or its key changes, so approvals no longer cost a round trip.
`mcp_list_tools` tags each tool with its risk for the model.

## Migration plan: built-in integrations → official MCP servers

### What changes now

Settings → Integrations marks each built-in that has a usable official server ("Official MCP server
available") and its sheet shows **Use the official MCP server**, which opens Library → Connectors on that
template. Nothing is removed: the built-in REST tools keep working for everyone who has a key saved.
For the search services (Firecrawl, Tavily, Exa) the card says "Also available" instead of
"Recommended", because their keys also power `web_search`'s fallback chain, which an MCP server can't
join.

### Recommendation per built-in

| Built-in | Recommendation | Why |
|---|---|---|
| GitHub | **MCP (token)** | Official, very active, covers far more (PRs, Actions, code security). GitHub's OAuth has no DCR, so it's a PAT like today. It's big: point heavy users at `/mcp/readonly` or `/mcp/x/{toolset}` to keep `mcp_list_tools` short. Keep `github_*` until the PAT can be reused (step 3). |
| Supabase | **MCP (sign-in or PAT)** | Official, covers migrations, logs, branching and docs. Recommend `?project_ref=…&read_only=true`. We lose our own `SqlCheck` classification of `supabase_sql`; read-only mode runs SQL as a read-only Postgres role, which is a stronger guarantee. |
| Render | **MCP (API key)** | Official; logs, metrics and read-only Postgres queries we don't have. No read-only mode, so writes rely on annotations and approvals. |
| Cloudflare | **MCP for the API, built-in for deploys** | Code Mode covers the whole API in three tools (`search`, `execute`, `docs`), which is ideal for tool-list size. But `execute` runs arbitrary API calls, so every call asks. `cloudflare_deploy_static` uploads files from the phone's workspace, which a remote server can't read: keep it. (`cloudflare_pages_deploy` only starts a Git build of a connected repo, which the API covers.) |
| Netlify | **MCP for management, built-in for deploys** | Sign-in with DCR works. A remote server can't read the phone's workspace, so `netlify_deploy`, which uploads from it, stays. |
| Vercel | **Keep the built-in** | Vercel's MCP only accepts clients it has reviewed, with no token option. Next step: apply through Vercel's client review form; until then the built-in is the only way from a phone. |
| Firecrawl, Tavily, Exa | **Keep the built-in for search, offer MCP as an extra** | The built-ins back `web_search` / `web_read`, two fixed tools with a fallback order and predictable cost. The MCP servers add crawl, map, extract and research tools for people who want them. |
| Brave Search | **Keep the built-in** | No hosted server. The local template exists for people who already run Linux, but it costs Node.js in proot for no gain. |
| ntfy, Telegram, Email | **Keep the built-in** | No official servers; community ones would add risk and a Linux dependency for a few HTTP calls. |

### Trade-offs

- **Prompt cache and tool-list stability.** MCP servers are reached through two fixed tools
  (`mcp_list_tools`, `mcp_call`), so adding a server never changes the declared tool set and never breaks
  the provider's cached prefix. Built-in tools are declared too and stay stable. Leaving a built-in's
  tools out changes the declared set, so it's decided when a chat is created and never mid-chat (see
  [When a user has both](#when-a-user-has-both)).
- **Tool-count bloat.** Big servers (GitHub, Postman's full set, Supabase with every feature group) make
  `mcp_list_tools` output long, which costs context each time the model lists them. Mitigations: the
  templates default to slim endpoints where they exist (Postman `/minimal`) and mention read-only or
  per-toolset URLs (GitHub, Linear, Supabase `features=`). Later, a per-connector tool allowlist in
  Library would let users hide tools they never use.
- **Approvals and policy.** Built-ins have hand-tuned risks (production deploys confirm, `supabase_sql`
  is checked, DNS deletes confirm). MCP tools get `McpToolRisk`: annotations with spec defaults, deleting
  names always confirm, and read-only claims are only fully trusted from catalogue hosts. That's safer
  than before, but coarser than the built-ins. Servers that annotate their tools
  lose little; servers that don't annotate ask on every call.
- **Offline and local-first.** Neither path works offline. MCP adds a hop through the vendor's MCP
  gateway (for GitHub, `api.githubcopilot.com`), which is another service that sees requests and can be
  down independently of the API. Local templates run in proot and need Node.js or uv installed once.
- **Secrets.** Both keep keys in the vault and out of the model's context. OAuth sign-ins are better than
  long-lived keys (scoped, refreshable, revocable per app). Static keys for MCP servers are sent the same
  way as built-in keys, but some servers have broader reach (a Render key covers every workspace).

### Steps

1. **Now (this change):** recommend and link; built-ins untouched.
2. **Shipped:** "Use the key saved for <Integration>" on the template sheet copies the built-in's vault
   entry to the connector after an explicit tap (see [Use my saved key](#use-my-saved-key)). Not done: a
   scope check (GitHub's MCP server may need repository access an older token lacks); a key that's
   too narrow shows up as the server's own error.
3. **Shipped, differently than first planned:** when a user has both, new chats leave the overlapping
   built-in tools out and use the MCP connector; Settings says "Using the MCP server in new chats". The
   first plan kept the built-ins declared; see [When a user has both](#when-a-user-has-both) for why.
4. **Two releases later, if usage shows it:** mark GitHub, Supabase and Render built-ins as legacy (hidden
   for new users, kept for existing keys). Never remove the workspace deploy tools or the search
   backends; they do things the MCP servers can't from a phone.

## Use my saved key

When the user opens a template for a service whose built-in integration already has a key, the sheet
offers **Use the key saved for <Integration>** (a checkbox row, off by default). Ticking it hides the key
field and shows where the key goes: "Your GitHub key will also be sent to api.githubcopilot.com, as a
bearer token". Adding the server then copies the key inside the vault (`connector:integration-github` →
`connector:github-token`). The UI and view model only ever hold a boolean; the value is read and written
in `LibraryRepository.addMcpTemplate` → `SavedKeys.copy`, and error messages never contain it.

Where it's offered (`SavedKeys.fits`, each checked against the vendor's MCP docs above):

| Template | Built-in key | Sent as |
|---|---|---|
| GitHub | fine-grained or classic PAT | `Authorization: Bearer` |
| Supabase | personal access token (`sbp_…`) | `Authorization: Bearer` |
| Render | account API key | `Authorization: Bearer` |
| Cloudflare | API token (the account ID some users add after a space is dropped) | `Authorization: Bearer` |
| Firecrawl | API key | `x-firecrawl-api-key` |
| Tavily | API key | `tavilyApiKey` URL parameter |
| Exa | API key | `x-api-key` |

Not offered: Netlify (its server is sign-in only), Vercel (no usable server), Brave (local server; the
key would go into an env var, which is a different path), and templates with no built-in behind them.
If a template's auth ever changes from the shape listed here, the offer disappears instead of sending a
key the server wasn't documented to take. The key also only goes to the template's own host: a URL the
user edited to another host (or with user info) needs a pasted key. The copy is independent afterwards:
changing or removing the built-in's key doesn't touch it, and the sheet says so.

## When a user has both

### How the tool list is built today

- `ToolCatalog.registry` holds every tool. Every request a chat makes (turns in `AgentLoop`, compaction,
  side questions, reflection, forked helpers) declared `registry.specs`, the same list for every chat,
  whatever keys are saved.
- A chat's system prompt is built once in `ConversationRepository.create` and stored with the chat
  ("frozen"), so the provider's cached prefix (tools, then system prompt) stays valid for its whole life.
  `PromptCacheKey.forChat(id)` keys the cache per chat.
- Per-chat restrictions (tool groups switched off, read-only mode, budgets) live in
  `Conversation.disabledTools`. They never remove a declared tool; the tool refuses at call time. That
  set is user-editable mid-chat and the home composer overwrites it after creating a chat.

So "both" meant the model saw `github_repos`, `github_issues`, … and `mcp_list_tools` / `mcp_call`
reaching GitHub's server, with nothing saying which to use.

### Options considered

1. **Keep both declared, steer with the prompt** (the original step 3). No cache risk, but the model still
   sees two overlapping ways in, pays for the built-ins' schemas on every request, and may pick either.
2. **Keep both declared, refuse the built-ins at call time** (a `disabledTools` key set at creation). The
   model still sees both and learns which one works by failing. And `disabledTools` is user-editable and
   overwritten by the composer, so the frozen choice could be lost or changed mid-chat.
3. **Leave the built-ins out of new chats' declared tools, decided once at creation (chosen).**

### Decision

A new column `conversations.mcpFirst` (database v10, auto-migrated with default `''`) stores the
integration ids a chat reaches through MCP. `ConversationRepository.create` fills it once from
`McpPreference.choose()`, adds one sentence to that chat's frozen system prompt naming the connectors
("Services through MCP: in this chat use GitHub through the MCP connector “github” …"), and nothing
writes it afterwards. `ToolCatalog.forChat(conversation)` returns the registry without
`McpFirst.leftOut(mcpFirst)`, and every request path uses it, so a chat's list is the same from its first
request to its last. Helper chats copy their parent's set (a fork shares the parent's cached prefix), and
forked copies of a chat keep it with the rest of the row. Existing chats have an empty set and declare
exactly what they did before.

An integration is MCP-first in a new chat only when all of these hold (`McpFirstRule`):

- it can hand over at all: GitHub, Supabase, Render, Cloudflare and Netlify. Firecrawl, Tavily and Exa
  can't, because their keys back `web_search` / `web_read`;
- its built-in key is saved (the user really has both);
- an active connector reaches the vendor's own server (same https host as the template, so a look-alike
  or a draft doesn't count) and has its key or sign-in;
- that server's last connection in this run didn't fail (`McpClients.health`, fed by tool listings and
  connection attempts; Settings → Integrations lists untried servers when it opens);
- the user hasn't chosen "Use the built-in tools instead" for it (`AppSettings.builtInOverMcp`).

What a chat leaves out (`McpFirst.leftOut`): all of `github_*` and `render_*`; Supabase's tools except
`supabase_function_deploy`; Cloudflare's except `cloudflare_deploy_static`; Netlify's except
`netlify_deploy`. Those three upload from the phone's workspace, which a remote server can't read.

Settings → Integrations shows "Using the MCP server in new chats" on the row, and the sheet explains it
with **Use the built-in tools instead** (or **Use the MCP server in new chats** to go back). If the server
is failing or missing its key, the sheet says so and new chats keep the built-ins.

### Trade-offs

- **Cache:** different chats now declare different lists, but each chat's list never changes, and the
  cache is per chat anyway. Every request type in a chat uses the same list, so compaction, side
  questions and reflection stay cache hits.
- **A server that breaks after a chat starts:** that chat has no built-in fallback for the service. The
  prompt sentence tells the model to say so and point the user at Settings; the failure is recorded, so
  the next new chat includes the built-ins again. A server that was never tried in this run counts as
  usable if its credentials are there; the alternative (only after a successful call in this run) would
  give new chats both tool sets after every restart until something happened to call the server.
- **Approvals get coarser** for chats that use MCP: the built-ins' hand-tuned risks (production deploys
  confirm, `supabase_sql` checks) give way to `McpToolRisk`. Supabase's `read_only=true` URL option and
  GitHub's `/mcp/readonly` endpoint are the stronger guarantees there.
- **Switching is per new chat**, never retroactive. That's the price of a stable prefix and is said
  wherever the switch is.
- **Out of scope:** leaving out built-ins when only the MCP server is set up (no key, so the built-ins
  don't work anyway but are still declared), and a per-connector tool allowlist.

