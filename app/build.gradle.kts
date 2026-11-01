import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.FilterConfiguration

plugins {
    alias(libs.plugins.locus.android.application)
    alias(libs.plugins.locus.android.compose)
    alias(libs.plugins.locus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// The ABIs Locus ships, each with the digit its version code ends in (see androidComponents below).
// Only 64-bit: proot and LiteRT-LM ship arm64-v8a and x86_64 libraries only, so Locus can't run on a
// 32-bit device. Digits 1 and 2 are kept for armeabi-v7a and x86 should they ever ship.
val abiDigits = mapOf("arm64-v8a" to 3, "x86_64" to 4)

android {
    namespace = "dev.locus"

    defaultConfig {
        applicationId = "dev.locus"
        // The canonical version, read from source by every build (F-Droid's included). Bump both
        // before tagging; the release workflow fails if the tag isn't v<versionName>. See docs/RELEASING.md.
        // This is the base code: each APK gets versionCode * 10 + its ABI's digit (abiDigits above).
        versionCode = 4
        versionName = "0.1.3"
        // The scheme the browser sign-in page returns to (LoopbackRedirect.returnUri); debug has its own.
        manifestPlaceholders["returnScheme"] = "locus"
    }

    // One APK per ABI (https://developer.android.com/build/configure-apk-splits), only for abiDigits'
    // ABIs. Leaving out armeabi-v7a and x86 also drops the 32-bit SQLite, DataStore and graphics
    // libraries that dependencies bring. No universal APK; docs/RELEASING.md says why.
    // `-Plocus.abi=arm64-v8a` builds just that ABI's APK (F-Droid's recipe builds one per build entry).
    splits {
        abi {
            isEnable = true
            reset()
            val abis = providers.gradleProperty("locus.abi").orNull?.split(',')?.map(String::trim) ?: abiDigits.keys
            require(abiDigits.keys.containsAll(abis)) { "locus.abi must be among ${abiDigits.keys}, was $abis" }
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }

    // The dependency-info block is a Google-signed blob only Google Play can read; F-Droid's
    // scanner flags it and it gets in the way of reproducible builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release CI signs the release build with Locus's own key, from repository secrets, so each
    // published APK installs as an update over the last. Debug builds never use it: a debuggable APK
    // with the release signature would install over Locus and let anyone with adb read its data.
    val ciKeystore = System.getenv("LOCUS_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
    if (ciKeystore != null) {
        signingConfigs.create("locus") {
            storeFile = ciKeystore
            storePassword = System.getenv("LOCUS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("LOCUS_KEY_ALIAS")
            keyPassword = System.getenv("LOCUS_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        debug {
            // A separate app, "Locus Debug" (src/debug/res), that never installs over the real one or
            // shares its data. Provider authorities use ${applicationId}, so they follow the suffix.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["returnScheme"] = "locus-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release CI signs with Locus's key; anyone else's local release build uses their debug key.
            signingConfig = if (ciKeystore != null) signingConfigs.getByName("locus") else signingConfigs.named("debug").get()
        }
    }

    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
            // Jakarta Mail, Jakarta Activation and jakarta.inject all ship these; Angus Mail's Android notes say pick one.
            pickFirsts.add("META-INF/LICENSE.md")
            pickFirsts.add("META-INF/NOTICE.md")
        }
        // The Linux runtime ships executables named lib*.so; they must be extracted to the native
        // library directory at install time, the only place Android lets an app run files from.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Per-ABI version codes: versionCode * 10 + the ABI's digit, so 0.1.4 (base 5) ships 53 (arm64-v8a)
// and 54 (x86_64). The version decides first, so a newer release always outranks an older one whatever
// the ABI; within a release, x86_64 outranks arm64-v8a, so a store picks the native APK on x86_64
// devices that can also translate ARM. F-Droid's recipe mirrors this with VercodeOperation
// `10 * %c + 3` and `10 * %c + 4` (docs/FDROID.md); the release workflow names the APKs by ABI.
androidComponents {
    onVariants { variant ->
        val base = checkNotNull(android.defaultConfig.versionCode)
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val digit = checkNotNull(abiDigits[abi]) { "No version code digit for ABI $abi" }
            output.versionCode.set(base * 10 + digit)
        }
        // A minified build must keep the classes Locus finds by name at run time (r8-kept-names.txt);
        // R8 renaming or removing one only fails on a phone (SSH broke this way), so it fails the build here.
        if (variant.isMinifyEnabled) {
            val cap = variant.name.replaceFirstChar(Char::uppercase)
            val verify = tasks.register<VerifyR8KeptNames>("verify${cap}R8KeptNames") {
                mapping.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                names.set(layout.projectDirectory.file("r8-kept-names.txt"))
                report.set(layout.buildDirectory.file("reports/r8/${variant.name}-kept-names.txt"))
            }
            tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(verify) }
        }
    }
}

/**
 * Fails when a class listed in [names] doesn't keep its own name in R8's [mapping] (`name -> name:`),
 * whether R8 renamed, repackaged or removed it.
 */
abstract class VerifyR8KeptNames : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mapping: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val names: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val wanted = names.get().asFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val kept = HashSet<String>()
        mapping.get().asFile.forEachLine { line ->
            if (line.isEmpty() || line[0] == ' ' || line[0] == '#' || !line.endsWith(":")) return@forEachLine
            val from = line.substringBefore(" -> ", "")
            if (from.isNotEmpty() && line.substringAfter(" -> ").removeSuffix(":") == from) kept += from
        }
        val missing = wanted.filterNot { it in kept }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "R8 renamed or removed classes that are looked up by name at run time:\n" +
                    missing.joinToString("\n") { "  $it" } +
                    "\nAdd keep rules for them (see the comments in app/r8-kept-names.txt).",
            )
        }
        report.get().asFile.writeText(wanted.joinToString("\n", postfix = "\n") { "kept $it" })
    }
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.feature.chat)
    implementation(projects.feature.settings)
    implementation(projects.feature.workspace)
    implementation(projects.feature.activity)
    implementation(projects.feature.browser)
    implementation(projects.feature.library)
    implementation(projects.feature.tasks)
    implementation(projects.feature.onboarding)
    implementation(projects.feature.analytics)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtimeCompose)
    implementation(libs.androidx.lifecycle.viewModel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.hilt.lifecycle.viewModelCompose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime)
}
