import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.google.devtools.ksp")
        apply(plugin = "com.google.dagger.hilt.android")
        dependencies {
            "implementation"(libs.lib("hilt.android"))
            "ksp"(libs.lib("hilt.compiler"))
            "ksp"(libs.lib("kotlin.metadata"))
        }
    }
}
