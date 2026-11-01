package dev.locus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.snapshotFlow
import dev.locus.core.data.agent.AgentService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharedFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.designsystem.LocusTheme
import dev.locus.feature.activity.ActivityRoute as ActivityScreen
import dev.locus.feature.browser.BrowserRoute as BrowserScreen
import dev.locus.feature.library.LibraryRoute as LibraryScreen
import dev.locus.feature.tasks.TasksRoute as TasksScreen
import dev.locus.feature.chat.ChatRoute as ChatScreen
import dev.locus.feature.analytics.AnalyticsRoute as AnalyticsScreen
import dev.locus.feature.chat.ConversationsRoute as ConversationsScreen
import dev.locus.feature.settings.SettingsRoute as SettingsScreen
import dev.locus.feature.workspace.WorkspaceRoute as WorkspaceScreen
import dev.locus.feature.onboarding.OnboardingGateViewModel
import dev.locus.feature.onboarding.OnboardingRoute as OnboardingScreen
import dev.locus.feature.onboarding.PersonaRoute as PersonaScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            LocusTheme { LocusNavigation(shareIntents) }
        }
        // Only on a fresh start: after recreation the back stack already holds what this intent opened.
        if (savedInstanceState == null) intent?.let { shareIntents.tryEmit(it) }
    }

    /** Shares and notification taps that arrive while Locus is already open come through here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        shareIntents.tryEmit(intent)
    }

    private val shareIntents = MutableSharedFlow<Intent>(replay = 1, extraBufferCapacity = 1)
}

@Composable
private fun LocusNavigation(shareIntents: SharedFlow<Intent>) {
    // Wait for settings, so a first run opens on onboarding without flashing home first.
    val gate: OnboardingGateViewModel = hiltViewModel()
    val needsOnboarding = gate.needsOnboarding.collectAsStateWithLifecycle().value
    if (needsOnboarding == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val backStack = rememberNavBackStack(if (needsOnboarding) OnboardingRoute else ConversationsRoute)
    val share: ShareViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        shareIntents.collect { intent ->
            // A share or a notification tap during setup waits until setup is done: a chat needs a provider.
            snapshotFlow { OnboardingRoute in backStack }.first { !it }
            // A notification opens its chat, unless that chat is already on screen.
            intent.getStringExtra(AgentService.EXTRA_OPEN_CHAT)?.let { id ->
                if ((backStack.lastOrNull() as? ChatRoute)?.conversationId != id) backStack.add(ChatRoute(id))
                intent.removeExtra(AgentService.EXTRA_OPEN_CHAT)
            }
            share.handle(intent) { id, draft -> backStack.add(ChatRoute(id, draft)) }
            intent.action = null // handle each share once, even after recreation
        }
    }
    NotificationPermissionOnFirstChat(inChat = backStack.lastOrNull() is ChatRoute)
    RestoreNotice()
    val activity = androidx.activity.compose.LocalActivity.current
    // Never empty the back stack (NavDisplay requires an entry); leaving home closes the app.
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else activity?.finish() }

    // On tablets and in landscape, keep content at a readable width instead of stretching edge to edge.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        NavDisplay(
            modifier = Modifier.widthIn(max = 840.dp),
            backStack = backStack,
            onBack = pop,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<ConversationsRoute> {
                    ConversationsScreen(
                        onOpenConversation = { backStack.add(ChatRoute(it)) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                        onOpenWorkspace = { backStack.add(WorkspaceRoute) },
                        onOpenActivity = { backStack.add(ActivityRoute()) },
                        onOpenLibrary = { backStack.add(LibraryRoute()) },
                        onOpenTasks = { backStack.add(TasksRoute) },
                        onOpenAnalytics = { backStack.add(AnalyticsRoute) },
                        onOpenMessage = { id, messageId -> backStack.add(ChatRoute(id, focusMessageId = messageId)) },
                    )
                }
                entry<ChatRoute> { key ->
                    ChatScreen(
                        conversationId = key.conversationId,
                        draft = key.draft,
                        onBack = pop,
                        onOpenBrowser = { backStack.add(BrowserRoute) },
                        onOpenChat = { id, draft -> backStack.add(ChatRoute(id, draft)) },
                        focusMessageId = key.focusMessageId,
                    )
                }
                entry<BrowserRoute> { BrowserScreen(onBack = pop) }
                entry<LibraryRoute> { key -> LibraryScreen(onBack = pop, initialTab = key.tab, initialTemplate = key.template) }
                entry<TasksRoute> { TasksScreen(onBack = pop) }
                entry<AnalyticsRoute> { AnalyticsScreen(onBack = pop, onOpenConversation = { backStack.add(ChatRoute(it)) }) }
                entry<SettingsRoute> {
                    SettingsScreen(
                        onBack = pop,
                        onOpenConnectors = { backStack.add(LibraryRoute(tab = 2)) },
                        onUseMcpTemplate = { backStack.add(LibraryRoute(tab = 2, template = it)) },
                        onOpenAgentSetup = { backStack.add(PersonaRoute) },
                        onRunSetup = { backStack.add(OnboardingRoute) },
                    )
                }
                entry<WorkspaceRoute> { WorkspaceScreen(onBack = pop) }
                entry<ActivityRoute> { key -> ActivityScreen(onBack = pop, onOpenConversation = { backStack.add(ChatRoute(it)) }, conversationId = key.conversationId) }
                entry<OnboardingRoute> {
                    OnboardingScreen(
                        onFinish = { chatId ->
                            if (backStack.size > 1) {
                                // Re-run from Settings: back to where it started, plus the chat if one was started.
                                backStack.removeAt(backStack.lastIndex)
                            } else {
                                // First run: home replaces setup.
                                backStack.add(ConversationsRoute)
                                backStack.removeAt(0)
                            }
                            chatId?.let { backStack.add(ChatRoute(it)) }
                        },
                        onExit = pop,
                    )
                }
                entry<PersonaRoute> { PersonaScreen(onBack = pop) }
            },
        )
    }
}

/** A restore that didn't finish at startup, said once: the next time the app opens after it. */
@Composable
private fun RestoreNotice() {
    val notice: RestoreNoticeViewModel = hiltViewModel()
    val message = notice.message.collectAsStateWithLifecycle().value ?: return
    AlertDialog(
        onDismissRequest = notice::dismiss,
        title = { Text("Restore didn't finish") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = notice::dismiss) { Text("OK") } },
    )
}

/**
 * Approval requests arrive as notifications while the app is in the background, so ask for the
 * permission the first time the user opens a chat, when the reason is obvious.
 */
@Composable
private fun NotificationPermissionOnFirstChat(inChat: Boolean) {
    if (Build.VERSION.SDK_INT < 33) return
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(inChat) {
        if (inChat && !asked) {
            asked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
