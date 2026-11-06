# Integrations: self-hosted services, Home Assistant and personal presets

Status: built, unit-tested, *not yet tried on the phone*. Research date: 2026-11-06.

Code:
- `core/model/.../ServiceAddress.kt`: server addresses.
- `core/agent/.../home/AgentHome.kt` (`Connector.Auth.prefix`, `basic`, `outward`, `Connector.template`, `HomeAssistantRules`) and `ConnectorKeys.kt` (`header`, `basicValue`, `secretForms`).
- `core/data/.../connectors/ConnectorTemplates.kt` and `ServiceConnections.kt`.
- `core/data/.../mcp/McpTemplates.kt` (`home-assistant`, `todoist`, `nextcloud`) and `HomeAssistantSafety.kt`.
- `LibraryRepository` (`testService`, `addService`, `testHomeAssistant`, `addHomeAssistant`, `setHomeAssistantRules`).
- `feature/library/.../ServiceTemplatesUi.kt`.
- `feature/tasks/.../TasksViewModel.kt` (`TaskDraft.newsDigest`).

Tests:
- `ServiceAddressTest`, `ConnectorAuthTest`, `ConnectorTemplatesTest`, `ServiceConnectionsTest`, `HomeAssistantSafetyTest`, `McpTemplatesTest`.
- Renders: `library-sheet-service-picker`, `library-sheet-service-miniflux`, `library-sheet-service-miniflux-tested`, `library-sheet-service-miniflux-failed`, `library-sheet-home-assistant`, `chat-approval-home-lock`.

## Why

Most self-hosted services (Miniflux, Paperless-ngx, Immich, Mastodon, Vikunja) are plain REST APIs. An MCP server for each would mean Node or Python in Locus's Linux system. HTTP connectors already had approvals, untrusted output and vault keys. They lacked three things:

- a catalogue;
- a base URL that differs per user;
- the auth shapes these services use.

Home Assistant ships its own MCP server, so it only needed a preset and a safety layer for physical actions.

## What a user sees

- **Library → Connectors → Add a service** opens a picker with two groups.
  - *Home & personal*: Home Assistant, Todoist, Nextcloud. These are MCP templates; they're also in Add an MCP server under the same heading.
  - *Self-hosted & social*: the HTTP templates.
- **Settings → Integrations & connectors → Home & self-hosted services** opens the same picker.
- A service's sheet asks for:
  - the server address;
  - a user name, when the service needs one;
  - the key, in the design system's `PasswordField`.

  It then shows **Test connection** with a result row (as in the email form), and lists what Locus can do: what it reads, what it changes (asks first) and what it publishes as you (always asks).
- **Add** saves the connector, active right away, because the user set it up. For a connector made from a template, **Replace key** reopens the same sheet with the address filled in.

## Server addresses (`ServiceAddress`)

- `https://` is the default: `rss.example.com` becomes `https://rss.example.com`.
- A path is kept (`https://home.example.com/miniflux`), without its trailing slash. The template appends its API path: `{host}/v1`, where `{host}` is the checked address with its scheme.
- `http://` is accepted only for:
  - this phone: `localhost`, 127/8, `::1`;
  - the local network: `LocalNetwork.isLocalHost`, i.e. RFC 1918, link-local, CGNAT, IPv6 ULA, `.local`, `.lan`, `.home.arpa` and single-label names.

  The sheet then shows a Caution banner: "Unencrypted (http://)…". The app's network security config already allows cleartext, for local model servers.
- **Refused:**
  - other schemes (`ftp`, `file`, `ws`, `javascript:`…);
  - plain http to a public host;
  - a user name or password in the address;
  - a query or fragment;
  - spaces;
  - a bad port.
- A LAN address goes through `LocalNetworkGate`, Android 17's Nearby devices permission, before Test connection or Add.

## Auth shapes

`Connector.Auth`:

| type | Sent as | Vault value |
|---|---|---|
| `bearer` | `Authorization: Bearer <v>` | the token |
| `header` | `<name>: <prefix><v>`, e.g. `X-Auth-Token: <v>` or `Authorization: Token <v>` | the bare key |
| `query` | `?<name>=<v>` | the key |
| `basic` (new) | `Authorization: Basic base64(<v>)` (RFC 7617) | `user:password`; a colon in the user name is refused |

