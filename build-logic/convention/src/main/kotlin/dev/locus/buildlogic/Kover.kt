package dev.locus.buildlogic

import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

/**
 * Name of the Kover report variant that holds every module's JVM unit tests: Android modules
 * contribute their `debug` build type, pure-Kotlin modules their `jvm` target. The root project
 * merges them, so `./gradlew :koverHtmlReportUnit` gives one report without running release tests.
 */
const val KOVER_VARIANT = "unit"

/**
 * Generated code that tests can't meaningfully reach (DI factories, Room implementations,
 * Compose singletons). Wildcards per the Kover filter DSL: `*` and `?`.
 */
val KOVER_GENERATED_CLASSES = listOf(
    "*_Factory",
    "*_Factory\$*",
    "*_MembersInjector",
    "*_HiltModules*",
    "*_GeneratedInjector",
    "*_ComponentTreeDeps",
    "*_HiltComponents*",
    "Hilt_*",
    "*.Hilt_*",
    "hilt_aggregated_deps.*",
    "dagger.hilt.internal.aggregatedroot.codegen.*",
    "*_Impl",
    "*_Impl\$*",
    "*ComposableSingletons*",
    "*.BuildConfig",
)

/** Applies Kover to a module and registers its share of the merged [KOVER_VARIANT]. */
internal fun Project.configureKover(androidVariant: String?) {
    apply(plugin = "org.jetbrains.kotlinx.kover")
    extensions.configure<KoverProjectExtension> {
        currentProject {
            createVariant(KOVER_VARIANT) {
                add(androidVariant ?: "jvm")
            }
        }
        koverFilters()
    }
}

internal fun KoverProjectExtension.koverFilters() {
    reports {
        filters {
            excludes {
                classes(KOVER_GENERATED_CLASSES)
                annotatedBy("androidx.compose.ui.tooling.preview.Preview")
            }
        }
    }
}
