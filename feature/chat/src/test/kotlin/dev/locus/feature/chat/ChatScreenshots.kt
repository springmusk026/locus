package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.agent.ApprovalFlag
import dev.locus.core.agent.ApprovalRequest
import dev.locus.core.agent.ApprovalScope
import dev.locus.core.agent.BudgetLimit
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.agent.LikelyCause
import dev.locus.core.agent.RememberOffer
import dev.locus.core.agent.RunFailure
import dev.locus.core.agent.TaskReceipts
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.BudgetPause
import dev.locus.core.data.agent.QueuedMessage
import dev.locus.core.data.agent.ReviewerBlock
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.agent.SideExchange
import dev.locus.core.data.agent.SideThread
import dev.locus.core.llm.LlmFailure
import dev.locus.core.model.ApprovalScopeKind
import dev.locus.core.model.Part
import dev.locus.core.model.PauseReason
import dev.locus.core.model.ProposedPlan
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import dev.locus.core.model.ToolRisk
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.feature.chat.Fx.msg
import org.junit.Test

/** The chat screen in each state it can be in, from fake [ChatUiState]s (no view model). */
class ChatScreenshots : ScreenshotTest() {

    @Composable
    private fun Chat(
        state: ChatUiState,
        aside: SideThread? = null,
        heatServices: List<String> = emptyList(),
    ) = ChatScreen(
        state = state,
        onBack = {},
        onOpenBrowser = {},
        onSend = {},
        onStop = {},
        onAnswer = { _, _ -> },
        onDismissMessages = {},
        onResume = {},
        aside = aside,
        heatServices = heatServices,
        onVoiceMode = {},
    )

    private val runningItems = transcriptItems(Fx.runningMessages)

    /** The same task between steps: the third test run has finished, and the agent waits on the user. */
    private val waitingItems = transcriptItems(
        Fx.runningMessages.dropLast(1) + msg(
            "x4", Role.TOOL,
            Fx.result("e4", "edit_file", "Edited garden-app/src/checkout/retry.ts"),
            Fx.result("t3", "run_command", "Exit code 1 (13.5 s).\nTests: 1 failed, 43 passed\n  ✕ checkout › retries a declined card", error = true),
        ),
    )

    private val budget = BudgetMeter(tokens = 182_000, tokenLimit = 500_000, costUsd = 0.84, costLimit = 2.0, elapsedMs = 252_000, timeLimitMs = 1_800_000)

    @Test
    fun empty() = snap("chat-empty") {
        Chat(Fx.state(emptyList(), title = "New chat", tokens = ChatTokens()))
    }

    @Test
    fun conversation() = snap("chat-conversation") {
        Chat(
            Fx.state(
                Fx.conversation,
                title = "Debounce a search field",
                run = RunState(suggestions = listOf("Show it with a TextField", "How do I test the debounce?", "Cancel on screen exit?")),
            ),
        )
    }

    @Test
    fun running() = snap("chat-running") {
        Chat(
            Fx.state(
                runningItems,
                run = RunState(running = true, activeTool = "run_command", step = 7, budget = budget, thinkingSince = Fx.T0, thoughtMs = 8_200),
                todos = Fx.runningTodos,
            ),
        )
    }

    @Test
    fun runningStreaming() = snap("chat-running-streaming") {
        Chat(
            Fx.state(
                transcriptItems(Fx.runningMessages.take(3)),
                run = RunState(
                    running = true,
                    streamingText = "The failures come from Stripe v18: `payment_method_types` was replaced by `automatic_payment_methods`, so the mock",
                    step = 3,
                ),
                todos = Fx.runningTodos.map { if (it.status == dev.locus.core.model.TodoStatus.IN_PROGRESS) it else it.copy(status = dev.locus.core.model.TodoStatus.PENDING) }
                    .mapIndexed { i, t -> if (i == 0) t.copy(status = dev.locus.core.model.TodoStatus.COMPLETED) else t },
            ),
        )
    }

