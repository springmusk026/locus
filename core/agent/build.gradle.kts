plugins {
    alias(libs.plugins.locus.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.llm)
    implementation(libs.jsoup)
    // Jakarta Mail (Eclipse Angus) for the email integration; R8 rules ship in src/main/resources/META-INF/proguard.
    implementation(libs.angus.mail)
    // The memory search index (docs/design/memory-search.md): only the driver API here. The app passes
    // the bundled SQLite driver Room already uses, so the same engine (with FTS5) runs on every Android version.
    api(libs.androidx.sqlite)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.greenmail)
    // The same bundled SQLite build with desktop natives, so tests run the engine the app ships.
    testImplementation(libs.androidx.sqlite.bundled)
}
