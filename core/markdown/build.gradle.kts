plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
}

android {
    namespace = "dev.locus.core.markdown"
}

dependencies {
    implementation(projects.core.designsystem)
    // The renderer and its parser (org.jetbrains:markdown) are part of this module's API: callers pass
    // the annotator and inline content it builds to Markdown(...).
    api(libs.markdown.renderer.m3)
    implementation(libs.latex.renderer)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
}
