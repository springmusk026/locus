package dev.locus.bench

import dev.locus.core.agent.AgentConfig
import dev.locus.core.agent.AgentEvent
import dev.locus.core.agent.AgentLoop
import dev.locus.core.agent.ApprovalGate
import dev.locus.core.agent.CompactionSetup
import dev.locus.core.agent.Outcome
import dev.locus.core.agent.RunBudget
import dev.locus.core.agent.tools.QuestionAnswer
import dev.locus.core.llm.AnthropicChatModel
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ApprovalDecision
import dev.locus.core.model.HelperPrompts
import dev.locus.core.model.ModelPrice
import dev.locus.core.model.Part
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.Role
import dev.locus.core.model.SshAuth
import dev.locus.core.model.SshHost
import dev.locus.core.model.TaskBudget
import dev.locus.core.testing.TestGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Which model the trials talk to: a real provider (key from the environment, held only in the trial's
 * in-memory vault) or the scripted fake ([fake], served by [FakeModel]).
 */
class ModelSetup(
    val provider: ProviderConfig?,
    val apiKey: String?,
    /** USD per million tokens from models.dev, or null when unknown (cost is then reported as unknown). */
    val price: ModelPrice?,
) {
    val fake: Boolean get() = provider == null

    companion object {
        val FAKE = ModelSetup(null, null, null)
    }
}

/**
 * Runs one trial of one task, start to finish, in its own temp folder: a fresh TestGraph (the app's
 * real data layer, tool catalog and model adapters, with an in-memory database), the task's workspace,
 * fixture servers, the real [AgentLoop], the scripted user, then the checks.
 */
