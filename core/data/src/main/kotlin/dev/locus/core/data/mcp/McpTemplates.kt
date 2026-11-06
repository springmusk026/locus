package dev.locus.core.data.mcp

import dev.locus.core.agent.home.Connector
import java.net.URI

/**
 * A known MCP server the user can add in one step from Library → Connectors. Every entry was checked
 * against the vendor's own documentation; docs/design/mcp-connectors.md lists the sources.
 *
 * Secrets the user pastes go to the vault like any connector key; the template only says how the
 * server expects them ([key]).
 */
data class McpTemplate(
    /** Stable id, also the connector id it suggests and the key for its logo. */
    val id: String,
    val name: String,
    /** Who runs the server ("GitHub", "Upstash"). */
    val vendor: String,
    /** One line for the picker. */
    val description: String,
    val category: Category,
    /** The vendor's page describing the server; the source for this entry. */
    val docsUrl: String,
    /** Streamable HTTP endpoint, or empty for a [local] server. */
    val url: String = "",
    /** Whether the server supports browser sign-in (OAuth with dynamic client registration). */
    val signIn: Boolean = false,
    /** How a pasted key or token is sent, or null when the server takes none. */
    val key: Key? = null,
    /** A local (stdio) server Locus runs in its Linux system, instead of [url]. */
    val local: Local? = null,
    /** Extra guidance shown before adding (URL options, plan limits). */
    val note: String? = null,
    /** The built-in integration this server can stand in for (Settings → Integrations), if any. */
    val replaces: String? = null,
    /**
     * A server the user runs themselves: [url] is empty and the address comes from the user
     * ([SelfHosted.path] is appended to it). Never "official", so its tool annotations aren't trusted.
     */
    val selfHosted: SelfHosted? = null,
    /** Written by the community, not the vendor: named as such, and its annotations aren't trusted. */
    val community: Boolean = false,
) {
    /**
     * The user's own server: the MCP endpoint is their address plus [path] (or [fullPath] when they
     * ask for full access), and [testPath] is a harmless GET for Test connection.
     */
    data class SelfHosted(
        val path: String,
        /** e.g. `homeassistant.local:8123`. */
        val hostHint: String,
        val testPath: String,
        /** A wider endpoint offered behind an explicit switch, with [fullWarning]. */
        val fullPath: String? = null,
        val fullWarning: String? = null,
    )

    enum class Category(val label: String) {
        CODE("Code & issues"),
        DOCS("Docs & knowledge"),
        DEPLOY("Deploy & hosting"),
        DATA("Databases"),
        DESIGN("Design"),
        WORK("Work & planning"),
        OBSERVE("Monitoring"),
        PAYMENTS("Payments"),
        SEARCH("Search & web"),
        HOME("Home & personal"),
        LOCAL("Runs in Linux"),
    }

    /**
     * A key or token: sent as `Authorization: Bearer` ([header] null), as the header [header], or as
     * the query parameter [query]. [optional] keys can be left out (a free tier, or sign-in instead).
     */
    data class Key(
        val label: String,
        /** Where to create one. */
        val url: String,
        /** Scopes or permissions it needs, in the vendor's words. */
        val scopes: String? = null,
        val header: String? = null,
        val query: String? = null,
        val optional: Boolean = false,
    ) {
        /** The connector auth for a key saved under vault name [secret]. */
        fun auth(secret: String) = when {
            header != null -> Connector.Auth("header", name = header, secret = secret)
            query != null -> Connector.Auth("query", name = query, secret = secret)
            else -> Connector.Auth("bearer", secret = secret)
        }

        val sentAs: String get() = when {
            header != null -> "the $header header"
            query != null -> "the $query URL parameter"
            else -> "a bearer token"
        }
    }

    /**
     * A stdio server: [command] run in /workspace; [env] names variables whose values the user pastes.
     * [labels] says what each one is in words ("app password"); every variable needs one, so the picker
     * and the form never show a raw name. [plain] variables aren't secret (an address, a user name) and
     * are typed unmasked; all of them still go to the vault.
     */
    data class Local(
        val command: String,
        val needs: String,
        val env: List<String> = emptyList(),
        val labels: Map<String, String> = emptyMap(),
        val plain: Set<String> = emptySet(),
    ) {
        fun label(name: String): String = labels[name] ?: name
    }

    val host: String get() = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    /** Who wrote the server, for the sheet's first line. */
    val byline: String get() = when {
        community -> "Community server by ${vendor}"
        category == Category.LOCAL -> "Reference server from the MCP project"
        selfHosted != null -> "Built into ${vendor}, on your own server"
        else -> "Official server by $vendor"
    }

    /** What the user must do to connect, in a few words. */
    val authSummary: String get() = when {
        local != null && local.env.isEmpty() -> "No key needed"
        local != null -> "Needs " + words(local.env.map { lowerLabel(local.label(it)) })
        signIn && key != null -> "Sign in, or use ${article(lowerLabel(key.label))}"
        signIn -> "Sign in with your account"
        key == null -> "No key needed"
        key.optional -> "${key.label} optional"
        else -> "Needs ${article(lowerLabel(key.label))}"
    }
}

