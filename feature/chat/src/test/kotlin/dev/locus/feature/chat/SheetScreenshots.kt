package dev.locus.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.agent.LikelyCause
import dev.locus.core.agent.RunFailure
import dev.locus.core.agent.SentBlock
import dev.locus.core.agent.SentMessage
import dev.locus.core.agent.SentTool
import dev.locus.core.agent.SentView
import dev.locus.core.data.remote.LinkPreview
import dev.locus.core.data.remote.RemoteActivity
import dev.locus.core.llm.LlmFailure
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Effort
import dev.locus.core.model.LocalWarning
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.PauseReason
import dev.locus.core.model.Project
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.ThermalLevel
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.OnScreen
import dev.locus.core.screenshot.UtcRule
import org.junit.Rule
import org.junit.Test

/** An on-device model too small to be given tools (SmallModels). */
private const val SMALL_MODEL = "gguf:qwen3.5-0.8b-q4_k_m"

/** The chat's sheets and dialogs, called directly with fake state. */
class SheetScreenshots : ScreenshotTest() {

    /** Failure details show the time of the failure: a fixed epoch, read in UTC. */
    @get:Rule val utc = UtcRule()

    private val garden = Project("p1", "garden-app", "projects/garden-app")
    private val portfolio = Project("p2", "Portfolio site", "projects/portfolio")
    private val taxes = Project("p3", "Taxes 2026", "projects/taxes-2026")

    // The composer's sheets.

    @Test
    fun model() = snap("sheet-model", heightDp = 1500) {
        ModelSheet(Fx.settings, onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {})
    }

    @Test
    fun modelLoading() = snap("sheet-model-loading", heightDp = 700) {
        val picker = Fx.picker.copy(lists = mapOf("a" to Fx.lists.getValue("a"), "r" to dev.locus.core.data.repository.ModelList.Loading), favorites = emptyList(), recents = emptyList())
        ModelSheet(Fx.settings.copy(picker = picker), onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {})
    }

    /** A new chat on the phone: a model too small for tools is marked Chat only, and the sheet says what that means. */
    @Test
    fun modelChatOnly() = snap("sheet-model-chat-only", heightDp = 1100) {
        ModelSheet(onPhone(Fx.controls.copy(model = SMALL_MODEL, chatOnly = true)), onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {})
    }

    /** A chat-only chat that has started: it stays without tools whatever model it switches to. */
    @Test
    fun modelChatOnlyStarted() = snap("sheet-model-chat-only-started", heightDp = 1100) {
        ModelSheet(onPhone(Fx.controls.copy(model = SMALL_MODEL, chatOnly = true, empty = false)), onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {})
    }

    @Test
    fun effort() = snap("sheet-effort", heightDp = 800) {
        EffortSheet(Fx.anthropic, Fx.controls.copy(effort = Effort.MEDIUM, effortOverridden = true), onPick = {}, onDismiss = {})
    }

    @Test
    fun mode() = snap("sheet-mode", heightDp = 700) {
        ModeSheet(Fx.controls, onPick = {}, onDismiss = {})
    }

    @Test
    fun budgetDefault() = snap("sheet-budget-default", heightDp = 800) {
        BudgetSheet(Fx.controls.copy(defaultBudget = TaskBudget(tokens = 500_000, minutes = 30)), onSet = {}, onDismiss = {})
    }

    @Test
    fun budgetOwnRunning() = snap("sheet-budget-own-running", heightDp = 1150) {
        val own = TaskBudget(tokens = 200_000, costUsd = 1.5, minutes = 20)
        val controls = Fx.controls.copy(
            defaultBudget = TaskBudget(tokens = 500_000),
            disabledTools = TaskBudget.withOverride(emptySet(), own),
        )
        BudgetSheet(
            controls, onSet = {}, onDismiss = {},
            meter = BudgetMeter(tokens = 182_000, tokenLimit = 200_000, costUsd = 0.84, costLimit = 1.5, elapsedMs = 252_000, timeLimitMs = 1_200_000),
        )
    }

    @Test
    fun tools() = snap("sheet-tools", heightDp = 1250) {
        ToolsSheet(Fx.controls.copy(disabledTools = setOf(dev.locus.core.model.ToolGroup.EMAIL.key, dev.locus.core.model.ToolGroup.connectorKey("linear"))), Fx.connectors, onSetEnabled = { _, _ -> }, onDismiss = {})
    }

    @Test
    fun toolsChatOnly() = snap("sheet-tools-chat-only", heightDp = 600) {
        ToolsSheet(Fx.controls.copy(chatOnly = true, empty = false), Fx.connectors, onSetEnabled = { _, _ -> }, onDismiss = {})
    }