    @Test
    fun finished() = snap("chat-finished") {
        Chat(Fx.state(Fx.finished))
    }

    @Test
    fun approval() = snap("chat-approval") {
        val request = ApprovalRequest(
            conversationId = "c",
            callId = "t3",
            toolName = "run_command",
            risk = ToolRisk.EXECUTE,
            summary = "Run: cd garden-app && npm test 2>&1 | tail -30",
            argumentsJson = """{"command":"cd garden-app && npm test 2>&1 | tail -30"}""",
            remember = RememberOffer(ApprovalScope("run_command", ApprovalScopeKind.COMMAND, "npm test", "npm test …"), always = true),
            flags = emptySet(),
        )
        Chat(Fx.state(waitingItems, run = RunState(running = true, step = 7, approvals = listOf(request)), todos = Fx.runningTodos))
    }

    /** A plain Ask that can't be remembered: one line says why instead of a missing "Don't ask again". */
    @Test
    fun approvalNoRemember() = snap("chat-approval-no-remember") {
        val request = ApprovalRequest(
            conversationId = "c",
            callId = "t4",
            toolName = "run_command",
            risk = ToolRisk.EXECUTE,
            summary = "Run: rm -rf node_modules && npm ci",
            argumentsJson = """{"command":"rm -rf node_modules && npm ci"}""",
            rememberNote = "This command can't be remembered: it chains, pipes, redirects or expands.",
            flags = emptySet(),
        )
        Chat(Fx.state(waitingItems, run = RunState(running = true, step = 7, approvals = listOf(request)), todos = Fx.runningTodos))
    }

    @Test
    fun approvalFromHelper() = snap("chat-approval-reviewer") {
        val request = ApprovalRequest(
            conversationId = "c",
            callId = "f1",
            toolName = "fetch_url",
            risk = ToolRisk.NETWORK,
            summary = "POST to https://hooks.slack.com/services/T024/B07/…",
            argumentsJson = """{"url":"https://hooks.slack.com/services/T024/B07/x","method":"POST"}""",
            reviewerNote = "Reviewer: sends the build log to an address the task hasn't used before.",
            origin = dev.locus.core.agent.ApprovalOrigin("h1", "Researcher #2"),
            flags = setOf(ApprovalFlag.TAINTED),
            siteForTask = "hooks.slack.com",
        )
        Chat(Fx.state(waitingItems, run = RunState(running = true, step = 7, approvals = listOf(request))))
    }

    @Test
    fun question() = snap("chat-question") {
        val q = UserQuestion(
            "c", "q1", "The retry test also fails on main. Should I fix it in this change or leave it for a separate one?",
            options = listOf("Fix it here", "Leave it, open an issue", "Skip the test for now"),
        )
        Chat(Fx.state(waitingItems, run = RunState(running = true, step = 7, questions = listOf(q)), todos = Fx.runningTodos))
    }

    @Test
    fun questionMulti() = snap("chat-question-multi") {
        val q = UserQuestion(
            "c", "q2", "Which browsers should the checkout tests run in?",
            options = listOf("Chrome", "Firefox", "Safari (WebKit)", "Edge"), multi = true,
        )
        Chat(Fx.state(waitingItems, run = RunState(running = true, step = 7, questions = listOf(q))))
    }

    /** ChatGPT failing the same way twice: the advice, Details, Switch model and Try again. */
    @Test
    fun errorChatGpt() = snap("chat-error-chatgpt") {
        Chat(errorState())
    }

    @Test
    fun errorPlain() = snap("chat-error-no-key") {
        Chat(
            Fx.state(
                Fx.conversation + ChatItem.User("u3", "Now write a test for it"),
                title = "Debounce a search field",
                run = RunState(error = "No API key for Anthropic. Add it in Settings → Models."),
            ),
        )
    }

