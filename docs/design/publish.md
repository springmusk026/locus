# Publish to your own host

Status: designed and built 2026-11-23. Unit-tested; *not yet tried on the phone*. Idea #13 of the [November feature research](../research/feature-opportunities-2026-11.md) (Cursor's shareable canvases and live apps, Amp's shared threads), without any Locus server.

Code:
- `core/agent/.../integrations/SitePublish.kt`: `PublishHost`, `SitePublisher` (deploy and unpublish through the existing integration code).
- `core/agent/.../integrations/Netlify.kt`, `Vercel.kt`, `CloudflareStatic.kt`: the deploy tools' bodies moved into `netlifyDeployFolder`, `vercelDeployFolder` and `CloudflareDeployStatic.deploy`, which the tools and `SitePublisher` both call.
- `core/agent/.../publish/PublishPage.kt`: escaping, the HTML allowlist, the page template, the counting scrubber and the vault-value guard.
- `core/data/.../publish/PublishedPages.kt`: `PublishService` and the published-pages file.
- `feature/chat`: `ChatPage.kt` (a chat as HTML), `PublishFlow.kt` (`PublishViewModel`, `PublishPolicy`), `PublishSheet.kt` (the sheet), `ShareChat.kt` (the entry in Share chat), `ChatScreen.kt` (wiring).
- `core/markdown/.../artifact/ArtifactCard.kt`: `ArtifactHost.publish` and the card's and full screen's Publish button.

Tests: `PublishPageTest`, `SitePublisherTest` (core/agent), `PublishedPagesTest` (core/data), `ChatPageTest`, `PublishFlowTest` (feature/chat). Renders: `sheet-publish-destination`, `-destination-update`, `-no-integration`, `-preview`, `-preview-blocked`, `-confirm`, `-publishing`, `-done`, `-error`, `-error-network`, `sheet-share-chat-publish`, `artifact-card-publish`.

## What the user sees

Two ways in, both a tap by the user (not an agent tool):

- **An artifact**: the globe button on an HTML or SVG artifact card, or in its full-screen view (which closes first).
- **A chat**: Share chat → **Web page** → **Publish…**.

The sheet then walks through:

1. **Destination.** The deploy integrations that have a key (Netlify, Cloudflare, Vercel), each with the shape of its address (`locus-….netlify.app`). None set up: "Nowhere to publish yet", and a link to Settings → Integrations. If this chat or artifact was published before on the chosen host: **Update** that page (same address) or **Publish a new page**. For a chat, **Include tool results** (off by default), and a line saying what is never published. Under it, **Published pages**: every page published from this phone, newest first.
2. **Building** (a spinner), then **Preview**: the page in the artifact sandbox, "Sandboxed · no network". If scrubbing removed anything, a caution banner says how many ("2 secrets removed"). If the page would still hold a vault value, a critical banner says it can't be published and **Next** is disabled.
3. **Confirm**: "Public page: anyone with the link can see it", the host, the address (new or the one being updated), what is published, and the size. **Publish**. In a locked chat Android's own prompt asks first, every time.
4. **Publishing** (a spinner), then **Done**: the address, **Copy link**, **Share**, **Open**, and "a new address can take a minute to start answering". Or **Couldn't publish**: the host's message (with "Open Integrations" when it names that page) or a generic one, and **Try again** back to Confirm.

A published page opens a small sheet: its address, Copy, Share, Open, **Unpublish** (asks first, critical) and **Remove from list** (only forgets it here). If unpublishing fails, the sheet says how to remove it on the provider's site.

## Decisions

