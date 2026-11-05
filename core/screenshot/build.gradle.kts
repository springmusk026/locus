plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
}

android {
    namespace = "dev.locus.core.screenshot"
}

/**
 * The screenshot-test harness (docs/design/design-system.md, "Review gate"): `snap(name) { … }` renders
 * a composable at 360 and 411dp, light and dark, and at 200% font, Roborazzi draws it into the calling
 * module's `build/screenshots`, and the render guard checks it. Nothing is committed. Test-only: modules get
 * it as a testImplementation through the `locus.android.screenshots` convention plugin, never shipped.
 */
dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    api(libs.junit)
    api(libs.robolectric)
    api(libs.roborazzi)
    api(libs.roborazzi.compose)
    api(libs.androidx.compose.ui.test.junit4)
    api(libs.androidx.test.core)
    implementation(libs.androidx.activity.compose)
    // HiltHostActivity: a host for routes that call hiltViewModel().
    implementation(libs.hilt.android)
}