    @Test
    fun toolsChatOnlyEmpty() = snap("sheet-tools-chat-only-empty", heightDp = 600) {
        ToolsSheet(Fx.controls.copy(chatOnly = true), Fx.connectors, onSetEnabled = { _, _ -> }, onDismiss = {})
    }

    /** The composer of a chat-only chat: the tools button says there are none. */
    @Test
    fun composerChatOnly() = snap("composer-chat-only", heightDp = 300) {
        LocusComposer(
            text = "", onTextChange = {}, settings = onPhone(Fx.controls.copy(model = SMALL_MODEL, chatOnly = true, empty = false)),
            actions = ComposerActions(), sheet = null, onSheet = {}, onSend = {},
        )
    }

    private val phone = chatProvider(
        dev.locus.core.model.ProviderConfig("p", "On this phone", dev.locus.core.model.ApiFormat.ON_DEVICE, "", SMALL_MODEL, requiresKey = false),
    )

    /** The composer's settings for a chat on the phone's provider, with a small model, a larger one and a template without tools. */
    private fun onPhone(controls: ChatControls) = Fx.settings.copy(
        provider = phone,
        providers = listOf(phone, Fx.anthropic),
        controls = controls,
        picker = Fx.picker.copy(
            lists = Fx.lists + (
                "p" to dev.locus.core.data.repository.ModelList.Loaded(
                    listOf(
                        RemoteModel(SMALL_MODEL, "Qwen3.5 0.8B", contextWindow = 16_384, tools = false, detail = "Q4_K_M · 0.5 GB · llama.cpp"),
                        RemoteModel("gguf:qwen3.5-2b-q4_k_m", "Qwen3.5 2B", contextWindow = 16_384, tools = true, detail = "Q4_K_M · 1.3 GB · llama.cpp"),
                        RemoteModel("gguf:gemma-3-1b-it-q8_0", "Gemma 3 1B", contextWindow = 16_384, tools = false, detail = "Q8_0 · 1.1 GB · llama.cpp"),
                    ),
                )
                ),
            favorites = emptyList(),
            recents = emptyList(),
        ),
    )

    /** The + sheet, opened from the composer (the only way in: it's private to the composer). */
    @Test
    fun more() = snap("sheet-more", heightDp = 1100) {
        LocusComposer(
            text = "", onTextChange = {}, settings = Fx.settings,
            actions = ComposerActions(onCompact = {}, onWorkflows = {}, onVoiceMode = {}),
            sheet = ControlSheet.More, onSheet = {}, onSend = {}, canCompact = true,
        )
    }

    // What was sent, failure details.

    @Test
    fun sent() = snap("sheet-what-was-sent", heightDp = 1400) {
        WhatWasSentSheet(SentState.Ready(sentView), onDismiss = {})
    }

    @Test
    fun sentMissing() = snap("sheet-what-was-sent-missing", heightDp = 500) {
        WhatWasSentSheet(SentState.Missing, onDismiss = {})
    }

    @Test
    fun failure() = snap("sheet-failure-details", heightDp = 1000) {
        FailureDetailsSheet(
            RunFailure(
                provider = "ChatGPT", model = "gpt-6.1-sol", httpStatus = null, errorCode = null, requestId = "req_8f2c41d0e9", responseId = "resp_68f1a2",
                at = Fx.T0, rawBody = """{"type":"response.failed","response":{"id":"resp_68f1a2","status":"failed","error":null}}""",
                kind = LlmFailure.TRANSIENT, occurrences = 2, cause = LikelyCause.TRANSIENT,
            ),
            "ChatGPT ended the response with an error and gave no reason (request id req_8f2c41d0e9).",
            onDismiss = {},
        )
    }

    @Test
    fun failureRateLimit() = snap("sheet-failure-details-429", heightDp = 1050) {
        FailureDetailsSheet(
            RunFailure(
                provider = "Anthropic", model = "claude-opus-5-5", httpStatus = 429, errorCode = "rate_limit_error", requestId = "req_011CU7xR2", responseId = null,
                at = Fx.T0, rawBody = """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}""",
                kind = LlmFailure.RATE_LIMIT, occurrences = 1, cause = LikelyCause.TRANSIENT,
            ),
            // What the chat shows for it: the provider's own words after the status (ProviderErrorText).
            dev.locus.core.llm.ProviderErrorText.message(
                "Anthropic", 429, """{"type":"error","error":{"type":"rate_limit_error","message":"Number of request tokens has exceeded your per-minute rate limit"}}""",
            ),
            onDismiss = {},
        )
    }

