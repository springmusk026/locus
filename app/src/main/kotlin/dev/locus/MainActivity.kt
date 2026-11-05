package dev.locus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.locus.core.designsystem.Tone
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.Manifest
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.runtime.snapshotFlow
import dev.locus.core.data.agent.OpenChatLink
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.locus.core.designsystem.LocusSheet
import dev.locus.core.designsystem.LocusType
import dev.locus.core.designsystem.Sizes
import dev.locus.core.designsystem.ProvideSettingsLinks
import dev.locus.core.designsystem.SettingsLinks
import dev.locus.core.designsystem.SettingsLinkIn
import androidx.compose.runtime.remember
import dev.locus.core.designsystem.LocusTheme
import dev.locus.feature.activity.ActivityRoute as ActivityScreen
import dev.locus.feature.browser.BrowserRoute as BrowserScreen
import dev.locus.feature.library.LibraryRoute as LibraryScreen
import dev.locus.feature.tasks.TasksRoute as TasksScreen
import dev.locus.feature.chat.ChatRoute as ChatScreen
import dev.locus.feature.analytics.AnalyticsRoute as AnalyticsScreen
import dev.locus.feature.chat.ConversationsRoute as ConversationsScreen
import dev.locus.feature.chat.ProjectEditorRoute as ProjectEditorScreen
import dev.locus.feature.chat.ProjectRoute as ProjectScreen
import dev.locus.feature.chat.ProjectsRoute as ProjectsScreen
import dev.locus.feature.settings.SettingsRoute as SettingsScreen
import dev.locus.feature.settings.ondevice.OnDeviceModelScreen
import dev.locus.feature.settings.ondevice.OnDeviceModelsScreen
import dev.locus.feature.settings.ondevice.OnDeviceSearchScreen
import dev.locus.feature.workspace.WorkspaceRoute as WorkspaceScreen
import dev.locus.feature.workspace.terminal.TerminalRoute as TerminalScreen
import dev.locus.feature.workspace.linux.health.LinuxHealthRoute as LinuxHealthScreen
import dev.locus.feature.workspace.linux.health.LinuxPackagesRoute as LinuxPackagesScreen
import dev.locus.core.terminal.TerminalSessions
import javax.inject.Inject
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

    @Inject lateinit var terminals: TerminalSessions

    /** Closing the app (not a rotation or a trip to another app) stops the terminal sessions (docs/design/terminal.md). */
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) terminals.closeAll()
        super.onDestroy()
    }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    // The tile asks home's ask bar to take focus (docs/design/android-bridge.md).
    var askFocus by rememberSaveable { mutableIntStateOf(0) }
    val home: () -> Unit = {
        while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        if (backStack.firstOrNull() != ConversationsRoute) {
            backStack.add(0, ConversationsRoute)
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }
    }
    LaunchedEffect(Unit) {
        shareIntents.collect { intent ->
            // A share or a notification tap during setup waits until setup is done: a chat needs a provider.
            snapshotFlow { OnboardingRoute in backStack }.first { !it }
            // A notification opens its chat, unless that chat is already on screen. Only Locus's own
            // notifications carry the token; another app's intent asking for a chat is ignored.
            OpenChatLink.chatId(context, intent)?.let { id ->
                if ((backStack.lastOrNull() as? ChatRoute)?.conversationId != id) backStack.add(ChatRoute(id))
            }
            OpenChatLink.clear(intent)
            share.handle(intent) { id, draft -> backStack.add(ChatRoute(id, draft)) }
            LaunchActions.resolve(intent.action)?.let { action ->
                share.reportShortcutUsed(action)
                when (action) {
                    LaunchActions.NEW_CHAT -> share.newChat { id -> backStack.add(ChatRoute(id)) }
                    // Voice mode in a new chat (docs/design/voice-mode.md).
                    LaunchActions.VOICE -> share.newChat { id -> backStack.add(ChatRoute(id, voice = true)) }
                    LaunchActions.ASK -> { home(); askFocus++ }
                    LaunchActions.WORKSPACE -> if (backStack.lastOrNull() != WorkspaceRoute()) backStack.add(WorkspaceRoute())
                    LaunchActions.UPDATES -> SettingsRoute(dev.locus.core.model.SettingsDestination.About).let { if (backStack.lastOrNull() != it) backStack.add(it) }
                }
            }
            intent.action = null // handle each share once, even after recreation
        }
    }
    // "Settings → Models" links anywhere open that page on top of the screen they're on (docs/design/design-system.md).
    val settingsLinks = remember(backStack) {
        SettingsLinks { page ->
            // On-device models is a screen of its own, not a page inside Settings.
            val route = if (page == dev.locus.core.model.SettingsDestination.OnDeviceModels) OnDeviceModelsRoute else SettingsRoute(page)
            if (backStack.lastOrNull() != route) backStack.add(route)
        }
    }
    ProvideSettingsLinks(settingsLinks) {
    NotificationPermissionOnFirstChat(inChat = backStack.lastOrNull() is ChatRoute)
    PhoneRequestHost()
    RestoreNotice()
    IntegrityNotice()
    StorageNotice()
    SavedKeysReset()
    ShareFailures(share)
    val activity = androidx.activity.compose.LocalActivity.current
    // Never empty the back stack (NavDisplay requires an entry); leaving home closes the app.
    val pop: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) else activity?.finish() }

    // On tablets and in landscape, keep content at a readable width instead of stretching edge to edge.
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        NavDisplay(
            modifier = Modifier.widthIn(max = Sizes.contentMax),
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
                        onOpenSettings = { backStack.add(SettingsRoute()) },
                        onOpenWorkspace = { backStack.add(WorkspaceRoute()) },
                        onOpenWorkspaceFile = { path -> backStack.add(WorkspaceRoute(openPath = path)) },
                        onOpenActivity = { backStack.add(ActivityRoute()) },
                        onOpenLibrary = { backStack.add(LibraryRoute()) },
                        onOpenTasks = { backStack.add(TasksRoute) },
                        onOpenAnalytics = { backStack.add(AnalyticsRoute) },
                        onOpenMessage = { id, messageId -> backStack.add(ChatRoute(id, focusMessageId = messageId)) },
                        onOpenProjects = { backStack.add(ProjectsRoute) },
                        onOpenProject = { backStack.add(ProjectRoute(it)) },
                        onNewProject = { backStack.add(ProjectEditRoute()) },
                        askFocusKey = askFocus,
                    )
                }
                entry<ProjectsRoute> {
                    ProjectsScreen(
                        onBack = pop,
                        onOpenProject = { backStack.add(ProjectRoute(it)) },
                        onNewProject = { backStack.add(ProjectEditRoute()) },
                    )
                }
                entry<ProjectRoute> { key ->
                    ProjectScreen(
                        projectId = key.projectId,
                        onBack = pop,
                        onOpenChat = { backStack.add(ChatRoute(it)) },
                        onEdit = { backStack.add(ProjectEditRoute(key.projectId)) },
                    )
                }
                entry<ProjectEditRoute> { key ->
                    ProjectEditorScreen(
                        projectId = key.projectId,
                        onBack = pop,
                        onSaved = { id ->
                            pop()
                            // A new project opens on its own page.
                            if (key.projectId == null) backStack.add(ProjectRoute(id))
                        },
                        onDeleted = {
                            // Back past the deleted project's page too.
                            backStack.removeAll { it is ProjectEditRoute || (it as? ProjectRoute)?.projectId == key.projectId }
                        },
                    )
                }
                entry<ChatRoute> { key ->
                    ChatScreen(
                        conversationId = key.conversationId,
                        draft = key.draft,
                        onBack = pop,
                        onOpenBrowser = { backStack.add(BrowserRoute) },
                        onOpenTerminal = { backStack.add(TerminalRoute) },
                        onOpenChat = { id, draft -> backStack.add(ChatRoute(id, draft)) },
                        onOpenProject = { backStack.add(ProjectRoute(it)) },
                        focusMessageId = key.focusMessageId,
                        startVoice = key.voice,
                        onOpenActivity = { f ->
                            backStack.add(ActivityRoute(f.conversationId, f.since, f.until, f.decisions.map { it.name }.sorted(), f.label))
                        },
                    )
                }
                entry<BrowserRoute> { BrowserScreen(onBack = pop) }
                entry<LibraryRoute> { key -> LibraryScreen(onBack = pop, initialTab = key.tab, initialTemplate = key.template, onOpenChat = { backStack.add(ChatRoute(it)) }) }
                entry<TasksRoute> { TasksScreen(onBack = pop, onOpenChat = { backStack.add(ChatRoute(it)) }) }
                entry<AnalyticsRoute> { AnalyticsScreen(onBack = pop, onOpenConversation = { backStack.add(ChatRoute(it)) }) }
                entry<SettingsRoute> { key ->
                    SettingsScreen(
                        initialPage = key.page,
                        onBack = pop,
                        onOpenConnectors = { backStack.add(LibraryRoute(tab = 2)) },
                        onUseMcpTemplate = { backStack.add(LibraryRoute(tab = 2, template = it)) },
                        onOpenAgentSetup = { backStack.add(PersonaRoute) },
                        onRunSetup = { backStack.add(OnboardingRoute) },
                        onOpenLinux = { backStack.add(LinuxHealthRoute) },
                        onOpenOnDeviceModels = { backStack.add(OnDeviceModelsRoute) },
                        onOpenActivity = { backStack.add(ActivityRoute()) },
                    )
                }
                entry<OnDeviceModelsRoute> {
                    OnDeviceModelsScreen(
                        dev.locus.feature.settings.ondevice.OnDeviceNavigation(
                            back = pop,
                            search = { backStack.add(OnDeviceSearchRoute()) },
                            model = { backStack.add(OnDeviceModelRoute(it.encode())) },
                        ),
                    )
                }
                entry<OnDeviceSearchRoute> { key ->
                    OnDeviceSearchScreen(onBack = pop, onOpen = { backStack.add(OnDeviceModelRoute(it.encode())) }, query = key.query)
                }
                entry<OnDeviceModelRoute> { key ->
                    val model = dev.locus.feature.settings.ondevice.ModelKey.parse(key.key)
                    if (model == null) {
                        LaunchedEffect(key) { pop() }
                    } else {
                        OnDeviceModelScreen(model, onBack = pop, onOpenChat = { backStack.add(ChatRoute(it)) }, onSearch = { backStack.add(OnDeviceSearchRoute(it)) })
                    }
                }
                entry<WorkspaceRoute> { key ->
                    WorkspaceScreen(onBack = pop, onOpenTerminal = { backStack.add(TerminalRoute) }, onOpenHealth = { backStack.add(LinuxHealthRoute) }, openPath = key.openPath)
                }
                // Linux is installed from the Workspace's card, which the health and packages screens open from.
                val backToLinuxCard: () -> Unit = {
                    // Any Workspace entry: one opened on a file shows its list once that file is closed or gone.
                    while (backStack.size > 1 && backStack.lastOrNull() !is WorkspaceRoute) backStack.removeLastOrNull()
                    if (backStack.lastOrNull() !is WorkspaceRoute) backStack.add(WorkspaceRoute())
                }
                entry<LinuxHealthRoute> {
                    LinuxHealthScreen(
                        onBack = pop,
                        onOpenTerminal = { backStack.add(TerminalRoute) },
                        onSetUpLinux = backToLinuxCard,
                        onOpenPackages = { backStack.add(LinuxPackagesRoute) },
                    )
                }
                entry<LinuxPackagesRoute> {
                    LinuxPackagesScreen(onBack = pop, onOpenTerminal = { backStack.add(TerminalRoute) }, onSetUpLinux = backToLinuxCard)
                }
                entry<TerminalRoute> {
                    TerminalScreen(
                        onBack = pop,
                        // A new chat with the selection in its composer; the user decides whether to send it.
                        onAsk = { draft -> share.newChat { id -> backStack.add(ChatRoute(id, draft)) } },
                        // The Linux card at the top of the Workspace: back to it when the terminal was opened from there.
                        onInstallLinux = {
                            if (backStack.getOrNull(backStack.lastIndex - 1) is WorkspaceRoute) pop()
                            else { backStack.removeLastOrNull(); backStack.add(WorkspaceRoute()) }
                        },
                    )
                }
                entry<ActivityRoute> { key ->
                    val task = key.taskSince?.let { since ->
                        key.conversationId?.let { chat ->
                            dev.locus.core.model.TaskActivityFilter(
                                chat, since, key.taskUntil ?: Long.MAX_VALUE,
                                key.taskDecisions.mapNotNull { n -> dev.locus.core.model.ApprovalDecision.entries.firstOrNull { it.name == n } }.toSet(),
                                key.taskLabel ?: "This task",
                            )
                        }
                    }
                    ActivityScreen(onBack = pop, onOpenConversation = { backStack.add(ChatRoute(it)) }, conversationId = key.conversationId, task = task)
                }
                entry<OnboardingRoute> {
                    OnboardingScreen(
                        onOpenWorkspace = { backStack.add(WorkspaceRoute()) },
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
    } // ProvideSettingsLinks
}

