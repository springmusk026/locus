# Reach-me channels: ntfy, Telegram, Gotify, Matrix and webhooks

Status: built, unit-tested, *not yet tried on the phone*. Research date: 2026-11-21 (Matrix: 2026-11-23, *not yet tried against a live homeserver*).

Code:
- `core/agent/.../integrations/ReachMe.kt` (`ReachMe.send`, `notify_me`), `Gotify.kt`, `Matrix.kt`, `Webhook.kt`, `Ntfy.kt`, `Telegram.kt`.
- `core/data/.../integrations/ReachMeSetup.kt` (check, test, save, remove for Gotify, Matrix and the webhook).
- `core/ui/.../ReachMeSheet.kt` (and `MatrixSheet.kt`, which it opens for Matrix), shown from Settings → Integrations (`IntegrationsSettings`) and onboarding's Connectors step (`SetupSteps`).
- Scheduled task results: `core/data/.../tasks/ScheduledRun.kt` calls `ReachMe.send`.

Tests: `ReachMeChannelsTest` and `MatrixTest` (core/agent), `ReachMeSetupTest` (core/data), `SettingsViewModelTest` (the Matrix sheet's actions), `TelegramTest`, `CatalogTextTest`, `IntegrationIconsTest`. Renders: `gotify-sheet`, `gotify-sheet-saved`, `gotify-sheet-lan-error`, `gotify-sheet-bad-address`, `webhook-sheet`, `webhook-sheet-saved`, `webhook-sheet-unsigned`, `matrix-sheet`, `matrix-sheet-checking`, `matrix-sheet-saved`, `matrix-sheet-encrypted`, `matrix-sheet-not-joined`.

## How it fits

- `notify_me` stays one tool whatever the user connects: a chat's tool list is fixed when it's created, so new channels add no tools. Its description is unchanged (it still names ntfy and Telegram), so existing chats keep their prompt cache. It says "on their own channels", which covers the new ones.
- `ReachMe.send` goes to every channel that has a usable vault value, in order ntfy, Telegram, Gotify, Matrix, Webhook. A failing channel doesn't stop the others; its error is scrubbed of the channel's URL and secret before the model sees it.
- Scheduled task results use the same `ReachMe.send`, so they reach every channel too.
- Each channel only reaches the user, so background (read-only) runs may use it (`isObservation`).
- The values live in the encrypted vault (`connector:integration-gotify`, `connector:integration-matrix`, `connector:integration-webhook`). The model never sees an address or a secret. The sheet reads back the address (and Matrix's room ID) only, never the secret. Settings → Integrations lists each channel as connected once its value is saved.

## Addresses

Gotify's server address, the Matrix homeserver and the webhook URL follow the self-hosted rule ([integrations](integrations.md#server-addresses-serviceaddress)): `https://` by default; `http://` only for this phone or the local network, with the "Not encrypted" banner; no user name or password; no fragment. The webhook URL also keeps its query (`?key=…`) and its path exactly as typed, trailing slash included, since receivers may depend on either. A LAN address goes through `LocalNetworkGate` before Send a test or Save.

Redirects are never followed (Ktor doesn't follow them for POST, and Send a test turns them off): a token or signature only goes to the address the user typed. A redirect is reported with "use the address it redirects to".

A blank secret field keeps the saved one, but only for the same origin. If the user changes the host, they must paste the token (or the webhook's secret) again, so a saved secret never goes to a server it wasn't saved for.

## Gotify

Checked on 2026-11-21 against:
- the server's OpenAPI spec, [gotify/server `docs/spec.json`](https://raw.githubusercontent.com/gotify/server/master/docs/spec.json) (rendered at [gotify.net/api-docs](https://gotify.net/api-docs));
- [Push messages](https://gotify.net/docs/pushmsg), [Message extras](https://gotify.net/docs/msgextras) and [Priority](https://gotify.net/docs/priority).

Facts used:
- `POST {server}/message` with a `CreateMessage` JSON body. Only `message` is required; `title` (string), `priority` (int64) and `extras` (object) are optional. Responses: 200 with the message; 400, 401, 403 with an `Error` body (`error`, `errorCode`, `errorDescription`).
- An **application** token authenticates it. The spec lists three ways: the `X-Gotify-Key` header (`appTokenHeader`), `Authorization: Bearer` (`appTokenAuthorizationHeader`) and the `token` query parameter (`appTokenQuery`). Locus uses `X-Gotify-Key`, the one the push docs show, so the token is never in a URL or a proxy log. A client token would also be accepted by the spec, but the sheet asks for an application token (Apps → Create Application): it can only send messages.
- No endpoint other than `POST /message` takes an application token, so the test is sending a real message.
- `extras.client::display.contentType = "text/markdown"` renders the message as Markdown (HTML ignored). `extras.client::notification.click.url` opens a URL when the notification is tapped.
- Priority: the Android app shows nothing at 0, an icon at 1–3, plus sound at 4–7, plus vibration at 8–10. Without a priority the application's default applies.

What Locus sends:
- Headers: `X-Gotify-Key: <token>`, `Content-Type: application/json`.
- Body: `title` (one line, at most 250 characters, left out when empty), `message` (at most 10,000 characters), `priority` (notify_me's 1–5 mapped to 1, 3, 5, 8, 10; left out when not given), `extras` with the Markdown content type and, for an http(s) link, the click URL.
- Vault value: `<server address> <token>`.

## Matrix

A bot account the user makes on their homeserver posts into one room they choose. Outbound only, like Gotify: Locus never syncs or reads the room.

Checked on 2026-11-23 against the Matrix specification **v1.19** (the version [spec.matrix.org/latest](https://spec.matrix.org/latest/) served that day), [Client-Server API](https://spec.matrix.org/v1.19/client-server-api/), and the spec's own sources in [matrix-org/matrix-spec](https://github.com/matrix-org/matrix-spec) (`data/api/client-server/*.yaml`, `data/event-schemas/schema/*.yaml`, `content/client-server-api/modules/*.md`) where the rendered page was too long to read whole:

| Fact used | Spec section |
|---|---|
| `Authorization: Bearer <token>`; the `access_token` query parameter is deprecated (v1.11) "to prevent the access token being leaked in access/HTTP logs". 401 with `M_UNKNOWN_TOKEN` or `M_MISSING_TOKEN` for a bad or missing token. | [Using access tokens](https://spec.matrix.org/v1.19/client-server-api/#using-access-tokens), [Common error codes](https://spec.matrix.org/v1.19/client-server-api/#common-error-codes) |
| `GET /_matrix/client/v3/account/whoami` returns `user_id` (and `device_id`, `is_guest`); 401 `M_UNKNOWN_TOKEN`; 429 `M_LIMIT_EXCEEDED`. | [GET /account/whoami](https://spec.matrix.org/v1.19/client-server-api/#get_matrixclientv3accountwhoami) |
| `GET /_matrix/client/v3/directory/room/{roomAlias}` returns `room_id` and `servers`; 404 `M_NOT_FOUND` when no room has the alias, 400 `M_INVALID_PARAM` for a malformed one. | [GET /directory/room/{roomAlias}](https://spec.matrix.org/v1.19/client-server-api/#get_matrixclientv3directoryroomroomalias) |
| `GET /_matrix/client/v3/joined_rooms` returns `joined_rooms`, "the ID of each room in which the user has `joined` membership". | [GET /joined_rooms](https://spec.matrix.org/v1.19/client-server-api/#get_matrixclientv3joined_rooms) |
| `GET /_matrix/client/v3/rooms/{roomId}/state/{eventType}/{stateKey}`: 200 with the content, 403 "You aren't a member of the room and weren't previously a member of the room", 404 "The room has no state with the given type or key". | [GET /rooms/{roomId}/state/{eventType}/{stateKey}](https://spec.matrix.org/v1.19/client-server-api/#get_matrixclientv3roomsroomidstateeventtypestatekey) |
| `m.room.encryption` is a state event with an empty `state_key` and `algorithm` `m.megolm.v1.aes-sha2`: its presence means the room is encrypted. | [m.room.encryption](https://spec.matrix.org/v1.19/client-server-api/#mroomencryption) |
| `PUT /_matrix/client/v3/rooms/{roomId}/send/{eventType}/{txnId}` returns `event_id`. Clients make `txnId` "unique across requests with the same access token"; a retransmission gets the original response, so a retry can't post twice. | [PUT /rooms/{roomId}/send](https://spec.matrix.org/v1.19/client-server-api/#put_matrixclientv3roomsroomidsendeventtypetxnid), [Transaction identifiers](https://spec.matrix.org/v1.19/client-server-api/#transaction-identifiers) |
| 429 `M_LIMIT_EXCEEDED`: homeservers SHOULD send `Retry-After`; `retry_after_ms` in the body is deprecated in its favour but may still be there. | [Rate limiting](https://spec.matrix.org/v1.19/client-server-api/#rate-limiting) |
| `m.room.message`: with `format: org.matrix.custom.html` a `formatted_body` must be given, and the plain version goes in `body`. Allowed tags include `b`, `i`, `s`, `code`, `pre`, `a` (an absolute http(s) `href`), `blockquote`, `br`; `code` may carry a `language-…` class; at most 100 levels of nesting. | [m.room.message msgtypes](https://spec.matrix.org/v1.19/client-server-api/#mroommessage-msgtypes) |
| `m.notice` is "primarily intended for responses from automated clients" and must never be answered automatically; the default override push rule `.m.rule.suppress_notices` (`content.msgtype` = `m.notice`, no actions) means it doesn't notify. | [m.notice](https://spec.matrix.org/v1.19/client-server-api/#mnotice), [Default override rules](https://spec.matrix.org/v1.19/client-server-api/#default-override-rules) |
| "The complete event MUST NOT be larger than 65536 bytes, when formatted with the federation event format." | [Size limits](https://spec.matrix.org/v1.19/client-server-api/#size-limits) |

### What Locus does

- **Setup** (Send a test and Save, `ReachMeSetup.checkMatrix`), in the order a user can fix things: the address (the self-hosted rule above); `whoami` (401 → "didn't accept the access token"; an answer that isn't Matrix JSON → "didn't answer as a Matrix homeserver"); an alias resolved through the directory (404 → "doesn't know the room"); the room in `joined_rooms` (if not → "@bot isn't in that room yet: invite it, then accept the invite from the bot's account"); then the room's `m.room.encryption` state (present → refused). Save stores the resolved room ID, never the alias, so a re-pointed alias can't move the bot. Send a test then posts one message and shows "Test sent as @bot:server".
- **Every send** (`Matrix.send`) checks the encryption state again first, since a room can turn encryption on after setup: 200 → refused, nothing posted; 404 `M_NOT_FOUND` → go on; 403 → the bot isn't in the room. A 403 on the send itself means the room's permissions don't let the bot post.
- **Message:** `msgtype` `m.text`, or `m.notice` for notify_me priority 1–2 (quiet, like Telegram's silent messages). `body` is the title, the text and the link as plain text; `formatted_body` is the same Markdown through Telegram's converter (it only emits `b`, `i`, `s`, `code`, `pre`, `a`, `blockquote`, all on the spec's list), with line breaks as `<br>` outside `<pre>`.
- **Size:** parts of at most 4,000 characters, split at lines as for Telegram, at most five per notification (the last says "…(truncated)"). Even fully escaped, a part's content stays well under 48,000 bytes, leaving room in the 65,536-byte federation event for the envelope, hashes and signatures. A part over that budget, or one the server refuses with 400 or 413 (`M_TOO_LARGE`, `M_BAD_JSON`, `M_NOT_JSON`, `M_UNKNOWN`), is sent as the plain `body` alone, under a new transaction ID.
- **Transaction IDs:** `locus-<random UUID>` per part. A 429 waits `Retry-After` (seconds), else `retry_after_ms`, when that is at most 30 s, at most twice, and resends with the **same** transaction ID; a longer wait is reported ("try again in N seconds").
- **Path segments** (room ID, alias, transaction ID) are percent-encoded whole, `!`, `#` and `:` included.
- **Errors** are mapped to the user's language in the sheet and to short English for the model. The token is only ever in the `Authorization` header; it's scrubbed from server error texts and exception messages, and `ReachMe` scrubs the address, token and room ID from anything the model reads.
- Vault value: `<homeserver address> <access token> <room ID>`.

### Not supported

- **End-to-end encrypted rooms.** Posting there needs Olm/Megolm sessions (vodozemac or libolm), a new native dependency and device-key management. Rather than send plaintext into a room whose members expect encryption, Locus refuses: the sheet says so up front (an info banner), and a refused room gets its own banner. In a room without encryption the homeserver can read the messages; the sheet says that too.
- **Server discovery.** The address is the client-server base URL the user's Matrix app connects to (e.g. `https://matrix-client.matrix.org`), not the server name; `/.well-known/matrix/client` isn't followed, so the token only goes to the address typed. A server name that doesn't serve the API gets "didn't answer as a Matrix homeserver" with that example.
- **Joining.** Locus doesn't accept the room invite for the bot; the user accepts it from the bot's account.

## Webhook

For the user's own automation: Home Assistant, n8n, Node-RED or a small server.

### The request

```
POST <your URL>
Content-Type: application/json
X-Locus-Timestamp: 1795000000
X-Locus-Signature: sha256=30ebc855c89d6786961a863b049d9fe38aa25a48b4d464d8c6b28c34797fc518

{"version":1,"title":"Daily digest","message":"3 new *items*","priority":3,"click":null,"sentAt":"2026-11-18T11:06:40Z"}
```

Body fields (UTF-8 JSON, no extra whitespace, always all six keys):

| Field | Type | Meaning |
|---|---|---|
| `version` | number | The format's version, `1`. A new field may be added within version 1; a change to existing fields would raise it. |
| `title` | string or `null` | A short title, one line, at most 250 characters. |
| `message` | string | The text, Markdown allowed, at most 10,000 characters. |
| `priority` | number | 1 (min) to 5 (urgent); 3 when not given. Same scale as ntfy. |
| `click` | string or `null` | An http(s) link to open, when there is one. |
| `sentAt` | string | When it was sent, ISO 8601 in UTC, to the second. |

Any 2xx answer counts as delivered. A 3xx is reported as a redirect and not followed; anything else as `The webhook answered HTTP <code>.`

### The signature

Only when the user sets a signing secret (at least 16 printable characters, no spaces; the sheet can generate a 32-character one and copy it):

- `X-Locus-Timestamp`: the Unix time in seconds when the request was signed. Sent on every request, signed or not.
- `X-Locus-Signature`: `sha256=` and the lowercase hex HMAC-SHA256, keyed with the secret's UTF-8 bytes, of the string `<timestamp>.<body>`: the timestamp header's value, a full stop, and the exact request body bytes.

The secret itself is never sent, in a header, the body or the URL.

The shape follows two documented schemes:
- [Stripe](https://docs.stripe.com/webhooks#verify-manually) signs `t.payload` (timestamp, `.`, raw body) with HMAC-SHA256, so the timestamp is covered by the signature and an old request can be rejected; its libraries allow 5 minutes.
- [GitHub](https://docs.github.com/en/webhooks/using-webhooks/validating-webhook-deliveries) sends `X-Hub-Signature-256: sha256=<hex HMAC-SHA256>` and recommends a constant-time comparison. Its documented vector (secret `It's a Secret to Everybody`, payload `Hello, World!` → `757107ea…3e17`) is checked against Locus's HMAC in `ReachMeChannelsTest`.

### Checking it on your server

1. Read the raw body before any JSON parsing (a re-serialised body won't match).
2. Compute `HMAC-SHA256(secret, timestamp + "." + body)` as lowercase hex and compare it with the part of `X-Locus-Signature` after `sha256=`, in constant time.
3. Reject the request if `X-Locus-Timestamp` is more than about 5 minutes from your clock.

```python
import hmac, hashlib, time

def verify(secret: bytes, timestamp: str, body: bytes, signature: str) -> bool:
    expected = "sha256=" + hmac.new(secret, timestamp.encode() + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature) and abs(time.time() - int(timestamp)) <= 300
```

Test vector: secret `whsec_test_secret_0123456789`, timestamp `1795000000` and the body above give the signature above.

Vault value: `<URL>` or `<URL> <secret>`.

## Not done

- Retries: a failed delivery is reported (to the model, or skipped for a scheduled run), not queued.
- Per-channel choice: every message goes to every channel the user set up.
- Telegram-style replies: Gotify, Matrix and webhooks only send; nothing comes back into Locus.
- Matrix: encrypted rooms, `.well-known` discovery, and a live test against a real homeserver.
