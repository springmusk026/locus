package dev.locus.core.model

import java.util.Locale

/**
 * A tool's approval line (`Tool.describe`) as data (docs/design/localization.md): the phrase and the
 * values in it, which come from the call's arguments and stay as they are (paths, commands, hosts,
 * addresses). [english] is what `describe` returns, word for word, for the audit log, the safety
 * reviewer and the notifications; the approval card words the same phrase in the user's language from
 * core/designsystem's strings (`ApprovalLines.kt`).
 *
 * An argument may itself be an [ApprovalLineText] (a subject Locus names, "(no subject)"): it is worded
 * first, in the same language.
 */
data class ApprovalLineText(val phrase: ApprovalPhrase, val args: List<Any>, val count: Int?) {
    constructor(phrase: ApprovalPhrase, vararg values: Any) : this(phrase, values.toList(), count = null)

    val english: String get() {
        val template = if (phrase.englishOne != null && count == 1) phrase.englishOne else phrase.english
        if (args.isEmpty()) return template
        val values = args.map { if (it is ApprovalLineText) it.english else it }.toTypedArray()
        return String.format(Locale.ROOT, template, *values)
    }

    companion object {
        /** A plural phrase: [count] picks the form and is one of the [args]. */
        fun counted(phrase: ApprovalPhrase, count: Int, vararg args: Any) = ApprovalLineText(phrase, args.toList(), count = count)
    }
}

/**
 * The approval lines of the tools that ask before they act, in English, exactly as their `describe`
 * says them. A plural phrase has [englishOne] for a count of one; where the core says the plural for
 * one too ("Edit a.kt (1 changes)"), it keeps that, since the audit log's text doesn't change. Every
 * entry has a string resource in each language (core/designsystem `ApprovalLines.kt`).
 */
enum class ApprovalPhrase(val english: String, val englishOne: String? = null) {
    // Workspace files (core/agent WorkspaceTools)
    CREATE_FILE("Create %1\$s (%2\$d characters)", "Create %1\$s (%2\$d characters)"),
    OVERWRITE_FILE("Overwrite %1\$s (%2\$d characters)", "Overwrite %1\$s (%2\$d characters)"),
    EDIT_FILE("Edit %1\$s"),
    EDIT_FILE_CHANGES("Edit %1\$s (%2\$d changes)", "Edit %1\$s (%2\$d changes)"),
    MOVE("Move %1\$s → %2\$s"),
    COPY("Copy %1\$s → %2\$s"),
    DELETE_TO_TRASH("Delete %1\$s (recoverable from trash)"),
    MAKE_FOLDER("Create folder %1\$s"),

    // Memory (core/agent HomeTools)
    REMEMBER("Remember “%1\$s”"),
    FORGET("Forget “%1\$s”"),

    // Drafts for review
    DRAFT_PROVIDER("Draft provider “%1\$s” for your review"),
    DRAFT_PROVIDER_AT("Draft provider “%1\$s” at %2\$s for your review"),

    // Commands (core/linux, core/data SSH)
    RUN_COMMAND("Run: %1\$s"),
    START_SERVICE("Start “%1\$s”: %2\$s"),
    COMMIT("Commit %1\$s: “%2\$s”"),
    COMMIT_ALL_CHANGES("all changes"),
    SSH_RUN("On %1\$s: %2\$s"),
    SSH_JOB_START("Start a background job on %1\$s: %2\$s"),
    SSH_JOB_STOP("Stop job %1\$s on %2\$s"),
    SFTP_DOWNLOAD("Download %1\$s:%2\$s → %3\$s"),
    SFTP_UPLOAD("Upload %1\$s → %2\$s:%3\$s"),

    // The agent's browser (core/browser)
    BROWSER_OPEN("Open %1\$s"),
    BROWSER_OPEN_NEW_TAB("Open %1\$s in a new tab"),
    BROWSER_CLICK("Click %1\$s"),
    BROWSER_TYPE("Type “%1\$s” into %2\$s"),
    BROWSER_TYPE_SUBMIT("Type “%1\$s” into %2\$s and submit"),
    BROWSER_CHOOSE("Choose “%1\$s” in %2\$s"),
    BROWSER_FILL_FIELDS("Fill %1\$d fields", "Fill %1\$d fields"),
    BROWSER_PRESS("Press %1\$s"),
    BROWSER_DIALOG_ACCEPT("Answer OK to the page's dialog"),
    BROWSER_DIALOG_CANCEL("Cancel the page's dialog"),
    BROWSER_UPLOAD("Upload %1\$s"),
    BROWSER_LOGIN("Fill your saved login for %1\$s"),
    BROWSER_LOGIN_THIS_PAGE("Fill your saved login for this page"),