- **A user action, not a tool.** No tool is added, changed or removed, and no chat's prompt changes, so every chat's cached prefix holds. The agent can already deploy a folder with the deploy tools (with approval); publishing is the user's own choice about their own content, made in the UI.
- **No Locus server, no telemetry.** The page goes straight from the phone to the user's own account, with the key already in the vault, through the same Ktor client.
- **Reuse, not a copy.** The three deploy tools' bodies were moved into functions (`netlifyDeployFolder`, `vercelDeployFolder`, `CloudflareDeployStatic.deploy`); the tools call them and format the same text as before (their tests are unchanged), and `SitePublisher` calls them too. Files are collected by the same `collectSiteFiles`, with its limits and symlink checks, from a fresh folder under `cache/publish/`.
- **Each page gets its own site.** `locus-<title>-<6 random>`: readable, never an existing site of the user's, and valid on all three hosts. So unpublishing can delete the whole site, Worker or project without touching anything else.
  - **Netlify**: a production deploy (not a draft) to a new site, created by name (`POST /sites`), `https://NAME.netlify.app`. Unpublish: `DELETE /sites/{id}`.
  - **Cloudflare**: a static-assets Worker tagged `locus-static`, `https://NAME.SUBDOMAIN.workers.dev`. Unpublish: `DELETE /accounts/{account}/workers/scripts/{name}`, only for a Worker that still carries the tag (otherwise Locus refuses and says how to remove it by hand).
  - **Vercel**: a production deployment with `framework: null` of a new project, and the URL is the project's production domain from `GET /v9/projects/{name}/domains?production=true` (the `*.vercel.app` one). Vercel's default Standard Protection "protects all domains except production domains" ([deployment protection](https://vercel.com/docs/deployment-protection), read 2026-11-23), so the generated deployment URL would ask visitors to sign in to Vercel; the production domain doesn't. Unpublish: `DELETE /v9/projects/{idOrName}` ([delete a project](https://vercel.com/docs/rest-api/projects/delete-a-project): 204). If Vercel hasn't assigned a domain yet, Done says to look in the Vercel dashboard.
  - A site already gone (404) counts as unpublished.
- **Update** deploys to the recorded site again (Netlify by ID, Cloudflare and Vercel by name); the address stays.
- **The list is a small file**, `files/published-pages.json` (host, site, URL, title, kind, source key, when), not Room and not the settings: no schema to migrate. At most 200 entries; an unreadable file counts as empty. It holds no secrets.

## The chat page

`ChatPage.build` turns the rows the chat shows (the active branch) into one `index.html`:

- **Who said what**: "You" and "Locus" turns, the chat's title, its date and "Published from Locus". The words come from string resources, so the page is in the app's language (`lang` is set to it).
- **Steps** are one line each (the chat's own labels, `ChatExport.stepLabel`, which already blanks `password=…`-style arguments), in a muted list. With **Include tool results**, each step is a `<details>` with its result (scrubbed, escaped, cut at 4,000 characters) and any pictures it showed.
- **Never on the page**: reasoning, the system prompt, memory (a `memory_*` tool's result says "Memory isn't published." even with tool results on), helper transcripts, receipts, provider drafts, and branch controls.
- **Pictures**: images the user attached (and, with tool results, images a tool showed) go in as `data:` URLs, PNG, JPEG, GIF or WebP up to 3 MB each. Other attachments are listed by name.
- **Design**: neutral system fonts, greys and one link colour; light and dark through `prefers-color-scheme`; readable at phone width; no script at all.

### Escaping and sanitising (in this order)

1. **Scrub first**: every piece of text (the title, messages, step labels, results, file names) goes through `PublishPage.Scrubber`: `SecretScrubber` with the vault's values (and their parts) and its key patterns, plus private key blocks, JWTs, AWS key IDs and `sk-`/`pk-`/`rk-` keys. Each replacement counts.
2. **Plain text is escaped** (`& < > " '`).
3. **Markdown** (the user's and the replies' text) is rendered with org.jetbrains:markdown's GFM flavour (already in the app through the chat's Markdown renderer), with its raw-HTML providers replaced so HTML blocks and inline tags are **escaped**: `<script>` in a reply shows as text, as it does in the chat. The result then goes through a jsoup allowlist (`Safelist.relaxed()` plus `del`, `s`, `hr`): no scripts, styles, frames, forms or event handlers; links keep only http, https and mailto, with `rel="nofollow noopener noreferrer"`; an `<img>` whose source isn't a `data:image/` URL becomes its alt text, so the page never fetches from elsewhere.
4. **The page's own CSP**: `default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'`, plus `referrer no-referrer` and `robots noindex`. Even if something slipped through step 3, a browser would run no script and load nothing.

## The artifact page

The artifact's HTML goes as it is (it is the user's page, and may load its libraries from a CDN, which the published page is free to do). A fragment or an SVG gets the smallest wrapper that makes it a document: doctype, charset, viewport and title; an SVG is centred. Its text is scrubbed like a chat's (so a key inside its script becomes `[secret]`, and the preview says so).

## Scrub and block rules

- **Removed**: whatever step 1 above finds. The Preview says "N secrets removed" and that they were replaced with `[secret]`.
- **Blocked**: after building, `PublishPage.leaksVaultValue` looks for every vault value (and its parts, as `SecretScrubber.candidates` lists them) in the finished `index.html`, as it is, HTML-escaped and URL-encoded. Any hit blocks the page: Next and Publish do nothing, and the Preview says why. This is defence in depth: scrubbing should already have removed it.
- Pictures are bytes, not text, and aren't scanned. The Preview shows them.

## The preview

The page is shown through `createArtifactWebView`, the artifact sandbox ([artifacts](artifacts.md)): its own WebView profile, no storage, no bridges, a CSP with no network, and every request except the page's own two in-memory documents answered 403. It takes the single live-preview slot while it shows. Screenshot tests have no WebView and show the page's source instead.

## Locked chats

Opening a locked chat already needs authentication ([app lock](app-lock.md)). Publishing is an outward action on top: from a locked chat (and, as for every lock decision, only where the phone has a screen lock), **Publish** asks Android's prompt again right before the upload, every time (`PublishPolicy.needsUnlock`; until the lock's settings are read it asks too). A cancelled prompt publishes nothing.

## Taint

Published content is the user's own choice, so publishing has no taint implications and nothing published is read back into any chat. Tool results stay opt-in, off by default, because they hold what tools read, which may include other people's content.

## Not done

- A page per artifact from a sent `.html` file publishes only that file, not files it links to next to it in the workspace.
- Cloudflare Pages and other hosts (GitHub Pages, S3) aren't offered; the agent's tools still deploy anywhere they support.
- No custom domains or password protection; set those up on the host.

## Phone checklist

1. No deploy integration: Share chat → Publish… says "Nowhere to publish yet" and the link opens Settings → Integrations.
2. With a Netlify token: publish a chat; the Preview shows the page in light and dark (follow the phone's theme); Done's address opens in the browser and shows the same page; the page's source has no `<script>`.
3. Paste a saved key into a message, publish: the Preview says "1 secret removed" and the page shows `[secret]`.
4. Publish again: Update keeps the address; the page changes.
5. Unpublish: the address stops answering; the site is gone from the Netlify dashboard.
6. Repeat 2 and 5 with Cloudflare (workers.dev) and Vercel (the `*.vercel.app` production domain opens without a Vercel sign-in).
7. An HTML artifact with a chart: the globe button publishes it; the full-screen view's button closes it first.
8. Lock the chat, publish: Android's prompt asks before the upload; cancel publishes nothing.
