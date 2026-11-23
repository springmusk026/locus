package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.Branch
import dev.locus.core.model.CompareEstimate
import dev.locus.core.model.CompareTurn
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import dev.locus.core.model.TokenUsage
import dev.locus.core.screenshot.ScreenshotTest
import org.junit.Test

/** Compare models (docs/design/compare-models.md): the picker, the estimate, and the two answers. */
class CompareScreenshots : ScreenshotTest() {
    private val opus = ModelRef("a", "claude-opus-5-5")
    private val gpt = ModelRef("r", "openai/gpt-5")
    private val choice = CompareChoice(gpt, "OpenAI: GPT-5")

    @Composable
    private fun Chat(state: ChatUiState) = ChatScreen(
        state = state,
        onBack = {},
        onOpenBrowser = {},
        onSend = {},
        onStop = {},
        onAnswer = { _, _ -> },
        onDismissMessages = {},
        onResume = {},
    )

    /** The + sheet's Compare models row, then the model sheet picking the second model. */
    @Test
    fun picker() = snap("sheet-compare-picker", heightDp = 1500) {
        // The chat's model can't be picked; a model known to lack tool calling can't either.
        val lists = Fx.lists + ("r" to dev.locus.core.data.repository.ModelList.Loaded(
            listOf(
                dev.locus.core.llm.RemoteModel("openai/gpt-5", "OpenAI: GPT-5", contextWindow = 400_000, tools = true),
                dev.locus.core.llm.RemoteModel("google/gemini-3-pro", "Google: Gemini 3 Pro", contextWindow = 1_048_576, tools = true),
                dev.locus.core.llm.RemoteModel("acme/no-tools-7b", "Acme: No Tools 7B", contextWindow = 32_000, tools = false),
            ),
        ))
        val settings = Fx.settings.copy(
            controls = Fx.controls.copy(empty = false, compare = choice),
            picker = Fx.picker.copy(lists = lists, needs = dev.locus.core.llm.ModelRequirements(tools = true, contextTokens = 12_000)),
        )
        ModelSheet(settings, onPick = { _, _ -> }, onOpen = {}, onToggleFavorite = {}, onEnableCatalog = {}, onDismiss = {}, compare = true, onClearCompare = {})
    }

    /** The composer with a second model picked: "vs OpenAI: GPT-5" in the bar. */
    @Test
    fun composerPill() = snap("composer-compare", heightDp = 300) {
        LocusComposer(
            text = "Which caching strategy fits a phone app?", onTextChange = {},
            settings = Fx.settings.copy(controls = Fx.controls.copy(empty = false, compare = choice)),
            actions = ComposerActions(setCompare = {}), sheet = null, onSheet = {}, onSend = {},
        )
    }

    /** The + sheet in a chat: Compare models under the budget. */
    @Test
    fun moreSheet() = snap("sheet-more-compare", heightDp = 1400) {
        LocusComposer(
            text = "", onTextChange = {}, settings = Fx.settings.copy(controls = Fx.controls.copy(empty = false, compare = choice)),
            actions = ComposerActions(setCompare = {}, onCompact = {}), sheet = ControlSheet.More, onSheet = {}, onSend = {},
        )
    }

    /** The first Compare send: what it does, both models' estimates (one unknown), and the cache write. */
    @Test
    fun confirmFirst() = snap("sheet-compare-confirm", heightDp = 1100) {
        CompareConfirmSheet(
            CompareConfirm(
                "Which caching strategy fits a phone app?", emptyList(),
                CompareChoice(opus, "Claude Opus 5.5"), choice,
                CompareEstimate(48_300, chat = 0.2415, other = null), first = true,
            ),
            onSend = {}, onCancel = {},
        )
    }

    /** A later send over the threshold: both prices known. */
    @Test
    fun confirmOver() = snap("sheet-compare-confirm-over", heightDp = 1000) {
        CompareConfirmSheet(
            CompareConfirm(
                "Summarise the whole repository", emptyList(),
                CompareChoice(opus, "Claude Opus 5.5"), choice,
                CompareEstimate(412_000, chat = 2.06, other = 0.6437), first = false,
            ),
            onSend = {}, onCancel = {},
        )
    }

    private fun answer(id: String, text: String, ref: ModelRef) = Message(
        id, "c", Role.ASSISTANT, listOf(Part.Text(text)), Fx.T0 + 10_000, usage = TokenUsage(6_200, 180),
        reply = ReplyInfo(ref.providerId, ref.model),
    )

    private val question = Fx.msg("u1", Role.USER, Part.Text("Which caching strategy fits a phone app that syncs notes?"))

    /** Two answers to one message: the chat's own shown (2/2) with its label, Keep this one, and the switcher. */
    @Test
    fun answers() = snap("chat-compare-answers") {
        val items = transcriptItems(
            listOf(question, answer("a2", OPUS_ANSWER, opus)),
            branches = mapOf("a2" to Branch(1, listOf("b1", "a2"))),
        ).markCompare("c", listOf(CompareTurn("c", "u1", opus, gpt)))
        Chat(Fx.state(items, title = "Notes sync caching"))
    }

    /** The second model's answer, switched to (1/2): labelled read-only, and kept. */
    @Test
    fun answerKept() = snap("chat-compare-kept") {
        val items = transcriptItems(
            listOf(question, answer("b1", GPT_ANSWER, gpt)),
            branches = mapOf("b1" to Branch(0, listOf("b1", "a2"))),
        ).markCompare("c", listOf(CompareTurn("c", "u1", opus, gpt, kept = gpt)))
        Chat(Fx.state(items, title = "Notes sync caching"))
    }

    /** While the second model writes: whose answer is streaming, and that it only reads. */
    @Test
    fun streaming() = snap("chat-compare-streaming") {
        val items = transcriptItems(listOf(question))
        Chat(
            Fx.state(
                items, title = "Notes sync caching",
                run = RunState(running = true, streamingText = "Cache the notes list in Room and treat the server as", comparing = "gpt-5 (OpenRouter)"),
            ),
        )
    }

    private companion object {
        const val OPUS_ANSWER = "Keep a local **Room** database as the source of truth and sync in the background:\n\n" +
            "- Write locally first, mark rows dirty, and push them with WorkManager when there's a network.\n" +
            "- Pull changes since the last sync token, and resolve conflicts per note by its edit time.\n\n" +
            "An HTTP cache alone won't do: notes change on both sides."
        const val GPT_ANSWER = "Use a write-through cache: the UI reads from Room, writes go to Room and a sync queue.\n\n" +
            "I'd add a `syncState` column and a periodic job; I can't create the files here, since this answer is read-only."
    }
}
