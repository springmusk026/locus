package dev.locus.core.data.connectors

import dev.locus.core.agent.home.Connector
import dev.locus.core.agent.home.Connector.Operation
import dev.locus.core.agent.home.Connector.Param
import dev.locus.core.model.ServiceAddress

/**
 * A self-hosted (or per-instance) service the user can connect in one step from Library → Connectors →
 * Add a service: an HTTP connector with a vetted list of operations, for a server whose address the
 * user types. Every entry was checked against the project's own API documentation or source on
 * 2026-11-06; docs/design/integrations.md lists the sources.
 *
 * Like [dev.locus.core.data.mcp.McpTemplate], the key the user pastes goes to the vault and is tied to
 * the address they typed; the template only says how the service expects it ([auth]).
 */
data class ConnectorTemplate(
    /** Stable id: the connector id it suggests and the key for its icon. */
    val id: String,
    val name: String,
    /** Who makes the software. */
    val vendor: String,
    /** One line for the picker. */
    val description: String,
    /** The API documentation this entry was checked against. */
    val docsUrl: String,
    /** The API's address relative to the user's server: `{host}/v1`. */
    val baseUrl: String,
    /** Shown in the address field: `rss.example.com`. */
    val hostHint: String,
    val auth: AuthSpec,
    val operations: List<Operation>,
    /** A harmless GET, relative to [baseUrl], that needs the key: Test connection. */
    val testPath: String,
    /** Extra guidance shown before adding (which permissions to give the key). */
    val note: String? = null,
) {
    /** How the service takes its credential. */
    data class AuthSpec(
        /** header, bearer or basic ([Connector.Auth.type]). */
        val type: String,
        /** For header: its name. */
        val header: String = "",
        /** For header: text before the key (`Token `). */
        val prefix: String = "",
        /** What the user pastes: "API key", "Access token", "App password". */
        val label: String,
        /** Where in the service to create it, in its own words. */
        val where: String,
        /** For basic and [login]: the label of the user-name field. */
        val usernameLabel: String? = null,
        /** The key is fetched once at setup by signing in ([Login]) instead of pasted. */
        val login: Login? = null,
    ) {
        val needsUsername: Boolean get() = type == "basic" || login != null

        /** How the key travels, for the sheet. */
        val sentAs: String get() = when {
            login != null -> "a sign-in token Locus gets once from your server; the password itself isn't kept"
            type == "basic" -> "HTTP Basic authentication"
            type == "bearer" -> "a bearer token (Authorization: Bearer …)"
            prefix.isNotEmpty() -> "the $header header (${header}: ${prefix.trim()} …)"
            else -> "the $header header"
        }
    }

    /** A sign-in exchange done once at setup, whose result is what's stored. */
    enum class Login {
        /**
         * Google Reader `ClientLogin` (FreshRSS): POST `Email` and `Passwd` to `accounts/ClientLogin`, keep
         * the `Auth=` line. FreshRSS derives it from the API password, so it lasts until that changes
         * (p/api/greader.php, `clientLogin`).
         */
        GOOGLE_READER,
    }

    /** Operations that publish or send as the user; they always ask. */
    val outward: List<String> get() = operations.filter { it.sideEffect == "outward" }.map { it.name }

    /** The connector for the user's server [address] (checked by [ServiceAddress]), its key saved as [secret]. */
    fun connector(id: String, address: String, secret: String): Connector = Connector(
        id = id,
        kind = "http",
        name = name,
        description = description,
        baseUrl = ServiceAddress.fill(baseUrl, address),
        auth = when (auth.type) {
            "bearer" -> Connector.Auth("bearer", secret = secret)
            "basic" -> Connector.Auth("basic", secret = secret)
            else -> Connector.Auth("header", name = auth.header, secret = secret, prefix = auth.prefix)
        },
        operations = operations,
        createdBy = "user",
        template = this.id,
    )
}

object ConnectorTemplates {
    /** What Settings → Integrations passes to Library to open the service picker. */
    const val PICKER = "services"

    private fun q(name: String, description: String, required: Boolean = false) = Param(name, description, "query", required)
    private fun p(name: String, description: String) = Param(name, description, "path", required = true)
    private fun b(name: String, description: String, required: Boolean = false) = Param(name, description, "body", required)

