package dev.locus

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.automation.AccessDecision
import dev.locus.core.data.automation.AutomationAccess
import dev.locus.core.data.automation.AutomationBridge
import dev.locus.core.data.automation.AutomationContract
import dev.locus.core.data.automation.AutomationRequest
import dev.locus.core.data.automation.AutomationResult
import dev.locus.core.data.automation.AutomationStart
import dev.locus.core.data.automation.Caller
import dev.locus.core.data.automation.Parsed
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.LocusTheme
import dev.locus.core.designsystem.SectionNote
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.color
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Where other apps (Tasker, Automate, a script) ask Locus to start a chat, run a saved workflow or run
 * a scheduled task now (docs/design/android-bridge.md). It is exported without a manifest permission,
 * so apps that can't declare Locus's permission can still ask, but nothing runs without the user:
 * automation must be on in Settings, and each request is confirmed in a sheet unless Android names the
 * app and the user allowed it (once in Locus, or by granting it the RUN_TASK permission). The request
 * is parsed and checked before anything is shown; what runs is tainted as input from that app.
 *
 * An activity, not a receiver or service: a task runs in Locus's foreground service, which Android only
 * lets Locus start while it is on screen, and the confirmation needs a screen anyway.
 */
@AndroidEntryPoint
class AutomationActivity : ComponentActivity() {
    @Inject lateinit var bridge: AutomationBridge

    @Inject lateinit var settings: SettingsRepository

    private var prompt by mutableStateOf<Prompt?>(null)

    /** What the sheet shows: the request, who asked, and whether automation is still off. */
    private data class Prompt(val request: AutomationRequest, val caller: Caller, val off: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Handled once: after recreation (rotation) the sheet's state is gone, so the request ends here.
        if (savedInstanceState != null) {
            finish()
            return
        }
        setContent { LocusTheme { prompt?.let { Sheet(it) } } }
        lifecycleScope.launch { receive(intent) }
    }

    private lateinit var reply: Reply

    private suspend fun receive(intent: Intent) {
        val result = runCatching { IntentCompat.getParcelableExtra(intent, AutomationContract.EXTRA_RESULT_INTENT, PendingIntent::class.java) }.getOrNull()
        val launchedFrom = if (Build.VERSION.SDK_INT >= 34) runCatching { launchedFromPackage }.getOrNull() else null
        val creator = result?.creatorPackage
        // The PendingIntent's creator only decides where the result goes, never who is asking.
        val pkg = AutomationAccess.identify(callingPackage, launchedFrom)
        val caller = Caller(pkg, pkg?.let(::labelOf))
        reply = Reply(
            pendingIntent = result.takeIf { AutomationAccess.resultTarget(pkg, creator) },
            // A broadcast goes only to a package Android named as the caller of this activity.
            broadcastTo = (callingPackage ?: launchedFrom).takeIf { result == null },
        )
        val extras = intent.extras
        val parsed = AutomationRequest.parse(intent.action, extras?.keySet().orEmpty()) { key ->
            @Suppress("DEPRECATION")
            runCatching { extras?.get(key) }.getOrNull()
        }
        val request = when (parsed) {
            is Parsed.Invalid -> return refuse(parsed.reason, null)
            is Parsed.Ok -> parsed.request
        }
        val current = settings.current()
        val permission = AutomationContract.permission(packageName)
        val decision = AutomationAccess.decide(
            enabled = current.automationEnabled,
            caller = caller,
            permissionIsOurs = permissionIsOurs(permission),
            holdsPermission = pkg != null && packageManager.checkPermission(permission, pkg) == PackageManager.PERMISSION_GRANTED,
            remembered = pkg?.let { current.automationCallers[it] },
            ownPackage = packageName,
        )
        when (decision) {
            AccessDecision.Run -> run(request, caller)
            AccessDecision.Confirm -> prompt = Prompt(request, caller, off = false)
            AccessDecision.Off -> prompt = Prompt(request, caller, off = true)
            is AccessDecision.Refuse -> refuse(decision.reason, request.requestId)
        }
    }