    // The receipt and the send warnings.

    @Test
    fun receipt() = snap("sheet-receipt", heightDp = 900) {
        ReceiptSheet(Fx.receipts.first(), ReceiptActions(saveWorkflow = {}), onDismiss = {})
    }

    @Test
    fun sendWarning() = snap("sheet-send-warning", heightDp = 600) {
        SendWarningDialog(
            SendWarning("deepseek-v4", "it can't read images", ModelRef("a", "claude-sonnet-5-5"), "Claude Sonnet 5.5", text = "What plant is this?"),
            onSwitch = {}, onSendAnyway = {}, onCancel = {},
        )
    }

    @Test
    fun heatHold() = snap("sheet-heat-hold", heightDp = 700) {
        HeatHoldSheet(HeatHold(LocalWarning(PauseReason.HOT, ThermalLevel.CRITICAL, fewerThreads = true), "Summarise this PDF"), onLight = {}, onSendAnyway = {}, onNotNow = {})
    }

    @Test
    fun heatHoldBattery() = snap("sheet-heat-hold-battery", heightDp = 600) {
        HeatHoldSheet(HeatHold(LocalWarning(PauseReason.POWER_SAVE, ThermalLevel.NONE, fewerThreads = false), "Summarise this PDF"), onLight = {}, onSendAnyway = {}, onNotNow = {})
    }

    // Projects and remote machines.

    @Test
    fun projectCurrent() = snap("sheet-project-current", heightDp = 700) {
        ChatProjectSheet(ChatProjectInfo(garden, listOf(garden, portfolio, taxes)), empty = false, onDismiss = {}, onOpenProject = {}, onMove = {}, onContinueIn = {})
    }

    @Test
    fun projectEmptyChat() = snap("sheet-project-move", heightDp = 800) {
        ChatProjectSheet(ChatProjectInfo(garden, listOf(garden, portfolio, taxes)), empty = true, onDismiss = {}, onOpenProject = {}, onMove = {}, onContinueIn = {})
    }

    @Test
    fun projectNone() = snap("sheet-project-none", heightDp = 700) {
        ChatProjectSheet(ChatProjectInfo(null, listOf(garden, portfolio, taxes)), empty = false, onDismiss = {}, onOpenProject = {}, onMove = {}, onContinueIn = {})
    }

    @Test
    fun chatRemote() = snap("sheet-chat-remote", heightDp = 600) {
        ChatRemoteSheet(ChatRemoteInfo("p1", "build-server", "~/locus/garden-app", RemoteActivity.RUNNING), onDismiss = {}, onOpenProject = {})
    }

    private val hosts = listOf(RemoteHostRow("h1", "build-server", "basanta@192.168.1.40"), RemoteHostRow("h2", "mac-mini", "basanta@mac-mini.local"))

    @Test
    fun remoteChoose() = snap("sheet-remote-choose", heightDp = 700) {
        RemoteBackendSheet(RemoteSheetStep.Choose, currentAlias = "build-server", hosts = hosts, onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {})
    }

    @Test
    fun remoteChooseNoHosts() = snap("sheet-remote-choose-empty", heightDp = 700) {
        RemoteBackendSheet(RemoteSheetStep.Choose, currentAlias = null, hosts = emptyList(), onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {})
    }

    @Test
    fun remoteFolder() = snap("sheet-remote-folder", heightDp = 800) {
        RemoteBackendSheet(
            RemoteSheetStep.Folder("h1", "build-server", "~/locus/garden-app", loading = false, error = "~/locus is not writable by basanta."),
            currentAlias = null, hosts = hosts, onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {},
        )
    }

    @Test
    fun remotePreview() = snap("sheet-remote-preview", heightDp = 900) {
        val preview = LinkPreview(
            alias = "build-server",
            folder = "/home/basanta/locus/garden-app",
            folderExists = false,
            upload = listOf("package.json", "tsconfig.json", "src/index.ts", "src/checkout/payment.ts", "src/checkout/refund.ts", "src/checkout/retry.ts", "src/api/plants.ts"),
            uploadBytes = 1_842_000,
            download = emptyList(),
            secrets = listOf(".env", ".env.production"),
            stays = listOf(".git/", "node_modules/", "dist/"),
            tooLarge = emptyList(),
        )
        RemoteBackendSheet(RemoteSheetStep.Preview("h1", preview), currentAlias = null, hosts = hosts, onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {})
    }

