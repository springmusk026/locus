import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/**
 * Screenshot tests for a Compose module (docs/design/design-system.md, "Review gate"). Apply after the
 * library and compose convention plugins. Tests extend `dev.locus.core.screenshot.ScreenshotTest` and
 * write goldens to `src/test/screenshots`, which are committed:
 *
 * - `./gradlew <module>:testDebugUnitTest -Proborazzi.test.record=true` records them,
 * - `-Proborazzi.test.verify=true` (CI) fails when a render no longer matches,
 * - without either, the tests run but draw nothing.
 */
class AndroidScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        apply(plugin = "io.github.takahirom.roborazzi")
        extensions.getByType<CommonExtension>().testOptions.unitTests.isIncludeAndroidResources = true
        dependencies {
            "testImplementation"(project(":core:screenshot"))
        }
        // tools/render-screens.sh points a review render at another folder (one subfolder per module)
        // and density.
        val module = path.removePrefix(":").replace(':', '-')
        val dir = providers.gradleProperty("locus.screenshots.dir").map { "$it/$module" }.orElse("")
        val density = providers.gradleProperty("locus.screenshots.density").orElse("")
        tasks.withType<Test>().configureEach {
            inputs.property("locusScreenshotsDir", dir)
            inputs.property("locusScreenshotsDensity", density)
            systemProperty("locus.screenshots.dir", dir.get())
            systemProperty("locus.screenshots.density", density.get())
            // Robolectric with native graphics needs more than the default 512m for a few hundred renders.
            maxHeapSize = "2g"
            // Robolectric 4.17 at SDK 36 reaches into FileDescriptor internals (its release notes: "may
            // need jvmFlags on JDK 17+").
            jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED")
        }
    }
}
