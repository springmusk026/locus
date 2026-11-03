package dev.locus.feature.chat.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.designsystem.Emphasis
import dev.locus.core.designsystem.IconSize
import dev.locus.core.designsystem.IconTile
import dev.locus.core.designsystem.Locus
import dev.locus.core.designsystem.LocusCard
import dev.locus.core.designsystem.LocusChip
import dev.locus.core.designsystem.LocusIcons
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.Spacing
import dev.locus.core.designsystem.SupportingText
import dev.locus.core.designsystem.Tone
import dev.locus.core.designsystem.VoiceOrb
import dev.locus.core.designsystem.VoiceSizes
import dev.locus.core.model.VoiceSettings
import dev.locus.feature.chat.ChatItem
import dev.locus.feature.chat.ChatUiState
import kotlinx.coroutines.delay

/** What voice mode needs from the chat's state. Pure, so it's tested. */
internal fun voiceView(state: ChatUiState): VoiceAgentView {
    val reply = state.items.lastOrNull { it is ChatItem.Assistant } as ChatItem.Assistant?
    val question = state.pendingQuestion
    return VoiceAgentView(
        running = state.run.running,
        approval = state.pendingApproval,
        question = question?.let { q ->
            buildString {
                q.origin?.let { append(it.label).append(" asks: ") }
                append(q.question.trim())
                if (q.options.isNotEmpty()) {
                    if (last() !in ".?!") append('.')
                    append(" You can say ")
                    append(if (q.options.size == 1) q.options.single() else q.options.dropLast(1).joinToString(", ") + ", or " + q.options.last())
                    append('.')
                }
            }
        },
        questionId = question?.callId,
        replyKey = reply?.key,
        replyText = reply?.text,
        activeTool = state.run.activeTool,
        error = state.run.error,
    )
}

/**
 * Full-screen voice mode over a chat (docs/design/voice-mode.md). It talks to this chat's agent through
 * [onSend] and [onAnswer], the same calls the composer and approval card use, so tools, approvals and
 * caching are the chat's own. [blocked] is set when the chat screen needs the user (a send warning, a
 * heat hold). The microphone permission is asked for here, the first time voice mode opens.
 */
@Composable
internal fun VoiceMode(
    state: ChatUiState,
    settings: VoiceSettings,
    onSend: (String) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    blocked: String?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) {
            asked = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    BackHandler(onBack = onExit)
    // Back from App info, where the user may have allowed it.
    val owner = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
        owner.addObserver(observer)
        onDispose { owner.removeObserver(observer) }
    }

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            // Swallow touches so the chat underneath can't be tapped through.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        if (!granted) {
            MicPermission(denied = denied, onAsk = { permission.launch(Manifest.permission.RECORD_AUDIO) }, onExit = onExit)
        } else {
            VoiceLoop(state, settings, onSend, onAnswer, blocked, onExit)
        }
    }
}

/** The loop itself, once the microphone is allowed. */
@Composable
private fun VoiceLoop(
    state: ChatUiState,
    settings: VoiceSettings,
    onSend: (String) -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    blocked: String?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val sendNow = androidx.compose.runtime.rememberUpdatedState(onSend)
    val answerNow = androidx.compose.runtime.rememberUpdatedState(onAnswer)
    val parts = remember {
        var holder: VoiceSession? = null
        val ears = SystemEars(context) { holder }
        val mouth = SystemMouth(context) { holder }
        val focus = VoiceFocus(context) { holder }
        val agent = object : VoiceAgent {
            override fun send(text: String) = sendNow.value(text)
            override fun answer(callId: String, approved: Boolean) = answerNow.value(callId, approved)
        }
        val session = VoiceSession(ears, mouth, agent, System::currentTimeMillis, settings)
        holder = session
        VoiceParts(session, ears, mouth, focus)
    }
    val session = parts.session
    // Settings can change while it's open (they're read live).
    LaunchedEffect(settings) {
        session.settings = settings
        parts.mouth.rate = settings.rate
    }
    LaunchedEffect(voiceView(state)) { session.onAgent(voiceView(state)) }
    DisposableEffect(parts) {
        session.onAgent(voiceView(state))
        parts.mouth.rate = settings.rate
        session.start()
        parts.focus.request()
        onDispose {
            session.end()
            parts.ears.destroy()
            parts.mouth.shutdown()
            parts.focus.abandon()
        }
    }
    LaunchedEffect(session) {
        while (true) {
            delay(1_000)
            session.tick()
        }
    }
    LaunchedEffect(blocked) { blocked?.let(session::needsScreen) }
    // No microphone in the background: leaving the screen pauses it.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.backgrounded() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val phase by session.phase.collectAsStateWithLifecycle()
    val level by session.level.collectAsStateWithLifecycle()
    val engine by parts.ears.engine.collectAsStateWithLifecycle()
    LaunchedEffect(phase) { if (phase == VoicePhase.Ended) onExit() }

    VoiceScreen(
        phase = phase,
        level = level,
        engine = engine,
        approval = state.pendingApproval,
        onTap = session::tap,
        onAnswer = onAnswer,
        onExit = { session.end() },
    )
}

