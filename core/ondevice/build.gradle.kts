plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
}

android {
    namespace = "dev.locus.core.ondevice"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.core.llm)
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
}
