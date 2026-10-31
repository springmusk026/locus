package dev.locus.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Human wording for a tool call: past tense once it has run, otherwise what it would do. */
fun toolLabel(name: String, argumentsJson: String, done: Boolean = true): String {
    val args = runCatching { Json.parseToJsonElement(argumentsJson) as JsonObject }.getOrNull()
    fun opt(key: String) = (args?.get(key) as? JsonPrimitive)?.contentOrNull
    fun arg(key: String) = opt(key) ?: "…"
    fun say(past: String, present: String) = if (done) past else present
    return when (name) {
        "list_files" -> "${say("Listed", "List")} ${opt("path") ?: "workspace"}"
        "read_file" -> "${say("Read", "Read")} ${arg("path")}"
        "search_files" -> "${say("Searched", "Search")} for “${arg("pattern")}”"
        "write_file" -> "${say("Wrote", "Write")} ${arg("path")}"
        "edit_file" -> "${say("Edited", "Edit")} ${arg("path")}"
        "move_path" -> "${say("Moved", "Move")} ${arg("from")} → ${arg("to")}"
        "copy_path" -> "${say("Copied", "Copy")} ${arg("from")} → ${arg("to")}"
        "delete_path" -> "${say("Deleted", "Delete")} ${arg("path")}"
        "send_file" -> "${say("Sent you", "Send you")} ${arg("path").substringAfterLast('/')}"
        "make_directory" -> "${say("Created", "Create")} folder ${arg("path")}"
        "browser_open" -> "${say("Opened", "Open")} ${arg("url").removePrefix("https://").removePrefix("http://")}" +
            if (opt("new_tab") == "true") " in a new tab" else ""
        "browser_snapshot" -> opt("find")?.let { "${say("Looked for", "Look for")} “$it” on the page" } ?: say("Looked at the page", "Look at the page")
        "browser_fill_form" -> {
            val n = (args?.get("fields") as? kotlinx.serialization.json.JsonArray)?.size
            "${say("Filled", "Fill")} ${n?.let { "$it ${if (it == 1) "field" else "fields"}" } ?: "a form"}"
        }
        "browser_wait" -> opt("text")?.let { "${say("Waited for", "Wait for")} “$it”" }
            ?: opt("gone")?.let { "${say("Waited for", "Wait for")} “$it” to go" }
            ?: "${say("Waited", "Wait")} ${opt("seconds") ?: "a moment"}${if (opt("seconds") != null) "s" else ""}"
        "browser_tabs" -> when (opt("action")) {
            "select" -> "${say("Switched to", "Switch to")} tab ${arg("index")}"
            "close" -> say("Closed a tab", "Close a tab")
            else -> say("Checked the tabs", "Check the tabs")
        }
        "browser_dialog" -> if (opt("accept") == "true") say("Answered OK to the page", "Answer OK to the page") else say("Cancelled the page's dialog", "Cancel the page's dialog")
        "browser_upload" -> {
            val names = (args?.get("paths") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/') }
            "${say("Uploaded", "Upload")} ${names?.takeIf { it.isNotEmpty() }?.joinToString() ?: "files"}"
        }
        "browser_save" -> "${say("Saved", "Save")} the page as ${when (opt("format")) { "png" -> "a screenshot"; "mhtml" -> "an archive"; "markdown" -> "Markdown"; else -> "a file" }}"
        "browser_logs" -> say("Checked the page's logs", "Check the page's logs")
        "browser_read" -> say("Read the page", "Read the page")
        "browser_click" -> "${say("Clicked", "Click")} ${arg("ref")}"
        "browser_type" -> "${say("Typed", "Type")} “${arg("text").take(40)}”"
        "browser_select" -> "${say("Chose", "Choose")} “${arg("value")}”"
        "browser_press_key" -> "${say("Pressed", "Press")} ${arg("key")}"
        "browser_scroll" -> "${say("Scrolled", "Scroll")} ${opt("direction") ?: "down"}"
        "browser_back" -> say("Went back", "Go back")
        "browser_ask_user" -> say("You took over the browser", "Hand the browser to you")
        "run_command" -> "${say("Ran", "Run")} `${arg("command").lineSequence().firstOrNull()?.take(60)}`"
        "memory_save" -> "${say("Remembered", "Remember")} “${arg("topic")}”"
        "memory_read" -> "${say("Recalled", "Recall")} “${arg("topic")}”"
        "memory_search" -> "${say("Searched memory for", "Search memory for")} “${arg("query")}”"
        "memory_forget" -> "${say("Forgot", "Forget")} “${arg("topic")}”"
        "skill_use" -> "${say("Used skill", "Use skill")} “${arg("name")}”"
        "skill_read_file" -> "${say("Read", "Read")} ${arg("path")} from “${arg("name")}”"
        "skill_create" -> "${say("Drafted skill", "Draft skill")} “${arg("name")}” for your review"
        "connector_call" -> "${arg("connector")} · ${arg("operation")}"
        "connector_create" -> say("Drafted a connector for your review", "Draft a connector")
        "device_calendar_event" -> "${say("Drafted event", "Draft event")} “${arg("title")}”"
        "device_alarm", "device_timer" -> say("Opened the clock", "Open the clock")
        "device_email" -> "${say("Drafted email to", "Draft email to")} ${arg("to")}"
        "device_sms" -> "${say("Drafted SMS to", "Draft SMS to")} ${arg("to")}"
        "device_dial" -> "${say("Opened dialer for", "Open dialer for")} ${arg("number")}"
        "device_directions" -> "${say("Opened map for", "Open map for")} ${arg("place")}"
        "device_open_url" -> "${say("Opened", "Open")} ${arg("url")}"
        "device_open_app" -> "${say("Opened", "Open")} ${arg("name")}"
        "device_share" -> say("Opened share sheet", "Open share sheet")
        "linux_service_start" -> "${say("Started", "Start")} “${arg("name")}”: `${arg("command").take(50)}`"
        "linux_service_stop" -> "${say("Stopped", "Stop")} “${arg("name")}”"
        "linux_service_list" -> say("Checked services", "Check services")
        "web_search" -> "${say("Searched the web for", "Search the web for")} “${arg("query")}”"
        "web_read" -> "${say("Read", "Read")} ${arg("url").removePrefix("https://").removePrefix("http://")}"
        "weather" -> "${say("Checked weather in", "Check weather in")} ${arg("place")}"
        "wikipedia" -> "${say("Looked up", "Look up")} “${arg("query")}” on Wikipedia"
        "fetch_url" -> "${say("Fetched", "Fetch")} ${arg("url").removePrefix("https://").removePrefix("http://")}"
        "ssh_exec" -> "${say("Ran", "Run")} `${arg("command").lineSequence().firstOrNull()?.take(50)}` on ${arg("host")}"
        "ssh_job_start" -> "${say("Started job", "Start job")} `${arg("command").lineSequence().firstOrNull()?.take(40)}` on ${arg("host")}"
        "ssh_job_status" -> "${say("Checked", "Check")} ${opt("id") ?: "jobs"} on ${arg("host")}"
        "ssh_job_stop" -> "${say("Stopped", "Stop")} ${arg("id")} on ${arg("host")}"
        "github_repos" -> say("Listed your GitHub repos", "List your GitHub repos")
        "github_issues" -> "${say("Looked at", "Look at")} ${opt("number")?.let { "#$it in " } ?: "issues in "}${arg("repo")}"
        "github_read" -> "${say("Read", "Read")} ${arg("repo")}/${opt("path").orEmpty()}"
        "github_create_issue" -> "${say("Opened issue", "Open issue")} “${arg("title")}” in ${arg("repo")}"
        "github_comment" -> "${say("Commented on", "Comment on")} ${arg("repo")}#${arg("number")}"
        "email_send" -> "${say(if (opt("reply_to_message_id") != null) "Replied to" else "Emailed", if (opt("reply_to_message_id") != null) "Reply to" else "Email")} ${arg("to")}"
        "email_list" -> "${say("Checked", "Check")} ${if (opt("unread_only") == "true") "unread " else ""}mail in ${opt("folder") ?: "inbox"}"
        "email_read" -> say("Read an email", "Read an email")
        "vercel_projects" -> say("Listed your Vercel projects", "List your Vercel projects")
        "vercel_deployments" -> opt("id")?.let { "${say("Checked", "Check")} Vercel deployment $it" } ?: "${say("Listed", "List")} Vercel deployments${opt("project")?.let { " of $it" }.orEmpty()}"
        "vercel_deploy" -> "${say("Deployed", "Deploy")} ${opt("path") ?: opt("redeploy_id") ?: "…"} to Vercel ${arg("project")}${if (opt("production") == "true") " (production)" else ""}"
        "vercel_env" -> "${say("Listed", "List")} env variables of ${arg("project")}"
        "vercel_env_set" -> "${say("Set", "Set")} ${arg("key")} on Vercel ${arg("project")}"
        "netlify_sites" -> say("Listed your Netlify sites", "List your Netlify sites")
        "netlify_deploys" -> opt("id")?.let { "${say("Checked", "Check")} Netlify deploy $it" } ?: "${say("Listed", "List")} deploys of ${arg("site")}"
        "netlify_deploy" -> "${say("Deployed", "Deploy")} ${arg("path")} to Netlify ${arg("site")}${if (opt("production") == "true") " (production)" else ""}"
        "netlify_env" -> "${say("Listed", "List")} env variables of ${arg("site")}"
        "netlify_env_set" -> "${say("Set", "Set")} ${arg("key")} on Netlify ${arg("site")}"
        "cloudflare_zones" -> say("Listed your Cloudflare domains", "List your Cloudflare domains")
        "cloudflare_dns" -> "${say("Listed", "List")} DNS records of ${arg("zone")}"
        "cloudflare_dns_set" -> "${say("Set", "Set")} DNS ${arg("type")} ${arg("name")} in ${arg("zone")}"
        "cloudflare_dns_delete" -> "${say("Deleted", "Delete")} a DNS record in ${arg("zone")}"
        "cloudflare_pages" -> opt("project")?.let { "${say("Checked", "Check")} Pages project $it" } ?: say("Listed Pages projects", "List Pages projects")
        "cloudflare_pages_deploy" -> "${say("Started a deploy of", "Deploy")} Pages project ${arg("project")}"
        "cloudflare_deploy_static" -> "${say("Published", "Publish")} ${arg("path")} to Cloudflare as ${arg("name")}"
        "supabase_projects" -> say("Listed your Supabase projects", "List your Supabase projects")
        "supabase_query" -> "${say("Queried", "Query")} the database of ${arg("project")}"
        "supabase_sql" -> "${say("Ran SQL on", "Run SQL on")} ${arg("project")}"
        "supabase_functions" -> "${say("Listed", "List")} Edge Functions of ${arg("project")}"
        "supabase_function_deploy" -> "${say("Deployed", "Deploy")} Edge Function ${arg("slug")} to ${arg("project")}"
        "supabase_api_keys" -> "${say("Listed", "List")} API key names of ${arg("project")}"
        "supabase_branches" -> "${say("Listed", "List")} branches of ${arg("project")}"
        "render_services" -> say("Listed your Render services", "List your Render services")
        "render_deploys" -> opt("id")?.let { "${say("Checked", "Check")} Render deploy $it" } ?: "${say("Listed", "List")} deploys of ${arg("service")}"
        "render_deploy" -> "${say("Started a deploy of", "Deploy")} ${arg("service")} on Render"
        "render_logs" -> "${say("Read", "Read")} ${opt("type")?.let { "$it " }.orEmpty()}logs of ${arg("service")}"
        "render_env" -> "${say("Listed", "List")} env variables of ${arg("service")}"
        "render_env_set" -> "${say("Set", "Set")} ${arg("key")} on Render ${arg("service")}"
        "render_suspend" -> if (opt("action")?.lowercase() == "resume") {
            "${say("Resumed", "Resume")} ${arg("service")} on Render"
        } else {
            "${say("Suspended", "Suspend")} ${arg("service")} on Render"
        }
        "notify_me" ->"${say("Notified you", "Notify you")}: ${opt("title") ?: arg("message").take(40)}"
        "chat_history_search" -> opt("query")?.let { "${say("Searched past chats for", "Search past chats for")} “$it”" } ?: say("Looked at recent chats", "Look at recent chats")
        "chat_history_read" -> say("Read an earlier chat", "Read an earlier chat")
        "ssh_hosts" -> say("Checked SSH machines", "Check SSH machines")
        "sftp_download" -> "${say("Downloaded", "Download")} ${arg("remote_path")} from ${arg("host")}"
        "sftp_upload" -> "${say("Uploaded", "Upload")} ${arg("workspace_path")} to ${arg("host")}"
        "mcp_list_tools" -> "${say("Listed tools of", "List tools of")} ${arg("connector")}"
        "mcp_call" -> "${arg("connector")} · ${arg("tool")}"
        "agent_spawn" -> "${say("Asked a", "Ask a")} ${opt("type") ?: "helper"}${if (opt("background") == "true") " (in the background)" else ""}"
        "agent_send" -> "${say("Continued", "Continue")} helper ${opt("agent_id") ?: ""}".trimEnd() + if (opt("background") == "true") " (in the background)" else ""
        "agent_wait" -> say("Waited for helpers", "Wait for helpers")
        "agent_status" -> say("Checked on a helper", "Check on a helper")
        "agent_cancel" -> say("Stopped a helper", "Stop a helper")
        "agent_create" -> "${say("Drafted helper", "Draft helper")} “${arg("name")}” for your review"
        else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** What the agent is doing while a tool runs, without its arguments: "Searching the web". */
fun toolActivity(name: String): String = when {
    name == "web_search" -> "Searching the web"
    name == "web_read" || name == "fetch_url" -> "Reading a page"
    name == "weather" -> "Checking the weather"
    name == "wikipedia" -> "Looking it up on Wikipedia"
    name.startsWith("browser_") -> "Browsing"
    name == "run_command" || name.startsWith("linux_") -> "Running a command"
    name.startsWith("ssh_") || name.startsWith("sftp_") -> "Working on a remote machine"
    name.startsWith("memory_") || name.startsWith("chat_history_") -> "Checking its memory"
    name.startsWith("skill_") -> "Using a skill"
    name.startsWith("device_") -> "Opening an app"
    name.startsWith("github_") -> "Working on GitHub"
    name.startsWith("email_") -> "Checking email"
    name.startsWith("vercel_") -> "Working on Vercel"
    name.startsWith("netlify_") -> "Working on Netlify"
    name.startsWith("cloudflare_") -> "Working on Cloudflare"
    name.startsWith("supabase_") -> "Working on Supabase"
    name.startsWith("render_") -> "Working on Render"
    name.startsWith("connector_") || name.startsWith("mcp_") -> "Calling a connector"
    name.startsWith("agent_") -> "Working with helpers"
    else -> "Working with files"
}