/** A restore that didn't finish at startup, or restored tasks that couldn't be scheduled, said once. */
@Composable
private fun RestoreNotice() {
    val notice: RestoreNoticeViewModel = hiltViewModel()
    val message = notice.message.collectAsStateWithLifecycle().value ?: return
    val title = if (notice.restoreUnfinished) "Restore didn't finish" else "Scheduled tasks are off"
    LocusSheet(title = title, onDismiss = notice::dismiss, action = "OK", onAction = notice::dismiss) {
        Text(message, style = LocusType.rowSupporting)
        SettingsLinkIn(message, beforeOpen = notice::dismiss)
    }
}

/** Permissions or connectors changed outside Locus, and what Locus did about it. */
@Composable
private fun IntegrityNotice() {
    val notice: IntegrityNoticeViewModel = hiltViewModel()
    val message = notice.message.collectAsStateWithLifecycle().value ?: return
    LocusSheet(title = "Settings were changed outside Locus", onDismiss = notice::dismiss, action = "OK", onAction = notice::dismiss) {
        Text(message, style = LocusType.rowSupporting)
        SettingsLinkIn(message, beforeOpen = notice::dismiss)
    }
}

/** Saved keys or settings that had to be reset because they couldn't be read; shown once. */
@Composable
private fun StorageNotice() {
    val notice: StorageNoticeViewModel = hiltViewModel()
    val notices = notice.notices.collectAsStateWithLifecycle().value
    if (notices.isEmpty()) return
    val title = when {
        notices.any { "couldn't unlock your saved keys" in it } -> "Enter your saved keys again"
        notices.any { "saved keys" in it } -> "Saved keys restored"
        else -> "Settings were reset"
    }
    LocusSheet(title = title, onDismiss = notice::dismiss, action = "OK", onAction = notice::dismiss) {
        Text(notices.joinToString("\n\n"), style = LocusType.rowSupporting)
        SettingsLinkIn(notices.joinToString("\n\n"), beforeOpen = notice::dismiss)
    }
}

