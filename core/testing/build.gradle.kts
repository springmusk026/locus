plugins {
    alias(libs.plugins.locus.android.library)
}

android {
    namespace = "dev.locus.core.testing"
}

/**
 * Test doubles shared by unit tests (testImplementation only, never shipped): an in-memory database
 * and DataStore, a Context that only answers for directories, a Main dispatcher rule, and TestGraph,
 * which wires the real repositories and runners on top of them.
 */
dependencies {
    api(projects.core.data)
    api(projects.core.database)
    api(projects.core.datastore)
    api(libs.junit)
    api(libs.kotlin.test)
    api(libs.kotlinx.coroutines.test)
    api(libs.ktor.client.mock)
    implementation(libs.javax.inject)
    implementation(libs.androidx.lifecycle.viewModel)
}
