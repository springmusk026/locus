package dev.locus.feature.chat

import dev.locus.core.data.repository.ModelList
import dev.locus.core.llm.ModelRequirements
import dev.locus.core.llm.RemoteModel
import dev.locus.core.model.Branch
import dev.locus.core.model.CompareTurn
import dev.locus.core.model.Message
import dev.locus.core.model.ModelRef
import dev.locus.core.model.Part
import dev.locus.core.model.ReplyInfo
import dev.locus.core.model.Role
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Compare models in the chat: which models can be the second, labels and Keep marks, and the estimate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CompareModelsUiTest {
    private val opus = ModelRef("a", "claude-opus-5-5")
    private val gpt = ModelRef("r", "openai/gpt-5")

    private val lists: Map<String, ModelList> = Fx.lists + (
        "r" to ModelList.Loaded(
            listOf(
                RemoteModel("openai/gpt-5", "OpenAI: GPT-5", contextWindow = 400_000, tools = true, vision = true),
                RemoteModel("acme/no-tools-7b", "Acme: No Tools 7B", contextWindow = 32_000, tools = false),
                RemoteModel("acme/text-only", "Acme: Text only", contextWindow = 400_000, tools = true, vision = false),
            ),
        )
        )

    private fun rows(needs: ModelRequirements?, chatOnly: Boolean = false, second: ModelRef? = null): Map<ModelRef, PickerRow> =
        buildModelPicker(
            providers = Fx.providers, lists = lists, currentProviderId = "a", controls = Fx.controls,
            favorites = emptyList(), recents = emptyList(), needs = compareNeeds(needs, chatOnly),
        ).forCompare(second).flatMap { it.rows }.associateBy { it.ref }

    @Test
    fun `the chat's own model and models lacking tool calling can't be the second model`() {
        val r = rows(ModelRequirements(tools = false, contextTokens = 2_000))
        assertFalse(r.getValue(opus).pickable, "the chat's own model")
        assertTrue(r.getValue(opus).chatModel)
        assertFalse(r.getValue(opus).selected)
        // A chat that declares tools needs them from the second model too, even before its first message.
        assertFalse(r.getValue(ModelRef("r", "acme/no-tools-7b")).pickable)
        assertTrue(r.getValue(gpt).pickable)
        assertTrue(r.getValue(ModelRef("a", "claude-sonnet-5-5")).pickable)
    }

    @Test
    fun `a chat-only chat takes any model as far as tools go`() {
        val r = rows(ModelRequirements(tools = true), chatOnly = true)
        assertTrue(r.getValue(ModelRef("r", "acme/no-tools-7b")).pickable)
    }

    @Test
    fun `images need vision, and a context larger than the window rules a model out`() {
        val withImage = rows(ModelRequirements(images = true))
        assertFalse(withImage.getValue(ModelRef("r", "acme/text-only")).pickable)
        assertTrue(withImage.getValue(gpt).pickable)
        val long = rows(ModelRequirements(contextTokens = 100_000))
        assertFalse(long.getValue(ModelRef("r", "acme/no-tools-7b")).pickable)
    }

    @Test
    fun `the second model picked is the one marked`() {
        val r = rows(ModelRequirements(), second = gpt)
        assertTrue(r.getValue(gpt).selected)
        assertEquals(1, r.values.count { it.selected })
    }

    private var t = 0L
    private fun msg(id: String, role: Role, text: String, ref: ModelRef? = null) =
        Message(id, "c", role, listOf(Part.Text(text)), t++, reply = ref?.let { ReplyInfo(it.providerId, it.model) })

    @Test
    fun `an answer with alternatives says which model wrote it, and one without doesn't`() {
        val user = msg("u1", Role.USER, "Hi")
        val branched = transcriptItems(listOf(user, msg("a2", Role.ASSISTANT, "Hello", gpt)), branches = mapOf("a2" to Branch(1, listOf("b1", "a2"))))
            .filterIsInstance<ChatItem.Assistant>().single()
        assertEquals(gpt, branched.model)
        assertEquals("gpt-5", modelLabel(branched.model!!))
        val plain = transcriptItems(listOf(user, msg("a1", Role.ASSISTANT, "Hello", gpt))).filterIsInstance<ChatItem.Assistant>().single()
        assertNull(plain.model)
    }

    @Test
    fun `Compare turns' answers get Keep, the kept one says so, and the second model's is read-only`() {
        val user = msg("u1", Role.USER, "Hi")
        fun shown(id: String, ref: ModelRef, index: Int, turns: List<CompareTurn>) =
            transcriptItems(listOf(user, msg(id, Role.ASSISTANT, "Hello", ref)), branches = mapOf(id to Branch(index, listOf("b1", "a2"))))
                .markCompare("c", turns).filterIsInstance<ChatItem.Assistant>().single().compare

        val open = listOf(CompareTurn("c", "u1", opus, gpt))
        assertEquals(ComparePick("u1", kept = false, second = false), shown("a2", opus, 1, open))
        assertEquals(ComparePick("u1", kept = false, second = true), shown("b1", gpt, 0, open))

        val kept = listOf(CompareTurn("c", "u1", opus, gpt, kept = gpt))
        assertEquals(ComparePick("u1", kept = true, second = true), shown("b1", gpt, 0, kept))
        assertEquals(ComparePick("u1", kept = false, second = false), shown("a2", opus, 1, kept))

        // Another chat's comparisons, or a regenerated (not compared) message, mark nothing.
        assertNull(shown("a2", opus, 1, listOf(CompareTurn("other", "u1", opus, gpt))))
        assertNull(shown("a2", opus, 1, listOf(CompareTurn("c", "u9", opus, gpt))))
    }

    @Test
    fun `the estimate uses the catalogue's prices, unknown without them`() {
        val e = compareEstimate(Fx.providers, catalog = null, contextTokens = 40_000, text = "a".repeat(400), attachments = emptyList(), chat = opus, other = gpt)
        assertEquals(40_100, e.promptTokens)
        assertNull(e.chat)
        assertNull(e.other)
        assertEquals("unknown", estimateLabel(testResources, null))
        assertEquals("\$0.012", estimateLabel(testResources, 0.0123))
        assertEquals("\$2.06", estimateLabel(testResources, 2.06))
    }
}
