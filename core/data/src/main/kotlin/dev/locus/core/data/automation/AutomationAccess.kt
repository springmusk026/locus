package dev.locus.core.data.automation

/**
 * Who is asking, as far as Android can tell. [packageName] is only ever one Android supplied (the
 * caller of startActivityForResult, a launch with shared identity, or the creator of the result
 * PendingIntent), never one the caller wrote into the intent. Null: Android didn't say.
 */
data class Caller(val packageName: String?, val label: String?) {
    val verified: Boolean get() = packageName != null

    /** How the caller is named to the user and the model. */
    val shown: String get() = label?.takeIf { it.isNotBlank() }?.let { l -> packageName?.let { "$l ($it)" } ?: l } ?: packageName ?: "an app Android didn't name"
}

/** What Locus does with a request (docs/design/android-bridge.md, "Who may ask"). */
sealed interface AccessDecision {
    /** Runs without asking: a named app the user allowed. */
    data object Run : AccessDecision

    /** The user confirms this request in Locus first (with "always allow" when the app is named). */
    data object Confirm : AccessDecision

    /** Automation is off in Settings; the sheet offers to turn it on, then confirms the request. */
    data object Off : AccessDecision

    /** Refused without asking anyone. */
    data class Refuse(val reason: String) : AccessDecision
}

object AutomationAccess {
    /**
     * Which package Android vouches for: the activity's caller first (startActivityForResult, or a launch
     * with shared identity), else the creator of the result PendingIntent. A result PendingIntent made by
     * another app than the vouched-for caller is never used ([resultTarget]).
     */
    fun identify(callingPackage: String?, launchedFromPackage: String?, resultCreatorPackage: String?): String? =
        callingPackage ?: launchedFromPackage ?: resultCreatorPackage

    /** Whether the result PendingIntent made by [creator] may be sent to: only when it is the caller's own. */
    fun resultTarget(caller: String?, creator: String?): Boolean = creator != null && creator == caller

    /**
     * The decision for one request.
     * - [permissionIsOurs]: Locus defined [AutomationContract.PERMISSION] (another app that defined it
     *   first could have made it a normal permission anyone gets, so then nothing runs).
     * - [holdsPermission]: the named caller was granted the permission (the user did that in Android's settings).
     * - [remembered]: the user's saved answer for the caller ([dev.locus.core.model.AppSettings.automationCallers]).
     */
    fun decide(
        enabled: Boolean,
        caller: Caller,
        permissionIsOurs: Boolean,
        holdsPermission: Boolean,
        remembered: Boolean?,
        ownPackage: String,
    ): AccessDecision = when {
        !permissionIsOurs -> AccessDecision.Refuse("Another app has defined Locus's automation permission, so Locus won't take requests until that app is removed.")
        caller.packageName == ownPackage -> AccessDecision.Refuse("Locus doesn't take automation requests from itself.")
        remembered == false -> AccessDecision.Refuse("You chose never to let ${caller.shown} start Locus tasks (Settings → Automation).")
        !enabled -> AccessDecision.Off
        caller.verified && (remembered == true || holdsPermission) -> AccessDecision.Run
        else -> AccessDecision.Confirm
    }
}
