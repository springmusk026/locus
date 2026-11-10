package dev.locus.core.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.DiscoveryTools

/**
 * A tool as a workflow sheet names it, in a few plain words: "write_file" → "write files",
 * "run_command" → "run commands". A connector's tool reads "GitHub · create issue". Tools without a
 * wording of their own fall back to their id with spaces ("list_files" → "list files"). The words fit
 * "Can %1$s on its own" (ui_workflow_can_on_its_own) in each language.
 */
fun workflowToolName(resources: Resources, name: String): String {
    DiscoveryTools.parseMcpName(name)?.let { (connector, tool) -> return "$connector · ${tool.replace('_', ' ')}" }
    return workflowToolNameRes(name)?.let(resources::getString) ?: name.replace('_', ' ')
}

/** [workflowToolName] in the composition's resources. */
@Composable
@ReadOnlyComposable
fun workflowToolName(name: String): String = workflowToolName(LocalResources.current, name)

/** The words for a built-in tool [name], or null for one without a wording of its own. */
internal fun workflowToolNameRes(name: String): Int? = when (name) {
    "list_files" -> R.string.ui_workflow_tool_list_files
    "read_file" -> R.string.ui_workflow_tool_read_files
    "view_image" -> R.string.ui_workflow_tool_view_images
    "write_file" -> R.string.ui_workflow_tool_write_files
    "edit_file", "multi_edit" -> R.string.ui_workflow_tool_edit_files
    "delete_path" -> R.string.ui_workflow_tool_delete_files
    "move_path" -> R.string.ui_workflow_tool_move_files
    "copy_path" -> R.string.ui_workflow_tool_copy_files
    "make_directory" -> R.string.ui_workflow_tool_make_folders
    "search_files", "find_files" -> R.string.ui_workflow_tool_search_files
    "send_file" -> R.string.ui_workflow_tool_send_files
    "run_command" -> R.string.ui_workflow_tool_run_commands
    "web_search" -> R.string.ui_workflow_tool_search_web
    "web_read", "fetch_url" -> R.string.ui_workflow_tool_read_web
    "git_commit" -> R.string.ui_workflow_tool_commit
    "git_inspect" -> R.string.ui_workflow_tool_look_at_changes
    "ask_user" -> R.string.ui_workflow_tool_ask_questions
    else -> null
}
