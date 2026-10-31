plugins {
    alias(libs.plugins.locus.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.llm)
    implementation(libs.jsoup)
    // Jakarta Mail (Eclipse Angus) for the email integration; R8 rules ship in src/main/resources/META-INF/proguard.
    implementation(libs.angus.mail)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.greenmail)
}
