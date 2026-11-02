plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.onboarding"
}

dependencies {
    implementation(projects.core.ui)
    implementation(libs.androidx.activity.compose)
}
