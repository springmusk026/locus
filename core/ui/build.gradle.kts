plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
    alias(libs.plugins.locus.android.screenshots)
}

android {
    namespace = "dev.locus.core.ui"
}

// Sheets and form pieces that more than one feature shows (Settings and onboarding both set up SSH
// machines and Telegram). Unlike :core:designsystem, this module may use :core:data.
dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    // The credential prompt below Android 9 is an activity started for a result (DeviceAuth.kt).
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    // The email sheet's screenshots build real test results from Jakarta Mail's exceptions.
    testImplementation(libs.angus.mail)
}
