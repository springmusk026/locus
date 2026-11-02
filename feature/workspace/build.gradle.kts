plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.workspace"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    // Markdown preview (same renderer as chat, with maths and Mermaid from core:markdown) and syntax highlighting (dev.snipme highlights, via the code module).
    implementation(projects.core.markdown)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.kotlinx.serialization.json)
}