- `prefix` is new. It only applies to `header`, is at most 40 characters, and contains no control characters, so it can't inject a header line.
- Header names must be RFC 9110 tokens. `Host`, `Cookie`, `Content-Length`, `Content-Type`, `Connection` and `Transfer-Encoding` are refused.
- Basic auth and the prefix work for HTTP connectors and for remote MCP servers (`ConnectorKeys.header` builds the header for both).
- **Scrubbing.** `ConnectorKeys.secretForms` lists every form a credential can take: as stored, the password part, and the base64 credentials. A reply or error is scrubbed of all of them before the model sees it. The model only ever sees connector ids, descriptions and operation names (`<available_connectors>`).
- **Origin binding.** As before, a key is tied to the origin the user typed it for (`ConnectorKeyOrigins.saved`) and sent nowhere else.

## Outward operations

`Operation.sideEffect` gains `outward`: the call publishes or sends something as the user, such as a Mastodon post.

- Like every non-read operation, it's a forced confirmation (`mustConfirm`), so it:
  - is never remembered;
  - is never answered by voice or Telegram;
  - asks even in a tainted run.
- The card says "This publishes or sends something from your Mastodon account that other people can see."
- Library's operation list marks it "(publishes as you; always asks)".

## Test connection (`ServiceConnections`)

