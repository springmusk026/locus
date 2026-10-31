import com.android.build.api.dsl.LibraryExtension
import dev.locus.buildlogic.configureKover
import dev.locus.buildlogic.configureKotlinAndroid
import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "com.android.library")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
        }
        configureKover(androidVariant = "debug")
        dependencies {
            "testImplementation"(libs.lib("kotlin.test"))
            "testImplementation"(libs.lib("junit"))
        }
    }
}