class TrialRunner(
    private val model: ModelSetup,
    private val keepFiles: Boolean = false,
    /** Fake model only: replay oracle.json (false: the fake gives up at once, for checking that checks fail). */
    private val oracles: Boolean = true,
    /** Fake model only: a different script than the task's oracle.json (tests). */
    private val oracleOverride: ((TaskSpec) -> Oracle?)? = null,
    /** Changes the scripted user (tests). */
    private val userOverride: ((ScriptedUserSpec) -> ScriptedUserSpec)? = null,
) {

    suspend fun run(task: TaskSpec, trial: Int): TrialResult {
        val trialDir = Files.createTempDirectory("locus-bench-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = System.currentTimeMillis()
        val closeables = mutableListOf<AutoCloseable>()
        val record = Recorder()
        try {
            val oracle = when {
                !model.fake || !oracles -> null
                oracleOverride != null -> oracleOverride.invoke(task)
                else -> Oracle.load(task.oracleFile)
            }
            val user = userOverride?.invoke(task.user) ?: task.user
            var fake: FakeModel? = null
            val web = FixtureWeb(task.sites.associateWith { task.siteDir(it) }) { req -> fake?.handle(req) ?: FixtureWeb.Response(503, "text/plain", "no model") }
            closeables += web

            val graph = TestGraph(scope, root = File(trialDir, "graph"), realEngine = { BenchHttp.engine(web.port) })
            val workspace = graph.workspace.root
            if (task.workspaceDir.isDirectory) copyFixture(task.workspaceDir, workspace)
            val shell = HostShell(workspace, trialDir)
            graph.linux.runOnHost(shell)

            val values = mutableMapOf<String, String>()
            task.sites.forEach { values["site:$it"] = web.base(it) }
            values["port"] = web.port.toString()

            if (task.ssh) {
                val home = File(trialDir, "sshhome/${FixtureSsh.USER}").apply { mkdirs() }
                if (task.remoteDir.isDirectory) copyFixture(task.remoteDir, home)
                val ssh = FixtureSsh(home, trialDir, shell.environment)
                closeables += ssh
                val fingerprint = graph.ssh.probeFingerprint("127.0.0.1", ssh.port)
                graph.settingsRepository.saveSshHost(
                    SshHost("bench", TaskSpec.SSH_ALIAS, "127.0.0.1", ssh.port, FixtureSsh.USER, SshAuth.PASSWORD.id, fingerprint),
                    password = FixtureSsh.PASSWORD, privateKey = null, passphrase = null,
                )
                values["ssh"] = TaskSpec.SSH_ALIAS
                values["ssh_home"] = home.path
            }
            val placeholders = Placeholders(values)
            val prompt = placeholders.fill(task.prompt)

            task.setup.forEach { command ->
                val result = shell.run(command)
                if (result.exitCode != 0) return record.harnessError(task, trial, started, "setup `$command` failed (${result.exitCode}): ${result.output.take(500)}")
            }

            val provider = model.provider ?: FakeModel.provider(web.base(FixtureWeb.LLM_HOST)).also { fake = FakeModel(prompt, oracle, placeholders) }
            graph.settingsRepository.saveProvider(provider, model.apiKey)
            graph.settingsRepository.setDefaultProvider(provider.id)
            // Helpers run on the chat's own model, as in a chat with no helper model chosen.
            graph.settingsRepository.setHelperModel(provider.id, provider.model)
            val settings = graph.settingsRepository.current()

            val conversation = graph.conversations.create(provider.id)
            graph.conversations.appendUserMessage(conversation.id, prompt)

            // The scripted user answers questions as they appear.
            scope.launch {
                graph.questions.desk.pending.collect { open ->
                    open.forEach { q ->
                        val answer = ScriptedUser.answer(user, q.question, q.options)
                        if (graph.questions.desk.answer(q.callId, answer)) record.questions.incrementAndGet()
                    }
                }
            }
            val gate = ApprovalGate { request ->
                record.approvals.incrementAndGet()
                val tainted = ScriptedUser.isTaintPrompt(request.summary)
                if (tainted) record.taintPrompts.incrementAndGet()
                ScriptedUser.approve(user, request.toolName, request.argumentsJson, tainted).also { ok -> if (!ok) record.denied.incrementAndGet() }
            }

            val tools = graph.tools.forChat(conversation).let { all -> all.without(all.all.map { it.spec.name }.filter(::deviceOnly).toSet()) }
            val budget = RunBudget(
                TaskBudget(tokens = task.budget.maxTokens),
                price = { _, _ -> model.price },
                costKnown = model.price != null,
            )
            val serverCompacts = provider.format == ApiFormat.ANTHROPIC_MESSAGES && AnthropicChatModel.compactsOnServer(provider.model)
            val loop = AgentLoop(
                model = graph.access.chatModel(provider),
                tools = tools,
                policyFor = { tool -> graph.tools.policy(tool, settings) },
                gate = gate,
                store = graph.conversations,
                config = AgentConfig(
                    maxSteps = task.budget.maxSteps,
                    providerId = provider.id,
                    model = provider.model,
                    budget = budget,
                    trustedHosts = { graph.tools.userHosts(graph.settingsRepository.current()) },
                    compaction = CompactionSetup(provider.contextWindow.takeUnless { serverCompacts }, HelperPrompts.compaction(settings)),
                ),
                ids = { UUID.randomUUID().toString() },
            )

            val outcome: Outcome? = try {
                withTimeout(task.budget.timeoutMinutes * 60_000L) {
                    var finished: Outcome? = null
                    loop.run(conversation, graph.conversations.history(conversation.id)).collect { event ->
                        when (event) {
                            is AgentEvent.StepStarted -> record.steps = event.step
                            is AgentEvent.MessageAppended -> record.onMessage(event.message)
                            is AgentEvent.ToolFinished -> record.onTool(event.name, event.decision, event.result.isError)
                            is AgentEvent.Finished -> finished = event.outcome
                            else -> Unit
                        }
                    }
                    finished
                }
            } catch (e: TimeoutCancellationException) {
                record.timedOut = true
                null
            }

            if (task.graderDir.isDirectory) copyFixture(task.graderDir, workspace)
            val grading = Grader(
                Grader.Inputs(
                    workspace = workspace,
                    shell = shell,
                    answer = record.lastText,
                    web = web,
                    remoteHome = File(trialDir, "sshhome/${FixtureSsh.USER}"),
                    agentHome = graph.home.dir,
                    toolSuccesses = record.toolSuccesses,
                    questions = record.questions.get(),
                    placeholders = placeholders,
                ),
            ).grade(task.checks)
            return record.result(task, trial, started, outcome, grading, budget)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is TimeoutCancellationException) throw e
            return record.harnessError(task, trial, started, "${e::class.simpleName}: ${e.message}")
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
            scope.cancel()
            if (!keepFiles) trialDir.deleteRecursively()
        }
    }

    /** What one trial did, gathered from the loop's events. */
    private class Recorder {
        var steps = 0
        var timedOut = false
        var lastText = ""
        val questions = AtomicInteger()
        val approvals = AtomicInteger()
        val taintPrompts = AtomicInteger()
        val denied = AtomicInteger()
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        val toolCalls = mutableListOf<Part.ToolCall>()
        val toolSuccesses = mutableMapOf<String, Int>()
        val toolCounts = sortedMapOf<String, Int>()

        fun onMessage(message: dev.locus.core.model.Message) {
            if (message.role != Role.ASSISTANT) return
            message.usage?.let {
                input += it.input
                output += it.output
                cacheRead += it.cacheRead
            }
            toolCalls += message.parts.filterIsInstance<Part.ToolCall>()
            val text = message.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
            if (text.isNotBlank()) lastText = text
        }

        fun onTool(name: String, decision: ApprovalDecision, isError: Boolean) {
            toolCounts.merge(name, 1, Int::plus)
            if (!isError && decision in RAN) toolSuccesses.merge(name, 1, Int::plus)
        }

        fun result(task: TaskSpec, trial: Int, started: Long, outcome: Outcome?, grading: Grading, budget: RunBudget): TrialResult {
            val meter = budget.meter()
            val attempted = task.injection?.let { inj -> toolCalls.any { call -> inj.attempted.any { ScriptedUser.matches(it, call.name, call.argumentsJson) } } }
            val outcomeLabel = when {
                timedOut -> "timeout"
                outcome == null -> "unknown"
                outcome is Outcome.Completed -> "completed"
                outcome is Outcome.StepLimitReached -> "step_limit"
                outcome is Outcome.BudgetStopped -> "token_budget"
                outcome is Outcome.Refused -> "refused"
                outcome is Outcome.Failed -> "model_error"
                else -> outcome::class.simpleName.orEmpty()
            }
            val failure = when {
                grading.passed -> null
                outcome is Outcome.Failed -> "model_error: ${outcome.message.take(300)}"
                outcomeLabel != "completed" -> "$outcomeLabel; ${grading.firstFailure}"
                else -> grading.firstFailure
            }
            return TrialResult(
                task = task.id,
                category = task.category,
                trial = trial,
                passed = grading.passed,
                utility = grading.utility,
                security = grading.security,
                attackAttempted = attempted,
                outcome = outcomeLabel,
                failure = failure,
                steps = steps,
                inputTokens = input,
                outputTokens = output,
                cacheReadTokens = cacheRead,
                budgetTokens = meter.tokens,
                costUsd = meter.costUsd.takeIf { budget.costKnown },
                wallMs = System.currentTimeMillis() - started,
                toolCalls = toolCounts.toMap(),
                approvals = approvals.get(),
                taintPrompts = taintPrompts.get(),
                deniedByUser = denied.get(),
                questionsAsked = questions.get(),
                checks = grading.results,
            )
        }

        fun harnessError(task: TaskSpec, trial: Int, started: Long, message: String) = TrialResult(
            task = task.id, category = task.category, trial = trial, passed = false, utility = false, security = null, attackAttempted = null,
            outcome = "harness_error", failure = "harness_error: $message", steps = steps, inputTokens = input, outputTokens = output,
            cacheReadTokens = cacheRead, budgetTokens = 0, costUsd = null, wallMs = System.currentTimeMillis() - started,
            toolCalls = toolCounts.toMap(), approvals = approvals.get(), taintPrompts = taintPrompts.get(), deniedByUser = denied.get(),
            questionsAsked = questions.get(), checks = emptyList(),
        )
    }

    companion object {
        private val RAN = setOf(ApprovalDecision.AUTO_ALLOWED, ApprovalDecision.USER_APPROVED, ApprovalDecision.REVIEWER_ALLOWED)

        /** Tools that need the phone (WebView, Android intents): left out on the JVM, see the README. */
        fun deviceOnly(name: String) = name.startsWith("browser_") || name.startsWith("device_")

        /** Copies a fixture folder; `.sh` files become executable (git and copies don't keep the bit reliably). */
        fun copyFixture(from: File, to: File) {
            from.copyRecursively(to, overwrite = true)
            to.walkTopDown().filter { it.isFile && it.extension == "sh" }.forEach { it.setExecutable(true) }
        }
    }
}

/** The scripted user's decisions (see [ScriptedUserSpec]). */
object ScriptedUser {
    private const val TAINT_MARK = "Tainted run:"

    fun isTaintPrompt(summary: String) = TAINT_MARK in summary

    fun approve(spec: ScriptedUserSpec, tool: String, argumentsJson: String, tainted: Boolean): Boolean =
        !spec.attentive || !tainted || spec.expected.any { matches(it, tool, argumentsJson) }

    fun answer(spec: ScriptedUserSpec, question: String, options: List<String>): QuestionAnswer {
        val rule = spec.answers.firstOrNull { question.contains(it.match, ignoreCase = true) }
        if (rule != null) {
            val option = options.firstOrNull { it.equals(rule.answer, ignoreCase = true) }
            return if (option != null) QuestionAnswer(chosen = listOf(option)) else QuestionAnswer(text = rule.answer)
        }
        return options.firstOrNull()?.let { QuestionAnswer(chosen = listOf(it)) } ?: QuestionAnswer(text = "Use your judgement.")
    }

    fun matches(pattern: CallPattern, tool: String, argumentsJson: String): Boolean =
        (pattern.tool == null || pattern.tool == tool) && pattern.argsContain.all { argumentsJson.contains(it, ignoreCase = true) }
}
