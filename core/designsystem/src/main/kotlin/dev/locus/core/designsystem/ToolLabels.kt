package dev.locus.core.designsystem

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.ToolActivity
import dev.locus.core.model.ToolLabelText
import dev.locus.core.model.ToolPhrase
import dev.locus.core.model.toolActivityOf
import dev.locus.core.model.toolLabelText

/*
 * Tool steps in the app's language (docs/design/localization.md). core/model words a tool call in
 * English for the model, exports and logs ([dev.locus.core.model.toolLabel], [dev.locus.core.model.toolActivity]);
 * these word the same phrase, with the same values, from this module's strings. The English strings are
 * the core's words exactly (ToolLabelsResourcesTest).
 */

/** "Ran `ls`", "Run `ls`": [dev.locus.core.model.toolLabel] in the user's language. */
fun localizedToolLabel(resources: Resources, name: String, argumentsJson: String, done: Boolean = true): String =
    toolLabelText(name, argumentsJson, done).asString(resources)

/** [localizedToolLabel] in the composition's resources, so it follows a language change. */
@Composable
@ReadOnlyComposable
fun localizedToolLabel(name: String, argumentsJson: String, done: Boolean = true): String =
    localizedToolLabel(LocalResources.current, name, argumentsJson, done)

/** "Searching the web": [dev.locus.core.model.toolActivity] in the user's language. */
fun localizedToolActivity(resources: Resources, name: String): String = resources.getString(toolActivityOf(name).res())

/** [localizedToolActivity] as a [UiText], for text made outside a composable. */
fun toolActivityText(name: String): UiText = UiText.Res(toolActivityOf(name).res())

/** [localizedToolActivity] in the composition's resources. */
@Composable
@ReadOnlyComposable
fun localizedToolActivity(name: String): String = localizedToolActivity(LocalResources.current, name)

/** The label in the user's language: a phrase from the strings, verbatim text as it is. */
fun ToolLabelText.asString(resources: Resources): String = when (this) {
    is ToolLabelText.Verbatim -> text
    is ToolLabelText.Phrase -> {
        val id = if (done) phrase.pastRes() else phrase.presentRes()
        val values = args.toTypedArray()
        when {
            phrase.plural -> resources.getQuantityString(id, count ?: 0, *values)
            values.isEmpty() -> resources.getString(id)
            else -> resources.getString(id, *values)
        }
    }
}

