package dev.locus.core.ui

import dev.locus.core.model.DiscoveryTools

/**
 * A tool as a workflow sheet names it, in a few plain words: "write_file" → "write files",
 * "run_command" → "run commands". A connector's tool reads "GitHub · create issue". Tools without a
 * wording of their own fall back to their id with spaces ("list_files" → "list files").
 */
fun workflowToolName(name: String): String {
    DiscoveryTools.parseMcpName(name)?.let { (connector, tool) -> return "$connector · ${tool.replace('_', ' ')}" }
    return when (name) {
        "list_files" -> "list files"
        "read_file" -> "read files"
        "write_file" -> "write files"
        "edit_file", "multi_edit" -> "edit files"
        "delete_path" -> "delete files"
        "move_path" -> "move files"
        "copy_path" -> "copy files"
        "make_directory" -> "make folders"
        "search_files", "find_files" -> "search files"
        "send_file" -> "send you files"
        "run_command" -> "run commands"
        "web_search" -> "search the web"
        "web_read", "fetch_url" -> "read web pages"
        "git_commit" -> "commit changes"
        "git_inspect" -> "look at changes"
        "ask_user" -> "ask you questions"
        else -> name.replace('_', ' ')
    }
}
