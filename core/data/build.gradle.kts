plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.locus.core.data"
    defaultConfig {
        // Keep rules for libraries this module brings that ship none (sshlib's JCA provider).
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(projects.core.agent)
    api(projects.core.browser)
    api(projects.core.linux)
    api(projects.core.ondevice)
    api(projects.core.model)
    implementation(projects.core.database)
    // The memory search index's SQLite (the same bundled build Room uses).
    implementation(libs.androidx.sqlite.bundled)
    implementation(projects.core.datastore)
    // Streaming AEAD for encrypted backups (docs/design/backup.md); the same tink-android core/datastore uses.
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore)
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
    // An in-process SSH and SFTP server for remote execution tests, as the benchmark uses (bench/FixtureSsh.kt).
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
}

// Local unit tests run on the desktop JVM, where the Android build of the bundled SQLite has no native
// library to load. Its desktop build (the same version, the same SQLite, with macOS and Linux natives)
// replaces it on the unit-test classpath only, so RestoredDatabaseCheckTest runs the engine the app ships.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.sqlite:sqlite-bundled-android"))
            .using(module("androidx.sqlite:sqlite-bundled-jvm:${libs.versions.sqliteBundled.get()}"))
            .because("unit tests load the desktop SQLite natives")
    }
}
