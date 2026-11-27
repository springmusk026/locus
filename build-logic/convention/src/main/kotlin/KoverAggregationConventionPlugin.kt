import dev.locus.buildlogic.KOVER_MIN_LINE_COVERAGE
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
 * `./gradlew :koverHtmlReportUnit` writes build/reports/kover/htmlUnit/index.html, and
 * `./gradlew :koverVerifyUnit` fails when its line coverage is under [KOVER_MIN_LINE_COVERAGE].
 */
class KoverAggregationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        check(this == rootProject) { "locus.kover.aggregation belongs on the root project" }
        apply(plugin = "org.jetbrains.kotlinx.kover")
        dependencies {
            // :core:testing holds test doubles, not app code.
            subprojects.filter { it.buildFile.exists() && it.path != ":core:testing" }.forEach { "kover"(it) }
        }
        extensions.configure<KoverProjectExtension> {
            currentProject {
                // The root has no sources; an empty variant of the same name collects the modules'.
                createVariant(KOVER_VARIANT) {}
            }
            koverFilters()
            // Only the merged report has a bound: one module's own coverage says little, and modules
            // without tests would fail on their own.
            reports.variant(KOVER_VARIANT) {
                verify {
                    rule("Merged line coverage") {
                        minBound(KOVER_MIN_LINE_COVERAGE)
                    }
                }
            }
        }
    }
}