- One GET of the template's `testPath` with the key. It's an endpoint that only reads and needs the key: Miniflux `/v1/me`, Paperless `/api/documents/?page_size=1`, Immich `/api/users/me`, Mastodon `/api/v1/accounts/verify_credentials`, Vikunja `/api/v1/user`, FreshRSS `reader/api/0/user-info`, Home Assistant `/api/`.
- **Redirects aren't followed.** A redirect is reported with its target ("use the address it redirects to"), so the key never goes to another host.
- Messages name the cause: 401 refused, 403 missing permission, 404 wrong address or sub-path, 5xx server error, unknown host, TLS failure (self-signed certificates aren't accepted), refused connection, timeout. They're scrubbed of every form of the key.
- Testing saves nothing. Editing a field clears an old result.

## The catalogue

Each entry was checked against the source linked below on 2026-11-06.

| Template | Base | Auth | Operations (side effect) | Test | Source |
|---|---|---|---|---|---|
| Miniflux | `{host}/v1` | header `X-Auth-Token` (API key; Basic also documented) | `entries` (status, limit, order, direction, category_id, search, after), `entry`, `categories` (counts), `feeds` (read); `mark_entries` PUT `/entries` {entry_ids, status} (write); `subscribe` POST `/feeds` {feed_url, category_id} (write) | `/me` | [miniflux.app/docs/api.html](https://miniflux.app/docs/api.html) |
| FreshRSS | `{host}/api/greader.php` | `Authorization: GoogleLogin auth=<token>`. The token comes from `accounts/ClientLogin` (POST `Email`, `Passwd` = API password) once at setup; the API password isn't stored | `unread` (`stream/contents/user/-/state/com.google/reading-list?xt=user/-/state/com.google/read`, n, c), `subscriptions`, `unread_counts` (read only) | `reader/api/0/user-info` | [Google Reader API page](https://freshrss.github.io/FreshRSS/en/developers/06_GoogleReader_API.html); `p/api/greader.php` (`clientLogin` derives `Auth` from the API password hash, so it lasts until that changes; `xt`, `n`, `c` parsed in the stream branch) |
| Paperless-ngx | `{host}/api` | header `Authorization: Token <t>` | `search` (`/documents/?query=`, page_size, page), `document` (`/documents/{id}/`, its `content`), `tags`, `correspondents` (read only) | `/documents/?page_size=1` | `docs/api.md` (token auth, full-text `query`); `src/paperless/views.py` (`page_size_query_param`) |
| Immich | `{host}/api` | header `x-api-key` | `smart_search` POST `/search/smart` {query, size, city, takenAfter, takenBefore}, `metadata_search` POST `/search/metadata`, `albums`, `album`, `asset` (read; the POSTs only search) | `/users/me` | `open-api/immich-openapi-specs.json` (3.4.0-rc.0: `api_key` scheme `x-api-key`; permissions `asset.read`, `album.read`, `user.read`) |
| Mastodon | `{host}/api/v1` | Bearer | `home_timeline` (limit ≤ 40, max_id, since_id), `notifications` (limit ≤ 80, max_id), `status` (read); `post` POST `/statuses` {status, visibility, in_reply_to_id, spoiler_text, language} (**outward**) | `/accounts/verify_credentials` | [statuses](https://docs.joinmastodon.org/methods/statuses/), [timelines](https://docs.joinmastodon.org/methods/timelines/), [notifications](https://docs.joinmastodon.org/methods/notifications/), [accounts](https://docs.joinmastodon.org/methods/accounts/); JSON bodies per [client intro](https://docs.joinmastodon.org/client/intro/) |
| Vikunja | `{host}/api/v1` | Bearer API token | `tasks` (filter, s, sort_by, order_by, per_page, page), `task`, `projects` (read); `add_task` PUT `/projects/{id}/tasks` {title, description, due_date, priority} (write) | `/user` | [API docs](https://vikunja.io/docs/api-documentation) ("Authorization: Bearer <token>"); `pkg/swagger/swagger.json` (paths, `models.Task`) |

**Left out on purpose:**
- **FreshRSS mark-read.** It needs a `T` token that FreshRSS derives from the same secret. A connector can't add it without handing it to the model, so FreshRSS is read-only.
- **Paperless upload.** Multipart isn't supported by HTTP connectors.
- **Vikunja task update.** v1's `POST /tasks/{id}` writes the whole task, so a partial body would reset the other fields.
- **Jellyfin and Gotify.** Gotify belongs in `notify_me`, not in a connector.
- **Vikunja's own MCP server and its API v2 (from 2.4.0).** Not used yet. v1 stays supported until 4.0.

## Home Assistant

**The preset.**
- `McpTemplates` entry `home-assistant` with `selfHosted`:
  - path `/api/mcp/assist` by default;
  - `/api/mcp` behind a **Full access** switch with a warning;
  - Test connection is GET `/api/`.
- The connector is `kind = mcp`, `template = "home-assistant"`, with auth Bearer `home-assistant-token`, tied to the typed origin.

**Verified in HA's code** (`homeassistant/components/mcp_server/http.py`; tags 2026.9.0 to 2026.11.0, read with `gh api`):
- `ModelContextProtocolStreamableApiView` serves `/api/mcp/{api_id}`.
- It requires an admin "except for the Assist API" (`llm.LLM_API_ASSIST == "assist"`).
- `/api/mcp` serves the configured APIs, admin-only by default (`require_admin`).
- The integration page says `/api/mcp/assist` "only exposes Device control", the entities exposed to Assist.

**Token auth: verified in code, not on a live server.**
- HA's MCP page only describes OAuth and Client ID Metadata Documents.
- The views don't override `requires_auth` (default `True`, `helpers/http.py`).
- The auth middleware (`components/http/auth.py`, `async_validate_auth_header`) accepts any `Authorization: Bearer` access token.
- The auth docs (contributors.home-assistant.io, auth-api) say long-lived tokens are "valid for 10 years" and "used exactly like an access token".

So a long-lived token works, and a non-admin user's token is enough for `/assist`.

Locus can't use HA's OAuth yet: HA identifies clients by URL (IndieAuth / CIMD), and Locus has no Client ID Metadata Document. That's on the roadmap.

**Tool pinning.** After Add, Locus lists the server's tools once, which pins them on first connect ("Tool pinning" in mcp-connectors.md). A later change to a tool's description, schema or hints is held back until the user approves it.

**Always confirm (`HomeAssistantSafety`).** Every HA tool that isn't read-only already asks: HA's tool annotations default to destructive (`helpers/llm.py`, `ToolAnnotations`). This layer adds two things:
- The card says what's at stake.
- Physical-security calls ask even if a tool claims to be read-only, and don't count as observations.

Calls are judged by their arguments, because `intent__HassTurnOn` lights a lamp and also locks a door (HA's own prompt: "Use intent__HassTurnOn to lock and intent__HassTurnOff to unlock a lock", `components/intent/llm.py`). Inputs:
- the intent slots `domain`, `device_class`, `name`, `area` and `floor`;
- `homeassistant__call_action`'s `action`, `target.entity_id/area_id/…` and `data`;
- the tool's namespace (`climate__…`, `script__…`).

**Built in, can't be removed:**
- `lock`, `alarm_control_panel` and `siren` in any domain, action or entity id;
- `cover` with device class `garage`, `door` or `gate`;
- the whole words lock, unlock, alarm, arm, disarm, garage, gate, door(s), security or siren in a name, area, entity id or script name. "Outdoor" isn't "door".

  HA has no "security" device class, so the word stands in for it.
- climate or water-heater setpoints (`temperature`, `target_temp_high/low`) outside the user's range (default 16–26°).

**The user's additions** (Library → the server → **What always asks**, or the setup sheet):
- domains (`vacuum`);
- entity ids (`switch.heater`);
- actions (`script.turn_on`);
- names ("Kids room").

They're stored in the connector file, which is sealed with the other connectors. They can only add confirmations: removing an entry, or a tampered file, still leaves every control call asking.

**The card's first line** is the server's name and the action in words (`McpCall.describe`): "Home Assistant · Unlock Front door", from `HomeAssistantSafety.title`, which maps the following, plus a readable verb and the device's name or entity id otherwise:
- `HassTurnOn`/`HassTurnOff` on a lock, cover or alarm → Lock/Unlock, Open/Close, Arm/Disarm;
- `lock.unlock`, `cover.open_cover` and `alarm_control_panel.alarm_*` → the same verbs;
- setpoints → "Set Living room to 21°".

Every other MCP server gets "GitHub · Create issue" (`McpNames.humanize`: no `intent__` namespace, CamelCase and snake_case split). The raw connector id, tool name and arguments stay under Details. Templates likewise name what they need in words (`McpTemplate.Local.labels`): "Needs your Nextcloud address, user name and app password", never variable names.

A forced confirmation is in-app only. It's never remembered, never answered by voice or a Telegram button, and in a background (read-only) run the call is denied. The card reads, for example: "Physical security: this unlocks “Front door”. Locus always asks before these, even if you've allowed this server."

**Not done.**
- Events from HA. HA can't reach the phone without a server; it can post to the user's ntfy topic, which already triggers tasks.
- HA's OAuth (needs CIMD).

## Todoist

- **Template:** `todoist`, the hosted `https://ai.todoist.net/mcp` (Doist/todoist-ai README).
- **Verified 2026-11-06:**
  - An unauthenticated `initialize` returns 401 with `resource_metadata` at `/.well-known/oauth-protected-resource/mcp`.
  - That names `https://todoist.com` as the authorization server.
  - Its RFC 8414 metadata advertises `registration_endpoint: https://todoist.com/oauth/register` (and CIMD).
  - A registration with Locus's exact body (native public client, loopback redirect `http://127.0.0.1:33418/callback`) returned **201** with a client id.
- So it ships enabled with browser sign-in. The consent screen and token exchange weren't run (they need a Todoist account): *not yet tried on the phone*.

## Nextcloud

- **Template:** `nextcloud`, the community server `cbcoutinho/nextcloud-mcp-server`: AGPL-3.0, about 405 stars, pushed 2026-11-06.
- It runs in Locus's Linux as `uvx nextcloud-mcp-server@0.204.3 run --transport stdio`, with `NEXTCLOUD_HOST`, `NEXTCLOUD_USERNAME` and `NEXTCLOUD_PASSWORD` (an app password) from the vault, as its README documents.
- The version is pinned, so an update only runs after the template changes.
- It's marked `community`:
  - The sheet says "Community server by …".
  - `McpTemplates.official` doesn't match it, so its tool annotations aren't trusted.
- Linux already runs `uvx` templates (git, fetch). This one pulls heavier wheels (cryptography, lxml, pillow, grpc), and whether all of them have musl aarch64 builds on Alpine is *unverified on a phone*.
- If it doesn't start, a WebDAV/CalDAV connector template is the fallback. Basic auth (above) is what it needs.

## RSS digest

Tasks → **News digest** runs every day from 07:00 (`TaskDraft.newsDigest`), like the Morning digest.

- It calls the Miniflux connector's `entries` with `status=unread`, groups the items by category and summarises them.
- It's read-only: it doesn't mark anything read.
- The run is a background run, so it can only read. Articles are untrusted outside content.

## Invariants kept

- **Secrets never reach the model:**
  - vault only;
  - every form scrubbed from replies and errors;
  - the connector JSON and the prompt hold names only;
  - FreshRSS's password is never stored.
- **Origin binding:** keys are tied to the typed origin, and Test connection doesn't follow redirects.
- **Taint and approvals:** replies are wrapped untrusted; write and outward calls ask; HA physical actions always ask.
- **Fixed tool list per chat:** no new tools. Connectors go through `connector_call`, and MCP servers through `mcp_list_tools`/`mcp_call` and discovery.
- **F-Droid:** no new dependencies, no Play services.
