plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.library"
}

dependencies {
    implementation(projects.core.ui)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.activity.compose)
}
