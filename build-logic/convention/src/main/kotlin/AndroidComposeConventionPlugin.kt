import com.android.build.api.dsl.CommonExtension
import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Apply after an application or library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
        extensions.configure<ComposeCompilerGradlePluginExtension> {
            // Model classes from non-Compose modules that are safe to compare with equals() (docs/design/transcript-reads.md).
            stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
            // `-Plocus.composeReports=true` writes the compiler's stability reports to build/compose-reports.
            if (providers.gradleProperty("locus.composeReports").orNull == "true") {
                reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                metricsDestination.set(layout.buildDirectory.dir("compose-reports"))
            }
        }
        dependencies {
            val bom = platform(libs.lib("androidx.compose.bom"))
            "implementation"(bom)
            "implementation"(libs.lib("androidx.compose.ui"))
            "implementation"(libs.lib("androidx.compose.foundation"))
            "implementation"(libs.lib("androidx.compose.material3"))
            "implementation"(libs.lib("androidx.compose.ui.tooling.preview"))
            "debugImplementation"(libs.lib("androidx.compose.ui.tooling"))
        }
    }
}
