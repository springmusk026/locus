plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.library"
}

dependencies {
    implementation(libs.markdown.renderer.m3)
}
