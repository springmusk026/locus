package dev.locus.core.agent

import dev.locus.core.agent.tools.AskUser
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.agent.tools.QuestionDesk
import dev.locus.core.agent.tools.UserAsker
import dev.locus.core.agent.tools.UserQuestion
import dev.locus.core.llm.AssistantTurn
import dev.locus.core.llm.ChatEvent
import dev.locus.core.llm.ChatModel
import dev.locus.core.llm.ChatRequest
import dev.locus.core.llm.StopReason
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.AuditEntry
import dev.locus.core.model.Conversation
import dev.locus.core.model.Message
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.ToolPolicy
import dev.locus.core.model.toolLabel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AskUserTest {
    private fun args(json: String) = Json.parseToJsonElement(json) as JsonObject

    private fun context(readOnly: Boolean = false, conversationId: String = "chat", callId: String = "q1") =
        ToolCallContext(conversationId, callId, depth = 0, reviewer = null, run = RunScope(readOnly = readOnly))

    private val colours = UserQuestion("chat", "q1", "Which colour?", listOf("Red", "Green", "Blue"), multi = true)

    @Test
    fun `a question waits until the user answers`() = runTest {
        val desk = QuestionDesk()
        val tool = AskUser(desk)
        val result = async { withContext(context()) { tool.execute(args("""{"question":"Deploy now?","options":["Yes","No"],"allow_other":false}""")) } }
        runCurrent()
        val open = desk.pending.value.single()
        assertEquals("chat", open.chatId)
        assertEquals("q1", open.callId)
        assertEquals(listOf("Yes", "No"), open.options)
        assertFalse(open.allowOther)
        assertFalse(result.isCompleted)

        assertTrue(desk.answerOption("q1", 0))
        val done = result.await()
        assertFalse(done.isError)
        assertEquals("The user chose: “Yes”", done.content)
        assertTrue(desk.pending.value.isEmpty())
        // Nothing is waiting any more.
        assertFalse(desk.answer("q1", QuestionAnswer(text = "late")))
    }

    @Test
    fun `answers are cleaned up against the question`() = runTest {
        val desk = QuestionDesk()
        val answer = async { desk.ask(colours) }
        runCurrent()
        // Empty answers are ignored; picks that aren't options are dropped; order follows the options.
        assertFalse(desk.answer("q1", QuestionAnswer()))
        assertFalse(desk.answer("q1", QuestionAnswer(chosen = listOf("Purple"), text = "  ")))
        assertTrue(desk.answer("q1", QuestionAnswer(chosen = listOf("Blue", "Purple", "Red"), text = "  and a bit of white ")))
        assertEquals(QuestionAnswer(listOf("Red", "Blue"), "and a bit of white"), answer.await())
    }

    @Test
    fun `no answer within 30 minutes is no answer`() = runTest {
        val desk = QuestionDesk()
        val tool = AskUser(desk)
        val result = async { withContext(context()) { tool.execute(args("""{"question":"Which one?"}""")) } }
        advanceTimeBy(AskUser.TIMEOUT_MS - 1)
        runCurrent()
        assertEquals(1, desk.pending.value.size)
        advanceTimeBy(2)
        runCurrent()
        val done = result.await()
        assertTrue(done.isError)
        assertEquals(AskUser.NO_ANSWER, done.content)
        assertTrue(desk.pending.value.isEmpty())
    }

    @Test
    fun `background runs get an immediate not-available`() = runTest {
        var asked = false
        val tool = AskUser(UserAsker { asked = true; null })
        val done = withContext(context(readOnly = true)) { tool.execute(args("""{"question":"Which one?"}""")) }
        assertTrue(done.isError)
        assertEquals(AskUser.UNAVAILABLE, done.content)
        assertFalse(asked)
        // Outside a run (no call context) nobody is asked either.
        assertEquals(AskUser.UNAVAILABLE, tool.execute(args("""{"question":"Which one?"}""")).content)
        assertFalse(asked)
        // It still counts as looking, so read-only runs and plan mode let it run (and answer as above).
        assertTrue(tool.isObservation(args("""{"question":"x"}""")))
        assertEquals(ToolPolicy.ALLOW, tool.defaultPolicy)
    }

    @Test
    fun `a helper's question goes to the parent chat with its name`() = runTest {
        val desk = QuestionDesk(route = { id -> if (id == "helper-1") "parent" to ApprovalOrigin("helper-1", "Researcher #2") else null })
        val tool = AskUser(desk)
        val result = async { withContext(context(conversationId = "helper-1", callId = "hq")) { tool.execute(args("""{"question":"Which source?"}""")) } }
        runCurrent()
        val open = desk.pending.value.single()
        assertEquals("parent", open.chatId)
        assertEquals(ApprovalOrigin("helper-1", "Researcher #2"), open.origin)
        assertTrue(desk.answer("hq", QuestionAnswer(text = "The official docs")))
        assertEquals("The user wrote: The official docs", result.await().content)
    }

    @Test
    fun `a question left open when the app died is answered as not answered`() {
        val history = listOf(
            Message("u", "c", Role.USER, listOf(Part.Text("tidy up")), 0),
            Message("a", "c", Role.ASSISTANT, listOf(Part.ToolCall("q1", AgentControlTools.ASK_USER, """{"question":"Delete the old logs?"}""")), 1),
        )
        val recovery = recoverTranscript(history)
        val result = recovery.missingResults.single()
        assertEquals("q1", result.callId)
        assertEquals(AskUser.RESTARTED, result.content)
        assertTrue(result.isError)
        // The chat offers Continue.
        assertTrue(recovery.unfinished)
    }

    @Test
    fun `results read back for the steps card`() {
        val chose = AskUser.formatAnswer(QuestionAnswer(listOf("Red", "Blue")))
        assertEquals("The user chose: “Red”, “Blue”", chose)
        assertEquals("“Red”, “Blue”", AskUser.shownAnswer(chose))
        val both = AskUser.formatAnswer(QuestionAnswer(listOf("Red"), "two lines\nof text"))
        assertEquals("“Red” · two lines\nof text", AskUser.shownAnswer(both))
        assertEquals("just text", AskUser.shownAnswer(AskUser.formatAnswer(QuestionAnswer(text = "just text"))))
        assertNull(AskUser.shownAnswer(AskUser.NO_ANSWER))
        assertNull(AskUser.shownAnswer(AskUser.RESTARTED))
        assertEquals("Asked you: “Which colour?”", toolLabel(AgentControlTools.ASK_USER, """{"question":"Which colour?"}"""))
    }

    @Test
    fun `questions and options are capped`() {
        val long = "x".repeat(AskUser.MAX_QUESTION_CHARS + 1)
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"$long"}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"  "}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"q","options":["a","b","c","d","e","f","g"]}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"q","options":["only"]}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"q","options":["a","a"]}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"q","options":["${"o".repeat(AskUser.MAX_OPTION_CHARS + 1)}","b"]}""")) }
        assertFailsWith<ToolException> { AskUser.parse(args("""{"question":"q","options":[1,2]}""")) }
        // Without options the answer is always free text, and multi means nothing.
        val free = AskUser.parse(args("""{"question":"Name?","multi":true,"allow_other":false}"""))
        assertTrue(free.allowOther)
        assertFalse(free.multi)
        assertTrue(AskUser.parse(args("""{"question":"q","options":["a","b"]}""")).allowOther)
    }

    @Test
    fun `through the loop the answer is the tool result and the call is not reviewed or asked`() = runTest {
        val desk = QuestionDesk()
        val turns = ArrayDeque(
            listOf(
                AssistantTurn("", listOf(Part.ToolCall("q1", AgentControlTools.ASK_USER, """{"question":"Keep the backup?","options":["Keep","Delete"]}""")), StopReason.TOOL_USE, ApiFormat.OPENAI_CHAT, null),
                AssistantTurn("Kept.", emptyList(), StopReason.END_TURN, ApiFormat.OPENAI_CHAT, null),
            ),
        )
        val model = object : ChatModel {
            override val format = ApiFormat.OPENAI_CHAT
            override fun stream(request: ChatRequest): Flow<ChatEvent> = flow { emit(ChatEvent.Completed(turns.removeFirst())) }
        }
        val messages = mutableListOf<Message>()
        val store = object : TranscriptStore {
            override suspend fun append(message: Message) { messages += message }
            override suspend fun record(entry: AuditEntry) = Unit
        }
        var gateAsked = false
        var n = 0
        val loop = AgentLoop(model, ToolRegistry(listOf(AskUser(desk))), { it.defaultPolicy }, ApprovalGate { gateAsked = true; false }, store, ids = { "id${n++}" })
        val events = async { loop.run(Conversation("c", "t", "p", "s", 0, 0), listOf(Message("u", "c", Role.USER, listOf(Part.Text("clean up")), 0))).toList() }
        runCurrent()
        assertEquals("c", desk.pending.value.single().chatId)
        desk.answer("q1", QuestionAnswer(chosen = listOf("Keep")))
        val all = events.await()
        assertFalse(gateAsked)
        val finished = all.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals(ApprovalDecision.AUTO_ALLOWED, finished.decision)
        assertEquals("The user chose: “Keep”", finished.result.content)
        assertIs<AgentEvent.Finished>(all.last())
        assertEquals("Kept.", messages.last().text)
    }
}
