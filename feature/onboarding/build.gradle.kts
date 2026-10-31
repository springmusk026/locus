plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.onboarding"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
