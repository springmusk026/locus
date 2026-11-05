package dev.locus.feature.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import dev.locus.core.agent.ApprovalOrigin
import dev.locus.core.agent.TaskReceipts
import dev.locus.core.agent.home.MemoryRef
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.data.agent.ReviewerBlock
import dev.locus.core.data.agent.SideExchange
import dev.locus.core.data.agent.SideThread
import dev.locus.core.data.repository.FileEdit
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.data.repository.UndoCheck
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TaskOutcome
import dev.locus.core.model.TaskRecord
import dev.locus.core.model.TokenUsage
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.core.screenshot.Step
import dev.locus.core.screenshot.PrivateComposable
import dev.locus.core.screenshot.OnScreen
import dev.locus.core.screenshot.UtcRule
import dev.locus.feature.chat.Fx.msg
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The transcript's pieces and the sheets they open: the changes card with its diff, history and undo
 * sheets, check rows and their runs sheet, memory reads, tokens, sharing, the cards above the composer,
 * receipts, and the failure details for each kind of failure.
 */
class TranscriptScreenshots : ScreenshotTest() {

    @get:Rule val utc = UtcRule()

    /** Relative times ("4 minutes ago") count back from the real clock, so they read the same on every run. */
    private val now = System.currentTimeMillis()
    private val min = 60_000L

    // ---- Changes: card, diff and history, undo

    private val paymentEdit1 = """
        @@ -14,7 +14,7 @@ export async function createPayment(cart: Cart) {
           const intent = await stripe.paymentIntents.create({
             amount: cart.totalCents,
             currency: cart.currency,
        -    payment_method_types: ["card"],
        +    automatic_payment_methods: { enabled: true },
             metadata: { cartId: cart.id },
           })
    """.trimIndent()

    private val paymentEdit2 = """
        @@ -20,3 +20,3 @@ export async function createPayment(cart: Cart) {
           })
        -  return intent.client_secret
        +  return intent.client_secret ?: throw new PaymentError("no client secret")
         }
    """.trimIndent()

