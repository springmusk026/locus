plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.activity"
}

dependencies {
    implementation(libs.androidx.activity.compose)
}
