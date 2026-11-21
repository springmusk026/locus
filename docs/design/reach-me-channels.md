# Reach-me channels: ntfy, Telegram, Gotify and webhooks

Status: built, unit-tested, *not yet tried on the phone*. Research date: 2026-11-21.

Code:
- `core/agent/.../integrations/ReachMe.kt` (`ReachMe.send`, `notify_me`), `Gotify.kt`, `Webhook.kt`, `Ntfy.kt`, `Telegram.kt`.
- `core/data/.../integrations/ReachMeSetup.kt` (check, test, save, remove for Gotify and the webhook).
- `core/ui/.../ReachMeSheet.kt`, shown from Settings → Integrations (`IntegrationsSettings`) and onboarding's Connectors step (`SetupSteps`).
- Scheduled task results: `core/data/.../tasks/ScheduledRun.kt` calls `ReachMe.send`.

Tests: `ReachMeChannelsTest` (core/agent), `ReachMeSetupTest` (core/data), `TelegramTest`, `CatalogTextTest`, `IntegrationIconsTest`. Renders: `gotify-sheet`, `gotify-sheet-saved`, `gotify-sheet-lan-error`, `gotify-sheet-bad-address`, `webhook-sheet`, `webhook-sheet-saved`, `webhook-sheet-unsigned`.

## How it fits

- `notify_me` stays one tool whatever the user connects: a chat's tool list is fixed when it's created, so new channels add no tools. Its description is unchanged (it still names ntfy and Telegram), so existing chats keep their prompt cache. It says "on their own channels", which covers the new ones.
- `ReachMe.send` goes to every channel that has a usable vault value, in order ntfy, Telegram, Gotify, Webhook. A failing channel doesn't stop the others; its error is scrubbed of the channel's URL and secret before the model sees it.
- Scheduled task results use the same `ReachMe.send`, so they reach every channel too.
- Each channel only reaches the user, so background (read-only) runs may use it (`isObservation`).
- The values live in the encrypted vault (`connector:integration-gotify`, `connector:integration-webhook`). The model never sees an address or a secret. The sheet reads back the address only, never the secret.

## Addresses

Gotify's server address and the webhook URL follow the self-hosted rule ([integrations](integrations.md#server-addresses-serviceaddress)): `https://` by default; `http://` only for this phone or the local network, with the "Not encrypted" banner; no user name or password; no fragment. The webhook URL also keeps its query (`?key=…`) and its path exactly as typed, trailing slash included, since receivers may depend on either. A LAN address goes through `LocalNetworkGate` before Send a test or Save.

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
- Telegram-style replies: Gotify and webhooks only send; nothing comes back into Locus.
