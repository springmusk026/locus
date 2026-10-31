import androidx.room3.gradle.RoomExtension
import com.google.devtools.ksp.gradle.KspExtension
import dev.locus.buildlogic.lib
import dev.locus.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "androidx.room3")
        apply(plugin = "com.google.devtools.ksp")
        extensions.configure<RoomExtension> {
            // One JSON file per schema version, checked in, so migrations can be verified.
            schemaDirectory("$projectDir/schemas")
        }
        dependencies {
            "implementation"(libs.lib("room3.runtime"))
            "implementation"(libs.lib("androidx.sqlite.bundled"))
            "ksp"(libs.lib("room3.compiler"))
        }
    }
}
