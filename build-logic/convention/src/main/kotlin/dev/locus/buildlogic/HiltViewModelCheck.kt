package dev.locus.buildlogic

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.io.File
import java.util.zip.ZipFile

/**
 * Fails the build when a `@HiltViewModel` class reaches the APK without its `<Name>_HiltModules`
 * class. Hilt's Gradle aggregation doesn't notice the gap, so such an APK builds fine and then
 * crashes when the screen opens ("Cannot create an instance of class …ViewModel"). It happens when a
 * module's KSP run generates nothing; see [serializeKspTasks].
 *
 * It reads the classes that go into dexing (before R8, so release names are still readable), finds
 * the classes annotated `@HiltViewModel` by their constant pool, and checks the generated class is
 * there too. `package<Variant>` depends on it, so `assemble…`, `install…` and Android Studio's Run
 * all stop before packaging a broken APK.
 */
@CacheableTask
abstract class VerifyHiltViewModelsTask : DefaultTask() {
    @get:Classpath
    abstract val jars: ListProperty<RegularFile>

    @get:Classpath
    abstract val dirs: ListProperty<Directory>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val classes = HashSet<String>()
        val viewModels = sortedSetOf<String>()
        fun visit(name: String, bytes: () -> ByteArray) {
            if (!name.endsWith(".class") || name.startsWith("META-INF/")) return
            val className = name.removeSuffix(".class")
            classes += className
            // Generated Hilt code may mention the annotation; only hand-written classes are candidates.
            if (className.substringAfterLast('/').let { it.startsWith("Hilt_") || "_Hilt" in it }) return
            if (bytes().containsAscii(HILT_VIEW_MODEL)) viewModels += className
        }
        jars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                    visit(entry.name) { zip.getInputStream(entry).use { it.readBytes() } }
                }
            }
        }
        dirs.get().forEach { dir ->
            val root = dir.asFile
            root.walkTopDown().filter(File::isFile).forEach { file ->
                visit(file.relativeTo(root).invariantSeparatorsPath, file::readBytes)
            }
        }

        val missing = viewModels.filter { "${it}_HiltModules" !in classes }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These @HiltViewModel classes have no generated _HiltModules class, so the app would crash " +
                    "when it creates them:\n" + missing.joinToString("\n") { "  " + it.replace('/', '.') } +
                    "\nKSP generated nothing for their module. Rerun it, e.g. " +
                    "./gradlew :feature:<module>:kspDebugKotlin --rerun, and build again.",
            )
        }
        check(viewModels.isNotEmpty()) { "Found no @HiltViewModel classes at all; the check is looking in the wrong place." }
        report.get().asFile.writeText(viewModels.joinToString("\n", postfix = "\n") { it.replace('/', '.') })
    }

    private fun ByteArray.containsAscii(needle: ByteArray): Boolean {
        val last = size - needle.size
        outer@ for (i in 0..last) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    private companion object {
        /** The annotation's descriptor, which an annotated class keeps in its constant pool. */
        val HILT_VIEW_MODEL = "Ldagger/hilt/android/lifecycle/HiltViewModel;".toByteArray(Charsets.US_ASCII)
    }
}

/** Registers `verify<Variant>HiltViewModels` for every app variant and makes packaging depend on it. */
internal fun Project.configureHiltViewModelCheck() {
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val name = variant.name.replaceFirstChar(Char::uppercase)
            val verify = tasks.register<VerifyHiltViewModelsTask>("verify${name}HiltViewModels") {
                group = "verification"
                description = "Checks every @HiltViewModel in the ${variant.name} APK has its generated Hilt module."
                report.set(layout.buildDirectory.file("reports/hiltViewModels/${variant.name}.txt"))
            }
            variant.artifacts.forScope(ScopedArtifacts.Scope.ALL)
                .use(verify)
                .toGet(ScopedArtifact.CLASSES, VerifyHiltViewModelsTask::jars, VerifyHiltViewModelsTask::dirs)
            tasks.matching { it.name == "package$name" }.configureEach { dependsOn(verify) }
        }
    }
}
