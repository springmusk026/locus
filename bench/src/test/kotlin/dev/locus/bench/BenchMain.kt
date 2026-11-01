package dev.locus.bench

import dev.locus.core.llm.ModelCatalogIndex
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.URI
import java.time.Instant

/**
 * The benchmark entry point, run by `./gradlew :bench:bench` (it is skipped in ordinary test runs).
 * See docs/benchmarks/README.md for the properties and what is measured.
 */
class BenchMain {
    @Test
    fun run() {
        assumeTrue("Run with ./gradlew :bench:bench", System.getProperty("bench.run") == "true")
        val config = BenchConfig.fromSystem()
        val report = Bench(config).run()
        println(MarkdownReport.render(report))
    }
}

data class BenchConfig(
    val tasksDir: File,
    val outDir: File,
    val provider: String,
    val model: String?,
    val baseUrl: String?,
    val suites: String?,
    val k: Int,
    val label: String,
    /** Stops starting new trials once the run has cost this much (USD, when prices are known). */
    val maxCostUsd: Double?,
    val apiKey: String?,
) {
    companion object {
        const val KEY_ENV = "LOCUS_BENCH_API_KEY"

        fun fromSystem() = BenchConfig(
            tasksDir = File(System.getProperty("bench.tasks") ?: "tasks"),
            outDir = File(System.getProperty("bench.out") ?: "build/results"),
            provider = System.getProperty("bench.provider") ?: "fake",
            model = System.getProperty("bench.model"),
            baseUrl = System.getProperty("bench.baseUrl"),
            suites = System.getProperty("bench.suites"),
            k = System.getProperty("bench.k")?.toIntOrNull() ?: 3,
            label = System.getProperty("bench.label") ?: "",
            maxCostUsd = System.getProperty("bench.maxCostUsd")?.toDoubleOrNull(),
            apiKey = System.getenv(KEY_ENV)?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}

/** Runs the selected tasks k times each and writes `results.json` and `results.md` to the output folder. */
class Bench(private val config: BenchConfig, private val log: (String) -> Unit = ::println) {
    fun run(): RunReport {
        require(config.k >= 1) { "bench.k must be at least 1" }
        val tasks = TaskLoader.select(TaskLoader.load(config.tasksDir), config.suites)
        require(tasks.isNotEmpty()) { "No tasks match '${config.suites}' in ${config.tasksDir}" }
        val setup = modelSetup()
        val started = Instant.now()
        val runner = TrialRunner(setup)
        val trials = mutableListOf<TrialResult>()
        var spent = 0.0
        runBlocking {
            for (task in tasks) {
                for (trial in 1..config.k) {
                    if (config.maxCostUsd != null && spent >= config.maxCostUsd) {
                        log("Stopping: spent \$${"%.4f".format(spent)} of the \$${config.maxCostUsd} limit.")
                        break
                    }
                    val result = runner.run(task, trial)
                    spent += result.costUsd ?: 0.0
                    trials += result
                    log(
                        "${if (result.passed) "PASS" else "FAIL"} ${task.id} #$trial  steps=${result.steps} tokens=${result.inputTokens + result.outputTokens}" +
                            " cost=${result.costUsd?.let { "$%.4f".format(it) } ?: "n/a"} ${result.wallMs / 1000}s" + (result.failure?.let { "  ($it)" } ?: ""),
                    )
                }
            }
        }
        val (categories, summaries) = Metrics.summarize(trials, config.k)
        val report = RunReport(
            label = config.label.ifBlank { if (setup.fake) "scripted fake model (harness validation)" else "${setup.provider!!.model}" },
            modelKind = if (setup.fake) "fake" else "real",
            provider = setup.provider?.let { "${it.name} (${it.format})" } ?: "scripted fake (OpenAI Chat Completions adapter)",
            model = setup.provider?.model ?: FakeModel.MODEL,
            k = config.k,
            startedAt = started.toString(),
            wallSeconds = (System.currentTimeMillis() - started.toEpochMilli()) / 1000.0,
            locusCommit = System.getenv("GITHUB_SHA") ?: gitCommit(),
            host = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, Java ${System.getProperty("java.version")}",
            deviations = DEVIATIONS,
            categories = categories,
            tasks = summaries,
            trials = trials,
        )
        config.outDir.mkdirs()
        File(config.outDir, "results.json").writeText(BenchJson.encodeToString(RunReport.serializer(), report))
        File(config.outDir, "results.md").writeText(MarkdownReport.render(report))
        log("Wrote ${File(config.outDir, "results.json").path} and results.md")
        return report
    }

    private fun modelSetup(): ModelSetup {
        if (config.provider == "fake") return ModelSetup.FAKE
        val model = config.model ?: error("Set -Pbench.model=<model id>")
        val key = config.apiKey ?: error("Set the ${BenchConfig.KEY_ENV} environment variable to the provider's API key")
        val (name, format, base) = PROVIDERS[config.provider] ?: error("Unknown bench.provider '${config.provider}'; use one of ${PROVIDERS.keys + "fake"}")
        val provider = ProviderConfig("bench-${config.provider}", name, format, config.baseUrl ?: base, model, maxOutputTokens = 16_000)
        val price = runCatching { pricesIndex().lookup(provider.baseUrl, provider.format, model)?.price }.getOrNull()
        if (price == null) log("No models.dev price for $model: costs will be reported as n/a.")
        // Context window from models.dev, so compaction works as in the app.
        val window = runCatching { pricesIndex().lookup(provider.baseUrl, provider.format, model)?.context }.getOrNull()
        return ModelSetup(provider.copy(contextWindow = window), key, price)
    }

    private val index: ModelCatalogIndex by lazy {
        URI(MODELS_DEV).toURL().openStream().use { ModelCatalogIndex.parseModelsDev(it) }
    }

    private fun pricesIndex() = index

    private fun gitCommit(): String? = runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD").directory(config.tasksDir).start().inputStream.bufferedReader().readText().trim().ifEmpty { null }
    }.getOrNull()

    companion object {
        const val MODELS_DEV = "https://models.dev/api.json"

        val PROVIDERS: Map<String, Triple<String, ApiFormat, String>> = mapOf(
            "anthropic" to Triple("Anthropic", ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com"),
            "openai" to Triple("OpenAI", ApiFormat.OPENAI_RESPONSES, "https://api.openai.com/v1"),
            "openai-chat" to Triple("OpenAI (Chat Completions)", ApiFormat.OPENAI_CHAT, "https://api.openai.com/v1"),
            "openrouter" to Triple("OpenRouter", ApiFormat.OPENAI_CHAT, "https://openrouter.ai/api/v1"),
            "gemini" to Triple("Google Gemini", ApiFormat.OPENAI_CHAT, "https://generativelanguage.googleapis.com/v1beta/openai"),
            "deepseek" to Triple("DeepSeek", ApiFormat.OPENAI_CHAT, "https://api.deepseek.com/v1"),
        )

        /** Printed with every report: what the JVM run does differently from Locus on a phone. */
        val DEVIATIONS = listOf(
            "Runs on the JVM over the app's real data layer (TestGraph: in-memory database and DataStores), not on a phone.",
            "Linux tools (run_command, git_inspect, git_commit, services) run in the host's /bin/sh with /workspace mapped to the trial folder, not in Alpine under proot; `apk add` only succeeds for preinstalled tools.",
            "browser_* (WebView) and device_* (Android intents) tools are left out; the web suite measures fetch_url against local fixture sites.",
            "The SSH server is Apache MINA SSHD in process, running commands in the host shell.",
            "Approvals and ask_user are answered by a scripted user; injection tasks use an attentive user who approves taint-flagged calls only when they match the task.",
            "No safety reviewer, no plan mode, no per-chat restrictions: Settings defaults otherwise.",
        )
    }
}
