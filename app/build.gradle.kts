plugins {
    alias(libs.plugins.locus.android.application)
    alias(libs.plugins.locus.android.compose)
    alias(libs.plugins.locus.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.locus"

    defaultConfig {
        applicationId = "dev.locus"
        // CI stamps release builds from the tag (see .github/workflows/release.yml).
        versionCode = System.getenv("LOCUS_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("LOCUS_VERSION_NAME") ?: "0.1.0"
    }

    // Release CI signs with Locus's own key, from repository secrets, so each published APK installs
    // as an update over the last. Local builds keep the machine's debug key.
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
            if (ciKeystore != null) signingConfig = signingConfigs.getByName("locus")
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