    private val paymentNet = """
        --- a/garden-app/src/checkout/payment.ts
        +++ b/garden-app/src/checkout/payment.ts
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

    private fun change(
        id: Long,
        path: String,
        kind: TrackedChange.Kind = TrackedChange.Kind.MODIFIED,
        added: Int,
        removed: Int,
        ago: Long,
        diff: String? = "@@ -1 +1 @@\n-a\n+b",
        binary: Boolean = false,
        dir: Boolean = false,
        undone: Boolean = false,
        canUndo: Boolean = true,
        edits: List<FileEdit> = emptyList(),
    ) = TrackedChange(id, "a1", path, kind, added, removed, diff, binary, dir, undone, canUndo, updatedAt = now - ago, edits = edits)

    private val payment = change(
        1, "garden-app/src/checkout/payment.ts", added = 2, removed = 2, ago = 6 * min, diff = paymentNet,
        edits = listOf(
            FileEdit("garden-app/src/checkout/payment.ts", "e1", "edit_file", Fx.T0 + 20_000, 1, 1, diff = paymentEdit1),
            FileEdit("garden-app/src/checkout/payment.ts", "e5", "edit_file", Fx.T0 + 190_000, 1, 1, diff = paymentEdit2),
        ),
    )

    private val three = ChatItem.Changes(
        "a1:changes", "a1",
        listOf(
            payment,
            change(2, "garden-app/src/checkout/refund.ts", added = 3, removed = 3, ago = 5 * min),
            change(3, "garden-app/src/checkout/retry.ts", TrackedChange.Kind.CREATED, added = 37, removed = 0, ago = 2 * min),
        ),
    )

    private val many = ChatItem.Changes(
        "a2:changes", "a2",
        listOf(
            payment,
            change(2, "garden-app/src/checkout/payment.test.ts", added = 18, removed = 9, ago = 6 * min),
            change(3, "garden-app/src/checkout/retry.ts", TrackedChange.Kind.CREATED, added = 37, removed = 0, ago = 2 * min),
            change(4, "garden-app/src/checkout/legacy-stripe.ts", TrackedChange.Kind.DELETED, added = 0, removed = 0, ago = 4 * min),
            change(5, "garden-app/public/og-checkout.png", TrackedChange.Kind.MODIFIED, added = 0, removed = 0, ago = 3 * min, diff = null, binary = true),
            change(6, "garden-app/src/checkout/__snapshots__", TrackedChange.Kind.CREATED, added = 0, removed = 0, ago = 2 * min, diff = null, dir = true),
            change(7, "garden-app/README.md", added = 4, removed = 1, ago = 9 * min, undone = true),
        ),
    )

    /**
     * The reported case: a file the reply created, then edited twice (+124, then +11 −14). It reads
     * "New · 121 lines · Edited 2 times" with each edit under it, never "+121 −0"; a deleted file beside it.
     */
    private val createdThenEdited = ChatItem.Changes(
        "a4:changes", "a4",
        listOf(
            change(
                1, "garden-app/src/checkout/retry-policy.ts", TrackedChange.Kind.CREATED, added = 121, removed = 0, ago = 3 * min,
                edits = listOf(
                    FileEdit("garden-app/src/checkout/retry-policy.ts", "w1", "write_file", Fx.T0 + 20_000, 124, 0),
                    FileEdit("garden-app/src/checkout/retry-policy.ts", "e2", "edit_file", Fx.T0 + 140_000, 11, 14),
                ),
            ),
            change(2, "garden-app/src/checkout/legacy-stripe.ts", TrackedChange.Kind.DELETED, added = 0, removed = 1_284, ago = 2 * min),
            change(3, "garden-app/src/checkout/payment.ts", added = 12_480, removed = 9_731, ago = 1 * min),
        ),
    )

    @Test
    fun changesCardNewAndDeleted() = snap("changes-card-new-edited-deleted", heightDp = 520) {
        OnScreen { ChangesCard(createdThenEdited, ChangeActions()) }
    }

    /** The same card folded: the totals show only what isn't zero, with the new and deleted files counted. */
    @Test
    fun changesCardNewCollapsed() = snapAfter("changes-card-new-collapsed", listOf(Step.Tap("Changed 3 files")), heightDp = 260) {
        OnScreen { ChangesCard(createdThenEdited, ChangeActions()) }
    }

    /** Only new files: the total is "+121 · 1 new file", with no "−0". */
    @Test
    fun changesCardOnlyNew() = snap("changes-card-only-new", heightDp = 300) {
        OnScreen { ChangesCard(ChatItem.Changes("a5:changes", "a5", createdThenEdited.files.take(1)), ChangeActions()) }
    }

    @Test
    fun changesCard() = snap("changes-card", heightDp = 420) {
        OnScreen { ChangesCard(three, ChangeActions()) }
    }

    @Test
    fun changesCardMany() = snapAfter("changes-card-many", listOf(Step.Tap("Changed 6 files")), heightDp = 760) {
        OnScreen { ChangesCard(many, ChangeActions()) }
    }

    @Test
    fun changesAllUndone() = snap("changes-card-all-undone", heightDp = 300) {
        OnScreen { ChangesCard(ChatItem.Changes("a3:changes", "a3", three.files.map { it.copy(undone = true) }), ChangeActions()) }
    }

    /** A file's net diff and its edit history, opened from its row. */
    @Test
    fun diff() = snapAfter("sheet-diff", listOf(Step.Tap("payment.ts")), heightDp = 1000) {
        OnScreen { ChangesCard(three, ChangeActions()) }
    }

    /**
     * The same, with the first edit's own diff open in the history. Not at 200% font: there the history
     * is below the fold of the sheet's lazy list, so there's no row to tap.
     */
    @Test
    fun diffEditOpen() = snapAfter(
        "sheet-diff-edit-open", listOf(Step.Tap("payment.ts"), Step.Tap("Edited")), heightDp = 1400,
        variants = dev.locus.core.screenshot.Variant.entries - dev.locus.core.screenshot.Variant.Font200,
    ) {
        OnScreen { ChangesCard(three, ChangeActions()) }
    }

    @Test
    fun diffChangedSince() = snap("sheet-diff-changed-since", heightDp = 1000) {
        val edits = payment.edits + FileEdit("garden-app/src/checkout/payment.ts", "", "command", Fx.T0 + 240_000, outside = true)
        PrivateComposable(
            "dev.locus.feature.chat.ChangesCardKt", "DiffSheet",
            payment.copy(edits = edits), ChangeActions(check = { UndoCheck(changedSince = true) }), {}, {},
        )
    }

    @Test
    fun diffUndone() = snap("sheet-diff-undone", heightDp = 800) {
        PrivateComposable("dev.locus.feature.chat.ChangesCardKt", "DiffSheet", payment.copy(undone = true, edits = emptyList()), ChangeActions(), {}, {})
    }

    @Test
    fun diffBinaryCantUndo() = snap("sheet-diff-binary", heightDp = 700) {
        PrivateComposable("dev.locus.feature.chat.ChangesCardKt", "DiffSheet", many.files[4].copy(canUndo = false), ChangeActions(), {}, {})
    }

    @Test
    fun diffTooLarge() = snap("sheet-diff-too-large", heightDp = 700) {
        val big = change(
            9, "garden-app/package-lock.json", added = 4_812, removed = 3_977, ago = 3 * min, diff = null,
            edits = listOf(FileEdit("garden-app/package-lock.json", "c1", "write_file", Fx.T0 + 60_000, 4_812, 3_977)),
        )
        PrivateComposable("dev.locus.feature.chat.ChangesCardKt", "DiffSheet", big, ChangeActions(), {}, {})
    }

    @Test
    fun undoAll() = snapAfter("sheet-undo-all", listOf(Step.Tap("Undo all")), heightDp = 700) {
        OnScreen { ChangesCard(three, ChangeActions()) }
    }

    @Test
    fun undoChangedSince() = snapAfter("sheet-undo-changed-since", listOf(Step.Tap("payment.ts"), Step.Tap("Undo")), heightDp = 800) {
        OnScreen { ChangesCard(three, ChangeActions(check = { UndoCheck(changedSince = true) })) }
    }

    @Test
    fun undoBlocked() = snapAfter("sheet-undo-blocked", listOf(Step.Tap("Undo all")), heightDp = 600) {
        OnScreen {
            ChangesCard(three, ChangeActions(checkTurn = { UndoCheck(false, "The earlier version is no longer in the workspace trash (it was restored or emptied).") }))
        }
    }

    // ---- Check rows (run dots) and the runs sheet

    private val checkMessages = listOf(
        msg("cu1", Role.USER, Part.Text("Make the checkout tests and the build pass")),
        msg("ca1", Role.ASSISTANT, Fx.run("t1", "npx vitest run"), Fx.run("b1", "npm run build"), Fx.run("l1", "npm run lint 2>&1 | tail -20")),
        msg(
            "cx1", Role.TOOL,
            Fx.result(
                "t1", "run_command",
                "Exit code 1 (14.2 s).\n ✕ checkout › applies the discount 41ms\n ✕ checkout › retries a declined card 12ms\n ✕ checkout › keeps the cart on a 402 8ms\n ✕ refund › refunds a partial amount 5ms\n\nTests  4 failed | 40 passed (44)",
                error = true,
            ),
            Fx.result(
                "b1", "run_command",
                "Exit code 2 (8.4 s).\nsrc/checkout/retry.ts(14,7): error TS2322: Type 'string | undefined' is not assignable to type 'string'.\nFound 1 error in src/checkout/retry.ts:14",
                error = true,
            ),
            Fx.result("l1", "run_command", "Exit code 0 (3.1 s).\n✔ No problems found in 64 files"),
        ),
        msg("ca2", Role.ASSISTANT, Fx.run("t2", "npx vitest run src/checkout")),
        msg("cx2", Role.TOOL, Fx.result("t2", "run_command", "Exit code 1 (6.9 s).\n ✕ checkout › retries a declined card 14ms\n\nTests  1 failed | 21 passed (22)", error = true)),
        msg("ca3", Role.ASSISTANT, Fx.run("t3", "npx vitest run")),
    )

    private val checkSteps = (groupSteps(transcriptItems(checkMessages)).first { it is TranscriptRow.Steps } as TranscriptRow.Steps).steps
    private val groups = checkGroups(checkSteps)

    @Test
    fun checkRows() = snap("check-rows", heightDp = 620) {
        OnScreen {
            dev.locus.core.designsystem.LocusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                groups.forEach { g -> CheckGroupRow(g, running = { it.result == null }) }
            }
            // The same tests once the third run finished: passed after two failures.
            val passed = groups.first().copy(
                runs = groups.first().runs.dropLast(1) + CheckRun(
                    ChatItem.ToolCall("ca3:t3", "run_command", """{"command":"npx vitest run"}""", Fx.result("t3", "run_command", "Exit code 0 (13.1 s).\nTests  44 passed (44)")),
                    dev.locus.core.model.Verification.ofRunCommand("""{"command":"npx vitest run"}""", "Exit code 0 (13.1 s).\nTests  44 passed (44)", false),
                ),
            )
            dev.locus.core.designsystem.LocusCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                CheckGroupRow(passed, running = { false })
            }
        }
    }

    @Test
    fun checkRunsSheet() = snapAfter("sheet-check-runs", listOf(Step.Tap("Tests · running (run 3)")), heightDp = 1100) {
        OnScreen { CheckGroupRow(groups.first(), running = { it.result == null }) }
    }

    @Test
    fun checkRunsSheetBuild() = snapAfter("sheet-check-runs-build", listOf(Step.Tap("Build failed")), heightDp = 800) {
        OnScreen { CheckGroupRow(groups.first { it.kind == dev.locus.core.model.Verification.Kind.BUILD }, running = { false }) }
    }

    // ---- Memory the turn read

    private val reads = listOf(MemoryRef("garden-app-stripe-setup"), MemoryRef("checkout-test-conventions"), MemoryRef("preferred-units", general = true))

    @Test
    fun memoryRead() = snapAfter("sheet-memory-read", listOf(Step.Tap("Read 3 memory notes")), heightDp = 700) {
        CompositionLocalProvider(LocalMemoryReads provides MemoryReadActions(forgotten = setOf(reads[1]))) {
            OnScreen { MemoryReadRow(reads) }
        }
    }

    @Test
    fun memoryForget() = snapAfter("sheet-memory-forget", listOf(Step.Tap("Read 3 memory notes"), Step.Tap("Forget", index = 1)), heightDp = 700) {
        OnScreen { MemoryReadRow(reads) }
    }

    // ---- Tokens, sharing

    private val tokens = ChatTokens(
        context = 48_200, window = 1_000_000, requests = 6, totalInput = 212_000, totalOutput = 9_400, totalCached = 160_000, totalCacheWritten = 41_800,
        recentSteps = listOf(StepCache(4, 46_100, 44_000, 2_100, 0), StepCache(5, 47_300, 46_100, 1_200, 0), StepCache(6, 48_200, 47_300, 900, 0)),
    )

    @Test
    fun tokensSheet() = snap("sheet-tokens", heightDp = 1000) {
        PrivateComposable("dev.locus.feature.chat.ChatScreenKt", "TokensSheet", tokens, Fx.anthropic, true, {}, {}, {})
    }

    @Test
    fun tokensNearlyFull() = snap("sheet-tokens-nearly-full", heightDp = 900) {
        PrivateComposable(
            "dev.locus.feature.chat.ChatScreenKt", "TokensSheet",
            ChatTokens(context = 176_000, window = 200_000, requests = 31, totalInput = 3_840_000, totalOutput = 61_200), Fx.router, true, {}, {}, {},
        )
    }

    @Test
    fun tokensUnknownWindow() = snap("sheet-tokens-unknown-window", heightDp = 800) {
        PrivateComposable(
            "dev.locus.feature.chat.ChatScreenKt", "TokensSheet",
            ChatTokens(context = 12_400, window = null, requests = 2, totalInput = 18_900, totalOutput = 1_100), Fx.router, false, {}, {}, {},
        )
    }

    @Test
    fun shareChat() = snap("sheet-share-chat", heightDp = 600) {
        val save: suspend (String, File) -> String = { name, _ -> "exports/$name" }
        PrivateComposable("dev.locus.feature.chat.ShareChatKt", "ShareChatSheet", "Fix checkout tests", Fx.T0, Fx.finished, save, {})
    }

    // ---- Above the composer: questions, reviewer blocks, side questions

    @Test
    fun aboveComposer() = snap("chat-above-composer", heightDp = 1300) {
        OnScreen {
            QuestionCard(
                UserQuestion("c", "q3", "Which Stripe account should the test keys come from?", options = emptyList(), origin = ApprovalOrigin("h2", "Researcher #2")),
                onAnswer = { _, _ -> },
            )
            QuestionCard(
                UserQuestion("c", "q4", "Keep the old Express server running during the move?", options = listOf("Yes, until the e2e tests pass", "No, switch at once"), allowOther = false),
                onAnswer = { _, _ -> },
            )
            ReviewerBlockChips(
                listOf(
                    ReviewerBlock("k1", "run_command", "Run “git push --force origin main”", """{"command":"git push --force origin main"}""", "Force-pushing to main rewrites shared history."),
                    ReviewerBlock("k2", "send_email", "Email build-log.txt to ops@northwind.dev", "{}", "Sends a file from the workspace to an address the task hasn't used.", queued = true),
                ),
                onRunAnyway = {}, onDismiss = {},
            )
            SideQuestionCard(
                SideThread(
                    listOf(
                        SideExchange("s1", "Is Stripe v18 a breaking release?", "Yes: it removes `payment_method_types`.", done = true),
                        SideExchange("s2", "Does automatic_payment_methods work with Apple Pay?"),
                    ),
                    open = true,
                ),
                running = true, onDismiss = {}, onClear = {},
            )
            SideQuestionCard(
                SideThread(listOf(SideExchange("s3", "What does the retry test check?", error = "Anthropic returned HTTP 529: Overloaded", done = true)), open = true),
                running = false, onDismiss = {}, onClear = {},
            )
            SideQuestionCard(
                SideThread(listOf(SideExchange("s4", "How big is the dist folder now?", "About **2.3 MB**, judging by the last build's log.", done = true, triedTools = true)), open = true),
                running = true, onDismiss = {}, onClear = {},
            )
        }
    }

    // ---- Receipts of tasks that didn't simply finish

    private fun receipt(outcome: TaskOutcome, detail: String?, seconds: Long) = TaskReceipts.build(
        listOf(TaskRecord("r-${outcome.wire}", "c", Fx.T0, Fx.T0 + seconds * 1000, outcome, detail)),
        Fx.runningMessages,
        listOf(AuditEntry("x", "c", "t1", "run_command", "{}", ApprovalDecision.USER_APPROVED, "", false, Fx.T0 + 2_000, Fx.T0 + 16_000)),
    ).first()

    @Test
    fun receiptLines() = snap("receipt-lines", heightDp = 420) {
        OnScreen {
            ReceiptLine(ChatItem.Receipt("r1", receipt(TaskOutcome.FAILED, "Anthropic returned HTTP 529: Overloaded", 74)), ReceiptActions())
            ReceiptLine(ChatItem.Receipt("r2", receipt(TaskOutcome.STOPPED, null, 191)), ReceiptActions())
            ReceiptLine(ChatItem.Receipt("r3", receipt(TaskOutcome.BUDGET, "This task has used its 500k-token budget.", 1_260)), ReceiptActions())
        }
    }

    @Test
    fun receiptFailed() = snap("sheet-receipt-failed", heightDp = 900) {
        ReceiptSheet(receipt(TaskOutcome.FAILED, "Anthropic returned HTTP 529: Overloaded", 74), ReceiptActions(), onDismiss = {})
    }

    // ---- Sent files, the send warning without a model to suggest

    @Test
    fun sentFiles() = snap("sent-file-cards", heightDp = 520) {
        OnScreen {
            SentFileCard(
                ChatItem.SentFile(
                    "f1", "garden-app/dist/coverage/index.html", "Coverage report for the checkout module",
                    SentFileInfo("index.html", 86_400, "/nonexistent/index.html", "text/html", "garden-app/dist/coverage/index.html"),
                ),
            )
            SentFileCard(
                ChatItem.SentFile(
                    "f2", "photos/2026-10/basil-on-the-balcony.jpg", null,
                    SentFileInfo("basil-on-the-balcony.jpg", 3_412_000, "/nonexistent/basil.jpg", "image/jpeg", "photos/2026-10/basil-on-the-balcony.jpg"),
                ),
            )
            SentFileCard(ChatItem.SentFile("f3", "notes/lisbon-itinerary-final-v2-with-restaurants-and-tram-times.md", "The day-by-day plan", null))
        }
    }

    @Test
    fun sendWarningNoSuggestion() = snap("sheet-send-warning-no-suggestion", heightDp = 600) {
        SendWarningDialog(
            SendWarning("deepseek-v4", "its context window (163.8k tokens) is smaller than this chat (212k tokens)", text = "Summarise everything above"),
            onSwitch = {}, onSendAnyway = {}, onCancel = {},
        )
    }

    // ---- Failure details, one per kind of failure, with the message the chat shows for it

    private fun failure(
        provider: String,
        model: String,
        error: dev.locus.core.llm.LlmException,
        previous: dev.locus.core.agent.RunFailure? = null,
        contextTokens: Int? = null,
        contextWindow: Int? = null,
    ) = dev.locus.core.agent.RunFailure.of(
        error, error.message.orEmpty(), provider, model, Fx.T0, previous, transcriptMark = "u2", rawBody = error.rawBody,
        contextTokens = contextTokens, contextWindow = contextWindow,
    )

    private fun failureSheet(name: String, height: Int, f: dev.locus.core.agent.RunFailure, message: String) = snap(name, heightDp = height) {
        FailureDetailsSheet(f, message, onDismiss = {})
    }

    /** A gateway's HTML page: never shown in the chat, the status in words instead; the page is in Details. */
    @Test
    fun failureGatewayHtml() {
        val body = "<html>\r\n<head><title>502 Bad Gateway</title></head>\r\n<body>\r\n<center><h1>502 Bad Gateway</h1></center>\r\n<hr><center>cloudflare</center>\r\n</body>\r\n</html>"
        val message = dev.locus.core.llm.ProviderErrorText.message("OpenRouter", 502, body)
        val e = dev.locus.core.llm.LlmException(message, 502, retryable = true, rawBody = body, requestId = "8f2a1c9e4b7d03a1-FRA")
        failureSheet("sheet-failure-details-gateway-html", 1100, failure("OpenRouter", "openai/gpt-5", e), message)
    }

    /** The phone's connection dropped mid-stream: no status, no body. */
    @Test
    fun failureNetwork() {
        val message = "${dev.locus.core.agent.AgentLoop.CONNECTION_DROPPED}: Software caused connection abort"
        val e = dev.locus.core.llm.LlmException(message)
        failureSheet("sheet-failure-details-network", 900, failure("Anthropic", "claude-opus-5-5", e), message)
    }

    /** ChatGPT's usage limit: a quota, so the advice is another model. */
    @Test
    fun failureChatGptLimit() {
        val body = """{"error":{"type":"usage_limit_reached","message":"The usage limit has been reached","plan_type":"plus","resets_in_seconds":7140}}"""
        val e = dev.locus.core.llm.ChatGptErrors.fromHttp(429, body)
        failureSheet("sheet-failure-details-chatgpt-limit", 1100, failure("ChatGPT", "gpt-6.1-sol", e), e.message.orEmpty())
    }

    /** The same opaque failure three times at the same point in a large chat: put down to the request. */
    @Test
    fun failureRepeatedRequest() {
        val message = "ChatGPT ended the response with an error and gave no reason (request id req_8f2c41d0e9)."
        val e = dev.locus.core.llm.LlmException(message, retryable = true, rawBody = """{"type":"response.failed","response":{"id":"resp_68f1a2","status":"failed","error":null}}""", requestId = "req_8f2c41d0e9")
        val first = failure("ChatGPT", "gpt-6.1-sol", e, contextTokens = 141_000, contextWindow = 272_000)
        val third = failure("ChatGPT", "gpt-6.1-sol", e, previous = failure("ChatGPT", "gpt-6.1-sol", e, previous = first, contextTokens = 141_000), contextTokens = 141_000, contextWindow = 272_000)
        failureSheet("sheet-failure-details-repeated", 1100, third, message)
    }
}
