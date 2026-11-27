package dev.locus.core.designsystem

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.model.ApprovalLineText
import dev.locus.core.model.ApprovalPhrase

/*
 * Approval lines in the app's language (docs/design/localization.md). A tool that asks before it acts
 * says what the call would do in English for the audit log, the safety reviewer and notifications
 * (`Tool.describe`), and hands the same line over as data (`Tool.describeText`); these word it from this
 * module's strings, with the call's own values (paths, commands, hosts) as they are. The English strings
 * are the tools' words exactly (ApprovalLinesResourcesTest, and each tool module's describe tests).
 */

/** The line in the user's language; an argument that is itself a line is worded first. */
fun ApprovalLineText.asString(resources: Resources): String {
    val values = args.map { if (it is ApprovalLineText) it.asString(resources) else it }.toTypedArray()
    val id = phrase.res()
    return when {
        phrase.plural -> resources.getQuantityString(id, count ?: 0, *values)
        values.isEmpty() -> resources.getString(id)
        else -> resources.getString(id, *values)
    }
}

/**
 * [summary] (an approval's line, then why it asks, if it says) with its first part, [line]'s English,
 * in the user's language. Said as it is when there's no [line] or [summary] doesn't start with its
 * English, so the card never shows something other than what was asked.
 */
fun localizedApprovalSummary(resources: Resources, summary: String, line: ApprovalLineText?): String {
    if (line == null) return summary
    val english = line.english
    return if (summary.startsWith(english)) line.asString(resources) + summary.removePrefix(english) else summary
}

/** [localizedApprovalSummary] in the composition's resources, so it follows a language change. */
@Composable
@ReadOnlyComposable
fun localizedApprovalSummary(summary: String, line: ApprovalLineText?): String = localizedApprovalSummary(LocalResources.current, summary, line)

/** The string (a plural for a plural phrase) for each [ApprovalPhrase]; ApprovalLinesResourcesTest checks every one has one. */
internal fun ApprovalPhrase.res(): Int = APPROVAL_RES.getValue(this)

