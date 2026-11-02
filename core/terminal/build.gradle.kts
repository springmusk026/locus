plugins {
    alias(libs.plugins.locus.android.library)
    alias(libs.plugins.locus.hilt)
}

// The interactive terminal (docs/design/terminal.md): Termux's terminal-emulator and terminal-view
// (Apache-2.0, vendored from termux-app v0.118.3, see NOTICE) plus Locus's session list.
android {
    namespace = "dev.locus.core.terminal"
    // Same NDK as tools/build-proot.sh, CI and the F-Droid recipe: native output depends on it.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // The ABIs Locus ships (abiDigits in app/build.gradle.kts).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            ndkBuild {
                // Termux's flags, plus the checkout path mapped to "." so the library doesn't depend on
                // where the repo is built (F-Droid compares its build with the published APK).
                cFlags += listOf(
                    "-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector",
                    "-ffile-prefix-map=${rootDir.absolutePath}=.",
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    testOptions {
        // Termux's emulator tests reach android.util.Log through its Logger.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(projects.core.linux)
    // Brings androidx.annotation (the vendored view uses @Nullable and @RequiresApi).
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