private class VoiceParts(val session: VoiceSession, val ears: SystemEars, val mouth: SystemMouth, val focus: VoiceFocus)

/** Voice mode's one screen: what it's doing, the big circle to tap, an approval to answer, and Exit. */
@Composable
internal fun VoiceScreen(
    phase: VoicePhase,
    level: Float,
    engine: VoiceEngine,
    approval: ApprovalRequest?,
    onTap: () -> Unit,
    onAnswer: (String, Boolean) -> Unit,
    onExit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Voice mode", Modifier.semantics { heading() }, style = LocusType.stateTitle)
                SupportingText("${engine.label} · ${engine.note} No audio is kept.", maxLines = 2)
            }
            IconButton(onClick = onExit) { Icon(LocusIcons.Close, contentDescription = "Close voice mode") }
        }
        // The microphone indicator: only while Locus is actually listening.
        val listening = phase is VoicePhase.Listening
        Box(Modifier.height(VoiceSizes.indicatorRow).padding(top = Spacing.sm), contentAlignment = Alignment.Center) {
            if (listening) LocusChip("Microphone on", tone = Tone.Critical, icon = LocusIcons.Mic)
        }
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Orb(phase, level, onTap)
            Spacer(Modifier.height(Spacing.xl))
            Text(
                phaseTitle(phase),
                style = LocusType.screenTitle,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            val detail = phaseDetail(phase)
            if (detail.isNotBlank()) {
                SupportingText(
                    detail,
                    Modifier.padding(top = Spacing.sm).heightIn(max = VoiceSizes.detail).verticalScroll(rememberScrollState()),
                    style = LocusType.body,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (approval != null) VoiceApprovalCard(approval, phase, onAnswer)
        Button(
            onClick = onExit,
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.lg).height(Sizes.row),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Icon(LocusIcons.Stop, contentDescription = null, modifier = Modifier.size(IconSize.medium))
            Spacer(Modifier.width(Spacing.sm))
            Text("End voice mode", style = LocusType.cardTitle)
        }
    }
}

/** The big circle: grows with the voice while listening, breathes while working or talking. Tap: talk, interrupt or finish. */
@Composable
private fun Orb(phase: VoicePhase, level: Float, onTap: () -> Unit) {
    val listening = phase is VoicePhase.Listening
    val breathing = phase is VoicePhase.Working || phase is VoicePhase.Speaking
    val pulse = rememberInfiniteTransition(label = "orb").animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val voice by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, tween(90), label = "level")
    val scale = when {
        listening -> voice
        breathing -> pulse.value
        else -> 1f
    }
    val color = when (phase) {
        is VoicePhase.Listening -> MaterialTheme.colorScheme.primary
        is VoicePhase.Speaking -> Locus.colors.live
        is VoicePhase.Failed -> Locus.colors.critical
        is VoicePhase.TapApproval -> Locus.colors.caution
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    VoiceOrb(
        color = color,
        icon = when (phase) {
            is VoicePhase.Speaking -> LocusIcons.Speaker
            is VoicePhase.Working -> LocusIcons.Sparkle
            else -> LocusIcons.Mic
        },
        contentDescription = tapLabel(phase),
        onTap = onTap,
        scale = scale,
    )
}