    @Test
    fun interrupted() = snap("chat-interrupted-queued") {
        Chat(
            Fx.state(
                waitingItems,
                run = RunState(
                    interrupted = true,
                    notice = "Locus was closed while working. The transcript is intact.",
                    queued = listOf(QueuedMessage("q1", "Also bump the Stripe SDK in the admin app", createdAt = Fx.T0)),
                ),
            ),
        )
    }

    @Test
    fun plan() = snap("chat-plan") {
        val items = listOf(
            ChatItem.User("u1", "Move the garden-app API from Express to Hono", planMode = true),
            ChatItem.Plan(
                "p1",
                ProposedPlan(
                    "Move garden-app's API to Hono",
                    listOf(
                        "Add hono and @hono/node-server; keep Express until the switch",
                        "Port the 14 routes in src/api, starting with /health and /plants",
                        "Replace the Express middleware (cors, auth, rate limit) with Hono's",
                        "Run the API tests and the e2e suite against both servers",
                        "Remove Express and update the README",
                    ),
                    notes = "Nothing changes until you approve. About 40 files.",
                ),
                PlanStatus.Waiting,
            ),
        )
        Chat(Fx.state(items, title = "Express to Hono").let { it.copy(controls = it.controls.copy(planMode = true)) })
    }

    @Test
    fun queuedAndAside() = snap("chat-queued-aside") {
        Chat(
            Fx.state(
                runningItems,
                run = RunState(
                    running = true,
                    activeTool = "run_command",
                    step = 7,
                    queued = listOf(QueuedMessage("q1", "Don't touch the admin app, it's on its own release", createdAt = Fx.T0)),
                ),
                todos = Fx.runningTodos,
            ),
            aside = SideThread(
                listOf(
                    SideExchange("s1", "What's the difference between payment_method_types and automatic_payment_methods?", "With `automatic_payment_methods`, Stripe picks the methods from your Dashboard settings, so new ones work without a code change.", done = true),
                ),
                open = true,
            ),
        )
    }

    @Test
    fun budgetPause() = snap("chat-budget-pause") {
        Chat(
            Fx.state(
                waitingItems,
                run = RunState(
                    running = true,
                    step = 12,
                    budget = budget.copy(tokens = 500_400),
                    budgetPause = BudgetPause(BudgetLimit.TOKENS, "This task has used its 500k-token budget."),
                    reviewerBlocks = listOf(
                        ReviewerBlock("k1", "run_command", "Run “git push --force origin main”", """{"command":"git push --force origin main"}""", "Force-pushing to main rewrites shared history."),
                    ),
                ),
                todos = Fx.runningTodos,
            ),
        )
    }

    @Test
    fun hotPhone() = snap("chat-hot-paused") {
        Chat(
            Fx.state(Fx.conversation, title = "Debounce a search field", run = RunState(paused = PauseReason.HOT)),
            heatServices = listOf("postgres", "redis"),
        )
    }

    @Test
    fun handoff() = snap("chat-browser-handoff") {
        Chat(
            Fx.state(
                transcriptItems(
                    listOf(
                        msg("u1", Role.USER, Part.Text("Book a table for two at Lula's on Friday at 8")),
                        msg("a1", Role.ASSISTANT, Fx.call("b1", "browser_open", "url" to "https://www.opentable.com/r/lulas-bistro")),
                        msg("x1", Role.TOOL, Fx.result("b1", "browser_open", "Opened Lula's Bistro – OpenTable")),
                    ),
                ),
                title = "Table at Lula's",
                run = RunState(running = true, activeTool = "browser_ask_user", step = 3),
            ).copy(browserHandoff = "Sign in to OpenTable, then tap Done in the browser.", browserHasPage = true, browserTitle = "Lula's Bistro – OpenTable", browserUrl = "https://www.opentable.com/r/lulas-bistro"),
        )
    }

