package dev.locus.core.agent.workflow

import dev.locus.core.agent.tools.SubagentLimits
import dev.locus.core.model.AgentControlTools
import dev.locus.core.model.SubagentTools
import dev.locus.core.model.TaskBudget
import dev.locus.core.model.Workflow
import dev.locus.core.model.WorkflowCheck
import dev.locus.core.model.WorkflowParam
import dev.locus.core.model.WorkflowParamType
import java.text.Normalizer
import java.time.LocalDate

/**
 * The built-in Research workflow (docs/design/deep-research.md): plan sub-questions, fan out researcher
 * helpers, verify the key claims against what the sources say, and write a cited Markdown report to the
 * workspace, with the claims that couldn't be confirmed kept and marked.
 *
 * It is an ordinary workflow run: one user message with the plan as a note, the existing helpers
 * (`agent_spawn`, `agent_wait`), the workflow's tool fence and checks, and a task budget carried on the
 * plan. No new tool and nothing in the system prompt, so every chat's cached prefix is unchanged. What
 * the model reads is English; the app shows its own translated names for it (core/ui).
 */
object ResearchWorkflow {
    const val ID = "research"
    const val NAME = "Research"

    const val QUESTION = "question"
    const val SCOPE = "scope"
    const val DEPTH = "depth"

    /** Where reports go in the workspace (or the project's folder). */
    const val FOLDER = "research"

    /**
     * How far a run goes. Helper counts never pass Locus's own helper limits ([SubagentLimits]: three at
     * once, six per turn), so a run never asks the engine for more than it allows.
     */
    enum class Depth(
        val key: String,
        /** Researcher helpers the run starts. */
        val helpers: Int,
        /** Sources each helper reads. */
        val sourcesPerHelper: Int,
        /** Sources the verify step may read again. */
        val rechecks: Int,
        /** Sub-questions the plan aims for. */
        val subQuestions: IntRange,
        /** The budget a run of this depth starts with unless the user picks another: about 1.5–2× [estimateTokens], on a preset. */
        val defaultTokens: Long,
    ) {
        QUICK("quick", helpers = 1, sourcesPerHelper = 4, rechecks = 2, subQuestions = 2..3, defaultTokens = 200_000),
        STANDARD("standard", helpers = 3, sourcesPerHelper = 5, rechecks = 4, subQuestions = 3..3, defaultTokens = 500_000),
        THOROUGH("thorough", helpers = 3, sourcesPerHelper = 10, rechecks = 8, subQuestions = 3..6, defaultTokens = 1_000_000),
        ;

        /** Sources the whole run aims to read. */
        val sources: Int get() = helpers * sourcesPerHelper

        /**
         * What a run usually uses, in budget tokens ([TaskBudget.budgetTokens]: cache reads at a tenth).
         * Each helper: a base for its prompt, searches and report, plus a share per page it reads; the main
         * agent: a base for planning and the report, plus a share per source it re-reads.
         */
        val estimateTokens: Long get() = helpers * (HELPER_BASE + sourcesPerHelper * PER_SOURCE) + LEAD_BASE + rechecks * PER_RECHECK

        val defaultBudget: TaskBudget get() = TaskBudget(tokens = defaultTokens)

        companion object {
            val DEFAULT = STANDARD
            fun of(key: String?): Depth = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: DEFAULT
        }
    }

    private const val HELPER_BASE = 15_000L
    private const val PER_SOURCE = 12_000L
    private const val LEAD_BASE = 50_000L
    private const val PER_RECHECK = 10_000L

    /** The tools a research run uses on its own; anything else that changes something asks first. Looking is always allowed. */
    val TOOLS = listOf(
        "web_search", "web_read", "fetch_url", "wikipedia",
        SubagentTools.SPAWN, SubagentTools.WAIT, SubagentTools.STATUS, SubagentTools.SEND,
        AgentControlTools.TODO_WRITE, "write_file", "send_file",
    )

    /**
     * The workflow as Library and the picker list it. Its steps here are the phases, for reading; a run
     * gets steps worded for its depth ([render]).
     */
    val workflow: Workflow = Workflow(
        id = ID,
        name = NAME,
        description = "Searches several sources, checks the key claims and writes a cited report.",
        goal = "{$QUESTION}",
        parameters = listOf(
            WorkflowParam(QUESTION, label = "Question", type = WorkflowParamType.TEXT, required = true),
            WorkflowParam(SCOPE, label = "Scope notes", type = WorkflowParamType.TEXT, required = false,
                description = "Time range, places, sources to prefer or avoid."),
            WorkflowParam(DEPTH, label = "Depth", type = WorkflowParamType.CHOICE, default = Depth.DEFAULT.key, required = true,
                options = Depth.entries.map { it.key }),
        ),
        steps = listOf(
            "Plan: break the question into sub-questions",
            "Research: helpers search and read sources",
            "Verify: check each key claim against its source",
            "Report: write a cited report with unverified claims marked",
        ),
        tools = TOOLS,
        checks = listOf(
            WorkflowCheck(WorkflowCheck.CheckKind.RESEARCH_REPORT, "The report has citations, sources and an unverified section"),
        ),
        expectedOutput = "A cited Markdown report in the workspace and a short summary.",
    )

    fun isResearch(workflow: Workflow): Boolean = workflow.id == ID

    /** `research/<slug>-<date>.md`: a few ASCII words of the question, else "report". */
    fun reportPath(question: String, date: LocalDate): String = "$FOLDER/${slug(question)}-$date.md"

