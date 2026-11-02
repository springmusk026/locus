package dev.locus.core.markdown.artifact

import java.net.URI

/**
 * The sandbox's rules as plain functions, so they can be tested off the device. The WebView that
 * applies them is [ArtifactWebView]; the reasoning and sources are in docs/design/artifacts.md.
 *
 * Each live preview gets a random [id] and exactly two documents on the WebViewAssetLoader origin:
 * - `https://appassets.androidplatform.net/artifact/<id>/index.html`, the host page, which only holds
 * - `…/<id>/content.html`, the artifact, in an `<iframe sandbox="allow-scripts">` (an opaque origin,
 *   so no storage, cookies or access to the host page), under [CONTENT_CSP].
 */
object ArtifactSandbox {
    const val HOST = "appassets.androidplatform.net"

    fun basePath(id: String) = "/artifact/$id/"

    fun hostUrl(id: String) = "https://$HOST${basePath(id)}$HOST_FILE"

    const val HOST_FILE = "index.html"
    const val CONTENT_FILE = "content.html"

    /** What to do with one request the WebView asks about in shouldInterceptRequest. */
    enum class Decision {
        /** Serve the host page. */
        Host,

        /** Serve the artifact. */
        Content,

        /** Let the WebView handle it locally: data: and blob: URLs, which never touch the network. */
        Local,

        /** Answer with an empty 403. Everything else, including other appassets paths. */
        Block,
    }

    /**
     * Allows exactly this preview's two documents, by an exact match on the raw (still-encoded) path,
     * so `%2e%2e` or a different id can't reach anything; data: and blob: stay local; the rest is
     * blocked. A request with a query, a port or user info is blocked too.
     */
    fun decide(url: String, id: String): Decision {
        val uri = runCatching { URI(url) }.getOrNull() ?: return Decision.Block
        val scheme = uri.scheme ?: return Decision.Block
        if (scheme == "data" || scheme == "blob") return Decision.Local
        if (scheme != "https" || !uri.host.equals(HOST, ignoreCase = true)) return Decision.Block
        if (uri.port != -1 || uri.rawUserInfo != null || uri.rawQuery != null) return Decision.Block
        return when (uri.rawPath) {
            basePath(id) + HOST_FILE -> Decision.Host
            basePath(id) + CONTENT_FILE -> Decision.Content
            else -> Decision.Block
        }
    }

    /**
     * A navigation the artifact tried (a tapped link): the URL to offer as "Open in browser?", or null
     * to drop it silently. Only http(s) links from a user gesture are offered; nothing navigates the
     * preview itself.
     */
    fun linkToOffer(url: String, userGesture: Boolean): String? {
        if (!userGesture || url.length > MAX_LINK) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.host.equals(HOST, ignoreCase = true)) return null
        return url
    }

    private const val MAX_LINK = 2048

    /**
     * The artifact's policy: inline and eval'd scripts, styles, images, fonts and media may come from
     * the document itself or from data: and blob: URLs, and nothing else; no fetch, XHR, WebSocket or
     * EventSource anywhere but data:/blob:; no frames, plugins, forms, <base> or manifests. Sent as a
     * response header and repeated as a <meta> tag.
     */
    const val CONTENT_CSP = "default-src 'none'; " +
        "script-src 'unsafe-inline' 'unsafe-eval' data: blob:; " +
        "style-src 'unsafe-inline' data: blob:; " +
        "img-src data: blob:; font-src data: blob:; media-src data: blob:; " +
        "connect-src data: blob:; worker-src blob: data:; " +
        "frame-src 'none'; child-src 'none'; object-src 'none'; manifest-src 'none'; " +
        "base-uri 'none'; form-action 'none'"

    /**
     * The artifact's response header adds `sandbox allow-scripts`, which only works as a header: the
     * document gets an opaque origin even if it's ever loaded outside the host page's sandboxed frame.
     */
    const val CONTENT_HEADER_CSP = "$CONTENT_CSP; sandbox allow-scripts"

    /** The host page runs no script and may only frame this origin. */
    const val HOST_CSP = "default-src 'none'; style-src 'unsafe-inline'; frame-src https://$HOST; base-uri 'none'; form-action 'none'"

    /** Headers on both documents: the policy, no sniffing, nothing cached. */
    fun headers(csp: String): Map<String, String> = mapOf(
        "Content-Security-Policy" to csp,
        "X-Content-Type-Options" to "nosniff",
        "Cache-Control" to "no-store",
        "Referrer-Policy" to "no-referrer",
    )

    /**
     * Runs before any of the artifact's own scripts: removes WebRTC, which CSP doesn't cover and which
     * could reach a STUN server. Defined non-configurable, so the artifact can't put it back, and nested
     * frames (a fresh window) are blocked by frame-src.
     */
    private const val PRELUDE = "(function(){var w=window;" +
        "['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCSessionDescription','RTCIceCandidate']" +
        ".forEach(function(n){try{Object.defineProperty(w,n,{value:undefined,writable:false,configurable:false})}catch(e){}});})();"

    /**
     * The host page: [background] behind the frame (the card's colour, so there's no white flash in
     * dark mode), and the artifact filling it.
     */
    fun hostDocument(title: String, background: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${escapeHtml(title)}</title>" +
            "<style>html,body{margin:0;height:100%;overflow:hidden;background:$background}" +
            "iframe{border:0;display:block;width:100%;height:100%}</style></head>" +
            "<body><iframe sandbox=\"allow-scripts\" src=\"$CONTENT_FILE\" title=\"${escapeHtml(title)}\"></iframe></body></html>"

    /**
     * The artifact as served: the policy and the prelude first, then the model's document. An HTML
     * artifact that styles itself for dark mode (it mentions `color-scheme`) follows the phone's
     * theme; one that doesn't gets a white page, which is what its author saw. An SVG is centred on
     * white paper, since a transparent drawing with dark strokes would vanish on a dark card.
     */
    fun contentDocument(artifact: Artifact): String = buildString {
        append("<!doctype html>\n<meta charset=\"utf-8\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CONTENT_CSP).append("\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        append("<script>").append(PRELUDE).append("</script>\n")
        when (artifact.kind) {
            ArtifactKind.Html -> {
                val darkAware = artifact.content.contains("color-scheme", ignoreCase = true)
                append(if (darkAware) "<style>:where(:root){color-scheme:light dark}</style>\n" else "<style>:where(:root){color-scheme:light;background:#fff;color:#000}</style>\n")
                append(stripPrologue(artifact.content))
            }
            ArtifactKind.Svg -> {
                append("<style>:where(:root){color-scheme:light;background:#fff}body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center}")
                append("svg{max-width:100%;height:auto}</style>\n<body>")
                append(stripPrologue(artifact.content))
                append("</body>")
            }
        }
    }

    /** Drops a leading BOM, XML declaration and doctype: the wrapper supplies its own doctype. */
    internal fun stripPrologue(content: String): String {
        var s = content.removePrefix("\uFEFF").trimStart()
        while (true) {
            val next = when {
                s.startsWith("<?xml", ignoreCase = true) -> s.substringAfter("?>", "")
                s.startsWith("<!doctype", ignoreCase = true) -> s.substringAfter('>', "")
                else -> return s
            }.trimStart()
            s = next
        }
    }

    internal fun escapeHtml(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
