plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
    alias(libs.plugins.locus.android.screenshots)
}

android {
    namespace = "dev.locus.core.designsystem"
}

dependencies {
    // Plain data only: the task budget editor shows a TaskBudget.
    implementation(projects.core.model)
    implementation(libs.androidx.activity.compose)
}

// DesignGuardTest scans the other modules' sources (docs/design/design-system.md, "Guardrail"), so
// they are inputs of this module's tests: a cached result must not hide a new raw colour elsewhere.
val designGuardRoot: File = rootDir
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(designGuardRoot) {
            include("app/src/main/**/*.kt", "core/*/src/main/**/*.kt", "feature/*/src/main/**/*.kt")
            exclude("core/designsystem/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("designGuardSources")
    inputs.file(layout.projectDirectory.file("design-guard-baseline.txt"))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("designGuardBaseline")
}
