plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.settings"
}

dependencies {
    implementation(projects.core.llm)
    implementation(projects.core.ui)
    // Release notes in About's update card, with the app's Markdown renderer.
    implementation(projects.core.markdown)
    implementation(libs.androidx.activity.compose)
}
