plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
}

android {
    namespace = "dev.locus.core.browser"
}

dependencies {
    api(projects.core.agent)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
}