private val APPROVAL_RES: Map<ApprovalPhrase, Int> = mapOf(
    ApprovalPhrase.CREATE_FILE to R.plurals.designsystem_approval_create_file,
    ApprovalPhrase.OVERWRITE_FILE to R.plurals.designsystem_approval_overwrite_file,
    ApprovalPhrase.EDIT_FILE to R.string.designsystem_approval_edit_file,
    ApprovalPhrase.EDIT_FILE_CHANGES to R.plurals.designsystem_approval_edit_file_changes,
    ApprovalPhrase.MOVE to R.string.designsystem_approval_move,
    ApprovalPhrase.COPY to R.string.designsystem_approval_copy,
    ApprovalPhrase.DELETE_TO_TRASH to R.string.designsystem_approval_delete_to_trash,
    ApprovalPhrase.MAKE_FOLDER to R.string.designsystem_approval_make_folder,
    ApprovalPhrase.REMEMBER to R.string.designsystem_approval_remember,
    ApprovalPhrase.FORGET to R.string.designsystem_approval_forget,
    ApprovalPhrase.DRAFT_PROVIDER to R.string.designsystem_approval_draft_provider,
    ApprovalPhrase.DRAFT_PROVIDER_AT to R.string.designsystem_approval_draft_provider_at,
    ApprovalPhrase.RUN_COMMAND to R.string.designsystem_approval_run_command,
    ApprovalPhrase.START_SERVICE to R.string.designsystem_approval_start_service,
    ApprovalPhrase.COMMIT to R.string.designsystem_approval_commit,
    ApprovalPhrase.COMMIT_ALL_CHANGES to R.string.designsystem_approval_commit_all_changes,
    ApprovalPhrase.SSH_RUN to R.string.designsystem_approval_ssh_run,
    ApprovalPhrase.SSH_JOB_START to R.string.designsystem_approval_ssh_job_start,
    ApprovalPhrase.SSH_JOB_STOP to R.string.designsystem_approval_ssh_job_stop,
    ApprovalPhrase.SFTP_DOWNLOAD to R.string.designsystem_approval_sftp_download,
    ApprovalPhrase.SFTP_UPLOAD to R.string.designsystem_approval_sftp_upload,
    ApprovalPhrase.BROWSER_OPEN to R.string.designsystem_approval_browser_open,
    ApprovalPhrase.BROWSER_OPEN_NEW_TAB to R.string.designsystem_approval_browser_open_new_tab,
    ApprovalPhrase.BROWSER_CLICK to R.string.designsystem_approval_browser_click,
    ApprovalPhrase.BROWSER_TYPE to R.string.designsystem_approval_browser_type,
    ApprovalPhrase.BROWSER_TYPE_SUBMIT to R.string.designsystem_approval_browser_type_submit,
    ApprovalPhrase.BROWSER_CHOOSE to R.string.designsystem_approval_browser_choose,
    ApprovalPhrase.BROWSER_FILL_FIELDS to R.plurals.designsystem_approval_browser_fill_fields,
    ApprovalPhrase.BROWSER_PRESS to R.string.designsystem_approval_browser_press,
    ApprovalPhrase.BROWSER_DIALOG_ACCEPT to R.string.designsystem_approval_browser_dialog_accept,
    ApprovalPhrase.BROWSER_DIALOG_CANCEL to R.string.designsystem_approval_browser_dialog_cancel,
    ApprovalPhrase.BROWSER_UPLOAD to R.string.designsystem_approval_browser_upload,
    ApprovalPhrase.BROWSER_LOGIN to R.string.designsystem_approval_browser_login,
    ApprovalPhrase.BROWSER_LOGIN_THIS_PAGE to R.string.designsystem_approval_browser_login_this_page,
    ApprovalPhrase.EMAIL_SEND to R.string.designsystem_approval_email_send,
    ApprovalPhrase.EMAIL_SEND_CC to R.string.designsystem_approval_email_send_cc,
    ApprovalPhrase.EMAIL_NO_SUBJECT to R.string.designsystem_approval_email_no_subject,
    ApprovalPhrase.EMAIL_REPLY_SUBJECT to R.string.designsystem_approval_email_reply_subject,
    ApprovalPhrase.EMAIL_INVALID_ADDRESSES to R.string.designsystem_approval_email_invalid_addresses,
    ApprovalPhrase.GITHUB_CREATE_ISSUE to R.string.designsystem_approval_github_create_issue,
    ApprovalPhrase.GITHUB_COMMENT to R.string.designsystem_approval_github_comment,
    ApprovalPhrase.VERCEL_DEPLOY_PRODUCTION to R.string.designsystem_approval_vercel_deploy_production,
    ApprovalPhrase.VERCEL_DEPLOY_PREVIEW to R.string.designsystem_approval_vercel_deploy_preview,
    ApprovalPhrase.VERCEL_REDEPLOY_PRODUCTION to R.string.designsystem_approval_vercel_redeploy_production,
    ApprovalPhrase.VERCEL_REDEPLOY_PREVIEW to R.string.designsystem_approval_vercel_redeploy_preview,
    ApprovalPhrase.VERCEL_ENV_SET to R.string.designsystem_approval_vercel_env_set,
    ApprovalPhrase.NETLIFY_DEPLOY_PRODUCTION to R.string.designsystem_approval_netlify_deploy_production,
    ApprovalPhrase.NETLIFY_DEPLOY_DRAFT to R.string.designsystem_approval_netlify_deploy_draft,
    ApprovalPhrase.NETLIFY_ENV_SET to R.string.designsystem_approval_netlify_env_set,
    ApprovalPhrase.RENDER_DEPLOY to R.string.designsystem_approval_render_deploy,
    ApprovalPhrase.RENDER_DEPLOY_CLEAR_CACHE to R.string.designsystem_approval_render_deploy_clear_cache,
    ApprovalPhrase.RENDER_DEPLOY_AT to R.string.designsystem_approval_render_deploy_at,
    ApprovalPhrase.RENDER_DEPLOY_AT_CLEAR_CACHE to R.string.designsystem_approval_render_deploy_at_clear_cache,
    ApprovalPhrase.RENDER_ENV_SET to R.string.designsystem_approval_render_env_set,
    ApprovalPhrase.RENDER_RESUME to R.string.designsystem_approval_render_resume,
    ApprovalPhrase.RENDER_SUSPEND to R.string.designsystem_approval_render_suspend,
    ApprovalPhrase.SUPABASE_SQL to R.string.designsystem_approval_supabase_sql,
    ApprovalPhrase.SUPABASE_FUNCTION_DEPLOY to R.string.designsystem_approval_supabase_function_deploy,
    ApprovalPhrase.CLOUDFLARE_DNS_SET to R.string.designsystem_approval_cloudflare_dns_set,
    ApprovalPhrase.CLOUDFLARE_DNS_DELETE to R.string.designsystem_approval_cloudflare_dns_delete,
    ApprovalPhrase.CLOUDFLARE_PAGES_DEPLOY_BRANCH to R.string.designsystem_approval_cloudflare_pages_deploy_branch,
    ApprovalPhrase.CLOUDFLARE_PAGES_DEPLOY_PRODUCTION to R.string.designsystem_approval_cloudflare_pages_deploy_production,
    ApprovalPhrase.CLOUDFLARE_PUBLISH to R.string.designsystem_approval_cloudflare_publish,
    ApprovalPhrase.CLOUDFLARE_PUBLISH_SPA to R.string.designsystem_approval_cloudflare_publish_spa,
    ApprovalPhrase.READ_CALENDAR_DAYS to R.plurals.designsystem_approval_read_calendar_days,
    ApprovalPhrase.READ_CALENDAR_DAYS_QUERY to R.plurals.designsystem_approval_read_calendar_days_query,
    ApprovalPhrase.PICK_CONTACT to R.string.designsystem_approval_pick_contact,
    ApprovalPhrase.PICK_CONTACT_REASON to R.string.designsystem_approval_pick_contact_reason,
    ApprovalPhrase.PICK_PHOTOS to R.plurals.designsystem_approval_pick_photos,
    ApprovalPhrase.PICK_PHOTOS_REASON to R.plurals.designsystem_approval_pick_photos_reason,
    ApprovalPhrase.ADD_TO_CALENDAR to R.string.designsystem_approval_add_to_calendar,
    ApprovalPhrase.CHANGE_EVENT to R.string.designsystem_approval_change_event,
    ApprovalPhrase.DELETE_EVENT to R.string.designsystem_approval_delete_event,
    ApprovalPhrase.CANCEL_REMINDER to R.string.designsystem_approval_cancel_reminder,
)