/**
 * Saved keys whose Keystore key looks unusable: Locus asks before resetting, and keeps a copy.
 * "Not now" (or swiping the sheet away) leaves the vault closed and unchanged; features that need a
 * key say why instead.
 */
@Composable
private fun SavedKeysReset() {
    val vm: SavedKeysResetViewModel = hiltViewModel()
    if (!vm.ask.collectAsStateWithLifecycle().value) return
    val busy = vm.busy.collectAsStateWithLifecycle().value
    val error = vm.error.collectAsStateWithLifecycle().value
    LocusSheet(
        title = "Can't open saved keys",
        onDismiss = vm::notNow,
        action = "Reset",
        actionEnabled = !busy,
        actionTone = Tone.Critical,
        onAction = vm::reset,
    ) {
        Text(
            "Locus can't open your saved keys. This can happen after changing the lock screen or restoring the phone. " +
                "Reset and re-enter keys? (A copy is kept in case it can be recovered.)",
            style = LocusType.rowSupporting,
        )
        if (error != null) {
            Text(error, style = LocusType.rowSupporting, color = MaterialTheme.colorScheme.error)
            SettingsLinkIn(error, beforeOpen = vm::notNow)
        }
        TextButton(onClick = vm::notNow, enabled = !busy) { Text("Not now") }
    }
}

