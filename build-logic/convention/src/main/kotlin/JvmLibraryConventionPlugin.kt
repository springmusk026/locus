import dev.locus.buildlogic.configureKotlinJvm
import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies

/** Pure-Kotlin modules (model, llm, agent): no Android types, unit-testable on the JVM. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        dependencies {
            "testImplementation"(libs.lib("kotlin.test"))
            "testImplementation"(libs.lib("junit"))
            "testImplementation"(libs.lib("kotlinx.coroutines.test"))
        }
    }
}
