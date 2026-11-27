package dev.locus.buildlogic

import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.withType

/**
 * Applies detekt to a module (docs/design/ci.md, "Static analysis"). `./gradlew detekt` runs it in
 * every module without type resolution: detekt's default rules plus `config/detekt/detekt.yml` and
 * the Compose rules. Each module has its own baseline, `detekt-baseline.xml`, which lists the
 * findings that were already there, so only new code has to pass; `./gradlew detektBaseline`
 * rewrites it. Generated code is never analysed: only the source directories below are.
 */
internal fun Project.configureDetekt() {
    apply(plugin = "dev.detekt")
    extensions.configure<DetektExtension> {
        // The plain task's default is main and test only; the app's debug and androidTest sources too.
        source.setFrom(
            "src/main/java",
            "src/main/kotlin",
            "src/debug/kotlin",
            "src/test/kotlin",
            "src/androidTest/kotlin",
        )
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig.set(true)
        baseline.set(file("detekt-baseline.xml"))
        basePath.set(rootProject.layout.projectDirectory)
        parallel.set(true)
    }
    tasks.withType<Detekt>().configureEach {
        reports {
            checkstyle.required.set(false)
        }
    }
    dependencies {
        "detektPlugins"(libs.lib("compose.rules.detekt"))
    }
}