    /** A long chat: every variant, the 200% font one included, scrolled to the latest turn. */
    @Test
    fun long() = snap("chat-long") {
        val turns = (1..6).flatMap { i ->
            listOf(
                msg("lu$i", Role.USER, Part.Text(LONG_QUESTIONS[i - 1])),
                msg("la$i", Role.ASSISTANT, Part.Text(LONG_ANSWERS[i - 1]), usage = TokenUsage(4_000 + i * 900, 120 + i * 30)),
            )
        }
        Chat(Fx.state(transcriptItems(turns), title = "Plan a 3-day trip to Lisbon"))
    }

    private fun errorState(): ChatUiState {
        val messages = listOf(
            msg("u1", Role.USER, Part.Text("Summarise the open issues in the garden-app repo by area")),
            msg("a1", Role.ASSISTANT, Part.Text("There are **23 open issues**. By area:\n\n- **Checkout** (8): Stripe v18, declined cards, VAT rounding\n- **Plants API** (6): pagination, slow search\n- **Mobile** (9): dark mode, offline sync"), usage = TokenUsage(12_400, 210)),
            msg("u2", Role.USER, Part.Text("Draft a fix for the VAT rounding one")),
        )
        val receipts = TaskReceipts.build(
            listOf(
                TaskRecord("t1", "c", Fx.T0, Fx.T0 + 40_000, TaskOutcome.DONE),
                TaskRecord("t2", "c", Fx.T0 + 60_000, Fx.T0 + 75_000, TaskOutcome.FAILED, "ChatGPT ended the response with an error"),
            ),
            messages,
        )
        val failure = RunFailure(
            provider = "ChatGPT", model = "gpt-6.1-sol", httpStatus = null, errorCode = null, requestId = "req_8f2c41d0e9", responseId = "resp_68f1a2",
            at = Fx.T0, rawBody = """{"type":"response.failed","response":{"id":"resp_68f1a2","status":"failed","error":null}}""",
            kind = LlmFailure.TRANSIENT, occurrences = 2, cause = LikelyCause.TRANSIENT,
        )
        return Fx.state(
            transcriptItems(messages, receipts = receipts),
            title = "garden-app issues",
            provider = Fx.chatgpt,
            run = RunState(error = "ChatGPT ended the response with an error and gave no reason (request id req_8f2c41d0e9).", failure = failure),
        )
    }

    private companion object {
        val LONG_QUESTIONS = listOf(
            "I'm going to Lisbon for 3 days in April. Where should I stay?",
            "Which day is best for Sintra?",
            "Do I need to book the Pena Palace?",
            "What should I eat in Alfama?",
            "How do I get from the airport to Baixa?",
            "Put it all into a day-by-day plan",
        )
        val LONG_ANSWERS = listOf(
            "For a first visit, **Baixa** or **Chiado**: flat, central and walkable. **Príncipe Real** is quieter with good cafés; **Alfama** is charming but steep and noisy at night.",
            "Go on a **weekday**, ideally Tuesday to Thursday. Take the first train from Rossio (about 40 minutes) and be at the gates when they open; by 11 the queues are long.",
            "Yes. Book a timed ticket on the Parques de Sintra site a few days ahead. The palace slot is fixed; the park around it you can walk any time that day.",
            "- **Bacalhau à Brás** at a tasca\n- **Grilled sardines** (best from June, but you'll find them)\n- **Pastéis de nata** warm from Manteigaria\n- A glass of **ginjinha** at the end",
            "The metro's red line runs from the airport to Saldanha, then change to the yellow or blue line. About 35 minutes and €1.85 with a Viva Viagem card. A taxi or Bolt is 15–20 minutes and roughly €15.",
            "### Day 1: Baixa, Chiado and Alfama\nWalk Rua Augusta to the river, tram 28 up to the castle, sunset at Miradouro da Senhora do Monte.\n\n### Day 2: Sintra\nFirst train from Rossio, Pena Palace at opening, Quinta da Regaleira after lunch.\n\n### Day 3: Belém\nJerónimos Monastery, the tower, pastéis de Belém, then LX Factory in the evening.",
        )
    }
}