/** "Access token" → "access token"; "API key" keeps its capitals. */
internal fun lowerLabel(label: String): String {
    val first = label.substringBefore(' ')
    return if (first.length > 1 && first.all { it.isUpperCase() || it.isDigit() }) label else label.replaceFirstChar { it.lowercase() }
}

/** "a token", "an API key"; a label already starting with "your" stays as it is. */
private fun article(label: String): String = when {
    label.startsWith("your ") -> label
    label.first().lowercaseChar() in "aeiou" -> "an $label"
    else -> "a $label"
}

/** "a, b and c". */
private fun words(items: List<String>): String = if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + " and " + items.last()

object McpTemplates {
    val all: List<McpTemplate> = listOf(
        // ---- Code & issues ------------------------------------------------------------------------
        McpTemplate(
            id = "github",
            name = "GitHub",
            vendor = "GitHub",
            description = "Repositories, issues, pull requests, Actions and code security.",
            category = McpTemplate.Category.CODE,
            docsUrl = "https://github.com/github/github-mcp-server/blob/main/docs/remote-server.md",
            url = "https://api.githubcopilot.com/mcp/",
            key = McpTemplate.Key(
                "Personal access token",
                url = "https://github.com/settings/personal-access-tokens/new",
                scopes = "access to the repositories you want; classic tokens need repo and read:org",
            ),
            note = "GitHub's sign-in only works with apps GitHub has registered, so Locus uses a token. " +
                "For read-only access, end the URL with /mcp/readonly.",
            replaces = "github",
        ),
        McpTemplate(
            id = "gitlab",
            name = "GitLab",
            vendor = "GitLab",
            description = "Projects, issues, merge requests and pipelines on GitLab.com.",
            category = McpTemplate.Category.CODE,
            docsUrl = "https://docs.gitlab.com/user/model_context_protocol/mcp_server/",
            url = "https://gitlab.com/api/v4/mcp",
            signIn = true,
            key = McpTemplate.Key(
                "Fine-grained access token",
                url = "https://gitlab.com/-/user_settings/personal_access_tokens",
                scopes = "the MCP tool resource with Execute permission",
            ),
            note = "Self-managed GitLab: use https://<your host>/api/v4/mcp. Your top-level group must allow MCP access.",
        ),
        McpTemplate(
            id = "linear",
            name = "Linear",
            vendor = "Linear",
            description = "Find, create and update issues, projects and comments.",
            category = McpTemplate.Category.WORK,
            docsUrl = "https://linear.app/docs/mcp",
            url = "https://mcp.linear.app/mcp",
            signIn = true,
            key = McpTemplate.Key("API key", url = "https://linear.app/settings/account/security", scopes = "Read for read-only; Write to change issues"),
            note = "For read-only access, end the URL with /mcp/readonly.",
        ),
        McpTemplate(
            id = "sentry",
            name = "Sentry",
            vendor = "Sentry",
            description = "Errors, issues, traces and releases from your Sentry projects.",
            category = McpTemplate.Category.OBSERVE,
            docsUrl = "https://mcp.sentry.dev/",
            url = "https://mcp.sentry.dev/mcp",
            signIn = true,
            note = "To limit it to one organisation or project, add them to the URL: /mcp/<org>/<project>.",
        ),

        // ---- Docs & knowledge ---------------------------------------------------------------------
        McpTemplate(
            id = "context7",
            name = "Context7",
            vendor = "Upstash",
            description = "Up-to-date docs and code examples for thousands of libraries.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://context7.com/docs/resources/all-clients",
            url = "https://mcp.context7.com/mcp",
            key = McpTemplate.Key("API key", url = "https://context7.com/dashboard", optional = true),
            note = "Works without a key at lower rate limits.",
        ),
        McpTemplate(
            id = "deepwiki",
            name = "DeepWiki",
            vendor = "Cognition",
            description = "Ask questions about any public GitHub repository's code and docs.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://docs.devin.ai/work-with-devin/deepwiki-mcp",
            url = "https://mcp.deepwiki.com/mcp",
        ),
        McpTemplate(
            id = "microsoft-learn",
            name = "Microsoft Learn",
            vendor = "Microsoft",
            description = "Search and read official Microsoft and Azure documentation and code samples.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://learn.microsoft.com/en-us/training/support/mcp",
            url = "https://learn.microsoft.com/api/mcp",
        ),
        McpTemplate(
            id = "aws-knowledge",
            name = "AWS Knowledge",
            vendor = "AWS",
            description = "AWS documentation, API references, What's New posts and architecture guidance.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://awslabs.github.io/mcp/servers/aws-knowledge-mcp-server",
            url = "https://knowledge-mcp.global.api.aws",
        ),
        McpTemplate(
            id = "cloudflare-docs",
            name = "Cloudflare Docs",
            vendor = "Cloudflare",
            description = "Search Cloudflare's developer documentation.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/",
            url = "https://docs.mcp.cloudflare.com/mcp",
        ),
        McpTemplate(
            id = "huggingface",
            name = "Hugging Face",
            vendor = "Hugging Face",
            description = "Search models, datasets, Spaces, papers and the Hugging Face docs.",
            category = McpTemplate.Category.DOCS,
            docsUrl = "https://huggingface.co/docs/hub/hf-mcp-server",
            url = "https://huggingface.co/mcp",
            key = McpTemplate.Key("Access token", url = "https://huggingface.co/settings/tokens", scopes = "Read; Write to create repos or run Jobs", optional = true),
            note = "Without a token you get the public tools only. Choose extra tools at huggingface.co/settings/mcp.",
        ),

        // ---- Deploy & hosting ---------------------------------------------------------------------
        McpTemplate(
            id = "cloudflare",
            name = "Cloudflare",
            vendor = "Cloudflare",
            description = "The whole Cloudflare API (Workers, DNS, R2, D1…) through search and execute.",
            category = McpTemplate.Category.DEPLOY,
            docsUrl = "https://developers.cloudflare.com/agents/model-context-protocol/mcp-servers-for-cloudflare/",
            url = "https://mcp.cloudflare.com/mcp",
            signIn = true,
            key = McpTemplate.Key("API token", url = "https://dash.cloudflare.com/profile/api-tokens", scopes = "only the permissions you want Locus to use"),
            replaces = "cloudflare",
        ),
        McpTemplate(
            id = "netlify",
            name = "Netlify",
            vendor = "Netlify",
            description = "Create, deploy and manage Netlify sites, forms and environment variables.",
            category = McpTemplate.Category.DEPLOY,
            docsUrl = "https://docs.netlify.com/build/build-with-ai/netlify-mcp-server/",
            url = "https://netlify-mcp.netlify.app/mcp",
            signIn = true,
            replaces = "netlify",
        ),
        McpTemplate(
            id = "render",
            name = "Render",
            vendor = "Render",
            description = "Services, deploys, logs, metrics and read-only Postgres queries on Render.",
            category = McpTemplate.Category.DEPLOY,
            docsUrl = "https://render.com/docs/mcp-server",
            url = "https://mcp.render.com/mcp",
            key = McpTemplate.Key("API key", url = "https://dashboard.render.com/u/settings?add-api-key", scopes = "a key can reach every workspace you belong to"),
            replaces = "render",
        ),
        McpTemplate(
            id = "railway",
            name = "Railway",
            vendor = "Railway",
            description = "Projects, services, deployments, variables and logs on Railway.",
            category = McpTemplate.Category.DEPLOY,
            docsUrl = "https://docs.railway.com/reference/mcp-server",
            url = "https://mcp.railway.com",
            signIn = true,
        ),

        // ---- Databases ----------------------------------------------------------------------------
        McpTemplate(
            id = "supabase",
            name = "Supabase",
            vendor = "Supabase",
            description = "Tables, SQL, migrations, edge functions, logs and docs for your projects.",
            category = McpTemplate.Category.DATA,
            docsUrl = "https://supabase.com/docs/guides/getting-started/mcp",
            url = "https://mcp.supabase.com/mcp",
            signIn = true,
            key = McpTemplate.Key("Personal access token", url = "https://supabase.com/dashboard/account/tokens"),
            note = "Safer: add ?project_ref=<your project>&read_only=true to the URL to stick to one project and run SQL as a read-only user.",
            replaces = "supabase",
        ),
        McpTemplate(
            id = "neon",
            name = "Neon",
            vendor = "Neon",
            description = "Serverless Postgres: projects, branches, SQL and migrations.",
            category = McpTemplate.Category.DATA,
            docsUrl = "https://neon.com/docs/ai/neon-mcp-server",
            url = "https://mcp.neon.tech/mcp",
            signIn = true,
            key = McpTemplate.Key("API key", url = "https://neon.com/docs/manage/api-keys#creating-api-keys"),
        ),
        McpTemplate(
            id = "prisma",
            name = "Prisma Postgres",
            vendor = "Prisma",
            description = "Create and manage Prisma Postgres databases, backups and connection strings.",
            category = McpTemplate.Category.DATA,
            docsUrl = "https://www.prisma.io/docs/postgres/integrations/mcp-server",
            url = "https://mcp.prisma.io/mcp",
            signIn = true,
        ),
        McpTemplate(
            id = "mongodb",
            name = "MongoDB",
            vendor = "MongoDB",
            description = "Query and inspect a MongoDB or Atlas database, read-only.",
            category = McpTemplate.Category.DATA,
            docsUrl = "https://github.com/mongodb-js/mongodb-mcp-server",
            local = McpTemplate.Local(
                command = "npx -y mongodb-mcp-server@latest --readOnly",
                needs = "Node.js (apk add nodejs npm)",
                env = listOf("MDB_MCP_CONNECTION_STRING"),
                labels = mapOf("MDB_MCP_CONNECTION_STRING" to "your database's connection string"),
            ),
            note = "MongoDB's hosted server needs a sign-in Locus can't do, so this one runs in Linux. Remove --readOnly to allow writes.",
        ),

        // ---- Design -------------------------------------------------------------------------------
        McpTemplate(
            id = "stitch",
            name = "Google Stitch",
            vendor = "Google",
            description = "Generate and edit UI designs and screens with Stitch, and get their code.",
            category = McpTemplate.Category.DESIGN,
            docsUrl = "https://stitch.withgoogle.com/docs/mcp/setup",
            url = "https://stitch.googleapis.com/mcp",
            key = McpTemplate.Key(
                "API key",
                url = "https://stitch.withgoogle.com/docs/mcp/setup",
                scopes = "create one in Stitch → Settings → API Keys",
                header = "X-Goog-Api-Key",
            ),
        ),

        // ---- Work & planning ----------------------------------------------------------------------
        McpTemplate(
            id = "notion",
            name = "Notion",
            vendor = "Notion",
            description = "Search, read and write pages and databases in your workspace.",
            category = McpTemplate.Category.WORK,
            docsUrl = "https://developers.notion.com/docs/get-started-with-mcp",
            url = "https://mcp.notion.com/mcp",
            signIn = true,
        ),
        McpTemplate(
            id = "atlassian",
            name = "Atlassian",
            vendor = "Atlassian",
            description = "Jira issues and Confluence pages through Atlassian's Rovo MCP server.",
            category = McpTemplate.Category.WORK,
            docsUrl = "https://support.atlassian.com/atlassian-rovo-mcp-server/docs/getting-started-with-the-atlassian-remote-mcp-server/",
            url = "https://mcp.atlassian.com/v2/mcp",
            signIn = true,
            note = "Your organisation's admin may need to allow the Rovo MCP server first.",
        ),
        McpTemplate(
            id = "postman",
            name = "Postman",
            vendor = "Postman",
            description = "Workspaces, collections, environments and API specs in Postman.",
            category = McpTemplate.Category.WORK,
            docsUrl = "https://learning.postman.com/docs/developer/postman-api/postman-mcp-server/set-up-postman-mcp-server/",
            url = "https://mcp.postman.com/minimal",
            signIn = true,
            key = McpTemplate.Key("API key", url = "https://learning.postman.com/docs/reference/postman-api/authentication/"),
            note = "/minimal has the essential tools; use /mcp for all of them (many more tools in every chat). EU accounts: mcp.eu.postman.com with an API key.",
        ),

        // ---- Monitoring ---------------------------------------------------------------------------
        McpTemplate(
            id = "posthog",
            name = "PostHog",
            vendor = "PostHog",
            description = "Product analytics, feature flags, experiments and error tracking.",
            category = McpTemplate.Category.OBSERVE,
            docsUrl = "https://posthog.com/docs/model-context-protocol",
            url = "https://mcp.posthog.com/mcp",
            signIn = true,
            key = McpTemplate.Key("Personal API key", url = "https://app.posthog.com/settings/user-api-keys?preset=mcp_server", scopes = "the MCP Server preset"),
        ),
        McpTemplate(
            id = "grafana",
            name = "Grafana Cloud",
            vendor = "Grafana Labs",
            description = "Dashboards, metrics, logs and alerts in your Grafana Cloud stack.",
            category = McpTemplate.Category.OBSERVE,
            docsUrl = "https://grafana.com/docs/grafana-cloud/platform/grafana-assistant/configure/cloud-mcp/",
            url = "https://mcp.grafana.com/mcp",
            signIn = true,
        ),

        // ---- Payments -----------------------------------------------------------------------------
        McpTemplate(
            id = "stripe",
            name = "Stripe",
            vendor = "Stripe",
            description = "Customers, payments, invoices, subscriptions and the Stripe docs.",
            category = McpTemplate.Category.PAYMENTS,
            docsUrl = "https://docs.stripe.com/mcp",
            url = "https://mcp.stripe.com",
            signIn = true,
            key = McpTemplate.Key("Agent API key", url = "https://docs.stripe.com/keys#agent-keys", scopes = "an agent key; from December 2026 secret and restricted keys stop working"),
            note = "Use a test-mode key until you trust it with live money.",
        ),

        // ---- Search & web -------------------------------------------------------------------------
        McpTemplate(
            id = "firecrawl",
            name = "Firecrawl",
            vendor = "Firecrawl",
            description = "Search the web, scrape pages, map and crawl sites.",
            category = McpTemplate.Category.SEARCH,
            docsUrl = "https://docs.firecrawl.dev/mcp-server",
            url = "https://mcp.firecrawl.dev/v2/mcp",
            key = McpTemplate.Key("API key", url = "https://www.firecrawl.dev/app/api-keys", header = "x-firecrawl-api-key", optional = true),
            note = "Works without a key for search and scraping at low limits.",
            replaces = "firecrawl",
        ),
        McpTemplate(
            id = "tavily",
            name = "Tavily",
            vendor = "Tavily",
            description = "Web search, extract, crawl and research built for AI agents.",
            category = McpTemplate.Category.SEARCH,
            docsUrl = "https://docs.tavily.com/documentation/mcp",
            url = "https://mcp.tavily.com/mcp/",
            signIn = true,
            key = McpTemplate.Key("API key", url = "https://app.tavily.com/home", query = "tavilyApiKey"),
            replaces = "tavily",
        ),
        McpTemplate(
            id = "exa",
            name = "Exa",
            vendor = "Exa",
            description = "Neural web search and page fetching.",
            category = McpTemplate.Category.SEARCH,
            docsUrl = "https://docs.exa.ai/reference/exa-mcp",
            url = "https://mcp.exa.ai/mcp",
            key = McpTemplate.Key("API key", url = "https://dashboard.exa.ai/api-keys", header = "x-api-key", optional = true),
            note = "Works without a key at free-tier rate limits.",
            replaces = "exa",
        ),
        McpTemplate(
            id = "brave-local",
            name = "Brave Search",
            vendor = "Brave",
            description = "Web, news, image, video and local search from Brave's own index.",
            category = McpTemplate.Category.SEARCH,
            docsUrl = "https://github.com/brave/brave-search-mcp-server",
            local = McpTemplate.Local(
                command = "npx -y @brave/brave-search-mcp-server",
                needs = "Node.js (apk add nodejs npm)",
                env = listOf("BRAVE_API_KEY"),
                labels = mapOf("BRAVE_API_KEY" to "a Brave Search API key"),
            ),
            note = "Brave has no hosted server, so this runs in Linux. The built-in Brave Search integration does the same without Linux.",
        ),

        // ---- Home & personal ----------------------------------------------------------------------
        McpTemplate(
            id = "home-assistant",
            name = "Home Assistant",
            vendor = "Home Assistant",
            description = "Check and control the devices you exposed to Assist on your own Home Assistant.",
            category = McpTemplate.Category.HOME,
            // Checked against the integration page and the server code (homeassistant/components/mcp_server/http.py,
            // 2026.11.0): /api/mcp/<api id> serves one LLM API, and only the Assist API ("assist") works without an
            // admin account. Long-lived tokens are sent as Bearer like any access token (components/http/auth.py).
            docsUrl = "https://www.home-assistant.io/integrations/mcp_server/",
            key = McpTemplate.Key(
                "Long-lived access token",
                url = "https://www.home-assistant.io/docs/authentication/#your-account-profile",
                scopes = "a token of a non-administrator user is enough; it sees only what you exposed to Assist",
            ),
            selfHosted = McpTemplate.SelfHosted(
                path = "/api/mcp/assist",
                hostHint = "homeassistant.local:8123",
                testPath = "/api/",
                fullPath = "/api/mcp",
                fullWarning = "Full access uses /api/mcp: every tool set you enabled, which can include reading all entities, " +
                    "history and logs and calling any action. It needs an administrator's token.",
            ),
            note = "Turn on the Model Context Protocol Server integration in Home Assistant first (Settings → Devices & services). " +
                "Create the token under your profile → Security → Long-lived access tokens.",
        ),
        McpTemplate(
            id = "todoist",
            name = "Todoist",
            vendor = "Doist",
            description = "Find, add, update and complete your tasks and projects.",
            category = McpTemplate.Category.HOME,
            // Checked 2026-11-06: the server's protected-resource metadata names https://todoist.com, whose
            // RFC 8414 metadata advertises a registration_endpoint, and registering a native client with
            // Locus's loopback redirect returned 201.
            docsUrl = "https://github.com/Doist/todoist-ai",
            url = "https://ai.todoist.net/mcp",
            signIn = true,
            note = "Locus opens Todoist's sign-in in your browser. The server asks for read and write access to your tasks.",
        ),
        McpTemplate(
            id = "nextcloud",
            name = "Nextcloud",
            vendor = "Chris Coutinho (AGPL-3.0)",
            description = "Files, notes, calendar, contacts, tasks and Deck on your Nextcloud.",
            category = McpTemplate.Category.HOME,
            docsUrl = "https://github.com/cbcoutinho/nextcloud-mcp-server",
            // Pinned: a community package, so a new release only runs after you change this.
            local = McpTemplate.Local(
                command = "uvx nextcloud-mcp-server@0.204.3 run --transport stdio",
                needs = "uv (apk add uv); the first start downloads its Python packages",
                env = listOf("NEXTCLOUD_HOST", "NEXTCLOUD_USERNAME", "NEXTCLOUD_PASSWORD"),
                labels = mapOf(
                    "NEXTCLOUD_HOST" to "your Nextcloud address",
                    "NEXTCLOUD_USERNAME" to "user name",
                    "NEXTCLOUD_PASSWORD" to "app password",
                ),
                plain = setOf("NEXTCLOUD_HOST", "NEXTCLOUD_USERNAME"),
            ),
            community = true,
            note = "The address is your server's, with https://. Use an app password from Nextcloud → Settings → Security → " +
                "Devices & sessions, not your login password: you can revoke it there.",
        ),

        // ---- Runs in Linux (reference servers) ----------------------------------------------------
        McpTemplate(
            id = "filesystem",
            name = "Files",
            vendor = "MCP project",
            description = "Read, write and search files in your workspace.",
            category = McpTemplate.Category.LOCAL,
            docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem",
            local = McpTemplate.Local("npx -y @modelcontextprotocol/server-filesystem /workspace", "Node.js (apk add nodejs npm)"),
        ),
        McpTemplate(
            id = "git",
            name = "Git",
            vendor = "MCP project",
            description = "Status, diffs, log, commits and branches of the repository in your workspace.",
            category = McpTemplate.Category.LOCAL,
            docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/git",
            local = McpTemplate.Local("uvx mcp-server-git --repository /workspace", "uv and git (apk add uv git)"),
        ),
        McpTemplate(
            id = "fetch",
            name = "Fetch",
            vendor = "MCP project",
            description = "Fetch a web page and turn it into Markdown.",
            category = McpTemplate.Category.LOCAL,
            docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/fetch",
            local = McpTemplate.Local("uvx mcp-server-fetch", "uv (apk add uv)"),
        ),
        McpTemplate(
            id = "memory",
            name = "Knowledge graph",
            vendor = "MCP project",
            description = "A local knowledge graph of entities and relations that persists between chats.",
            category = McpTemplate.Category.LOCAL,
            docsUrl = "https://github.com/modelcontextprotocol/servers/tree/main/src/memory",
            local = McpTemplate.Local("npx -y @modelcontextprotocol/server-memory", "Node.js (apk add nodejs npm)"),
        ),
    )

