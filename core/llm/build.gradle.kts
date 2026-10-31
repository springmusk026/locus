plugins {
    alias(libs.plugins.locus.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.ktor.client.core)
    testImplementation(libs.ktor.client.mock)
}