/** The string (a plural for a plural phrase) for [ToolPhrase.past]. */
internal fun ToolPhrase.pastRes(): Int = when (this) {
    ToolPhrase.LIST -> R.string.designsystem_tool_list_past
    ToolPhrase.LIST_WORKSPACE -> R.string.designsystem_tool_list_workspace_past
    ToolPhrase.READ -> R.string.designsystem_tool_read_past
    ToolPhrase.LOOK_AT -> R.string.designsystem_tool_look_at_past
    ToolPhrase.LOOK_AT_AN_IMAGE -> R.string.designsystem_tool_look_at_an_image_past
    ToolPhrase.SEARCH_FOR -> R.string.designsystem_tool_search_for_past
    ToolPhrase.FIND -> R.string.designsystem_tool_find_past
    ToolPhrase.FIND_IN -> R.string.designsystem_tool_find_in_past
    ToolPhrase.OUTLINE -> R.string.designsystem_tool_outline_past
    ToolPhrase.OUTLINE_WORKSPACE -> R.string.designsystem_tool_outline_workspace_past
    ToolPhrase.LOOK_AT_PROJECT -> R.string.designsystem_tool_look_at_project_past
    ToolPhrase.LOOK_AT_PROJECT_WORKSPACE -> R.string.designsystem_tool_look_at_project_workspace_past
    ToolPhrase.WRITE -> R.string.designsystem_tool_write_past
    ToolPhrase.EDIT -> R.string.designsystem_tool_edit_past
    ToolPhrase.MOVE -> R.string.designsystem_tool_move_past
    ToolPhrase.COPY -> R.string.designsystem_tool_copy_past
    ToolPhrase.DELETE -> R.string.designsystem_tool_delete_past
    ToolPhrase.SEND_FILE -> R.string.designsystem_tool_send_file_past
    ToolPhrase.MAKE_FOLDER -> R.string.designsystem_tool_make_folder_past
    ToolPhrase.OPEN -> R.string.designsystem_tool_open_past
    ToolPhrase.OPEN_NEW_TAB -> R.string.designsystem_tool_open_new_tab_past
    ToolPhrase.LOOK_FOR_ON_PAGE -> R.string.designsystem_tool_look_for_on_page_past
    ToolPhrase.LOOK_AT_PAGE -> R.string.designsystem_tool_look_at_page_past
    ToolPhrase.FILL_FORM -> R.string.designsystem_tool_fill_form_past
    ToolPhrase.WAIT_FOR -> R.string.designsystem_tool_wait_for_past
    ToolPhrase.WAIT_FOR_GONE -> R.string.designsystem_tool_wait_for_gone_past
    ToolPhrase.WAIT_SECONDS -> R.string.designsystem_tool_wait_seconds_past
    ToolPhrase.WAIT_MOMENT -> R.string.designsystem_tool_wait_moment_past
    ToolPhrase.SWITCH_TAB -> R.string.designsystem_tool_switch_tab_past
    ToolPhrase.CLOSE_TAB -> R.string.designsystem_tool_close_tab_past
    ToolPhrase.CHECK_TABS -> R.string.designsystem_tool_check_tabs_past
    ToolPhrase.DIALOG_ACCEPT -> R.string.designsystem_tool_dialog_accept_past
    ToolPhrase.DIALOG_CANCEL -> R.string.designsystem_tool_dialog_cancel_past
    ToolPhrase.UPLOAD -> R.string.designsystem_tool_upload_past
    ToolPhrase.UPLOAD_FILES -> R.string.designsystem_tool_upload_files_past
    ToolPhrase.SAVE_PAGE_SCREENSHOT -> R.string.designsystem_tool_save_page_screenshot_past
    ToolPhrase.SAVE_PAGE_ARCHIVE -> R.string.designsystem_tool_save_page_archive_past
    ToolPhrase.SAVE_PAGE_MARKDOWN -> R.string.designsystem_tool_save_page_markdown_past
    ToolPhrase.SAVE_PAGE_FILE -> R.string.designsystem_tool_save_page_file_past
    ToolPhrase.CHECK_PAGE_LOGS -> R.string.designsystem_tool_check_page_logs_past
    ToolPhrase.READ_PAGE -> R.string.designsystem_tool_read_page_past
    ToolPhrase.CLICK -> R.string.designsystem_tool_click_past
    ToolPhrase.TYPE -> R.string.designsystem_tool_type_past
    ToolPhrase.CHOOSE -> R.string.designsystem_tool_choose_past
    ToolPhrase.PRESS -> R.string.designsystem_tool_press_past
    ToolPhrase.SCROLL -> R.string.designsystem_tool_scroll_past
    ToolPhrase.SCROLL_UP -> R.string.designsystem_tool_scroll_up_past
    ToolPhrase.SCROLL_DOWN -> R.string.designsystem_tool_scroll_down_past
    ToolPhrase.SCROLL_LEFT -> R.string.designsystem_tool_scroll_left_past
    ToolPhrase.SCROLL_RIGHT -> R.string.designsystem_tool_scroll_right_past
    ToolPhrase.GO_BACK -> R.string.designsystem_tool_go_back_past
    ToolPhrase.BROWSER_TAKEOVER -> R.string.designsystem_tool_browser_takeover_past
    ToolPhrase.FILL_LOGIN -> R.string.designsystem_tool_fill_login_past
    ToolPhrase.FILL_LOGIN_THIS_SITE -> R.string.designsystem_tool_fill_login_this_site_past
    ToolPhrase.ASK_USER -> R.string.designsystem_tool_ask_user_past
    ToolPhrase.TASK_BUDGET_PAUSED -> R.string.designsystem_tool_task_budget_paused_past
    ToolPhrase.TASK_BUDGET -> R.string.designsystem_tool_task_budget_past
    ToolPhrase.RUN_COMMAND -> R.string.designsystem_tool_run_command_past
    ToolPhrase.REMEMBER -> R.string.designsystem_tool_remember_past
    ToolPhrase.RECALL -> R.string.designsystem_tool_recall_past
    ToolPhrase.SEARCH_MEMORY -> R.string.designsystem_tool_search_memory_past
    ToolPhrase.FORGET -> R.string.designsystem_tool_forget_past
    ToolPhrase.USE_SKILL -> R.string.designsystem_tool_use_skill_past
    ToolPhrase.READ_SKILL_FILE -> R.string.designsystem_tool_read_skill_file_past
    ToolPhrase.DRAFT_SKILL -> R.string.designsystem_tool_draft_skill_past
    ToolPhrase.DRAFT_CONNECTOR -> R.string.designsystem_tool_draft_connector_past
    ToolPhrase.DRAFT_PROVIDER -> R.string.designsystem_tool_draft_provider_past
    ToolPhrase.DRAFT_PROVIDER_UNNAMED -> R.string.designsystem_tool_draft_provider_unnamed_past
    ToolPhrase.DRAFT_EVENT -> R.string.designsystem_tool_draft_event_past
    ToolPhrase.OPEN_CLOCK -> R.string.designsystem_tool_open_clock_past
    ToolPhrase.DRAFT_EMAIL -> R.string.designsystem_tool_draft_email_past
    ToolPhrase.DRAFT_SMS -> R.string.designsystem_tool_draft_sms_past
    ToolPhrase.OPEN_DIALER -> R.string.designsystem_tool_open_dialer_past
    ToolPhrase.OPEN_MAP -> R.string.designsystem_tool_open_map_past
    ToolPhrase.OPEN_SHARE_SHEET -> R.string.designsystem_tool_open_share_sheet_past
    ToolPhrase.READ_CALENDAR -> R.string.designsystem_tool_read_calendar_past
    ToolPhrase.READ_CALENDAR_DAYS -> R.string.designsystem_tool_read_calendar_days_past
    ToolPhrase.PICK_CONTACT -> R.string.designsystem_tool_pick_contact_past
    ToolPhrase.PICK_PHOTOS -> R.string.designsystem_tool_pick_photos_past
    ToolPhrase.ADD_TO_CALENDAR -> R.string.designsystem_tool_add_to_calendar_past
    ToolPhrase.CHANGE_EVENT -> R.string.designsystem_tool_change_event_past
    ToolPhrase.DELETE_EVENT -> R.string.designsystem_tool_delete_event_past
    ToolPhrase.SET_REMINDER -> R.string.designsystem_tool_set_reminder_past
    ToolPhrase.CHECK_REMINDERS -> R.string.designsystem_tool_check_reminders_past
    ToolPhrase.CANCEL_REMINDER -> R.string.designsystem_tool_cancel_reminder_past
    ToolPhrase.NEW_CONTACT -> R.string.designsystem_tool_new_contact_past
    ToolPhrase.OFFER_SAVE -> R.string.designsystem_tool_offer_save_past
    ToolPhrase.COPY_TO_CLIPBOARD -> R.string.designsystem_tool_copy_to_clipboard_past
    ToolPhrase.START_SERVICE -> R.string.designsystem_tool_start_service_past
    ToolPhrase.STOP_SERVICE -> R.string.designsystem_tool_stop_service_past
    ToolPhrase.CHECK_SERVICES -> R.string.designsystem_tool_check_services_past
    ToolPhrase.READ_SERVICE_LOG -> R.string.designsystem_tool_read_service_log_past
    ToolPhrase.GIT_CHANGES -> R.string.designsystem_tool_git_changes_past
    ToolPhrase.GIT_CHANGES_TO -> R.string.designsystem_tool_git_changes_to_past
    ToolPhrase.GIT_LOG -> R.string.designsystem_tool_git_log_past
    ToolPhrase.GIT_SHOW -> R.string.designsystem_tool_git_show_past
    ToolPhrase.GIT_BRANCHES -> R.string.designsystem_tool_git_branches_past
    ToolPhrase.GIT_BLAME -> R.string.designsystem_tool_git_blame_past
    ToolPhrase.GIT_STATUS -> R.string.designsystem_tool_git_status_past
    ToolPhrase.COMMIT -> R.string.designsystem_tool_commit_past
    ToolPhrase.WEB_SEARCH -> R.string.designsystem_tool_web_search_past
    ToolPhrase.WEATHER -> R.string.designsystem_tool_weather_past
    ToolPhrase.WIKIPEDIA -> R.string.designsystem_tool_wikipedia_past
    ToolPhrase.FETCH -> R.string.designsystem_tool_fetch_past
    ToolPhrase.SSH_RUN -> R.string.designsystem_tool_ssh_run_past
    ToolPhrase.SSH_JOB_START -> R.string.designsystem_tool_ssh_job_start_past
    ToolPhrase.SSH_JOB_STATUS -> R.string.designsystem_tool_ssh_job_status_past
    ToolPhrase.SSH_JOBS_STATUS -> R.string.designsystem_tool_ssh_jobs_status_past
    ToolPhrase.SSH_JOB_STOP -> R.string.designsystem_tool_ssh_job_stop_past
    ToolPhrase.GITHUB_REPOS -> R.string.designsystem_tool_github_repos_past
    ToolPhrase.GITHUB_ISSUE -> R.string.designsystem_tool_github_issue_past
    ToolPhrase.GITHUB_ISSUES -> R.string.designsystem_tool_github_issues_past
    ToolPhrase.GITHUB_CREATE_ISSUE -> R.string.designsystem_tool_github_create_issue_past
    ToolPhrase.GITHUB_COMMENT -> R.string.designsystem_tool_github_comment_past
    ToolPhrase.EMAIL_REPLY -> R.string.designsystem_tool_email_reply_past
    ToolPhrase.EMAIL_SEND -> R.string.designsystem_tool_email_send_past
    ToolPhrase.EMAIL_CHECK -> R.string.designsystem_tool_email_check_past
    ToolPhrase.EMAIL_CHECK_UNREAD -> R.string.designsystem_tool_email_check_unread_past
    ToolPhrase.EMAIL_CHECK_INBOX -> R.string.designsystem_tool_email_check_inbox_past
    ToolPhrase.EMAIL_CHECK_UNREAD_INBOX -> R.string.designsystem_tool_email_check_unread_inbox_past
    ToolPhrase.EMAIL_READ -> R.string.designsystem_tool_email_read_past
    ToolPhrase.VERCEL_PROJECTS -> R.string.designsystem_tool_vercel_projects_past
    ToolPhrase.VERCEL_DEPLOYMENT -> R.string.designsystem_tool_vercel_deployment_past
    ToolPhrase.VERCEL_DEPLOYMENTS -> R.string.designsystem_tool_vercel_deployments_past
    ToolPhrase.VERCEL_DEPLOYMENTS_OF -> R.string.designsystem_tool_vercel_deployments_of_past
    ToolPhrase.VERCEL_DEPLOY -> R.string.designsystem_tool_vercel_deploy_past
    ToolPhrase.VERCEL_DEPLOY_PRODUCTION -> R.string.designsystem_tool_vercel_deploy_production_past
    ToolPhrase.ENV_VARIABLES -> R.string.designsystem_tool_env_variables_past
    ToolPhrase.VERCEL_ENV_SET -> R.string.designsystem_tool_vercel_env_set_past
    ToolPhrase.NETLIFY_SITES -> R.string.designsystem_tool_netlify_sites_past
    ToolPhrase.NETLIFY_DEPLOY_CHECK -> R.string.designsystem_tool_netlify_deploy_check_past
    ToolPhrase.DEPLOYS_OF -> R.string.designsystem_tool_deploys_of_past
    ToolPhrase.NETLIFY_DEPLOY -> R.string.designsystem_tool_netlify_deploy_past
    ToolPhrase.NETLIFY_DEPLOY_PRODUCTION -> R.string.designsystem_tool_netlify_deploy_production_past
    ToolPhrase.NETLIFY_ENV_SET -> R.string.designsystem_tool_netlify_env_set_past
    ToolPhrase.CLOUDFLARE_ZONES -> R.string.designsystem_tool_cloudflare_zones_past
    ToolPhrase.DNS_RECORDS -> R.string.designsystem_tool_dns_records_past
    ToolPhrase.DNS_SET -> R.string.designsystem_tool_dns_set_past
    ToolPhrase.DNS_DELETE -> R.string.designsystem_tool_dns_delete_past
    ToolPhrase.PAGES_PROJECT -> R.string.designsystem_tool_pages_project_past
    ToolPhrase.PAGES_PROJECTS -> R.string.designsystem_tool_pages_projects_past
    ToolPhrase.PAGES_DEPLOY -> R.string.designsystem_tool_pages_deploy_past
    ToolPhrase.CLOUDFLARE_PUBLISH -> R.string.designsystem_tool_cloudflare_publish_past
    ToolPhrase.SUPABASE_PROJECTS -> R.string.designsystem_tool_supabase_projects_past
    ToolPhrase.SUPABASE_QUERY -> R.string.designsystem_tool_supabase_query_past
    ToolPhrase.SUPABASE_SQL -> R.string.designsystem_tool_supabase_sql_past
    ToolPhrase.SUPABASE_FUNCTIONS -> R.string.designsystem_tool_supabase_functions_past
    ToolPhrase.SUPABASE_FUNCTION_DEPLOY -> R.string.designsystem_tool_supabase_function_deploy_past
    ToolPhrase.SUPABASE_API_KEYS -> R.string.designsystem_tool_supabase_api_keys_past
    ToolPhrase.SUPABASE_BRANCHES -> R.string.designsystem_tool_supabase_branches_past
    ToolPhrase.RENDER_SERVICES -> R.string.designsystem_tool_render_services_past
    ToolPhrase.RENDER_DEPLOY_CHECK -> R.string.designsystem_tool_render_deploy_check_past
    ToolPhrase.RENDER_DEPLOY -> R.string.designsystem_tool_render_deploy_past
    ToolPhrase.RENDER_LOGS -> R.string.designsystem_tool_render_logs_past
    ToolPhrase.RENDER_LOGS_TYPE -> R.string.designsystem_tool_render_logs_type_past
    ToolPhrase.RENDER_ENV_SET -> R.string.designsystem_tool_render_env_set_past
    ToolPhrase.RENDER_RESUME -> R.string.designsystem_tool_render_resume_past
    ToolPhrase.RENDER_SUSPEND -> R.string.designsystem_tool_render_suspend_past
    ToolPhrase.NOTIFY -> R.string.designsystem_tool_notify_past
    ToolPhrase.SEARCH_CHATS -> R.string.designsystem_tool_search_chats_past
    ToolPhrase.RECENT_CHATS -> R.string.designsystem_tool_recent_chats_past
    ToolPhrase.READ_CHAT -> R.string.designsystem_tool_read_chat_past
    ToolPhrase.SSH_HOSTS -> R.string.designsystem_tool_ssh_hosts_past
    ToolPhrase.SFTP_DOWNLOAD -> R.string.designsystem_tool_sftp_download_past
    ToolPhrase.SFTP_UPLOAD -> R.string.designsystem_tool_sftp_upload_past
    ToolPhrase.FIND_TOOLS -> R.string.designsystem_tool_find_tools_past
    ToolPhrase.RUN_TOOL -> R.string.designsystem_tool_run_tool_past
    ToolPhrase.LIST_CONNECTOR_TOOLS -> R.string.designsystem_tool_list_connector_tools_past
    ToolPhrase.ASK_HELPER -> R.string.designsystem_tool_ask_helper_past
    ToolPhrase.ASK_HELPER_BACKGROUND -> R.string.designsystem_tool_ask_helper_background_past
    ToolPhrase.ASK_A_HELPER -> R.string.designsystem_tool_ask_a_helper_past
    ToolPhrase.ASK_A_HELPER_BACKGROUND -> R.string.designsystem_tool_ask_a_helper_background_past
    ToolPhrase.CONTINUE_HELPER -> R.string.designsystem_tool_continue_helper_past
    ToolPhrase.CONTINUE_HELPER_BACKGROUND -> R.string.designsystem_tool_continue_helper_background_past
    ToolPhrase.CONTINUE_A_HELPER -> R.string.designsystem_tool_continue_a_helper_past
    ToolPhrase.CONTINUE_A_HELPER_BACKGROUND -> R.string.designsystem_tool_continue_a_helper_background_past
    ToolPhrase.WAIT_HELPERS -> R.string.designsystem_tool_wait_helpers_past
    ToolPhrase.CHECK_HELPER -> R.string.designsystem_tool_check_helper_past
    ToolPhrase.STOP_HELPER -> R.string.designsystem_tool_stop_helper_past
    ToolPhrase.DRAFT_HELPER -> R.string.designsystem_tool_draft_helper_past
    ToolPhrase.LOOK_AT_IMAGES -> R.plurals.designsystem_tool_look_at_images_past
    ToolPhrase.EDIT_CHANGES -> R.plurals.designsystem_tool_edit_changes_past
    ToolPhrase.FILL_FIELDS -> R.plurals.designsystem_tool_fill_fields_past
}

