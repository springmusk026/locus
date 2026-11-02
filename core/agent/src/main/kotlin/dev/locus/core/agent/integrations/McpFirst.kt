package dev.locus.core.agent.integrations

/**
 * Built-in integrations whose tools a new chat leaves out when the user also has the vendor's own MCP
 * server connected and working (see docs/design/mcp-connectors.md, "When a user has both").
 *
 * The choice is made once, when a chat is created, and stored with the chat: the declared tool list
 * is part of the provider's cached prefix, so a chat's list never changes after it starts. Chats
 * created before the choice, or with nothing chosen, keep every tool.
 *
 * Never left out: tools that upload from the phone's workspace (a remote server can't read it), and
 * the search backends behind `web_search` / `web_read` (an MCP server can't join that fallback chain).
 * That's why Firecrawl, Tavily and Exa have no entry.
 */
object McpFirst {
    /** Integration id → the built-in tools a chat leaves out when it uses that service's MCP server. */
    val leftOut: Map<String, Set<String>> = mapOf(
        "github" to setOf("github_repos", "github_issues", "github_read", "github_create_issue", "github_comment"),
        // supabase_function_deploy uploads a workspace folder: kept.
        "supabase" to setOf(
            "supabase_projects", "supabase_query", "supabase_sql", "supabase_functions", "supabase_api_keys", "supabase_branches",
        ),
        "render" to setOf(
            "render_services", "render_deploys", "render_logs", "render_deploy", "render_env", "render_env_set", "render_suspend",
        ),
        // cloudflare_deploy_static uploads a workspace folder: kept. cloudflare_pages_deploy only starts a
        // Git build, which the MCP server's API access covers.
        "cloudflare" to setOf("cloudflare_zones", "cloudflare_dns", "cloudflare_dns_set", "cloudflare_dns_delete", "cloudflare_pages", "cloudflare_pages_deploy"),
        // netlify_deploy uploads a workspace folder: kept.
        "netlify" to setOf("netlify_sites", "netlify_deploys", "netlify_env", "netlify_env_set"),
    )

    /** Tools that always stay, whatever is chosen. */
    val alwaysKept: Set<String> = setOf("cloudflare_deploy_static", "netlify_deploy", "supabase_function_deploy", "vercel_deploy", "web_search", "web_read")

    /** Whether integration [id] can hand its tools over to an MCP server at all. */
    fun canHandOver(id: String): Boolean = id in leftOut

    /** The tool names a chat that uses the MCP servers for [integrationIds] leaves out. */
    fun leftOut(integrationIds: Set<String>): Set<String> =
        integrationIds.flatMapTo(mutableSetOf()) { leftOut[it].orEmpty() } - alwaysKept

    /**
     * The note added to a new chat's system prompt, frozen with it: which services go through which
     * MCP connector. [choices] maps integration name to connector id. Empty when nothing was chosen.
     */
    fun promptNote(choices: Map<String, String>): String {
        if (choices.isEmpty()) return ""
        val list = choices.entries.joinToString("; ") { (name, connector) -> "$name through the MCP connector “$connector”" }
        return "Services through MCP: in this chat use $list (mcp_list_tools, then mcp_call). Their built-in tools aren't available here " +
            "(workspace deploy tools and web_search still are). If such a connector fails, tell the user; they can switch that service " +
            "back to its built-in tools for new chats in Settings → Integrations."
    }
}