/** The approval waiting: allow or deny by tap always; by voice only when [VoiceApprovals] allows it, and why not otherwise. */
@Composable
private fun VoiceApprovalCard(request: ApprovalRequest, phase: VoicePhase, onAnswer: (String, Boolean) -> Unit) {
    val reason = VoiceApprovals.tapReason(request)
    val caution = Locus.colors.caution
    LocusCard(Modifier.fillMaxWidth(), outline = caution.copy(alpha = Emphasis.toneOutline)) {
        Text(request.origin?.let { "${it.label} wants to…" } ?: "Allow this?", style = LocusType.cardTitle)
        SupportingText(request.summary, style = LocusType.rowSupporting, maxLines = 3)
        Text(
            when {
                reason != null -> "Voice can't answer this one. $reason"
                (phase as? VoicePhase.TapApproval) != null -> "Voice didn't get a clear answer. Tap to choose."
                else -> "Say “allow”, then “confirm”, or say “deny”. Payments, anything after reading outside content, and anything the safety reviewer flags always need a tap."
            },
            style = LocusType.supporting,
            color = if (reason != null) caution else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
        Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { onAnswer(request.callId, false) }) { Text("Deny") }
            Button(onClick = { onAnswer(request.callId, true) }, modifier = Modifier.padding(start = Spacing.sm)) { Text("Allow") }
        }
    }
}

/** Before the microphone is allowed: why it's needed, and how to allow it. */
@Composable
private fun MicPermission(denied: Boolean, onAsk: () -> Unit, onExit: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconTile(LocusIcons.Mic, size = IconSize.hero, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.lg))
        Text("Voice mode needs the microphone", Modifier.semantics { heading() }, style = LocusType.stateTitle, textAlign = TextAlign.Center)
        SupportingText(
            "Locus listens only while this screen shows “Microphone on”, and only while Locus is open. Speech becomes text " +
                "(on the phone when it can); no audio is kept.",
            Modifier.padding(top = Spacing.sm),
            style = LocusType.rowSupporting,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = {
            if (denied) {
                // Asked and refused: Android won't ask again, so open Locus's app settings.
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))) }
            } else {
                onAsk()
            }
        }) { Text(if (denied) "Allow in settings" else "Allow microphone") }
        TextButton(onClick = onExit) { Text("Not now") }
    }
}

internal fun phaseTitle(phase: VoicePhase): String = when (phase) {
    is VoicePhase.Idle -> phase.hint
    is VoicePhase.Listening -> when (phase.purpose) {
        ListenFor.MESSAGE -> "Listening…"
        ListenFor.APPROVAL -> "Allow or deny?"
        ListenFor.CONFIRM -> "Say “confirm” to allow"
    }
    is VoicePhase.Working -> phase.status + "…"
    is VoicePhase.Speaking -> when (phase.kind) {
        SpeechKind.REPLY -> "Speaking"
        SpeechKind.STATUS, SpeechKind.NOTICE -> "Locus"
        SpeechKind.PROMPT -> "Locus asks"
    }
    is VoicePhase.TapApproval -> "Tap to answer"
    is VoicePhase.Paused -> "Paused"
    is VoicePhase.Failed -> "Voice mode stopped"
    VoicePhase.Ended -> "Ended"
}

internal fun phaseDetail(phase: VoicePhase): String = when (phase) {
    is VoicePhase.Listening -> phase.partial
    is VoicePhase.Speaking -> phase.text
    is VoicePhase.Working -> "Tap to tell Locus something while it works."
    is VoicePhase.TapApproval -> phase.reason
    is VoicePhase.Paused -> phase.reason
    is VoicePhase.Failed -> phase.message
    is VoicePhase.Idle, VoicePhase.Ended -> ""
}

private fun tapLabel(phase: VoicePhase): String = when (phase) {
    is VoicePhase.Speaking -> "Stop talking and listen"
    is VoicePhase.Listening -> "Done talking"
    is VoicePhase.Paused, is VoicePhase.Failed -> "Go on"
    else -> "Talk"
}
