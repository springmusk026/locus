plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.locus.bench"
}

/**
 * The agent benchmark (docs/benchmarks/README.md). Everything lives in the test source set: it runs on
 * the JVM, over the real data layer wired by core:testing's TestGraph, and nothing here ships in the app.
 *
 * `./gradlew :bench:testDebugUnitTest` runs the harness's own tests (graders, metrics, and the runner
 * against a scripted fake model). `./gradlew :bench:bench` runs suites against a real provider; see
 * the README for its properties. It never runs in CI on its own: only the manual Benchmarks workflow
 * calls it, with a key a maintainer provides.
 */
dependencies {
    testImplementation(projects.core.testing)
    testImplementation(projects.core.data)
    testImplementation(libs.ktor.client.okhttp)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
}

val benchTasks = layout.projectDirectory.dir("tasks")

tasks.withType<Test>().configureEach {
    // Fixtures (task folders) are read from the source tree, so changing one re-runs the tests.
    inputs.dir(benchTasks).withPropertyName("benchTasks").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("bench.tasks", benchTasks.asFile.path)
}

/**
 * Runs benchmark suites against a real provider. Properties (all `-P`):
 *   bench.provider  anthropic | openai | openai-chat | openrouter | gemini | fake   (default fake)
 *   bench.model     the model id, e.g. claude-haiku-4-5
 *   bench.baseUrl   overrides the provider's base URL
 *   bench.suites    comma-separated categories or task ids (default: all)
 *   bench.k         trials per task (default 3)
 *   bench.out       output folder (default bench/build/results)
 *   bench.label     free text recorded in the results
 * The API key is read from the LOCUS_BENCH_API_KEY environment variable and never written anywhere.
 */
tasks.register<Test>("bench") {
    group = "verification"
    description = "Runs the agent benchmark suites (docs/benchmarks/README.md)."
    val unit = tasks.named<Test>("testDebugUnitTest")
    testClassesDirs = files(unit.map { it.testClassesDirs })
    classpath = files(unit.map { it.classpath })
    filter { includeTestsMatching("dev.locus.bench.BenchMain") }
    systemProperty("bench.run", "true")
    listOf("bench.provider", "bench.model", "bench.baseUrl", "bench.suites", "bench.k", "bench.label", "bench.maxCostUsd").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    systemProperty("bench.out", providers.gradleProperty("bench.out").getOrElse(layout.buildDirectory.dir("results").get().asFile.path))
    // A benchmark is never up to date: every run is new data.
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
        events("failed")
    }
    // Real runs take long; the agent's own limits stop each trial.
    maxHeapSize = "2g"
}
