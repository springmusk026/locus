import com.android.build.api.dsl.CommonExtension
import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/** Apply after an application or library convention plugin. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "org.jetbrains.kotlin.plugin.compose")
        extensions.getByType<CommonExtension>().buildFeatures.compose = true
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