    private fun permissionIsOurs(permission: String): Boolean = try {
        packageManager.getPermissionInfo(permission, 0).packageName == packageName
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun labelOf(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

    private fun refuse(reason: String, requestId: String?) {
        reply.send(this, AutomationResult(AutomationContract.Status.REFUSED, error = reason, requestId = requestId))
        finish()
    }

    private suspend fun run(request: AutomationRequest, caller: Caller) {
        val app = applicationContext
        val sink = reply
        when (val started = bridge.start(request, caller) { result -> sink.send(app, result) }) {
            is AutomationStart.Failed -> refuse(started.reason, request.requestId)
            is AutomationStart.Started -> {
                val chat = started.chatId
                if (chat != null && (request as? AutomationRequest.StartChat)?.openChat == true) {
                    startActivity(OpenChatLink.attach(this, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), chat))
                }
                finish()
            }
        }
    }

    @Composable
    private fun Sheet(p: Prompt) {
        val who = p.caller.shown
        val pkg = p.caller.packageName
        val decline = { prompt = null; refuse("The user didn't allow it.", p.request.requestId) }
        if (p.off) {
            LocusSheet(
                title = "Let other apps start Locus tasks?",
                onDismiss = decline,
                action = "Turn on",
                onAction = {
                    lifecycleScope.launch {
                        settings.setAutomationEnabled(true)
                        prompt = p.copy(off = false)
                    }
                },
            ) {
                Text(
                    "$who asked Locus to ${p.request.summary}. Automation is off, so apps can't start Locus tasks. " +
                        "Turn it on to review this request; you can turn it off again in Settings → Automation.",
                    style = LocusType.rowSupporting,
                )
                TextButton(onClick = decline) { Text("Not now") }
            }
            return
        }
        LocusSheet(
            title = "Allow this request?",
            subtitle = "From $who",
            onDismiss = decline,
            action = "Run once",
            onAction = { prompt = null; lifecycleScope.launch { run(p.request, p.caller) } },
        ) {
            Text("$who asked Locus to ${p.request.summary}.", style = LocusType.rowSupporting)
            details(p.request)?.let { SupportingText(it) }
            SectionNote(
                "It runs in a new chat with your usual approvals. Locus treats what the app sent as untrusted, so anything that " +
                    "would send or change something outside the phone asks you first.",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = decline) { Text("Don't allow") }
                if (pkg != null) {
                    TextButton(onClick = {
                        prompt = null
                        lifecycleScope.launch {
                            settings.setAutomationCaller(pkg, false)
                            refuse("The user chose never to allow this app.", p.request.requestId)
                        }
                    }) { Text("Never", color = Tone.Critical.color()) }
                    TextButton(onClick = {
                        prompt = null
                        lifecycleScope.launch {
                            settings.setAutomationCaller(pkg, true)
                            run(p.request, p.caller)
                        }
                    }) { Text("Always allow") }
                }
            }
            if (pkg == null) {
                SupportingText(
                    "Android didn't say which app this is, so Locus asks every time. Apps that start Locus for a result, " +
                        "or share their identity when they start it (Android 14+), can be allowed always.",
                )
            }
        }
    }

    private fun details(request: AutomationRequest): String? = when (request) {
        is AutomationRequest.StartChat -> "“" + request.prompt.take(600) + (if (request.prompt.length > 600) "…" else "") + "”"
        is AutomationRequest.RunWorkflow -> request.params.entries.joinToString("\n") { (k, v) -> "$k: ${v.take(200)}" }.ifEmpty { null }
        is AutomationRequest.RunTask -> null
    }

    /** Where the result goes: the caller's own PendingIntent, else a broadcast to the package Android named, else nowhere. */
    private class Reply(private val pendingIntent: PendingIntent?, private val broadcastTo: String?) {
        fun send(context: android.content.Context, result: AutomationResult) {
            val fill = Intent(AutomationContract.ACTION_RESULT)
                .putExtra(AutomationContract.EXTRA_STATUS, result.status.wire)
                .apply {
                    result.text?.let { putExtra(AutomationContract.EXTRA_RESULT, it.take(AutomationContract.MAX_RESULT_CHARS)) }
                    result.error?.let { putExtra(AutomationContract.EXTRA_ERROR, it.take(2_000)) }
                    result.chatId?.let { putExtra(AutomationContract.EXTRA_CHAT_ID, it) }
                    result.requestId?.let { putExtra(AutomationContract.EXTRA_REQUEST_ID, it) }
                }
            try {
                when {
                    pendingIntent != null -> {
                        // Never lends Locus's right to start activities from the background to the caller's intent.
                        val options = if (Build.VERSION.SDK_INT >= 34) {
                            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED).toBundle()
                        } else {
                            null
                        }
                        pendingIntent.send(context, 0, fill, null, null, null, options)
                    }
                    broadcastTo != null -> context.sendBroadcast(fill.setPackage(broadcastTo))
                }
            } catch (_: Exception) {
                // The caller went away or cancelled its PendingIntent: nothing more to do.
            }
        }
    }
}
