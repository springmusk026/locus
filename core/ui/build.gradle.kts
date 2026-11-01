plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.android.compose)
}

android {
    namespace = "dev.locus.core.ui"
}

// Sheets and form pieces that more than one feature shows (Settings and onboarding both set up SSH
// machines and Telegram). Unlike :core:designsystem, this module may use :core:data.
dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
}
