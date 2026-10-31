package dev.locus.core.model

/**
 * How much one chat may change, chosen in the composer. A mode only narrows: the user's per-tool
 * Allow / Ask / Deny still applies on top, and calls that must be confirmed still ask. Like tools
 * switched off for a chat, a mode never removes tools from the request (that would break the cached
 * prefix) and never changes the system prompt; a call the mode forbids refuses at run time with a
 * reason the model can act on.
 *
 * Stored in the conversation's per-chat restriction set (the `disabledTools` column) as [key], next to
 * the [ToolGroup] keys, so no schema change was needed. [FULL] stores nothing.
 */
enum class ChatMode(val label: String, val description: String) {
    READ_ONLY("Read-only", "Only reads and looks: files, the web, mail and history. Nothing is changed or sent."),
    WORKSPACE("Workspace", "Changes files in the workspace and runs Linux, but sends, posts and deploys nothing."),
    FULL("Full", "Everything your tool permissions allow."),
    ;

    /** The key stored in a conversation's restriction set, or null for [FULL]. */
    val key: String? get() = if (this == FULL) null else "$PREFIX${name.lowercase()}"

    companion object {
        private const val PREFIX = "mode:"

        /** The mode recorded in a chat's restriction set; [FULL] when none is. The narrowest wins if several are. */
        fun of(restrictions: Set<String>): ChatMode = entries.firstOrNull { it.key != null && it.key in restrictions } ?: FULL

        /** [restrictions] with [mode] recorded instead of whatever mode was there. */
        fun withMode(restrictions: Set<String>, mode: ChatMode): Set<String> =
            restrictions.filterNot { isModeKey(it) }.toSet() + listOfNotNull(mode.key)

        fun isModeKey(key: String) = key.startsWith(PREFIX)
    }
}

/**
 * What a tool can change when a call of it is not an observation. Whether a particular call only
 * looks is still the tool's own `isObservation(args)` (the classification read-only background runs
 * use), so `fetch_url` with GET or a read-only connector operation stays a read.
 */
enum class ToolCapability {
    /** Only looks. */
    READ,

    /** Changes things on the phone only: the workspace, the Linux environment, Locus's own memory, skills and helpers. */
    LOCAL,

    /** Can reach past the phone: posts and network writes, deploys, mail and messages, remote machines, websites, other apps. */
    OUTWARD,
    ;

    companion object {
        /**
         * Every built-in tool, by name. A tool missing here is treated as [OUTWARD] (the safe side), and
         * `ToolCapabilityTest` / `ToolCatalogCapabilityTest` fail until it is added.
         */
        val BY_TOOL: Map<String, ToolCapability> = buildMap {
            fun classify(capability: ToolCapability, vararg names: String) = names.forEach { check(put(it, capability) == null) { "$it is classified twice" } }

            // Workspace files.
            classify(READ, "list_files", "read_file", "search_files")
            classify(LOCAL, "write_file", "edit_file", "move_path", "copy_path", "delete_path", "make_directory")
            // Web and open data. fetch_url with another method than GET is a network write.
            classify(READ, "web_search", "web_read", "weather", "wikipedia")
            classify(OUTWARD, "fetch_url")
            // Browser: looking and navigating vs acting on a page (clicks and typing can submit forms).
            classify(READ, "browser_open", "browser_snapshot", "browser_read", "browser_scroll", "browser_back", "browser_wait", "browser_tabs", "browser_logs", "browser_ask_user")
            classify(LOCAL, "browser_save")
            classify(OUTWARD, "browser_click", "browser_type", "browser_select", "browser_fill_form", "browser_press_key", "browser_dialog", "browser_upload")
            // Linux on the phone.
            classify(READ, "linux_service_list")
            classify(LOCAL, "run_command", "linux_service_start", "linux_service_stop")
            // Memory, skills and connectors (definitions are local; calls reach the service).
            classify(READ, "memory_read", "memory_search", "skill_use", "skill_read_file")
            classify(LOCAL, "memory_save", "memory_forget", "skill_create", "connector_create")
            classify(OUTWARD, "connector_call", "mcp_call")
            classify(READ, "mcp_list_tools")
            // Integrations.
            classify(READ, "github_repos", "github_issues", "github_read")
            classify(OUTWARD, "github_create_issue", "github_comment")
            classify(READ, "netlify_sites", "netlify_deploys", "netlify_env")
            classify(OUTWARD, "netlify_deploy", "netlify_env_set")
            classify(READ, "vercel_projects", "vercel_deployments", "vercel_env")
            classify(OUTWARD, "vercel_deploy", "vercel_env_set")
            classify(READ, "cloudflare_zones", "cloudflare_dns", "cloudflare_pages")
            classify(OUTWARD, "cloudflare_dns_set", "cloudflare_dns_delete", "cloudflare_pages_deploy", "cloudflare_deploy_static")
            classify(READ, "email_list", "email_read")
            classify(OUTWARD, "email_send")
            // Reaches only the user's own ntfy topic; it is an observation, so even read-only runs may notify.
            classify(OUTWARD, "notify_me")
            // SSH: a download brings a file into the workspace; anything else acts on the machine.
            classify(READ, "ssh_hosts", "ssh_job_status")
            classify(LOCAL, "sftp_download")
            classify(OUTWARD, "ssh_exec", "ssh_job_start", "ssh_job_stop", "sftp_upload")
            // Phone apps: every one hands something to another app.
            classify(
                OUTWARD, "device_calendar_event", "device_alarm", "device_timer", "device_email", "device_sms", "device_dial",
                "device_directions", "device_open_url", "device_open_app", "device_share",
            )
            // Chat history, checklist, plan, delivery.
            classify(READ, "chat_history_search", "chat_history_read", AgentControlTools.TODO_WRITE, AgentControlTools.PLAN_PROPOSE, "send_file")
            // Helpers run with the chat's mode, so starting or defining one is local.
            classify(LOCAL, SubagentTools.SPAWN, SubagentTools.CREATE)
            classify(READ, SubagentTools.WAIT, SubagentTools.STATUS, SubagentTools.CANCEL)
        }

        fun of(toolName: String): ToolCapability = BY_TOOL[toolName] ?: OUTWARD
    }
}
