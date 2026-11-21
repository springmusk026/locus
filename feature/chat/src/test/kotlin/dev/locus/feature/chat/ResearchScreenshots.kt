package dev.locus.feature.chat

import androidx.compose.runtime.Composable
import dev.locus.core.agent.BudgetMeter
import dev.locus.core.agent.workflow.ResearchWorkflow
import dev.locus.core.data.agent.RunState
import dev.locus.core.model.ModeNote
import dev.locus.core.model.Part
import dev.locus.core.model.Role
import dev.locus.core.model.TodoItem
import dev.locus.core.model.TodoStatus
import dev.locus.core.screenshot.ScreenshotTest
import dev.locus.feature.chat.Fx.call
import dev.locus.feature.chat.Fx.msg
import dev.locus.feature.chat.Fx.result
import org.junit.Test
import java.time.LocalDate

/** A research workflow run in the chat (docs/design/deep-research.md): working through its checklist, and finished with its report card. */
class ResearchScreenshots : ScreenshotTest() {

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
        onVoiceMode = {},
    )

    private val rendered = ResearchWorkflow.render(
        mapOf("question" to "Do heat pumps work well in cold climates?", "scope" to "Since 2022. Prefer energy.gov and iea.org", "depth" to "standard"),
        date = LocalDate.of(2026, 11, 21),
    )
    private val path = rendered.plan.inputs["report_path"]!!

    private val started = listOf(
        msg("u1", Role.USER, Part.Text(rendered.text), Part.Text(ModeNote.wrap(rendered.note))),
        msg("a1", Role.ASSISTANT, Part.Text("I'll split this into three questions and send a researcher on each."), call("t1", "todo_write", "todos" to "[]")),
        msg("x1", Role.TOOL, result("t1", "todo_write", "Checklist updated.")),
        msg("a2", Role.ASSISTANT, call("w1", "agent_wait", "ids" to "[]")),
        msg("x2", Role.TOOL, result("w1", "agent_wait", "3 helpers finished: 14 findings from 13 sources.")),
        msg("a3", Role.ASSISTANT, Part.Text("Checking the claims that rest on one source."), call("f1", "fetch_url", "url" to "https://www.energy.gov/energysaver/heat-pump-systems")),
        msg("x3", Role.TOOL, result("f1", "fetch_url", "HTTP 200 text/html (48 KB)")),
        msg("a4", Role.ASSISTANT, call("f2", "fetch_url", "url" to "https://www.iea.org/reports/the-future-of-heat-pumps")),
    )

    private val checklist = listOf(
        TodoItem("How do they perform below freezing?", TodoStatus.COMPLETED),
        TodoItem("What do they cost to run in cold places?", TodoStatus.COMPLETED),
        TodoItem("What do field trials in cold countries show?", TodoStatus.COMPLETED),
        TodoItem("Verify key claims", TodoStatus.IN_PROGRESS, "Verifying key claims"),
        TodoItem("Write the report", TodoStatus.PENDING),
    )

    private val finished = started.dropLast(1) + listOf(
        msg("a4", Role.ASSISTANT, call("f2", "fetch_url", "url" to "https://www.iea.org/reports/the-future-of-heat-pumps")),
        msg("x4", Role.TOOL, result("f2", "fetch_url", "HTTP 200 text/html (112 KB)")),
        msg("a5", Role.ASSISTANT, call("r1", "write_file", "path" to path, "content" to "# Heat pumps in cold climates")),
        msg("x5", Role.TOOL, result("r1", "write_file", "Created $path (6,214 characters)")),
        msg("a6", Role.ASSISTANT, call("s1", "send_file", "path" to path, "note" to "13 sources · 2 unverified claims")),
        msg("x6", Role.TOOL, result("s1", "send_file", "Sent $path")),
        msg(
            "a7", Role.ASSISTANT,
            Part.Text(
                "Yes. Cold-climate heat pumps keep 70–100% of their rated heat down to about −15 °C [1], and Nordic field trials " +
                    "measured seasonal efficiency around 2.8 [2]. Below −25 °C most need backup heat. Two claims couldn't be confirmed " +
                    "and are listed in the report.\n\nCheck: Every key claim was checked against its source — passed",
            ),
        ),
    )

    @Test
    fun running() = snap("chat-research-running") {
        Chat(
            Fx.state(
                transcriptItems(started),
                title = "Heat pumps in cold climates",
                run = RunState(
                    running = true, activeTool = "fetch_url", step = 9,
                    budget = BudgetMeter(tokens = 286_000, tokenLimit = 500_000, costUsd = null, costLimit = null, elapsedMs = 380_000, timeLimitMs = null),
                ),
                todos = checklist,
            ),
        )
    }

    @Test
    fun finished() = snap("chat-research-finished") {
        Chat(
            Fx.state(
                // The report as the chat finds it in the workspace (the view model looks the file up).
                transcriptItems(finished).map {
                    if (it is ChatItem.SentFile) it.copy(file = SentFileInfo(path.substringAfterLast('/'), 6_214, "/nonexistent/report.md", "text/markdown", path)) else it
                },
                title = "Heat pumps in cold climates",
                todos = checklist.map { it.copy(status = TodoStatus.COMPLETED) },
            ),
        )
    }
}