/** Shared files that weren't imported, named with the reason, instead of silently dropped. */
@Composable
private fun ShareFailures(share: ShareViewModel) {
    val failures = share.failures.collectAsStateWithLifecycle().value
    if (failures.isEmpty()) return
    val title = if (failures.size == 1) "A shared file wasn't imported" else "${failures.size} shared files weren't imported"
    LocusSheet(title = title, onDismiss = share::dismissFailures, action = "OK", onAction = share::dismissFailures) {
        Text(failures.joinToString("\n"), style = LocusType.rowSupporting)
    }
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

/**
 * Shows what the phone integration tools need from the screen (docs/design/android-bridge.md): the
 * calendar permission prompt, the contact picker and the Photo Picker. Requests arrive only while
 * Locus is in front ([dev.locus.core.data.device.PhoneRequests]); each is answered once.
 */
@Composable
private fun PhoneRequestHost() {
    val vm: PhoneRequestsViewModel = hiltViewModel()
    val requests = vm.requests
    androidx.compose.runtime.DisposableEffect(requests) {
        val detach = requests.attach()
        onDispose { detach() }
    }
    val current = requests.current.collectAsStateWithLifecycle().value
    var asked by rememberSaveable { mutableStateOf(-1L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> requests.answer(asked, granted) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requests.answer(asked, result.data?.data.takeIf { result.resultCode == android.app.Activity.RESULT_OK })
    }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> requests.answer(asked, listOfNotNull(uri)) }
    val max = (current as? dev.locus.core.data.device.PhoneRequest.Photos)?.max?.coerceAtLeast(2) ?: 2
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(max)) { uris -> requests.answer(asked, uris) }
    LaunchedEffect(current?.id) {
        val request = current ?: return@LaunchedEffect
        if (request.id == asked) return@LaunchedEffect
        asked = request.id
        val media = androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
        try {
            when (request) {
                is dev.locus.core.data.device.PhoneRequest.Permission -> permission.launch(request.permission)
                is dev.locus.core.data.device.PhoneRequest.Pick -> pick.launch(request.intent)
                is dev.locus.core.data.device.PhoneRequest.Photos -> if (request.max <= 1) one.launch(media) else many.launch(media)
            }
        } catch (_: android.content.ActivityNotFoundException) {
            requests.answer(request.id, null)
        }
    }
}
