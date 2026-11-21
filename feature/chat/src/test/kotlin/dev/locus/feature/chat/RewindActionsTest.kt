package dev.locus.feature.chat

import dev.locus.core.agent.Compaction
import dev.locus.core.agent.Rewind
import dev.locus.core.data.repository.RewindFile
import dev.locus.core.data.repository.TrackedChange
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.Message
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The message menus' Rewind here and Summarise from here (docs/design/rewind.md): which messages offer them, the
 * same answer the runner gets from `Rewind.plan` and `Compaction.from`, and how the sheets word what they'll do.
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [36])
class RewindActionsTest {
    private val english get() = testResources

    private fun msg(id: String, role: Role, vararg parts: Part, format: ApiFormat? = null, raw: String? = null) =
        Message(id, "c", role, parts.toList(), 0, format = format, rawPayload = raw)

    private val path = listOf(
        msg("u1", Role.USER, Part.Text("one")),
        msg("r1", Role.ASSISTANT, Part.Text("first")),
        msg("u2", Role.USER, Part.Text("two")),
        msg("a2", Role.ASSISTANT, Part.ToolCall("k", "read_file", "{}")),
        msg("t2", Role.TOOL, Part.ToolResult("k", "read_file", "x")),
        msg("r2", Role.ASSISTANT, Part.Text("second")),
        msg("cp", Role.USER, Part.Checkpoint("Summary of one and two.", 6)),
        msg("u3", Role.USER, Part.Text("three")),
        msg("r3", Role.ASSISTANT, Part.Text("third"), format = ApiFormat.ANTHROPIC_MESSAGES, raw = """[{"type":"compaction","content":"…"},{"type":"text","text":"third"}]"""),
        msg("n3", Role.USER, Part.Text(ModeNote.wrap(ModeNote.userUndid(listOf("a.txt"))))),
        msg("u4", Role.USER, Part.Text("four")),
        msg("r4", Role.ASSISTANT, Part.Text("fourth")),
        msg("u5", Role.USER, Part.Text("five")),
        msg("r5", Role.ASSISTANT, Part.Text("fifth")),
    )

    @Test
    fun `the menus agree with what the runner does`() {
        val context = Compaction.modelContext(path)
        val actions = messageActions(path, context)
        for (m in path.filter { it.role == Role.ASSISTANT || it.role == Role.USER && it.parts.none { p -> p is Part.Checkpoint } }) {
            val a = actions.getValue(m.id)
            assertEquals(Rewind.plan(path, m.id).unavailable, a.rewind, "rewind ${m.id}")
            val from = Compaction.from(context, m.id)
            val expected = when {
                from.unavailable == Compaction.FromUnavailable.SUMMARISED -> SummariseUnavailable.SUMMARISED
                from.unavailable == Compaction.FromUnavailable.NOTHING_AFTER -> SummariseUnavailable.NOTHING_AFTER
                from.unavailable == Compaction.FromUnavailable.TOO_LITTLE -> SummariseUnavailable.TOO_LITTLE
                Compaction.serverCompactedIn(context.drop(from.start)) -> SummariseUnavailable.SERVER_COMPACTED
                else -> null
            }
            assertEquals(expected, a.summarise, "summarise ${m.id}")
            if (expected == null) assertEquals(context.size - from.start, a.summariseCount, "count ${m.id}")
        }
        // What that means here: the first message can't be rewound, the summarised ones can't be summarised, the
        // ones before Anthropic's own compaction neither, and the last reply has nothing after it.
        assertEquals(Rewind.Unavailable.FIRST_MESSAGE, actions.getValue("u1").rewind)
        assertEquals(SummariseUnavailable.SUMMARISED, actions.getValue("u2").summarise)
        assertEquals(SummariseUnavailable.SERVER_COMPACTED, actions.getValue("u3").summarise)
        assertEquals(MessageActions(summariseCount = 4), actions.getValue("u4"))
        assertEquals(MessageActions(summariseCount = 2), actions.getValue("r4"))
        assertEquals(MessageActions(rewind = Rewind.Unavailable.NOTHING_AFTER, summarise = SummariseUnavailable.NOTHING_AFTER), actions.getValue("r5"))
        assertNull(actions["cp"])
    }

    @Test
    fun `unavailable entries say why, and every entry is off while the agent works`() {
        assertEquals("Not while the agent works", rewindUnavailableText(english, running = true, reason = null))
        assertEquals("Not while the agent works", summariseUnavailableText(english, running = true, reason = null))
        assertNull(rewindUnavailableText(english, running = false, reason = null))
        assertEquals("The first message: use Edit to change it", rewindUnavailableText(english, false, Rewind.Unavailable.FIRST_MESSAGE))
        assertEquals("Already part of an earlier summary", summariseUnavailableText(english, false, SummariseUnavailable.SUMMARISED))
        assertEquals("The provider already compacted this part on its server", summariseUnavailableText(english, false, SummariseUnavailable.SERVER_COMPACTED))
    }

    @Test
    fun `the rewind sheet words files and what stays`() {
        assertEquals("Changed after this point: goes back", rewindFileLine(english, RewindFile("a.kt", TrackedChange.Kind.MODIFIED, RewindFile.State.RESTORE)))
        assertEquals("Made after this point: moves to the trash", rewindFileLine(english, RewindFile("b.kt", TrackedChange.Kind.CREATED, RewindFile.State.RESTORE)))
        assertEquals("Changed since: left as it is", rewindFileLine(english, RewindFile("c.kt", TrackedChange.Kind.MODIFIED, RewindFile.State.CHANGED_SINCE)))
        val effects = listOf(
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npm install"),
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npm test"),
            Rewind.Effect(Rewind.EffectKind.COMMAND, "run_command", "npm install"),
            Rewind.Effect(Rewind.EffectKind.OUTSIDE, "github_create_issue", "github_create_issue"),
        )
        assertEquals(
            listOf(
                "3 shell commands ran after this; their effects stay." to listOf("npm install", "npm test"),
                "1 call reached a service outside the phone (a connector, mail, a deploy); it can't be taken back." to listOf("github_create_issue"),
            ),
            rewindEffectLines(english, effects),
        )
    }
}