    // Email (core/agent Email)
    EMAIL_SEND("Send email to %1\$s: “%2\$s” — %3\$s"),
    EMAIL_SEND_CC("Send email to %1\$s (cc %2\$s): “%3\$s” — %4\$s"),
    EMAIL_NO_SUBJECT("(no subject)"),
    EMAIL_REPLY_SUBJECT("reply"),
    EMAIL_INVALID_ADDRESSES("(an invalid address list, which won't be sent)"),

    // Developer platforms (core/agent integrations)
    GITHUB_CREATE_ISSUE("Open issue “%1\$s” in %2\$s"),
    GITHUB_COMMENT("Comment on %1\$s#%2\$s"),
    VERCEL_DEPLOY_PRODUCTION("Deploy %1\$s to Vercel project %2\$s (production)"),
    VERCEL_DEPLOY_PREVIEW("Deploy %1\$s to Vercel project %2\$s (preview)"),
    VERCEL_REDEPLOY_PRODUCTION("Redeploy %1\$s to Vercel project %2\$s (production)"),
    VERCEL_REDEPLOY_PREVIEW("Redeploy %1\$s to Vercel project %2\$s (preview)"),
    VERCEL_ENV_SET("Set %1\$s on Vercel project %2\$s"),
    NETLIFY_DEPLOY_PRODUCTION("Deploy %1\$s to Netlify site %2\$s (production)"),
    NETLIFY_DEPLOY_DRAFT("Deploy %1\$s to Netlify site %2\$s (draft)"),
    NETLIFY_ENV_SET("Set %1\$s on Netlify site %2\$s"),
    RENDER_DEPLOY("Deploy Render service %1\$s"),
    RENDER_DEPLOY_CLEAR_CACHE("Deploy Render service %1\$s (clear cache)"),
    RENDER_DEPLOY_AT("Deploy Render service %1\$s at %2\$s"),
    RENDER_DEPLOY_AT_CLEAR_CACHE("Deploy Render service %1\$s at %2\$s (clear cache)"),
    RENDER_ENV_SET("Set %1\$s on Render service %2\$s"),
    RENDER_RESUME("Resume Render service %1\$s"),
    RENDER_SUSPEND("Suspend Render service %1\$s"),
    SUPABASE_SQL("Run SQL on %1\$s: %2\$s"),
    SUPABASE_FUNCTION_DEPLOY("Deploy %1\$s as Edge Function %2\$s on %3\$s"),
    CLOUDFLARE_DNS_SET("Set DNS %1\$s %2\$s → %3\$s in %4\$s"),
    CLOUDFLARE_DNS_DELETE("Delete DNS record %1\$s from %2\$s"),
    CLOUDFLARE_PAGES_DEPLOY_BRANCH("Deploy Pages project %1\$s from %2\$s"),
    CLOUDFLARE_PAGES_DEPLOY_PRODUCTION("Deploy Pages project %1\$s (production)"),
    CLOUDFLARE_PUBLISH("Publish %1\$s to Cloudflare as %2\$s.workers.dev"),
    CLOUDFLARE_PUBLISH_SPA("Publish %1\$s to Cloudflare as %2\$s.workers.dev (single-page app)"),

    // The phone (core/data device integrations)
    READ_CALENDAR_DAYS("Read your calendar for the next %1\$d days", "Read your calendar for the next %1\$d days"),
    READ_CALENDAR_DAYS_QUERY("Read your calendar for the next %1\$d days (“%2\$s”)", "Read your calendar for the next %1\$d days (“%2\$s”)"),
    PICK_CONTACT("Ask you to pick a contact"),
    PICK_CONTACT_REASON("Ask you to pick a contact: %1\$s"),
    PICK_PHOTOS("Ask you to pick up to %1\$d photos", "Ask you to pick up to %1\$d photo"),
    PICK_PHOTOS_REASON("Ask you to pick up to %1\$d photos: %2\$s", "Ask you to pick up to %1\$d photo: %2\$s"),
    ADD_TO_CALENDAR("Add “%1\$s” to your calendar"),
    CHANGE_EVENT("Change a calendar event"),
    DELETE_EVENT("Delete a calendar event"),
    CANCEL_REMINDER("Cancel a reminder"),
    ;

    /** Whether the phrase has a form per count ([ApprovalLineText.count]). */
    val plural: Boolean get() = englishOne != null
}
