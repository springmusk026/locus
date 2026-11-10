package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The tool name of Activity entries that record a task's budget: a pause and its answer, or what the task used. */
const val TASK_BUDGET_ENTRY = "task_budget"

/** Human wording for a tool call: past tense once it has run, otherwise what it would do. */
fun toolLabel(name: String, argumentsJson: String, done: Boolean = true): String = toolLabelText(name, argumentsJson, done).english

/**
 * [toolLabel] as data: the phrase and the values in it, so the app can word it in the user's language
 * while the model, exports and logs keep the English ([ToolLabelText.english]).
 */
fun toolLabelText(name: String, argumentsJson: String, done: Boolean = true): ToolLabelText {
    // A tool run through call_tool reads as that tool's own call (docs/design/tool-discovery.md).
    if (name == DiscoveryTools.CALL_TOOL) {
        val (inner, innerArgs) = DiscoveryTools.unwrap(name, argumentsJson)
        if (inner != name) return toolLabelText(inner, innerArgs, done)
    }
    DiscoveryTools.parseMcpName(name)?.let { (connector, tool) -> return ToolLabelText.Verbatim("$connector · $tool") }
    val args = runCatching { Json.parseToJsonElement(argumentsJson) as JsonObject }.getOrNull()
    fun opt(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    fun arg(key: String) = opt(key) ?: "…"
    fun say(phrase: ToolPhrase, vararg values: Any) = ToolLabelText.Phrase(phrase, done, values.toList())
    fun count(phrase: ToolPhrase, n: Int, vararg values: Any) = ToolLabelText.Phrase(phrase, done, values.toList(), count = n)
    fun site(url: String) = url.removePrefix("https://").removePrefix("http://")
    fun firstLine(text: String, max: Int) = "${text.lineSequence().firstOrNull()?.take(max)}"
    val background = opt("background") == "true"
    val production = opt("production") == "true"
    return when (name) {
        "list_files" -> opt("path")?.let { say(ToolPhrase.LIST, it) } ?: say(ToolPhrase.LIST_WORKSPACE)
        "read_file" -> say(ToolPhrase.READ, arg("path"))
        ViewImages.TOOL -> {
            val all = ViewImages.paths(argumentsJson)
            when {
                all.size == 1 -> say(ToolPhrase.LOOK_AT, all[0])
                all.isEmpty() -> say(ToolPhrase.LOOK_AT_AN_IMAGE)
                else -> count(ToolPhrase.LOOK_AT_IMAGES, all.size, all.size)
            }
        }
        "search_files" -> say(ToolPhrase.SEARCH_FOR, arg("pattern"))
        "find_files" -> opt("path")?.let { say(ToolPhrase.FIND_IN, arg("pattern"), it) } ?: say(ToolPhrase.FIND, arg("pattern"))
        "code_outline" -> opt("path")?.let { say(ToolPhrase.OUTLINE, it) } ?: say(ToolPhrase.OUTLINE_WORKSPACE)
        "project_info" -> opt("path")?.let { say(ToolPhrase.LOOK_AT_PROJECT, it) } ?: say(ToolPhrase.LOOK_AT_PROJECT_WORKSPACE)
        "write_file" -> say(ToolPhrase.WRITE, arg("path"))
        "edit_file" -> say(ToolPhrase.EDIT, arg("path"))
        "multi_edit" -> {
            val n = (args?.get("edits") as? kotlinx.serialization.json.JsonArray)?.size
            n?.let { count(ToolPhrase.EDIT_CHANGES, it, arg("path"), it) } ?: say(ToolPhrase.EDIT, arg("path"))
        }
        "move_path" -> say(ToolPhrase.MOVE, arg("from"), arg("to"))
        "copy_path" -> say(ToolPhrase.COPY, arg("from"), arg("to"))
        "delete_path" -> say(ToolPhrase.DELETE, arg("path"))
        "send_file" -> say(ToolPhrase.SEND_FILE, arg("path").substringAfterLast('/'))
        "make_directory" -> say(ToolPhrase.MAKE_FOLDER, arg("path"))
        "browser_open" -> say(if (opt("new_tab") == "true") ToolPhrase.OPEN_NEW_TAB else ToolPhrase.OPEN, site(arg("url")))
        "browser_snapshot" -> opt("find")?.let { say(ToolPhrase.LOOK_FOR_ON_PAGE, it) } ?: say(ToolPhrase.LOOK_AT_PAGE)
        "browser_fill_form" -> {
            val n = (args?.get("fields") as? kotlinx.serialization.json.JsonArray)?.size
            n?.let { count(ToolPhrase.FILL_FIELDS, it, it) } ?: say(ToolPhrase.FILL_FORM)
        }
        "browser_wait" -> opt("text")?.let { say(ToolPhrase.WAIT_FOR, it) }
            ?: opt("gone")?.let { say(ToolPhrase.WAIT_FOR_GONE, it) }
            ?: opt("seconds")?.let { say(ToolPhrase.WAIT_SECONDS, it) }
            ?: say(ToolPhrase.WAIT_MOMENT)
        "browser_tabs" -> when (opt("action")) {
            "select" -> say(ToolPhrase.SWITCH_TAB, arg("index"))
            "close" -> say(ToolPhrase.CLOSE_TAB)
            else -> say(ToolPhrase.CHECK_TABS)
        }
        "browser_dialog" -> say(if (opt("accept") == "true") ToolPhrase.DIALOG_ACCEPT else ToolPhrase.DIALOG_CANCEL)
        "browser_upload" -> {
            val names = (args?.get("paths") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/') }
            names?.takeIf { it.isNotEmpty() }?.let { say(ToolPhrase.UPLOAD, it.joinToString()) } ?: say(ToolPhrase.UPLOAD_FILES)
        }
        "browser_save" -> say(
            when (opt("format")) {
                "png" -> ToolPhrase.SAVE_PAGE_SCREENSHOT
                "mhtml" -> ToolPhrase.SAVE_PAGE_ARCHIVE
                "markdown" -> ToolPhrase.SAVE_PAGE_MARKDOWN
                else -> ToolPhrase.SAVE_PAGE_FILE
            },
        )
        "browser_logs" -> say(ToolPhrase.CHECK_PAGE_LOGS)
        "browser_read" -> say(ToolPhrase.READ_PAGE)
        "browser_click" -> say(ToolPhrase.CLICK, arg("ref"))
        "browser_type" -> say(ToolPhrase.TYPE, arg("text").take(40))
        "browser_select" -> say(ToolPhrase.CHOOSE, arg("value"))
        "browser_press_key" -> say(ToolPhrase.PRESS, arg("key"))
        "browser_scroll" -> when (val direction = opt("direction")) {
            null, "down" -> say(ToolPhrase.SCROLL_DOWN)
            "up" -> say(ToolPhrase.SCROLL_UP)
            "left" -> say(ToolPhrase.SCROLL_LEFT)
            "right" -> say(ToolPhrase.SCROLL_RIGHT)
            else -> say(ToolPhrase.SCROLL, direction)
        }
        "browser_back" -> say(ToolPhrase.GO_BACK)
        "browser_ask_user" -> say(ToolPhrase.BROWSER_TAKEOVER)
        "browser_login" -> SiteOrigin.of(opt("origin"))?.let(SiteOrigin::host)?.let { say(ToolPhrase.FILL_LOGIN, it) } ?: say(ToolPhrase.FILL_LOGIN_THIS_SITE)
        "ask_user" -> say(ToolPhrase.ASK_USER, arg("question").take(80))
        // Not a tool: Locus's own Activity entry for a task's budget (see TASK_BUDGET_ENTRY).
        TASK_BUDGET_ENTRY -> say(if (opt("event") == "paused") ToolPhrase.TASK_BUDGET_PAUSED else ToolPhrase.TASK_BUDGET)
        "run_command" -> say(ToolPhrase.RUN_COMMAND, firstLine(arg("command"), 60))
        "memory_save" -> say(ToolPhrase.REMEMBER, arg("topic"))
        "memory_read" -> say(ToolPhrase.RECALL, arg("topic"))
        "memory_search" -> say(ToolPhrase.SEARCH_MEMORY, arg("query"))
        "memory_forget" -> say(ToolPhrase.FORGET, arg("topic"))
        "skill_use" -> say(ToolPhrase.USE_SKILL, arg("name"))
        "skill_read_file" -> say(ToolPhrase.READ_SKILL_FILE, arg("path"), arg("name"))
        "skill_create" -> say(ToolPhrase.DRAFT_SKILL, arg("name"))
        "connector_call" -> ToolLabelText.Verbatim("${arg("connector")} · ${arg("operation")}")
        "connector_create" -> say(ToolPhrase.DRAFT_CONNECTOR)
        ProviderDrafts.TOOL -> opt("name")?.let { say(ToolPhrase.DRAFT_PROVIDER, it) } ?: say(ToolPhrase.DRAFT_PROVIDER_UNNAMED)
        "device_calendar_event" -> say(ToolPhrase.DRAFT_EVENT, arg("title"))
        "device_alarm", "device_timer" -> say(ToolPhrase.OPEN_CLOCK)
        "device_email" -> say(ToolPhrase.DRAFT_EMAIL, arg("to"))
        "device_sms" -> say(ToolPhrase.DRAFT_SMS, arg("to"))
        "device_dial" -> say(ToolPhrase.OPEN_DIALER, arg("number"))
        "device_directions" -> say(ToolPhrase.OPEN_MAP, arg("place"))
        "device_open_url" -> say(ToolPhrase.OPEN, arg("url"))
        "device_open_app" -> say(ToolPhrase.OPEN, arg("name"))
        "device_share" -> say(ToolPhrase.OPEN_SHARE_SHEET)
        DeviceIntegrations.CALENDAR_READ -> opt("days")?.let { say(ToolPhrase.READ_CALENDAR_DAYS, it) } ?: say(ToolPhrase.READ_CALENDAR)
        DeviceIntegrations.CONTACTS_PICK -> say(ToolPhrase.PICK_CONTACT)
        DeviceIntegrations.PHOTOS_PICK -> say(ToolPhrase.PICK_PHOTOS)
        DeviceIntegrations.CALENDAR_CREATE -> say(ToolPhrase.ADD_TO_CALENDAR, arg("title"))
        DeviceIntegrations.CALENDAR_UPDATE -> say(ToolPhrase.CHANGE_EVENT)
        DeviceIntegrations.CALENDAR_DELETE -> say(ToolPhrase.DELETE_EVENT)
        DeviceIntegrations.REMIND_ME -> say(ToolPhrase.SET_REMINDER, arg("text").take(60))
        DeviceIntegrations.REMINDERS_LIST -> say(ToolPhrase.CHECK_REMINDERS)
        DeviceIntegrations.REMINDER_CANCEL -> say(ToolPhrase.CANCEL_REMINDER)
        DeviceIntegrations.CONTACT_ADD -> say(ToolPhrase.NEW_CONTACT, arg("name"))
        DeviceIntegrations.SAVE_FILE -> say(ToolPhrase.OFFER_SAVE, arg("path"))
        DeviceIntegrations.COPY -> say(ToolPhrase.COPY_TO_CLIPBOARD)
        "linux_service_start" -> say(ToolPhrase.START_SERVICE, arg("name"), arg("command").take(50))
        "linux_service_stop" -> say(ToolPhrase.STOP_SERVICE, arg("name"))
        "linux_service_list" -> say(ToolPhrase.CHECK_SERVICES)
        "linux_service_log" -> say(ToolPhrase.READ_SERVICE_LOG, arg("name"))
        "git_inspect" -> when (opt("action")) {
            "diff" -> opt("path")?.let { say(ToolPhrase.GIT_CHANGES_TO, it) } ?: say(ToolPhrase.GIT_CHANGES)
            "log" -> say(ToolPhrase.GIT_LOG)
            "show" -> say(ToolPhrase.GIT_SHOW, opt("ref") ?: "HEAD")
            "branches" -> say(ToolPhrase.GIT_BRANCHES)
            "blame" -> say(ToolPhrase.GIT_BLAME, arg("path"))
            else -> say(ToolPhrase.GIT_STATUS)
        }
        "git_commit" -> say(ToolPhrase.COMMIT, opt("message")?.lineSequence()?.firstOrNull()?.take(60) ?: "…")
        "web_search" -> say(ToolPhrase.WEB_SEARCH, arg("query"))
        "web_read" -> say(ToolPhrase.READ, site(arg("url")))
        "weather" -> say(ToolPhrase.WEATHER, arg("place"))
        "wikipedia" -> say(ToolPhrase.WIKIPEDIA, arg("query"))
        "fetch_url" -> say(ToolPhrase.FETCH, site(arg("url")))
        "ssh_exec" -> say(ToolPhrase.SSH_RUN, firstLine(arg("command"), 50), arg("host"))
        "ssh_job_start" -> say(ToolPhrase.SSH_JOB_START, firstLine(arg("command"), 40), arg("host"))
        "ssh_job_status" -> opt("id")?.let { say(ToolPhrase.SSH_JOB_STATUS, it, arg("host")) } ?: say(ToolPhrase.SSH_JOBS_STATUS, arg("host"))
        "ssh_job_stop" -> say(ToolPhrase.SSH_JOB_STOP, arg("id"), arg("host"))
        "github_repos" -> say(ToolPhrase.GITHUB_REPOS)
        "github_issues" -> opt("number")?.let { say(ToolPhrase.GITHUB_ISSUE, it, arg("repo")) } ?: say(ToolPhrase.GITHUB_ISSUES, arg("repo"))
        "github_read" -> say(ToolPhrase.READ, "${arg("repo")}/${opt("path").orEmpty()}")
        "github_create_issue" -> say(ToolPhrase.GITHUB_CREATE_ISSUE, arg("title"), arg("repo"))
        "github_comment" -> say(ToolPhrase.GITHUB_COMMENT, arg("repo"), arg("number"))
        "email_send" -> say(if (opt("reply_to_message_id") != null) ToolPhrase.EMAIL_REPLY else ToolPhrase.EMAIL_SEND, arg("to"))
        "email_list" -> {
            val unread = opt("unread_only") == "true"
            opt("folder")?.let { say(if (unread) ToolPhrase.EMAIL_CHECK_UNREAD else ToolPhrase.EMAIL_CHECK, it) }
                ?: say(if (unread) ToolPhrase.EMAIL_CHECK_UNREAD_INBOX else ToolPhrase.EMAIL_CHECK_INBOX)
        }
        "email_read" -> say(ToolPhrase.EMAIL_READ)
        "vercel_projects" -> say(ToolPhrase.VERCEL_PROJECTS)
        "vercel_deployments" -> opt("id")?.let { say(ToolPhrase.VERCEL_DEPLOYMENT, it) }
            ?: opt("project")?.let { say(ToolPhrase.VERCEL_DEPLOYMENTS_OF, it) }
            ?: say(ToolPhrase.VERCEL_DEPLOYMENTS)
        "vercel_deploy" -> say(
            if (production) ToolPhrase.VERCEL_DEPLOY_PRODUCTION else ToolPhrase.VERCEL_DEPLOY,
            opt("path") ?: opt("redeploy_id") ?: "…",
            arg("project"),
        )
        "vercel_env" -> say(ToolPhrase.ENV_VARIABLES, arg("project"))
        "vercel_env_set" -> say(ToolPhrase.VERCEL_ENV_SET, arg("key"), arg("project"))
        "netlify_sites" -> say(ToolPhrase.NETLIFY_SITES)
        "netlify_deploys" -> opt("id")?.let { say(ToolPhrase.NETLIFY_DEPLOY_CHECK, it) } ?: say(ToolPhrase.DEPLOYS_OF, arg("site"))
        "netlify_deploy" -> say(if (production) ToolPhrase.NETLIFY_DEPLOY_PRODUCTION else ToolPhrase.NETLIFY_DEPLOY, arg("path"), arg("site"))
        "netlify_env" -> say(ToolPhrase.ENV_VARIABLES, arg("site"))
        "netlify_env_set" -> say(ToolPhrase.NETLIFY_ENV_SET, arg("key"), arg("site"))
        "cloudflare_zones" -> say(ToolPhrase.CLOUDFLARE_ZONES)
        "cloudflare_dns" -> say(ToolPhrase.DNS_RECORDS, arg("zone"))
        "cloudflare_dns_set" -> say(ToolPhrase.DNS_SET, arg("type"), arg("name"), arg("zone"))
        "cloudflare_dns_delete" -> say(ToolPhrase.DNS_DELETE, arg("zone"))
        "cloudflare_pages" -> opt("project")?.let { say(ToolPhrase.PAGES_PROJECT, it) } ?: say(ToolPhrase.PAGES_PROJECTS)
        "cloudflare_pages_deploy" -> say(ToolPhrase.PAGES_DEPLOY, arg("project"))
        "cloudflare_deploy_static" -> say(ToolPhrase.CLOUDFLARE_PUBLISH, arg("path"), arg("name"))
        "supabase_projects" -> say(ToolPhrase.SUPABASE_PROJECTS)
        "supabase_query" -> say(ToolPhrase.SUPABASE_QUERY, arg("project"))
        "supabase_sql" -> say(ToolPhrase.SUPABASE_SQL, arg("project"))
        "supabase_functions" -> say(ToolPhrase.SUPABASE_FUNCTIONS, arg("project"))
        "supabase_function_deploy" -> say(ToolPhrase.SUPABASE_FUNCTION_DEPLOY, arg("slug"), arg("project"))
        "supabase_api_keys" -> say(ToolPhrase.SUPABASE_API_KEYS, arg("project"))
        "supabase_branches" -> say(ToolPhrase.SUPABASE_BRANCHES, arg("project"))
        "render_services" -> say(ToolPhrase.RENDER_SERVICES)
        "render_deploys" -> opt("id")?.let { say(ToolPhrase.RENDER_DEPLOY_CHECK, it) } ?: say(ToolPhrase.DEPLOYS_OF, arg("service"))
        "render_deploy" -> say(ToolPhrase.RENDER_DEPLOY, arg("service"))
        "render_logs" -> opt("type")?.let { say(ToolPhrase.RENDER_LOGS_TYPE, it, arg("service")) } ?: say(ToolPhrase.RENDER_LOGS, arg("service"))
        "render_env" -> say(ToolPhrase.ENV_VARIABLES, arg("service"))
        "render_env_set" -> say(ToolPhrase.RENDER_ENV_SET, arg("key"), arg("service"))
        "render_suspend" -> say(if (opt("action")?.lowercase() == "resume") ToolPhrase.RENDER_RESUME else ToolPhrase.RENDER_SUSPEND, arg("service"))
        "notify_me" -> say(ToolPhrase.NOTIFY, opt("title") ?: arg("message").take(40))
        "chat_history_search" -> opt("query")?.let { say(ToolPhrase.SEARCH_CHATS, it) } ?: say(ToolPhrase.RECENT_CHATS)
        "chat_history_read" -> say(ToolPhrase.READ_CHAT)
        "ssh_hosts" -> say(ToolPhrase.SSH_HOSTS)
        "sftp_download" -> say(ToolPhrase.SFTP_DOWNLOAD, arg("remote_path"), arg("host"))
        "sftp_upload" -> say(ToolPhrase.SFTP_UPLOAD, arg("workspace_path"), arg("host"))
        DiscoveryTools.FIND_TOOLS -> say(ToolPhrase.FIND_TOOLS, arg("query"))
        DiscoveryTools.CALL_TOOL -> say(ToolPhrase.RUN_TOOL, arg("name"))
        "mcp_list_tools" -> say(ToolPhrase.LIST_CONNECTOR_TOOLS, arg("connector"))
        "mcp_call" -> ToolLabelText.Verbatim("${arg("connector")} · ${arg("tool")}")
        "agent_spawn" -> opt("type")?.let { say(if (background) ToolPhrase.ASK_HELPER_BACKGROUND else ToolPhrase.ASK_HELPER, it) }
            ?: say(if (background) ToolPhrase.ASK_A_HELPER_BACKGROUND else ToolPhrase.ASK_A_HELPER)
        "agent_send" -> opt("agent_id").orEmpty().trimEnd().takeIf { it.isNotEmpty() }
            ?.let { say(if (background) ToolPhrase.CONTINUE_HELPER_BACKGROUND else ToolPhrase.CONTINUE_HELPER, it) }
            ?: say(if (background) ToolPhrase.CONTINUE_A_HELPER_BACKGROUND else ToolPhrase.CONTINUE_A_HELPER)
        "agent_wait" -> say(ToolPhrase.WAIT_HELPERS)
        "agent_status" -> say(ToolPhrase.CHECK_HELPER)
        "agent_cancel" -> say(ToolPhrase.STOP_HELPER)
        "agent_create" -> say(ToolPhrase.DRAFT_HELPER, arg("name"))
        else -> ToolLabelText.Verbatim(name.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
}

/** What the agent is doing while a tool runs, without its arguments: "Searching the web". */
fun toolActivity(name: String): String = toolActivityOf(name).english

/** [toolActivity] as data, for the app to word in the user's language. */
fun toolActivityOf(name: String): ToolActivity = when {
    name == DiscoveryTools.FIND_TOOLS -> ToolActivity.FINDING_TOOL
    name == DiscoveryTools.CALL_TOOL -> ToolActivity.USING_TOOL
    name.startsWith(DiscoveryTools.MCP_PREFIX) -> ToolActivity.CALLING_CONNECTOR
    name == "web_search" -> ToolActivity.SEARCHING_WEB
    name == "web_read" || name == "fetch_url" -> ToolActivity.READING_PAGE
    name == "weather" -> ToolActivity.CHECKING_WEATHER
    name == "wikipedia" -> ToolActivity.WIKIPEDIA
    name.startsWith("browser_") -> ToolActivity.BROWSING
    name == "run_command" || name.startsWith("linux_") -> ToolActivity.RUNNING_COMMAND
    name == "git_inspect" -> ToolActivity.CHECKING_CHANGES
    name == "git_commit" -> ToolActivity.COMMITTING
    name == "search_files" || name == "find_files" -> ToolActivity.SEARCHING_FILES
    name == "code_outline" || name == "project_info" -> ToolActivity.READING_CODE
    name.startsWith("ssh_") || name.startsWith("sftp_") -> ToolActivity.REMOTE_MACHINE
    name.startsWith("memory_") || name.startsWith("chat_history_") -> ToolActivity.CHECKING_MEMORY
    name.startsWith("skill_") -> ToolActivity.USING_SKILL
    name == DeviceIntegrations.CALENDAR_READ -> ToolActivity.CHECKING_CALENDAR
    name == DeviceIntegrations.CONTACTS_PICK -> ToolActivity.PICKING_CONTACT
    name == DeviceIntegrations.PHOTOS_PICK -> ToolActivity.PICKING_PHOTOS
    name in DeviceIntegrations.CALENDAR_WRITES -> ToolActivity.UPDATING_CALENDAR
    name == DeviceIntegrations.REMIND_ME || name == DeviceIntegrations.REMINDERS_LIST || name == DeviceIntegrations.REMINDER_CANCEL -> ToolActivity.REMINDERS
    name == DeviceIntegrations.SAVE_FILE -> ToolActivity.PICKING_SAVE_PLACE
    name == DeviceIntegrations.COPY -> ToolActivity.COPYING
    name.startsWith("device_") -> ToolActivity.OPENING_APP
    name.startsWith("github_") -> ToolActivity.GITHUB
    name.startsWith("email_") -> ToolActivity.EMAIL
    name.startsWith("vercel_") -> ToolActivity.VERCEL
    name.startsWith("netlify_") -> ToolActivity.NETLIFY
    name.startsWith("cloudflare_") -> ToolActivity.CLOUDFLARE
    name.startsWith("supabase_") -> ToolActivity.SUPABASE
    name.startsWith("render_") -> ToolActivity.RENDER
    name.startsWith("connector_") || name.startsWith("mcp_") -> ToolActivity.CALLING_CONNECTOR
    name == "ask_user" -> ToolActivity.WAITING_FOR_ANSWER
    name.startsWith("agent_") -> ToolActivity.HELPERS
    else -> ToolActivity.FILES
}
