plugins {
    alias(libs.plugins.locus.android.feature)
}

android {
    namespace = "dev.locus.feature.browser"
}

dependencies {
    // BackHandler: system back goes back through the page's history before it leaves the browser.
    implementation(libs.androidx.activity.compose)
}
