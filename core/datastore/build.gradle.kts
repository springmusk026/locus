plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.locus.core.datastore"
}

dependencies {
    api(projects.core.model)
    api(libs.androidx.datastore)
    implementation(libs.androidx.datastore.tink)
    implementation(libs.tink.android)
    implementation(libs.kotlinx.coroutines.android)
}