/** The string (a plural for a plural phrase) for [ToolPhrase.present]. */
internal fun ToolPhrase.presentRes(): Int = when (this) {
    ToolPhrase.LIST -> R.string.designsystem_tool_list_present
    ToolPhrase.LIST_WORKSPACE -> R.string.designsystem_tool_list_workspace_present
    ToolPhrase.READ -> R.string.designsystem_tool_read_present
    ToolPhrase.LOOK_AT -> R.string.designsystem_tool_look_at_present
    ToolPhrase.LOOK_AT_AN_IMAGE -> R.string.designsystem_tool_look_at_an_image_present
    ToolPhrase.SEARCH_FOR -> R.string.designsystem_tool_search_for_present
    ToolPhrase.FIND -> R.string.designsystem_tool_find_present
    ToolPhrase.FIND_IN -> R.string.designsystem_tool_find_in_present
    ToolPhrase.OUTLINE -> R.string.designsystem_tool_outline_present
    ToolPhrase.OUTLINE_WORKSPACE -> R.string.designsystem_tool_outline_workspace_present
    ToolPhrase.LOOK_AT_PROJECT -> R.string.designsystem_tool_look_at_project_present
    ToolPhrase.LOOK_AT_PROJECT_WORKSPACE -> R.string.designsystem_tool_look_at_project_workspace_present
    ToolPhrase.WRITE -> R.string.designsystem_tool_write_present
    ToolPhrase.EDIT -> R.string.designsystem_tool_edit_present
    ToolPhrase.MOVE -> R.string.designsystem_tool_move_present
    ToolPhrase.COPY -> R.string.designsystem_tool_copy_present
    ToolPhrase.DELETE -> R.string.designsystem_tool_delete_present
    ToolPhrase.SEND_FILE -> R.string.designsystem_tool_send_file_present
    ToolPhrase.MAKE_FOLDER -> R.string.designsystem_tool_make_folder_present
    ToolPhrase.OPEN -> R.string.designsystem_tool_open_present
    ToolPhrase.OPEN_NEW_TAB -> R.string.designsystem_tool_open_new_tab_present
    ToolPhrase.LOOK_FOR_ON_PAGE -> R.string.designsystem_tool_look_for_on_page_present
    ToolPhrase.LOOK_AT_PAGE -> R.string.designsystem_tool_look_at_page_present
    ToolPhrase.FILL_FORM -> R.string.designsystem_tool_fill_form_present
    ToolPhrase.WAIT_FOR -> R.string.designsystem_tool_wait_for_present
    ToolPhrase.WAIT_FOR_GONE -> R.string.designsystem_tool_wait_for_gone_present
    ToolPhrase.WAIT_SECONDS -> R.string.designsystem_tool_wait_seconds_present
    ToolPhrase.WAIT_MOMENT -> R.string.designsystem_tool_wait_moment_present
    ToolPhrase.SWITCH_TAB -> R.string.designsystem_tool_switch_tab_present
    ToolPhrase.CLOSE_TAB -> R.string.designsystem_tool_close_tab_present
    ToolPhrase.CHECK_TABS -> R.string.designsystem_tool_check_tabs_present
    ToolPhrase.DIALOG_ACCEPT -> R.string.designsystem_tool_dialog_accept_present
    ToolPhrase.DIALOG_CANCEL -> R.string.designsystem_tool_dialog_cancel_present
    ToolPhrase.UPLOAD -> R.string.designsystem_tool_upload_present
    ToolPhrase.UPLOAD_FILES -> R.string.designsystem_tool_upload_files_present
    ToolPhrase.SAVE_PAGE_SCREENSHOT -> R.string.designsystem_tool_save_page_screenshot_present
    ToolPhrase.SAVE_PAGE_ARCHIVE -> R.string.designsystem_tool_save_page_archive_present
    ToolPhrase.SAVE_PAGE_MARKDOWN -> R.string.designsystem_tool_save_page_markdown_present
    ToolPhrase.SAVE_PAGE_FILE -> R.string.designsystem_tool_save_page_file_present
    ToolPhrase.CHECK_PAGE_LOGS -> R.string.designsystem_tool_check_page_logs_present
    ToolPhrase.READ_PAGE -> R.string.designsystem_tool_read_page_present
    ToolPhrase.CLICK -> R.string.designsystem_tool_click_present
    ToolPhrase.TYPE -> R.string.designsystem_tool_type_present
    ToolPhrase.CHOOSE -> R.string.designsystem_tool_choose_present
    ToolPhrase.PRESS -> R.string.designsystem_tool_press_present
    ToolPhrase.SCROLL -> R.string.designsystem_tool_scroll_present
    ToolPhrase.SCROLL_UP -> R.string.designsystem_tool_scroll_up_present
    ToolPhrase.SCROLL_DOWN -> R.string.designsystem_tool_scroll_down_present
    ToolPhrase.SCROLL_LEFT -> R.string.designsystem_tool_scroll_left_present
    ToolPhrase.SCROLL_RIGHT -> R.string.designsystem_tool_scroll_right_present
    ToolPhrase.GO_BACK -> R.string.designsystem_tool_go_back_present
    ToolPhrase.BROWSER_TAKEOVER -> R.string.designsystem_tool_browser_takeover_present
    ToolPhrase.FILL_LOGIN -> R.string.designsystem_tool_fill_login_present
    ToolPhrase.FILL_LOGIN_THIS_SITE -> R.string.designsystem_tool_fill_login_this_site_present
    ToolPhrase.ASK_USER -> R.string.designsystem_tool_ask_user_present
    ToolPhrase.TASK_BUDGET_PAUSED -> R.string.designsystem_tool_task_budget_paused_present
    ToolPhrase.TASK_BUDGET -> R.string.designsystem_tool_task_budget_present
    ToolPhrase.RUN_COMMAND -> R.string.designsystem_tool_run_command_present
    ToolPhrase.REMEMBER -> R.string.designsystem_tool_remember_present
    ToolPhrase.RECALL -> R.string.designsystem_tool_recall_present
    ToolPhrase.SEARCH_MEMORY -> R.string.designsystem_tool_search_memory_present
    ToolPhrase.FORGET -> R.string.designsystem_tool_forget_present
    ToolPhrase.USE_SKILL -> R.string.designsystem_tool_use_skill_present
    ToolPhrase.READ_SKILL_FILE -> R.string.designsystem_tool_read_skill_file_present
    ToolPhrase.DRAFT_SKILL -> R.string.designsystem_tool_draft_skill_present
    ToolPhrase.DRAFT_CONNECTOR -> R.string.designsystem_tool_draft_connector_present
    ToolPhrase.DRAFT_PROVIDER -> R.string.designsystem_tool_draft_provider_present
    ToolPhrase.DRAFT_PROVIDER_UNNAMED -> R.string.designsystem_tool_draft_provider_unnamed_present
    ToolPhrase.DRAFT_EVENT -> R.string.designsystem_tool_draft_event_present
    ToolPhrase.OPEN_CLOCK -> R.string.designsystem_tool_open_clock_present
    ToolPhrase.DRAFT_EMAIL -> R.string.designsystem_tool_draft_email_present
    ToolPhrase.DRAFT_SMS -> R.string.designsystem_tool_draft_sms_present
    ToolPhrase.OPEN_DIALER -> R.string.designsystem_tool_open_dialer_present
    ToolPhrase.OPEN_MAP -> R.string.designsystem_tool_open_map_present
    ToolPhrase.OPEN_SHARE_SHEET -> R.string.designsystem_tool_open_share_sheet_present
    ToolPhrase.READ_CALENDAR -> R.string.designsystem_tool_read_calendar_present
    ToolPhrase.READ_CALENDAR_DAYS -> R.string.designsystem_tool_read_calendar_days_present
    ToolPhrase.PICK_CONTACT -> R.string.designsystem_tool_pick_contact_present
    ToolPhrase.PICK_PHOTOS -> R.string.designsystem_tool_pick_photos_present
    ToolPhrase.ADD_TO_CALENDAR -> R.string.designsystem_tool_add_to_calendar_present
    ToolPhrase.CHANGE_EVENT -> R.string.designsystem_tool_change_event_present
    ToolPhrase.DELETE_EVENT -> R.string.designsystem_tool_delete_event_present
    ToolPhrase.SET_REMINDER -> R.string.designsystem_tool_set_reminder_present
    ToolPhrase.CHECK_REMINDERS -> R.string.designsystem_tool_check_reminders_present
    ToolPhrase.CANCEL_REMINDER -> R.string.designsystem_tool_cancel_reminder_present
    ToolPhrase.NEW_CONTACT -> R.string.designsystem_tool_new_contact_present
    ToolPhrase.OFFER_SAVE -> R.string.designsystem_tool_offer_save_present
    ToolPhrase.COPY_TO_CLIPBOARD -> R.string.designsystem_tool_copy_to_clipboard_present
    ToolPhrase.START_SERVICE -> R.string.designsystem_tool_start_service_present
    ToolPhrase.STOP_SERVICE -> R.string.designsystem_tool_stop_service_present
    ToolPhrase.CHECK_SERVICES -> R.string.designsystem_tool_check_services_present
    ToolPhrase.READ_SERVICE_LOG -> R.string.designsystem_tool_read_service_log_present
    ToolPhrase.GIT_CHANGES -> R.string.designsystem_tool_git_changes_present
    ToolPhrase.GIT_CHANGES_TO -> R.string.designsystem_tool_git_changes_to_present
    ToolPhrase.GIT_LOG -> R.string.designsystem_tool_git_log_present
    ToolPhrase.GIT_SHOW -> R.string.designsystem_tool_git_show_present
    ToolPhrase.GIT_BRANCHES -> R.string.designsystem_tool_git_branches_present
    ToolPhrase.GIT_BLAME -> R.string.designsystem_tool_git_blame_present
    ToolPhrase.GIT_STATUS -> R.string.designsystem_tool_git_status_present
    ToolPhrase.COMMIT -> R.string.designsystem_tool_commit_present
    ToolPhrase.WEB_SEARCH -> R.string.designsystem_tool_web_search_present
    ToolPhrase.WEATHER -> R.string.designsystem_tool_weather_present
    ToolPhrase.WIKIPEDIA -> R.string.designsystem_tool_wikipedia_present
    ToolPhrase.FETCH -> R.string.designsystem_tool_fetch_present
    ToolPhrase.SSH_RUN -> R.string.designsystem_tool_ssh_run_present
    ToolPhrase.SSH_JOB_START -> R.string.designsystem_tool_ssh_job_start_present
    ToolPhrase.SSH_JOB_STATUS -> R.string.designsystem_tool_ssh_job_status_present
    ToolPhrase.SSH_JOBS_STATUS -> R.string.designsystem_tool_ssh_jobs_status_present
    ToolPhrase.SSH_JOB_STOP -> R.string.designsystem_tool_ssh_job_stop_present
    ToolPhrase.GITHUB_REPOS -> R.string.designsystem_tool_github_repos_present
    ToolPhrase.GITHUB_ISSUE -> R.string.designsystem_tool_github_issue_present
    ToolPhrase.GITHUB_ISSUES -> R.string.designsystem_tool_github_issues_present
    ToolPhrase.GITHUB_CREATE_ISSUE -> R.string.designsystem_tool_github_create_issue_present
    ToolPhrase.GITHUB_COMMENT -> R.string.designsystem_tool_github_comment_present
    ToolPhrase.EMAIL_REPLY -> R.string.designsystem_tool_email_reply_present
    ToolPhrase.EMAIL_SEND -> R.string.designsystem_tool_email_send_present
    ToolPhrase.EMAIL_CHECK -> R.string.designsystem_tool_email_check_present
    ToolPhrase.EMAIL_CHECK_UNREAD -> R.string.designsystem_tool_email_check_unread_present
    ToolPhrase.EMAIL_CHECK_INBOX -> R.string.designsystem_tool_email_check_inbox_present
    ToolPhrase.EMAIL_CHECK_UNREAD_INBOX -> R.string.designsystem_tool_email_check_unread_inbox_present
    ToolPhrase.EMAIL_READ -> R.string.designsystem_tool_email_read_present
    ToolPhrase.VERCEL_PROJECTS -> R.string.designsystem_tool_vercel_projects_present
    ToolPhrase.VERCEL_DEPLOYMENT -> R.string.designsystem_tool_vercel_deployment_present
    ToolPhrase.VERCEL_DEPLOYMENTS -> R.string.designsystem_tool_vercel_deployments_present
    ToolPhrase.VERCEL_DEPLOYMENTS_OF -> R.string.designsystem_tool_vercel_deployments_of_present
    ToolPhrase.VERCEL_DEPLOY -> R.string.designsystem_tool_vercel_deploy_present
    ToolPhrase.VERCEL_DEPLOY_PRODUCTION -> R.string.designsystem_tool_vercel_deploy_production_present
    ToolPhrase.ENV_VARIABLES -> R.string.designsystem_tool_env_variables_present
    ToolPhrase.VERCEL_ENV_SET -> R.string.designsystem_tool_vercel_env_set_present
    ToolPhrase.NETLIFY_SITES -> R.string.designsystem_tool_netlify_sites_present
    ToolPhrase.NETLIFY_DEPLOY_CHECK -> R.string.designsystem_tool_netlify_deploy_check_present
    ToolPhrase.DEPLOYS_OF -> R.string.designsystem_tool_deploys_of_present
    ToolPhrase.NETLIFY_DEPLOY -> R.string.designsystem_tool_netlify_deploy_present
    ToolPhrase.NETLIFY_DEPLOY_PRODUCTION -> R.string.designsystem_tool_netlify_deploy_production_present
    ToolPhrase.NETLIFY_ENV_SET -> R.string.designsystem_tool_netlify_env_set_present
    ToolPhrase.CLOUDFLARE_ZONES -> R.string.designsystem_tool_cloudflare_zones_present
    ToolPhrase.DNS_RECORDS -> R.string.designsystem_tool_dns_records_present
    ToolPhrase.DNS_SET -> R.string.designsystem_tool_dns_set_present
    ToolPhrase.DNS_DELETE -> R.string.designsystem_tool_dns_delete_present
    ToolPhrase.PAGES_PROJECT -> R.string.designsystem_tool_pages_project_present
    ToolPhrase.PAGES_PROJECTS -> R.string.designsystem_tool_pages_projects_present
    ToolPhrase.PAGES_DEPLOY -> R.string.designsystem_tool_pages_deploy_present
    ToolPhrase.CLOUDFLARE_PUBLISH -> R.string.designsystem_tool_cloudflare_publish_present
    ToolPhrase.SUPABASE_PROJECTS -> R.string.designsystem_tool_supabase_projects_present
    ToolPhrase.SUPABASE_QUERY -> R.string.designsystem_tool_supabase_query_present
    ToolPhrase.SUPABASE_SQL -> R.string.designsystem_tool_supabase_sql_present
    ToolPhrase.SUPABASE_FUNCTIONS -> R.string.designsystem_tool_supabase_functions_present
    ToolPhrase.SUPABASE_FUNCTION_DEPLOY -> R.string.designsystem_tool_supabase_function_deploy_present
    ToolPhrase.SUPABASE_API_KEYS -> R.string.designsystem_tool_supabase_api_keys_present
    ToolPhrase.SUPABASE_BRANCHES -> R.string.designsystem_tool_supabase_branches_present
    ToolPhrase.RENDER_SERVICES -> R.string.designsystem_tool_render_services_present
    ToolPhrase.RENDER_DEPLOY_CHECK -> R.string.designsystem_tool_render_deploy_check_present
    ToolPhrase.RENDER_DEPLOY -> R.string.designsystem_tool_render_deploy_present
    ToolPhrase.RENDER_LOGS -> R.string.designsystem_tool_render_logs_present
    ToolPhrase.RENDER_LOGS_TYPE -> R.string.designsystem_tool_render_logs_type_present
    ToolPhrase.RENDER_ENV_SET -> R.string.designsystem_tool_render_env_set_present
    ToolPhrase.RENDER_RESUME -> R.string.designsystem_tool_render_resume_present
    ToolPhrase.RENDER_SUSPEND -> R.string.designsystem_tool_render_suspend_present
    ToolPhrase.NOTIFY -> R.string.designsystem_tool_notify_present
    ToolPhrase.SEARCH_CHATS -> R.string.designsystem_tool_search_chats_present
    ToolPhrase.RECENT_CHATS -> R.string.designsystem_tool_recent_chats_present
    ToolPhrase.READ_CHAT -> R.string.designsystem_tool_read_chat_present
    ToolPhrase.SSH_HOSTS -> R.string.designsystem_tool_ssh_hosts_present
    ToolPhrase.SFTP_DOWNLOAD -> R.string.designsystem_tool_sftp_download_present
    ToolPhrase.SFTP_UPLOAD -> R.string.designsystem_tool_sftp_upload_present
    ToolPhrase.FIND_TOOLS -> R.string.designsystem_tool_find_tools_present
    ToolPhrase.RUN_TOOL -> R.string.designsystem_tool_run_tool_present
    ToolPhrase.LIST_CONNECTOR_TOOLS -> R.string.designsystem_tool_list_connector_tools_present
    ToolPhrase.ASK_HELPER -> R.string.designsystem_tool_ask_helper_present
    ToolPhrase.ASK_HELPER_BACKGROUND -> R.string.designsystem_tool_ask_helper_background_present
    ToolPhrase.ASK_A_HELPER -> R.string.designsystem_tool_ask_a_helper_present
    ToolPhrase.ASK_A_HELPER_BACKGROUND -> R.string.designsystem_tool_ask_a_helper_background_present
    ToolPhrase.CONTINUE_HELPER -> R.string.designsystem_tool_continue_helper_present
    ToolPhrase.CONTINUE_HELPER_BACKGROUND -> R.string.designsystem_tool_continue_helper_background_present
    ToolPhrase.CONTINUE_A_HELPER -> R.string.designsystem_tool_continue_a_helper_present
    ToolPhrase.CONTINUE_A_HELPER_BACKGROUND -> R.string.designsystem_tool_continue_a_helper_background_present
    ToolPhrase.WAIT_HELPERS -> R.string.designsystem_tool_wait_helpers_present
    ToolPhrase.CHECK_HELPER -> R.string.designsystem_tool_check_helper_present
    ToolPhrase.STOP_HELPER -> R.string.designsystem_tool_stop_helper_present
    ToolPhrase.DRAFT_HELPER -> R.string.designsystem_tool_draft_helper_present
    ToolPhrase.LOOK_AT_IMAGES -> R.plurals.designsystem_tool_look_at_images_present
    ToolPhrase.EDIT_CHANGES -> R.plurals.designsystem_tool_edit_changes_present
    ToolPhrase.FILL_FIELDS -> R.plurals.designsystem_tool_fill_fields_present
}

