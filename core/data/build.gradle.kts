plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.locus.core.data"
}

dependencies {
    api(projects.core.agent)
    api(projects.core.browser)
    api(projects.core.linux)
    api(projects.core.ondevice)
    api(projects.core.model)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.mcp.client)
    implementation(libs.androidx.javascriptengine)
    implementation(libs.androidx.concurrent.futures.ktx)
    // sshlib depends on JVM Tink; the app already ships tink-android (same version, same API).
    implementation(libs.sshlib) { exclude(group = "com.google.crypto.tink", module = "tink") }
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    api(libs.androidx.work.runtime)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(projects.core.testing)
}
