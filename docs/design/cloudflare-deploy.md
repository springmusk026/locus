# Cloudflare static deploy: design

Status: researched and built 2026-10-31. The code is in `core/agent/.../integrations/CloudflareStatic.kt` and the mock tests are in `DevPlatformTest`. It has not been tried against the live API yet.

## Why
A user asked the agent to put a built React site (`dist/`) on Cloudflare. The account had no Pages projects, and `cloudflare_pages_deploy` can only rebuild a Git-connected project. So the agent tried things in this order:

1. Netlify, which the reviewer blocked.
2. `wrangler` in proot, which failed.
3. The dashboard in the browser.
4. Digging up undocumented upload endpoints.
5. Asking the user to paste their API token into the chat.

That last step breaks a core rule: secrets never reach the model. Locus needs one tool that uploads a folder, and the agent needs to know never to ask for a token.

## Research (sources read 2026-10-31)

### Workers static assets: Direct Upload (documented)
Guide: https://developers.cloudflare.com/workers/static-assets/direct-upload/

**1. Upload session.** `POST /accounts/{account_id}/workers/scripts/{script_name}/assets-upload-session`
- Body: `{"manifest": {"/path": {"hash", "size"}}}`.
- Response: `result.jwt` (the upload token) and `result.buckets` (an array of arrays of the hashes Cloudflare still needs).
- The guide: "If all assets have been previously uploaded, `buckets` will be empty, and `jwt` will contain a completion token." The JWT is valid for one hour.
- API ref: https://developers.cloudflare.com/api/resources/workers/subresources/scripts/subresources/assets/subresources/upload/methods/create/

**2. Bucket upload.** `POST /accounts/{account_id}/workers/assets/upload?base64=true`
- `Authorization: Bearer <upload JWT>`, not the API token.
- The body is `multipart/form-data`, with one field per hash whose value is the base64 of the file.
- The guide: "The provided `Content-Type` header of each file part will be attached when eventually serving the file"; `application/null` sends none.
- When every file is in, the response is 201 with `result.jwt`, the completion token (valid one hour).
- API ref: https://developers.cloudflare.com/api/resources/workers/subresources/assets/subresources/upload/methods/create/

**3. Script upload.** `PUT /accounts/{account_id}/workers/scripts/{script_name}`
- Multipart, with a `metadata` part: `{"main_module", "compatibility_date", "assets": {"jwt": <completion>, "config": {...}}}`.
- `assets.config.not_found_handling` is `none`, `404-page` or `single-page-application`.
- A module is required. https://developers.cloudflare.com/workers/configuration/multipart-upload-metadata/ says: "At a minimum, the `main_module` key is required." The API ref says: "At least one module must be present."
- Metadata accepts `tags` (script uploads only).
- API ref: https://developers.cloudflare.com/api/resources/workers/subresources/scripts/methods/update/

**Hash.** The guide's programmatic example:

```js
createHash("sha256").update(fileContent.toString("base64") + extension).digest("hex").slice(0, 32)
```

- `extension` has no dot. The guide calls it "a 32 hexadecimal character hash of the file".
- Wrangler uses BLAKE3 over the same input (`packages/deploy-helpers/src/deploy/helpers/hash.ts`; that is wrangler source, not docs). So the server evidently treats the hash as an opaque content key. That is inference, not documented.
- We use the documented SHA-256 recipe: it is in `java.security.MessageDigest`, so no new dependency.

**Limits.** From https://developers.cloudflare.com/workers/platform/limits/:
- 20,000 files per version on Free and 100,000 on Paid.
- 25 MiB per file.
- No documented per-request bucket size. Cloudflare picks the buckets; a wrangler comment says up to about 50 MiB.

