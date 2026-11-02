import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** A screen-level module: Compose UI + a Hilt ViewModel over :core:data. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "locus.android.library")
        apply(plugin = "locus.android.compose")
        apply(plugin = "locus.hilt")
        dependencies {
            "implementation"(project(":core:data"))
            "implementation"(project(":core:designsystem"))
            "implementation"(libs.lib("androidx.lifecycle.runtimeCompose"))
            "implementation"(libs.lib("androidx.lifecycle.viewModelCompose"))
            "implementation"(libs.lib("androidx.hilt.lifecycle.viewModelCompose"))
            "testImplementation"(libs.lib("kotlinx.coroutines.test"))
            "testImplementation"(libs.lib("turbine"))
            "testImplementation"(project(":core:testing"))
        }
    }
}
