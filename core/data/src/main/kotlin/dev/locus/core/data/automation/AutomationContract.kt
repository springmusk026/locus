package dev.locus.core.data.automation

/**
 * The intent contract other apps (Tasker, Automate, a script) use to ask Locus to start tasks. The
 * full description, with examples, is docs/design/android-bridge.md; these names are public API, so
 * they never change once released.
 */
object AutomationContract {
    /**
     * Declared by Locus with protectionLevel "dangerous": an app that declares it in its own manifest can
     * be granted it by the user (App info → Permissions). Holding it, and being named by Android, lets an
     * app's requests run without Locus asking each time; anything else is confirmed in Locus first.
     * Named after the application id (`dev.locus.permission.RUN_TASK`; the debug build's is
     * `dev.locus.debug.permission.RUN_TASK`), so the two builds, signed differently, can be installed side by side.
     */
    fun permission(packageName: String) = "$packageName.permission.RUN_TASK"

    /** The release build's permission. */
    const val PERMISSION = "dev.locus.permission.RUN_TASK"

    /** Start a new chat with [EXTRA_PROMPT] (optionally in [EXTRA_PROJECT]). */
    const val ACTION_START_CHAT = "dev.locus.action.START_CHAT"

    /** Run the saved workflow [EXTRA_WORKFLOW] in a new chat, with its inputs from [EXTRA_PARAMS] or `dev.locus.param.<name>` extras. */
    const val ACTION_RUN_WORKFLOW = "dev.locus.action.RUN_WORKFLOW"

    /** Run the scheduled task [EXTRA_TASK] now, read-only, as its schedule would. */
    const val ACTION_RUN_TASK = "dev.locus.action.RUN_TASK"

    /**
     * An event for tasks whose trigger is "When another app sends…" (docs/design/event-triggers.md):
     * [EXTRA_EVENT] names it, [EXTRA_TEXT] optionally says what happened. Every listening task whose
     * filter matches runs, read-only, with the text as untrusted input.
     */
    const val ACTION_EVENT = "dev.locus.action.EVENT"

    /** The event's name, e.g. "backup-failed". */
    const val EXTRA_EVENT = "dev.locus.extra.EVENT"

    /** What happened, as text the run reads (untrusted). */
    const val EXTRA_TEXT = "dev.locus.extra.TEXT"

    const val EXTRA_PROMPT = "dev.locus.extra.PROMPT"

    /** A project's id or name (case-insensitive). */
    const val EXTRA_PROJECT = "dev.locus.extra.PROJECT"

    /** Boolean: also open the new chat on screen. Default false. */
    const val EXTRA_SHOW_CHAT = "dev.locus.extra.SHOW_CHAT"

    /** A workflow's id or name (case-insensitive). */
    const val EXTRA_WORKFLOW = "dev.locus.extra.WORKFLOW"

    /** A JSON object of workflow inputs, `{"city": "Lisbon"}`. */
    const val EXTRA_PARAMS = "dev.locus.extra.PARAMS"

    /** One workflow input per extra, for apps that can't build JSON: `dev.locus.param.city` = "Lisbon". */
    const val PARAM_PREFIX = "dev.locus.param."

    /** A scheduled task's id or title (case-insensitive). */
    const val EXTRA_TASK = "dev.locus.extra.TASK"

    /**
     * A PendingIntent Locus sends when the task ends, filled in with the result extras below. It must be
     * mutable for the extras to arrive (an immutable one is still sent, as a bare "it ended" signal), and
     * Locus only sends it when Android says it was made by the app that asked.
     */
    const val EXTRA_RESULT_INTENT = "dev.locus.extra.RESULT_INTENT"

    /** Any string; echoed back with the result so the caller can match it. */
    const val EXTRA_REQUEST_ID = "dev.locus.extra.REQUEST_ID"

    /** The result broadcast's action, sent only to the calling app's package when it has no [EXTRA_RESULT_INTENT]. */
    const val ACTION_RESULT = "dev.locus.action.RESULT"

    /** One of [Status]'s wire names. */
    const val EXTRA_STATUS = "dev.locus.extra.STATUS"

    /** The task's final answer (at most [MAX_RESULT_CHARS] characters). */
    const val EXTRA_RESULT = "dev.locus.extra.RESULT"

    /** Why it failed or was refused. */
    const val EXTRA_ERROR = "dev.locus.extra.ERROR"

    /** The chat the task ran in. */
    const val EXTRA_CHAT_ID = "dev.locus.extra.CHAT_ID"

    /** Results are cut to this, well under Binder's transaction limit. */
    const val MAX_RESULT_CHARS = 32_000

    /** How a request ended, as sent in [EXTRA_STATUS]. */
    enum class Status(val wire: String) {
        DONE("done"),
        FAILED("failed"),

        /** The user, Locus's settings or the request itself said no; nothing ran. */
        REFUSED("refused"),
    }
}
