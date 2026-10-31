plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.chat"
}

dependencies {
    implementation(libs.markdown.renderer.m3)
    implementation(libs.markdown.renderer.code)
    implementation(libs.androidx.activity.compose)
}