    val all: List<ConnectorTemplate> = listOf(
        ConnectorTemplate(
            id = "miniflux",
            name = "Miniflux",
            vendor = "Miniflux",
            description = "Your RSS reader: unread articles, feeds and categories.",
            docsUrl = "https://miniflux.app/docs/api.html",
            baseUrl = "{host}/v1",
            hostHint = "rss.example.com",
            auth = ConnectorTemplate.AuthSpec(type = "header", header = "X-Auth-Token", label = "API key", where = "Settings → API Keys → Create a new API key"),
            testPath = "/me",
            operations = listOf(
                Operation(
                    "entries", "List articles, newest first by default. For unread ones pass status=unread.", "GET", "/entries",
                    listOf(
                        q("status", "read, unread or removed"),
                        q("limit", "How many, e.g. 20"),
                        q("order", "id, status, published_at, category_title or category_id"),
                        q("direction", "asc or desc"),
                        q("category_id", "Only this category"),
                        q("search", "Text to search for"),
                        q("after", "Only articles published after this Unix time"),
                    ),
                ),
                Operation("entry", "One article with its full content.", "GET", "/entries/{entry_id}", listOf(p("entry_id", "The article's id"))),
                Operation("categories", "Categories; with counts=true, unread counts too.", "GET", "/categories", listOf(q("counts", "true for unread counts"))),
                Operation("feeds", "Subscribed feeds.", "GET", "/feeds"),
                Operation(
                    "mark_entries", "Mark articles read or unread.", "PUT", "/entries",
                    listOf(b("entry_ids", "Array of article ids", required = true), b("status", "read or unread", required = true)),
                    sideEffect = "write",
                ),
                Operation(
                    "subscribe", "Subscribe to a feed.", "POST", "/feeds",
                    listOf(b("feed_url", "The feed's URL", required = true), b("category_id", "Category to put it in")),
                    sideEffect = "write",
                ),
            ),
        ),
        ConnectorTemplate(
            id = "freshrss",
            name = "FreshRSS",
            vendor = "FreshRSS",
            description = "Your RSS reader, through its Google Reader API: unread articles and subscriptions.",
            docsUrl = "https://freshrss.github.io/FreshRSS/en/developers/06_GoogleReader_API.html",
            baseUrl = "{host}/api/greader.php",
            hostHint = "rss.example.com (or …/freshrss)",
            auth = ConnectorTemplate.AuthSpec(
                type = "header", header = "Authorization", prefix = "GoogleLogin auth=",
                label = "API password", where = "Profile → API management (enable the API in Administration → Authentication first)",
                usernameLabel = "FreshRSS user name",
                login = ConnectorTemplate.Login.GOOGLE_READER,
            ),
            testPath = "/reader/api/0/user-info",
            note = "Read-only: marking articles read needs a second token FreshRSS derives from the same secret, which Locus doesn't hand to the AI.",
            operations = listOf(
                Operation(
                    "unread", "Unread articles from all feeds, newest first. Pass c (from the last reply's continuation) for more.", "GET",
                    "/reader/api/0/stream/contents/user/-/state/com.google/reading-list?xt=user/-/state/com.google/read",
                    listOf(q("n", "How many, default 20"), q("c", "Continuation from the previous page")),
                ),
                Operation("subscriptions", "Subscribed feeds.", "GET", "/reader/api/0/subscription/list?output=json"),
                Operation("unread_counts", "Unread counts per feed and category.", "GET", "/reader/api/0/unread-count?output=json"),
            ),
        ),
        ConnectorTemplate(
            id = "paperless",
            name = "Paperless-ngx",
            vendor = "Paperless-ngx",
            description = "Search your scanned documents and read their text.",
            docsUrl = "https://docs.paperless-ngx.com/api/",
            baseUrl = "{host}/api",
            hostHint = "paperless.example.com",
            auth = ConnectorTemplate.AuthSpec(type = "header", header = "Authorization", prefix = "Token ", label = "API token", where = "My Profile → API Auth Token"),
            testPath = "/documents/?page_size=1",
            note = "Read-only. Consider a Paperless user that can only view the documents Locus should see.",
            operations = listOf(
                Operation(
                    "search", "Full-text search of documents (title, content, tags…).", "GET", "/documents/",
                    listOf(q("query", "Search words", required = true), q("page_size", "How many, e.g. 10"), q("page", "Page number")),
                ),
                Operation("document", "One document's details and its text (content).", "GET", "/documents/{id}/", listOf(p("id", "The document's id"))),
                Operation("tags", "Tags.", "GET", "/tags/", listOf(q("page_size", "How many"))),
                Operation("correspondents", "Correspondents.", "GET", "/correspondents/", listOf(q("page_size", "How many"))),
            ),
        ),
        ConnectorTemplate(
            id = "immich",
            name = "Immich",
            vendor = "Immich",
            description = "Find photos and albums in your Immich library.",
            docsUrl = "https://api.immich.app/",
            baseUrl = "{host}/api",
            hostHint = "photos.example.com",
            auth = ConnectorTemplate.AuthSpec(type = "header", header = "x-api-key", label = "API key", where = "Account settings → API keys"),
            testPath = "/users/me",
            note = "Give the key only the asset.read, album.read and user.read permissions. Photo details go to your AI provider when Locus reads them.",
            operations = listOf(
                Operation(
                    "smart_search", "Search photos by what's in them (CLIP).", "POST", "/search/smart",
                    listOf(b("query", "What to look for", required = true), b("size", "How many, default 100"), b("city", "Only in this city"),
                        b("takenAfter", "ISO date"), b("takenBefore", "ISO date")),
                ),
                Operation(
                    "metadata_search", "Search photos by file name, place or date.", "POST", "/search/metadata",
                    listOf(b("originalFileName", "File name"), b("city", "City"), b("country", "Country"), b("takenAfter", "ISO date"), b("takenBefore", "ISO date"), b("size", "How many")),
                ),
                Operation("albums", "Albums.", "GET", "/albums"),
                Operation("album", "One album and its photos.", "GET", "/albums/{id}", listOf(p("id", "The album's id"))),
                Operation("asset", "One photo or video's details (date, place, camera).", "GET", "/assets/{id}", listOf(p("id", "The asset's id"))),
            ),
        ),
        ConnectorTemplate(
            id = "mastodon",
            name = "Mastodon",
            vendor = "Mastodon",
            description = "Read your home timeline and notifications; post only when you confirm.",
            docsUrl = "https://docs.joinmastodon.org/methods/statuses/",
            baseUrl = "{host}/api/v1",
            hostHint = "mastodon.social",
            auth = ConnectorTemplate.AuthSpec(
                type = "bearer", label = "Access token",
                where = "Preferences → Development → New application (scopes read:statuses, read:notifications, read:accounts; write:statuses to post)",
            ),
            testPath = "/accounts/verify_credentials",
            note = "Posting always asks you first and can't be remembered. Leave out write:statuses for a read-only token.",
            operations = listOf(
                Operation(
                    "home_timeline", "Posts from people you follow, newest first.", "GET", "/timelines/home",
                    listOf(q("limit", "How many, at most 40"), q("max_id", "Older than this post id"), q("since_id", "Newer than this post id")),
                ),
                Operation(
                    "notifications", "Your notifications: mentions, follows, boosts, favourites.", "GET", "/notifications",
                    listOf(q("limit", "How many, at most 80"), q("max_id", "Older than this id")),
                ),
                Operation("status", "One post.", "GET", "/statuses/{id}", listOf(p("id", "The post's id"))),
                Operation(
                    "post", "Publish a post from your account.", "POST", "/statuses",
                    listOf(b("status", "The text", required = true), b("visibility", "public, unlisted, private or direct"),
                        b("in_reply_to_id", "Reply to this post"), b("spoiler_text", "Content warning"), b("language", "ISO 639-1 code")),
                    sideEffect = "outward",
                ),
            ),
        ),
        ConnectorTemplate(
            id = "vikunja",
            name = "Vikunja",
            vendor = "Vikunja",
            description = "Your to-do lists: find tasks and projects, add tasks.",
            docsUrl = "https://vikunja.io/docs/api-documentation",
            baseUrl = "{host}/api/v1",
            hostHint = "tasks.example.com",
            auth = ConnectorTemplate.AuthSpec(type = "bearer", label = "API token", where = "Settings → API Tokens (give it the tasks and projects permissions)"),
            testPath = "/user",
            operations = listOf(
                Operation(
                    "tasks", "Tasks across your projects. filter uses Vikunja's syntax, e.g. done = false && due_date < now+7d.", "GET", "/tasks",
                    listOf(q("filter", "Filter query"), q("s", "Search text"), q("sort_by", "e.g. due_date"), q("order_by", "asc or desc"), q("per_page", "How many"), q("page", "Page")),
                ),
                Operation("task", "One task.", "GET", "/tasks/{id}", listOf(p("id", "The task's id"))),
                Operation("projects", "Projects.", "GET", "/projects"),
                Operation(
                    "add_task", "Add a task to a project.", "PUT", "/projects/{id}/tasks",
                    listOf(p("id", "The project's id"), b("title", "Task title", required = true), b("description", "Details"),
                        b("due_date", "RFC 3339 date-time"), b("priority", "0 (unset) to 5 (do now)")),
                    sideEffect = "write",
                ),
            ),
        ),
    )

    fun byId(id: String): ConnectorTemplate? = all.firstOrNull { it.id == id }
}
