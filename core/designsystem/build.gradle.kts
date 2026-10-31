plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
}

android {
    namespace = "dev.locus.core.designsystem"
}

dependencies {
    implementation(projects.core.model)
    implementation(libs.androidx.activity.compose)
}