**workers.dev.**
- Account subdomain: `GET /accounts/{id}/workers/subdomain` returns `result.subdomain`. API ref: https://developers.cloudflare.com/api/resources/workers/subresources/subdomains/methods/get/
- Per-script route: `POST /accounts/{id}/workers/scripts/{name}/subdomain` with `{"enabled": true, "previews_enabled": false}`. API ref: https://developers.cloudflare.com/api/resources/workers/subresources/scripts/subresources/subdomain/methods/create/
- URL: `<worker>.<subdomain>.workers.dev`. Names are at most 63 characters, alphanumerics and dashes, not starting or ending with a dash. https://developers.cloudflare.com/workers/configuration/routing/workers-dev/

**Existing scripts.** `GET /accounts/{id}/workers/scripts` lists `id`, `has_assets`, `has_modules`, `handlers` and `tags`. API ref: https://developers.cloudflare.com/api/resources/workers/subresources/scripts/methods/list/

**Permission.** Account → **Workers Scripts → Edit** (shown as "Workers Scripts Write" in the API ref). It covers the session, the script PUT and both subdomain calls. The bucket upload uses the session JWT.

### Pages Direct Upload
- The guide (https://developers.cloudflare.com/pages/get-started/direct-upload/) covers only wrangler and drag-and-drop.
- The API reference now lists the endpoints:
  - `GET .../pages/projects/{name}/upload-token`
  - `POST /pages/assets/check-missing`
  - `POST /pages/assets/upload`
  - `POST /pages/assets/upsert-hashes`
  - the `manifest` field of `POST .../pages/projects/{name}/deployments`
- It does **not** document the hash those calls expect. Wrangler uses BLAKE3, which would need a new dependency on Android.

## Decision
- Build on **Workers static assets**. It is the flow Cloudflare documents end to end, hash included, and it is the platform Cloudflare recommends for new static sites.
- Leave Pages Direct Upload out, and say so in `cloudflare_pages_deploy`'s description.

## Tool: `cloudflare_deploy_static(path, name, spa?)`

**Files.**
- `collectSiteFiles` (shared with Vercel and Netlify) walks the folder inside the workspace and skips `.git`, `node_modules` and `.wrangler`.
- Up to 20,000 files and 500 MB in total; each file up to 25 MiB. A clear error names the offending file.
- With `spa`, the folder must have an `index.html` at the top.

**Account and existing Worker.**
- The account comes from the vault value or the token's single account, as for Pages.
- If a Worker with that name exists and has code (`has_modules` or `handlers`) without our tag `locus-static`, the tool refuses, because replacing it would delete that code.

**Upload.**
- Session, then only the requested buckets, then the script PUT with the completion JWT.
- The Worker gets `tags: ["locus-static"]`, an `ASSETS` binding and a tiny module. Files are served before the module runs, so it answers only unmatched requests and serves the site's `404.html` with status 404.
- `spa` sets `not_found_handling: single-page-application`, so navigations to unknown paths get `index.html`. Without it the setting is `404-page`.

**Publishing.**
- Reads the account's workers.dev subdomain and enables the route.
- Returns `https://<name>.<subdomain>.workers.dev` and how many files were uploaded.
- If the account has never set up a workers.dev subdomain, the tool stops and asks the user to open Workers & Pages once. Creating one would claim an account-wide name for them.

**Policy.**
- NETWORK risk, default Ask.
- `mustConfirm` always: it publishes publicly.
- Not an observation, so read-only and background runs refuse it.

**Secrets.**
- The token is used only in Authorization headers. Error text has it scrubbed and the result never contains it.
- The system prompt now tells the agent never to ask for keys in the chat and to name the missing setting instead. It only reaches new chats, because the prompt is frozen per conversation.

## Not verified against the live API
- That an upload works end to end with a real token.
- Whether a GET of the subdomain on an account without one returns 404. That error isn't documented; any other error is reported as is.
- How the stub module behaves with `404-page` and `single-page-application` on the live runtime.
- Whether the server accepts our SHA-256 hashes alongside wrangler's BLAKE3 ones for the same Worker. A mismatch would only cost a re-upload.
- Whether intermediate bucket uploads return a JWT. We keep the last one returned, as the guide's code and wrangler do.
