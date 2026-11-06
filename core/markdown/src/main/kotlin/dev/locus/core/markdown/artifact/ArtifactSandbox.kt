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
     * The host page: [background] (the card's colour) around the frame, and the artifact filling it.
     * The frame is painted with the system `Canvas` colour of [frameScheme], the page's own declared
     * `color-scheme` ([frameScheme] of the artifact), so the page sits on the background a browser
     * would give it: white unless it declares dark support and the phone is dark. Painting the frame
     * rather than the page's root leaves the page's own `body` background free to fill its whole
     * canvas, as in a browser.
     */
    fun hostDocument(title: String, background: String, frameScheme: String = NORMAL): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>${escapeHtml(title)}</title>" +
            "<style>html,body{margin:0;height:100%;overflow:hidden;background:$background}" +
            "iframe{border:0;display:block;width:100%;height:100%;color-scheme:${sanitizeScheme(frameScheme)};background:Canvas}</style></head>" +
            "<body><iframe sandbox=\"allow-scripts\" src=\"$CONTENT_FILE\" title=\"${escapeHtml(title)}\"></iframe></body></html>"

    private const val NORMAL = "normal"

    /** The `color-scheme` the frame is painted for: the page's own, and always light behind an SVG. */
    fun frameScheme(artifact: Artifact): String = when (artifact.kind) {
        ArtifactKind.Html -> pageColorScheme(artifact.content)
        ArtifactKind.Svg -> "light"
    }

    /**
     * The `color-scheme` an HTML page declares, from `<meta name="color-scheme">` or else the first CSS
     * `color-scheme:` declaration, as plain keywords (`light`, `dark`, `only`, `normal`); "normal" when
     * it declares none. A page that declares nothing is a light page in every browser whatever the
     * phone's theme, and its default text is black. Mentioning `prefers-color-scheme` is not a
     * declaration: such a page paints its own dark colours.
     */
    fun pageColorScheme(html: String): String {
        val meta = META_TAG.findAll(html).firstOrNull { tag -> attr(tag.value, "name")?.trim().equals("color-scheme", ignoreCase = true) }
        val declared = meta?.let { attr(it.value, "content") } ?: CSS_SCHEME.find(html)?.groupValues?.get(1)
        return sanitizeScheme(declared ?: NORMAL)
    }

    /**
     * The canvas a browser gives a page with [scheme] ([pageColorScheme]) on a phone that is [phoneDark],
     * as ARGB: Chromium's dark `Canvas` (#121212) only when the page supports dark and the phone is dark,
     * white otherwise. For a WebView showing a page directly, whose own background would show through.
     */
    fun canvasColor(scheme: String, phoneDark: Boolean): Int {
        val words = sanitizeScheme(scheme).split(' ')
        val dark = "dark" in words && (phoneDark || "light" !in words)
        return if (dark) DARK_CANVAS else LIGHT_CANVAS
    }

    const val LIGHT_CANVAS: Int = -0x1 // 0xFFFFFFFF
    const val DARK_CANVAS: Int = -0xededee // 0xFF121212

    /** Only color-scheme keywords, so nothing from the page reaches the host page's stylesheet. */
    internal fun sanitizeScheme(value: String): String =
        WORD.findAll(value.lowercase()).map { it.value }.filter { it in SCHEME_WORDS }.distinct().joinToString(" ").ifEmpty { NORMAL }

    /** What a page asks for from other origins, which the sandbox refuses ([CONTENT_CSP]). */
    data class WebResources(val styles: Int = 0, val scripts: Int = 0, val other: Int = 0) {
        val any: Boolean get() = styles + scripts + other > 0
    }

    /**
     * The stylesheets, scripts and other files (fonts, images, media, frames) [html] loads from the web:
     * `http(s)://` or protocol-relative `//` URLs in `<link rel=stylesheet>`, `<script src>`, CSS
     * `@import` and `url()`, and `src`/`href` on media tags. Read from the source because the CSP refuses
     * them inside the renderer, before there is a request the WebView could count.
     */
    fun webResources(html: String): WebResources {
        var styles = 0
        var scripts = 0
        var other = 0
        for (tag in TAG.findAll(html)) {
            val attrs = tag.groupValues[2]
            when (tag.groupValues[1].lowercase()) {
                "script" -> if (attr(attrs, "src")?.let(::isWeb) == true) scripts++
                "link" -> if (attr(attrs, "href")?.let(::isWeb) == true) {
                    val rel = attr(attrs, "rel").orEmpty().lowercase().split(SPACES)
                    val asType = attr(attrs, "as")?.lowercase()
                    when {
                        "stylesheet" in rel -> styles++
                        "modulepreload" in rel || ("preload" in rel && asType == "script") -> scripts++
                        "preload" in rel && asType == "style" -> styles++
                        "preload" in rel -> other++
                    }
                }
                in MEDIA_TAGS -> if (MEDIA_ATTRS.any { a -> attr(attrs, a)?.let(::isWeb) == true }) other++
            }
        }
        val imports = CSS_IMPORT.findAll(html).filter { m -> isWeb(m.groupValues.drop(1).first { it.isNotEmpty() }) }.toList()
        styles += imports.size
        other += CSS_URL.findAll(html).count { m ->
            isWeb(m.groupValues[1].trim().trim('"', '\'')) && imports.none { m.range.first in it.range }
        }
        return WebResources(styles, scripts, other)
    }

    /** The card's footer in plain words: what the page wanted from the web and didn't get. Null for nothing. */
    fun blockedNotice(resources: WebResources): String? = when {
        resources.styles > 0 && resources.scripts > 0 -> "Styles and scripts from the web are blocked here"
        resources.styles > 0 -> "Styles from the web are blocked here"
        resources.scripts > 0 -> "Scripts from the web are blocked here"
        resources.other > 0 -> "Images and fonts from the web are blocked here"
        else -> null
    }

    private fun isWeb(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("//")
    }

    /** One attribute's value from a tag's text, quoted or not. */
    private fun attr(attrs: String, name: String): String? =
        Regex("""(?:^|[\s"'/])${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>"']+))""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }

    private val SPACES = Regex("[\\s,]+")
    private val WORD = Regex("[a-z]+")
    private val SCHEME_WORDS = setOf("normal", "light", "dark", "only")
    private val META_TAG = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val CSS_SCHEME = Regex("""(?<![\w-])color-scheme\s*:\s*([a-z\s]+?)\s*(?:!important\s*)?[;}"']""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("""<([a-zA-Z][a-zA-Z0-9-]*)\b([^>]*)>""")
    private val MEDIA_TAGS = setOf("img", "image", "video", "audio", "source", "iframe", "embed", "object", "track", "use")
    private val MEDIA_ATTRS = listOf("src", "href", "xlink:href", "data", "poster", "srcset")
    private val CSS_IMPORT = Regex("""@import\s+(?:url\(\s*["']?([^"')\s]+)["']?\s*\)|"([^"]+)"|'([^']+)')""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*([^)]+)\)""", RegexOption.IGNORE_CASE)

    /**
     * The artifact as served: the policy and the prelude first, then the model's document. An HTML
     * page gets nothing else: its colours are its own, on the frame [hostDocument] paints for its
     * declared `color-scheme`. An SVG is centred on white paper, since a transparent drawing with
     * dark strokes would vanish on a dark card.
     */
    fun contentDocument(artifact: Artifact): String = buildString {
        append("<!doctype html>\n<meta charset=\"utf-8\">\n")
        append("<meta http-equiv=\"Content-Security-Policy\" content=\"").append(CONTENT_CSP).append("\">\n")
        append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
        append("<script>").append(PRELUDE).append("</script>\n")
        when (artifact.kind) {
            ArtifactKind.Html -> append(stripPrologue(artifact.content))
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
