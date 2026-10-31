plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
}

android {
    namespace = "dev.locus.core.linux"
}

dependencies {
    api(projects.core.agent)
    implementation(libs.kotlinx.coroutines.android)
}