    fun slug(question: String): String {
        val ascii = Normalizer.normalize(question, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
        val words = Regex("[a-z0-9]+").findAll(ascii).map { it.value }.filter { it !in STOP_WORDS }.take(6).toList()
        return words.joinToString("-").take(48).trimEnd('-').ifEmpty { "report" }
    }

    private val STOP_WORDS = setOf("a", "an", "the", "of", "in", "on", "for", "to", "and", "or", "is", "are", "what", "how", "why", "does", "do", "which", "with")

    /**
     * Fills in a run: checks the inputs as any workflow's ([WorkflowRender.inputs]), words the steps for
     * the chosen depth, and carries the [budget] (else the depth's default) and what to do when it runs
     * out. The visible message is the question, with the scope notes under it: web addresses in either
     * count as hosts the user named, so pages there open without asking in a tainted run.
     */
    fun render(values: Map<String, String>, budget: TaskBudget? = null, date: LocalDate = LocalDate.now(), projectId: String? = null): RenderedWorkflow {
        val inputs = WorkflowRender.inputs(workflow.parameters, values)
        val question = inputs[QUESTION].orEmpty()
        val scope = inputs[SCOPE].orEmpty()
        val depth = Depth.of(inputs[DEPTH])
        val path = reportPath(question, date)
        val text = if (scope.isBlank()) question else "$question\n\n$scope"
        val plan = WorkflowPlan(
            id = ID,
            name = NAME,
            goal = text,
            inputs = inputs + mapOf(DEPTH to depth.key, "report_path" to path, "date" to date.toString()),
            steps = steps(depth, path, date),
            tools = TOOLS,
            approvals = emptyList(),
            checks = listOf(
                WorkflowCheck(WorkflowCheck.CheckKind.RESEARCH_REPORT, "The report has citations, sources and an unverified section", path),
                WorkflowCheck(WorkflowCheck.CheckKind.AGENT, "Every key claim was checked against its source"),
            ),
            expectedOutput = "the report at $path, sent with send_file, and a summary of what it found in a few sentences",
            project = projectId,
            // The user's choice, "no budget" included ([TaskBudget.OFF]); none chosen: the depth's default.
            budget = budget ?: depth.defaultBudget,
            wrapUp = wrapUp(path),
        )
        val note = WorkflowRender.note(plan)
        if (WorkflowRender.planInNote(note) != plan) throw WorkflowInputException("", "This research run couldn't be prepared from these values. Check the inputs for unusual text.")
        return RenderedWorkflow(text = text, note = note, plan = plan)
    }

    /** The plan's steps, for the model: what each phase does at this depth. */
    fun steps(depth: Depth, path: String, date: LocalDate): List<String> {
        val helpers = depth.helpers
        val subs = depth.subQuestions.let { if (it.first == it.last) "${it.first}" else "${it.first} to ${it.last}" }
        val spawn = if (helpers == 1) {
            "Start one researcher helper (agent_spawn, type researcher) for all the sub-questions"
        } else {
            "Start $helpers researcher helpers at once (agent_spawn, type researcher, background true), splitting the sub-questions between them with no overlap, then collect them with agent_wait"
        }
        return listOf(
            "Plan: break the question into $subs sub-questions that together answer it, within the scope notes (inputs.scope). " +
                "Put them on your checklist with todo_write, one item per sub-question, then \"Verify key claims\" and \"Write the report\". Keep the checklist current as you go.",
            "Research: $spawn. Start no more than $helpers helper${if (helpers == 1) "" else "s"} for this run. Each helper's task must carry its sub-questions, the scope notes and today's date ($date), " +
                "and ask it to search broadly first and then narrow, read about ${depth.sourcesPerHelper} good sources (primary and recent first; note each one's date), " +
                "and report its findings as a numbered list: the claim, the source's title, its URL, and a short exact quote from the page that supports it; " +
                "then where sources disagree, and what it couldn't find. Pages on sites the user didn't name ask the user before they open; prefer fewer, better sites.",
            "Verify: do this yourself, not with a helper. For each key claim, check that its quote really says it and whether other sources agree. " +
                "Where a claim matters and rests on one source, read that source again with fetch_url (at most ${depth.rechecks} re-reads). " +
                "A claim you can't confirm stays in the report, marked unverified with why (no quote, the page didn't load, sources conflict); never drop it silently.",
            "Report: write the report with write_file to $path, in the question's language, as Markdown: a \"# \" title; a line with today's date ($date); " +
                "\"## Summary\" (3 to 6 sentences); one \"## \" section per sub-question, with an inline citation like [1] or [2, 3] after every claim taken from a source; " +
                "\"## Unverified claims\" as a list, each with why it couldn't be confirmed (or \"- None.\"); and \"## Sources\" as a numbered list, " +
                "\"1. Title — URL (accessed $date)\", numbered as cited. Translate those headings if the report isn't in English. " +
                "Then send it with send_file, with a note giving how many sources it cites and how many claims are unverified, and answer with the summary. " +
                "If write_file is refused (a scheduled run can't write files), put the whole report in your answer instead.",
        )
    }

    /** What the run is told when its budget stops it ([dev.locus.core.agent.AgentConfig.budgetWrapUp]). */
    fun wrapUp(path: String): String =
        "This research task has reached its budget. Stop researching now: start no helpers, run no searches and read no more pages. " +
            "Write the report to $path from what you already have, in the plan's format, with a \"## What's missing\" section before \"## Sources\" " +
            "listing the sub-questions and claims you didn't get to, then send it with send_file and answer with the summary."

    /** The helper limits a run's depth must stay within: the engine's own defaults. */
    val limits: SubagentLimits get() = SubagentLimits()
}
