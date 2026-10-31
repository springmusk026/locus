import dev.locus.buildlogic.KOVER_VARIANT
import dev.locus.buildlogic.koverFilters
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * Root project only: merges every module's Kover data into one report.
 * `./gradlew :koverHtmlReportUnit` writes build/reports/kover/htmlUnit/index.html.
 */
class KoverAggregationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        check(this == rootProject) { "locus.kover.aggregation belongs on the root project" }
        apply(plugin = "org.jetbrains.kotlinx.kover")
        dependencies {
            subprojects.filter { it.buildFile.exists() }.forEach { "kover"(it) }
        }
        extensions.configure<KoverProjectExtension> {
            currentProject {
                // The root has no sources; an empty variant of the same name collects the modules'.
                createVariant(KOVER_VARIANT) {}
            }
            koverFilters()
        }
    }
}
