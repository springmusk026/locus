# Network settings per provider

Extra headers, a proxy and extra trusted CA certificates for model providers. Settings → Models →
provider → Network, plus Settings → Network for the default proxy and the imported certificates.
Asked for by users of comparable apps (proxies, custom CAs, OpenRouter and gateway headers);
AndroidHarness and Kelivo have per-provider proxy and headers.

## Scope

Only model traffic: chat requests and model lists, which all go through `ProviderAccess`. Tool
traffic (integrations, MCP over HTTP, `fetch_url`), the ChatGPT and MCP sign-ins and the models.dev
catalogue keep using the shared client and connect directly.

## Storage

- `ProviderConfig.network` (`ProviderNetwork`): header names, whether a header may replace the
  credential header, proxy mode (Default / No proxy / Custom) and proxy, and the ids of the
  certificates this provider trusts. In the settings DataStore; no Room change.
- `AppSettings.network` (`NetworkDefaults`): the default proxy and the imported certificates (PEM,
  subject, SHA-256, expiry, "all providers"). Certificates are public, so they live in settings.
- In the vault: `provider-header:<providerId>:<lowercase name>` for each header value and
  `proxy:<providerId>` or `proxy:*default` for proxy credentials (JSON username and password). Both
  are outside `provider:` (so they never count as the API key) and outside `connector:` (so no
  connector can name them). Removing a header, a proxy sign-in or the provider removes them.

## Clients

`ProviderHttpClients` returns the shared client for a provider with nothing set. Otherwise it builds a
Ktor client over its own OkHttp engine derived from the app's `OkHttpClient` (`newBuilder()`, same
timeouts, its own dispatcher and pool so closing it can't touch other clients), cached per provider
until the plan or a vault value changes (the cache key holds a SHA-256 of the values, never the
values).

Ktor configures a proxy on the engine, not per request ("To configure proxy settings, call the
`engine` function inside a client configuration block"; OkHttp supports HTTP and SOCKS; "Proxy
authentication and authorization are engine-specific and should be handled manually"):
https://ktor.io/docs/client-proxy.html. Hence one engine per proxied provider.

### Headers

A Ktor client plugin adds the values after the adapter built the request, so the adapters
(AnthropicChatModel, OpenAi*) are unchanged. Rules (`HeaderRules`):

- Always refused: Host, Content-Length, Content-Type, Content-Encoding, Transfer-Encoding, Connection,
  Keep-Alive, Upgrade, TE, Trailer, Expect, Accept-Encoding, Via, Date, Proxy-*, Sec-*, invalid
  token characters; values with CR, LF, NUL or non-Latin-1 characters.
- Credential headers (Authorization, x-api-key, api-key, x-goog-api-key, chatgpt-account-id) only
  with the explicit "replace the key header" switch and its warning; never for a ChatGPT sign-in.
  Replacing touches only the same header, so a gateway's Authorization can travel next to the
  provider's x-api-key.
- Any other header the adapter set wins over an extra header of the same name.
- Redirects are off (Ktor `followRedirects = false`, OkHttp `followRedirects(false)` and
  `followSslRedirects(false)`) whenever extra headers are set. OkHttp only drops Authorization on a
  cross-host redirect, so other headers would follow it. AndroidHarness does the same.
- Values never reach settings, logs or "What was sent" (that view shows the request body; headers
  are added below it). A gateway that echoes a header in an error body is scrubbed before the error
  reaches the chat. The vault-wide `SecretScrubber` on the what-was-sent branch reads every vault
  value, so header values are covered there too.

### Proxy

`LoopbackBypassProxySelector` returns `NO_PROXY` for localhost, `*.localhost`, 127.0.0.0/8 and ::1
(decided from the name, no DNS), so OAuth loopback redirects and servers in the Linux workspace never
go out. Everything else goes to the proxy. LAN servers are not bypassed; such a provider can choose
"No proxy".

- HTTP: OkHttp `proxyAuthenticator` answers a 407 (including the CONNECT tunnel for https) with
  Basic credentials, once.
- SOCKS5: Android's SOCKS client asks `java.net.Authenticator` for the user name and password with
  protocol "SOCKS5" and the proxy's host and port (`Authenticator.requestPasswordAuthentication(
  server, addr, serverPort, "SOCKS5", "SOCKS authentication", null)` in libcore's
  `ojluni/src/main/java/java/net/SocksSocketImpl.java`). That Authenticator is process-wide, so
  Locus installs one that answers only for a registered SOCKS5 proxy host and port. Two providers
  using the same SOCKS proxy with different accounts share the last one saved.
- Target names are resolved by the proxy (OkHttp passes unresolved addresses for SOCKS).

### Custom CA certificates

Options on Android:

1. Network Security Config. Apps targeting API 24+ trust only the system store by default; `<trust-
   anchors>` can add `src="user"` or a raw resource, per `<domain-config>`
   (https://developer.android.com/privacy-and-security/security-config). It's static XML: a CA
   imported at run time can't be added, `src="user"` would make every user-installed CA trusted for
   that domain, and the domain list would have to be known at build time. Not used.
2. A per-client trust manager. `ExtraCaTrustManager` asks the platform's default trust manager first
   (the system store plus the app's Network Security Config) and only when it rejects a chain checks
   it against the provider's chosen CAs with PKIX path validation (signatures, validity, basic
   constraints; serverAuth EKU when present; revocation off, as private CAs rarely publish reachable
   endpoints). A server certificate that is itself imported (self-signed) is accepted as pinned.
   Host names are still checked by OkHttp. Only that provider's client uses it.

Certificate Transparency: Android 16 (API 36) has CT off by default with opt-in; for apps targeting
Android 17 (API 37), Locus's target, it is on by default
(https://developer.android.com/about/versions/17/behavior-changes-17). The Network Security Config
docs say "Certificate transparency verification isn't performed on connections that use custom
trust anchors" and that CT is disabled where `<trust-anchors>` include "user" or an inline
certificate. That exemption is per domain-config, not per trust manager: Conscrypt's
`TrustManagerImpl` runs CT whenever a host name is known and the policy requires it for that host
(https://github.com/google/conscrypt/blob/master/common/src/main/java/org/conscrypt/TrustManagerImpl.java),
and `CertificateTransparency.checkCT` has no exemption by anchor origin. So the extra-CA path does not
go through Conscrypt's host-aware check: it validates with `CertPathValidator`, which has no CT step.
Public CAs still go through the platform trust manager first, with CT, so CT is never skipped for a
chain to a system root. Not verified on an Android 17 device yet (see the device checklist).

"Trust for every provider" applies a certificate to all model providers, behind a warning that
whoever holds the CA's key can read and change that traffic, keys included.

## Tests

core/model `HeaderRulesTest`; core/data `ProviderHeadersTest`, `ProxySelectionTest` (includes an
end-to-end HTTP proxy with Basic auth and the loopback bypass), `CustomTrustTest` (an HTTPS server
signed by a test CA; fixtures made with keytool in `src/test/resources/network`),
`NetworkSettingsRepositoryTest`; feature/settings `NetworkSettingsTest`.
