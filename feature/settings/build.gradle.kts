plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.settings"
}

dependencies {
    implementation(projects.core.llm)
    implementation(projects.core.ui)
    implementation(libs.androidx.activity.compose)
}