/** The string for [ToolActivity.english]. */
internal fun ToolActivity.res(): Int = when (this) {
    ToolActivity.FINDING_TOOL -> R.string.designsystem_tool_activity_finding_tool
    ToolActivity.USING_TOOL -> R.string.designsystem_tool_activity_using_tool
    ToolActivity.CALLING_CONNECTOR -> R.string.designsystem_tool_activity_calling_connector
    ToolActivity.SEARCHING_WEB -> R.string.designsystem_tool_activity_searching_web
    ToolActivity.READING_PAGE -> R.string.designsystem_tool_activity_reading_page
    ToolActivity.CHECKING_WEATHER -> R.string.designsystem_tool_activity_checking_weather
    ToolActivity.WIKIPEDIA -> R.string.designsystem_tool_activity_wikipedia
    ToolActivity.BROWSING -> R.string.designsystem_tool_activity_browsing
    ToolActivity.RUNNING_COMMAND -> R.string.designsystem_tool_activity_running_command
    ToolActivity.CHECKING_CHANGES -> R.string.designsystem_tool_activity_checking_changes
    ToolActivity.COMMITTING -> R.string.designsystem_tool_activity_committing
    ToolActivity.SEARCHING_FILES -> R.string.designsystem_tool_activity_searching_files
    ToolActivity.READING_CODE -> R.string.designsystem_tool_activity_reading_code
    ToolActivity.REMOTE_MACHINE -> R.string.designsystem_tool_activity_remote_machine
    ToolActivity.CHECKING_MEMORY -> R.string.designsystem_tool_activity_checking_memory
    ToolActivity.USING_SKILL -> R.string.designsystem_tool_activity_using_skill
    ToolActivity.CHECKING_CALENDAR -> R.string.designsystem_tool_activity_checking_calendar
    ToolActivity.PICKING_CONTACT -> R.string.designsystem_tool_activity_picking_contact
    ToolActivity.PICKING_PHOTOS -> R.string.designsystem_tool_activity_picking_photos
    ToolActivity.UPDATING_CALENDAR -> R.string.designsystem_tool_activity_updating_calendar
    ToolActivity.REMINDERS -> R.string.designsystem_tool_activity_reminders
    ToolActivity.PICKING_SAVE_PLACE -> R.string.designsystem_tool_activity_picking_save_place
    ToolActivity.COPYING -> R.string.designsystem_tool_activity_copying
    ToolActivity.OPENING_APP -> R.string.designsystem_tool_activity_opening_app
    ToolActivity.GITHUB -> R.string.designsystem_tool_activity_github
    ToolActivity.EMAIL -> R.string.designsystem_tool_activity_email
    ToolActivity.VERCEL -> R.string.designsystem_tool_activity_vercel
    ToolActivity.NETLIFY -> R.string.designsystem_tool_activity_netlify
    ToolActivity.CLOUDFLARE -> R.string.designsystem_tool_activity_cloudflare
    ToolActivity.SUPABASE -> R.string.designsystem_tool_activity_supabase
    ToolActivity.RENDER -> R.string.designsystem_tool_activity_render
    ToolActivity.WAITING_FOR_ANSWER -> R.string.designsystem_tool_activity_waiting_for_answer
    ToolActivity.HELPERS -> R.string.designsystem_tool_activity_helpers
    ToolActivity.FILES -> R.string.designsystem_tool_activity_files
}
