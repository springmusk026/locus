package dev.locus.feature.chat

import dev.locus.core.agent.TaskReceipts
import dev.locus.core.data.agent.RunState
import dev.locus.core.data.repository.FileEdit
import dev.locus.core.data.repository.ModelList
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.model.TokenUsage
import kotlinx.serialization.json.JsonPrimitive

/** Fake but plausible chat data for the screenshot tests: providers, transcripts, changes and receipts. */
internal object Fx {
    /** A fixed wall clock, so absolute times in sheets don't change between recordings. */
    const val T0 = 1_793_700_000_000L

    /**
     * The real clock, for what the app shows relative to now ("4 minutes ago"): counted back from it, the
     * words stay the same on every run, where a fixed epoch would age into a date.
     */
    private val NOW = System.currentTimeMillis()

    val anthropic = chatProvider(ProviderConfig("a", "Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "claude-opus-5-5"))
        .copy(contextWindow = 1_000_000)
    val router = chatProvider(ProviderConfig("r", "OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1", "openai/gpt-5"))
    val chatgpt = chatProvider(ProviderConfig("c", "ChatGPT", ApiFormat.CHATGPT, "https://chatgpt.com/backend-api/codex", "gpt-6.1-sol"))
    val providers = listOf(anthropic, router)

    val lists: Map<String, ModelList> = mapOf(
        "a" to ModelList.Loaded(
            listOf(
                RemoteModel("claude-haiku-5", "Claude Haiku 5", contextWindow = 200_000, tools = true, vision = true),
                RemoteModel("claude-opus-5-5", "Claude Opus 5.5", contextWindow = 1_000_000, tools = true, vision = true, reasoning = true),
                RemoteModel("claude-sonnet-5-5", "Claude Sonnet 5.5", contextWindow = 1_000_000, tools = true, vision = true, reasoning = true),
            ),
        ),
        "r" to ModelList.Loaded(
            listOf(
                RemoteModel("anthropic/claude-sonnet-5-5", "Anthropic: Claude Sonnet 5.5", contextWindow = 1_000_000),
                RemoteModel("google/gemini-3-pro", "Google: Gemini 3 Pro", contextWindow = 1_048_576),
                RemoteModel("openai/gpt-5", "OpenAI: GPT-5", contextWindow = 400_000),
                RemoteModel("deepseek/deepseek-v4", "DeepSeek: DeepSeek V4", contextWindow = 163_840),
            ),
        ),
    )

    val controls = ChatControls(model = "claude-opus-5-5")

    val picker = ModelPickerData(
        lists = lists,
        favorites = listOf(ModelRef("r", "google/gemini-3-pro")),
        recents = listOf(ModelRef("a", "claude-sonnet-5-5")),
        catalogOn = false,
    )

    val connectors = listOf(
        ChatConnector("gh", "GitHub", mcp = true),
        ChatConnector("linear", "Linear", mcp = true),
        ChatConnector("ha", "Home Assistant", mcp = false),
    )

    val settings = ComposerSettings(anthropic, providers, controls, connectors, picker)

    private var seq = 0L

    fun msg(id: String, role: Role, vararg parts: Part, usage: TokenUsage? = null) = Message(
        id, "c", role, parts.toList(), T0 + (seq++) * 1_000, usage = usage,
        reply = if (role == Role.ASSISTANT) ReplyInfo("a", "claude-opus-5-5") else null,
    )

    fun run(id: String, command: String) = Part.ToolCall(id, "run_command", """{"command":${JsonPrimitive(command)}}""")
    fun call(id: String, name: String, vararg args: Pair<String, String>) =
        Part.ToolCall(id, name, args.joinToString(",", "{", "}") { (k, v) -> "\"$k\":${JsonPrimitive(v)}" })
    fun result(id: String, name: String, text: String, error: Boolean = false) =
        Part.ToolResult(id, name, text, isError = error, decision = ApprovalDecision.AUTO_ALLOWED)

    /** Items and rows for a chat state, as the view model would build them. */
    fun state(
        items: List<ChatItem>,
        run: RunState = RunState(),
        title: String = "Fix checkout tests",
        todos: List<TodoItem>? = null,
        tokens: ChatTokens = ChatTokens(context = 48_200, window = 1_000_000, requests = 6, totalInput = 212_000, totalOutput = 9_400, totalCached = 160_000),
        provider: ChatProvider = anthropic,
    ) = ChatUiState(
        title = title,
        createdAt = T0,
        provider = provider,
        providers = providers,
        items = items,
        rows = groupSteps(items),
        run = run,
        loading = false,
        tokens = tokens,
        controls = controls,
        connectors = connectors,
        todos = todos,
        picker = picker,
    )

    // A normal conversation: a question, a Markdown answer with code, and a follow-up.

    val conversation: List<ChatItem> = transcriptItems(
        listOf(
            msg("u1", Role.USER, Part.Text("How do I debounce a search field in Compose so it doesn't query on every keystroke?")),
            msg(
                "a1", Role.ASSISTANT,
                Part.Text(
                    """
                    Collect the text as a `Flow` and let `debounce` drop the keystrokes in between. Keep it in the **view model**, so a rotation doesn't restart the timer.

                    ### In the view model

                    ```kotlin
                    private val query = MutableStateFlow("")

                    val results = query
                        .debounce(300)
                        .distinctUntilChanged()
                        .mapLatest { q -> if (q.isBlank()) emptyList() else repo.search(q) }
                        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

                    fun onQuery(text: String) { query.value = text }
                    ```

                    ### Why this works

                    1. `debounce(300)` waits for 300 ms of quiet before passing a value on.
                    2. `mapLatest` cancels a search that's still running when a newer query arrives.
                    3. `WhileSubscribed(5_000)` keeps the flow alive across a configuration change.

                    > Tip: 250–400 ms feels responsive without flooding the API.
                    """.trimIndent(),
                ),
                usage = TokenUsage(8_412, 391),
            ),
            msg("u2", Role.USER, Part.Text("And if I want the first query to fire immediately?")),
            msg(
                "a2", Role.ASSISTANT,
                Part.Text(
                    "Start the flow with the current value and skip the delay for it:\n\n" +
                        "```kotlin\nquery\n    .debounce { if (it.length <= 1) 0L else 300L }\n```\n\n" +
                        "`debounce` takes a lambda, so single characters (or an empty box) go straight through and longer input waits.",
                ),
                usage = TokenUsage(8_951, 118),
            ),
        ),
    )

    // A running task: checks run several times (run dots), edits, narration, and a test run in progress.

    val runningMessages = listOf(
        msg("u1", Role.USER, Part.Text("The checkout tests in garden-app fail since the Stripe SDK bump. Fix them and make sure the build passes.")),
        msg(
            "a1", Role.ASSISTANT,
            Part.Text("Let me look at the failing tests first."),
            call("r1", "read_file", "path" to "garden-app/src/checkout/payment.ts"),
            run("t1", "npm test"),
        ),
        msg(
            "x1", Role.TOOL,
            result("r1", "read_file", "export async function createPayment(cart: Cart) { … }"),
            result("t1", "run_command", "Exit code 1 (14.2 s).\nTests: 3 failed, 41 passed\n  ✕ checkout › applies the discount\n  ✕ checkout › retries a declined card", error = true),
        ),
        msg(
            "a2", Role.ASSISTANT,
            Part.Text("Stripe v18 renamed the `payment_method_types` option. Updating the call sites."),
            call("e1", "edit_file", "path" to "garden-app/src/checkout/payment.ts"),
            call("e2", "edit_file", "path" to "garden-app/src/checkout/payment.test.ts"),
            call("s1", "search_files", "query" to "payment_method_types"),
        ),
        msg(
            "x2", Role.TOOL,
            result("e1", "edit_file", "Edited garden-app/src/checkout/payment.ts"),
            result("e2", "edit_file", "Edited garden-app/src/checkout/payment.test.ts"),
            result("s1", "search_files", "garden-app/src/checkout/refund.ts:12"),
        ),
        msg("a3", Role.ASSISTANT, Part.Text("One more call site in refund.ts."), call("e3", "edit_file", "path" to "garden-app/src/checkout/refund.ts"), run("t2", "npm test")),
        msg(
            "x3", Role.TOOL,
            result("e3", "edit_file", "Edited garden-app/src/checkout/refund.ts"),
            result("t2", "run_command", "Exit code 1 (13.8 s).\nTests: 1 failed, 43 passed\n  ✕ checkout › retries a declined card", error = true),
        ),
        msg("a4", Role.ASSISTANT, call("e4", "edit_file", "path" to "garden-app/src/checkout/retry.ts"), run("t3", "npm test")),
        msg("x4", Role.TOOL, result("e4", "edit_file", "Edited garden-app/src/checkout/retry.ts")),
    )

    val runningTodos = listOf(
        TodoItem("Reproduce the failing checkout tests", TodoStatus.COMPLETED),
        TodoItem("Update payment calls for Stripe v18", TodoStatus.COMPLETED),
        TodoItem("Fix the declined-card retry", TodoStatus.IN_PROGRESS, "Fixing the declined-card retry"),
        TodoItem("Run the full build", TodoStatus.PENDING),
    )

    // A finished task: checks, a changes card and a receipt on the reply.

    private val finishedMessages = runningMessages.dropLast(1) + listOf(
        msg("x4", Role.TOOL, result("e4", "edit_file", "Edited garden-app/src/checkout/retry.ts"), result("t3", "run_command", "Exit code 0 (13.1 s).\nTests: 44 passed")),
        msg("a5", Role.ASSISTANT, run("b1", "npm run build")),
        msg("x5", Role.TOOL, result("b1", "run_command", "Exit code 0 (41.6 s).\nBuild complete: dist/ (2.3 MB)")),
        msg(
            "a6", Role.ASSISTANT,
            Part.Text(
                "All 44 tests pass and the build is clean.\n\n" +
                    "- **payment.ts**, **refund.ts**: `payment_method_types` → `automatic_payment_methods`, as Stripe v18 expects.\n" +
                    "- **retry.ts**: a declined card now retries once with the saved payment method instead of throwing.\n" +
                    "- **payment.test.ts**: the mocks match the new response shape.",
            ),
            usage = TokenUsage(52_310, 640, cacheRead = 44_000),
        ),
    )

    private val paymentDiff = """
        @@ -14,9 +14,9 @@ export async function createPayment(cart: Cart) {
           const intent = await stripe.paymentIntents.create({
             amount: cart.totalCents,
             currency: cart.currency,
        -    payment_method_types: ["card"],
        +    automatic_payment_methods: { enabled: true },
             metadata: { cartId: cart.id },
           })
        -  return intent.client_secret
        +  return intent.client_secret ?: throw new PaymentError("no client secret")
         }
    """.trimIndent()

    val changes = listOf(
        TrackedChange(
            1, "a1", "garden-app/src/checkout/payment.ts", TrackedChange.Kind.MODIFIED, 2, 2, paymentDiff, binary = false, isDirectory = false, undone = false, canUndo = true,
            updatedAt = NOW - 4 * 60_000,
            edits = listOf(
                FileEdit("garden-app/src/checkout/payment.ts", "e1", "edit_file", T0 + 20_000, 1, 1),
                FileEdit("garden-app/src/checkout/payment.ts", "e5", "edit_file", T0 + 30_000, 1, 1),
            ),
        ),
        TrackedChange(2, "a1", "garden-app/src/checkout/payment.test.ts", TrackedChange.Kind.MODIFIED, 18, 9, "@@ -1 +1 @@\n-a\n+b", binary = false, isDirectory = false, undone = false, canUndo = true, updatedAt = NOW - 6 * 60_000),
        TrackedChange(3, "a1", "garden-app/src/checkout/refund.ts", TrackedChange.Kind.MODIFIED, 3, 3, "@@ -1 +1 @@\n-a\n+b", binary = false, isDirectory = false, undone = false, canUndo = true, updatedAt = NOW - 5 * 60_000),
        TrackedChange(4, "a1", "garden-app/src/checkout/retry.ts", TrackedChange.Kind.CREATED, 37, 0, "@@ -0,0 +1 @@\n+b", binary = false, isDirectory = false, undone = false, canUndo = true, updatedAt = NOW - 2 * 60_000),
    )

    private val records = listOf(TaskRecord("task1", "c", T0, T0 + 312_000, TaskOutcome.DONE))
    private val audit = listOf(
        AuditEntry("x", "c", "t1", "run_command", "{}", ApprovalDecision.USER_APPROVED, "", false, T0 + 2_000, T0 + 16_000),
        AuditEntry("y", "c", "b1", "run_command", "{}", ApprovalDecision.AUTO_ALLOWED, "", false, T0 + 200_000, T0 + 241_000),
    )
    val receipts = TaskReceipts.build(records, finishedMessages, audit, mapOf("a1" to changes.map { it.path }))

    val finished: List<ChatItem> = transcriptItems(finishedMessages, changes = mapOf("a1" to changes), receipts = receipts)
}