    @Test
    fun remoteLinking() = snap("sheet-remote-linking", heightDp = 400) {
        RemoteBackendSheet(RemoteSheetStep.Linking("build-server", 128, 311), currentAlias = null, hosts = hosts, onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {})
    }

    @Test
    fun remoteUnlink() = snap("sheet-remote-unlink", heightDp = 500) {
        RemoteBackendSheet(RemoteSheetStep.Unlink("build-server"), currentAlias = "build-server", hosts = hosts, onDismiss = {}, onChoosePhone = {}, onChooseHost = {}, onFolder = {}, onPreview = {}, onLink = {}, onUnlink = {})
    }

    // The composer on its own, and the chat's smaller cards.

    @Test
    fun composer() = snap("composer-states", heightDp = 640) {
        OnScreen {
            LocusComposer(text = "", onTextChange = {}, settings = Fx.settings, actions = ComposerActions(onVoiceMode = {}), sheet = null, onSheet = {}, onSend = {})
            LocusComposer(
                text = "Also update the README", onTextChange = {}, settings = Fx.settings.copy(controls = Fx.controls.copy(planMode = true, effort = Effort.LOW, effortOverridden = true)),
                actions = ComposerActions(), sheet = null, onSheet = {}, onSend = {}, running = true,
                attachments = listOf(Part.Attachment("receipt-march.pdf", "application/pdf", "/nonexistent/receipt-march.pdf", "inbox/receipt-march.pdf", 182_000)),
                dock = { ChecklistStrip(Fx.runningTodos, running = true) },
            )
            LocusComposer(text = "", onTextChange = {}, settings = Fx.settings, actions = ComposerActions(), sheet = null, onSheet = {}, onSend = {}, answering = true, placeholder = "Answer Locus")
        }
    }

    @Test
    fun cards() = snap("chat-cards", heightDp = 560) {
        // On the screen's background, as in the chat (without it the dark variant drew dark text on white).
        OnScreen {
            ChangesCard(Fx.finished.filterIsInstance<ChatItem.Changes>().single(), ChangeActions())
            Fx.finished.firstNotNullOfOrNull { (it as? ChatItem.Receipt) }?.let { ReceiptLine(it.copy(attached = false), ReceiptActions()) }
            SentFileCard(
                ChatItem.SentFile(
                    "f1", "garden-app/dist/report.pdf", "The test report, as asked",
                    SentFileInfo("report.pdf", 248_000, "/nonexistent/report.pdf", "application/pdf", "garden-app/dist/report.pdf"),
                ),
            )
            SentFileCard(ChatItem.SentFile("f2", "notes/old-plan.md", null, null))
            ThoughtRow(ChatItem.Thought("th1", "The failing assertion compares cents to euros; the mock returns 1999 but the test expects 19.99.", 8_200, inSteps = false))
            PausedChip(PauseReason.POWER_SAVE, onRunAnyway = {})
            RemoteChip(ChatRemoteInfo("p1", "build-server", "~/locus/garden-app", RemoteActivity.SYNCING), onClick = {})
            ProjectChip("garden-app", onClick = {})
        }
    }

    private val sentView = SentView(
        format = ApiFormat.ANTHROPIC_MESSAGES,
        provider = "Anthropic",
        model = "claude-opus-5-5",
        host = "api.anthropic.com",
        params = listOf("model" to "claude-opus-5-5", "max_tokens" to "64000", "stream" to "true", "output_config.effort" to "high"),
        system = "You are Locus, an agent on the user's Android phone. You work in a Linux workspace at /workspace…",
        tools = listOf(
            SentTool("read_file", "Read a text file from the workspace.", """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""),
            SentTool("run_command", "Run a shell command in the Linux workspace.", """{"type":"object","properties":{"command":{"type":"string"}}}"""),
            SentTool("edit_file", "Replace exact text in a file.", """{"type":"object"}"""),
        ),
        messages = listOf(
            SentMessage("user", listOf(SentBlock.Text("The checkout tests in garden-app fail since the Stripe SDK bump. Fix them."))),
            SentMessage("assistant", listOf(SentBlock.Text("Let me look at the failing tests first."), SentBlock.ToolCall("run_command", "toolu_01", """{"command":"npm test"}"""))),
            SentMessage("user", listOf(SentBlock.ToolResult("toolu_01", "Exit code 1 (14.2 s).\nTests: 3 failed, 41 passed", isError = true), SentBlock.Attachment("[image, 1.2 MB]"))),
        ),
        estimate = SentView.Estimate(system = 3_200, tools = 5_400, messages = 9_800),
        reportedInputTokens = 18_412,
        json = """{"model":"claude-opus-5-5","max_tokens":64000}""",
    )
}
