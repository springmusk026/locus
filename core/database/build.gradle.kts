plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.room)
    alias(libs.plugins.locus.hilt)
}

android {
    namespace = "dev.locus.core.database"
}

dependencies {
    api(projects.core.model)
    implementation(libs.kotlinx.coroutines.android)
}