    fun byId(id: String): McpTemplate? = all.firstOrNull { it.id == id }

    /** The template that stands in for built-in integration [integrationId], if any. */
    fun forIntegration(integrationId: String): McpTemplate? = all.firstOrNull { it.replaces == integrationId }

    /**
     * The template [connector] was made from, when it reaches the vendor's own server: the same
     * https host, or the same local command. Locus trusts tool annotations from these servers.
     */
    /**
     * The id to draw [connector]'s logo by (`ConnectorIcon` in core/designsystem), the same ids the
     * template picker uses: the template it was made from (by its server, or by the id it was saved
     * under, possibly with a `-mcp` suffix), else, for a connector Locus drafted for a service, its
     * own id (a built-in integration's logo, when it has one). Null for a custom MCP server: it
     * gets a monogram.
     */
    fun iconId(connector: Connector): String? =
        official(connector)?.id
            ?: byId(connector.id)?.id
            ?: byId(connector.id.removeSuffix("-mcp"))?.id
            ?: connector.id.takeIf { connector.kind != "mcp" }

    fun official(connector: Connector): McpTemplate? = vendorTemplate(connector)?.takeUnless { it.community || it.selfHosted != null }

    private fun vendorTemplate(connector: Connector): McpTemplate? {
        if (connector.kind != "mcp") return null
        connector.local?.let { local ->
            val command = local.command.trim().split(Regex("\\s+"))
            return all.firstOrNull { t -> t.local != null && t.local.command.split(' ').take(3) == command.take(3) }
        }
        val uri = runCatching { URI(connector.baseUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank()) return null
        return all.firstOrNull { it.local == null && it.selfHosted == null && it.host.equals(uri.host, ignoreCase = true) }
    }
}
