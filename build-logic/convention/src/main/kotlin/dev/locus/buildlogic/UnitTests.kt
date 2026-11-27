package dev.locus.buildlogic

import org.gradle.api.Project

/**
 * `./gradlew unitTests` runs every module's JVM unit tests: an Android module's debug unit tests
 * (`testDebugUnitTest`, never the release ones), a pure-Kotlin module's `test`. Each convention plugin
 * registers it, so a new module is covered as soon as it applies one, and CI and the release workflow
 * run this name instead of a hand-written list of modules (docs/TESTING.md). The root project has no
 * task of its own: Gradle runs the task of that name in every module.
 */
const val UNIT_TESTS_TASK = "unitTests"

internal fun Project.registerUnitTests(testTask: String) {
    tasks.register(UNIT_TESTS_TASK) {
        group = "verification"
        description = "Runs this module's JVM unit tests ($testTask)."
        dependsOn(testTask)
    }
}
