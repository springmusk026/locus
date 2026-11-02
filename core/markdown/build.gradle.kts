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
    // Since 1.6.0 the KaTeX fonts are a separate module; without it the renderer falls back to
    // system fonts, which its KaTeX metrics don't match. Loaded automatically, no setup.
    implementation(libs.latex.fonts.katex)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
}
