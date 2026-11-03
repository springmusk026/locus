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

// Make libtermux.so the same wherever it's built, so F-Droid's rebuild matches the release APK
// (docs/FDROID.md, "Reproducible builds"). The macOS and Linux NDKs compile identical code, but their
// clang and lld write their own version and build path into .comment ("Since NDK r26, the Clang
// version string in the comment section is different when building on MacOS and Linux",
// https://f-droid.org/docs/Reproducible_Builds/), and the debug info names the NDK's install path.
// AGP doesn't strip either, so remove both after ndk-build links the library.
tasks.matching { it.name.startsWith("buildNdkBuild") }.configureEach {
    // Locals only: the configuration cache can't serialise a doLast that reaches into the script.
    val ndk = android.ndkVersion
    val toolchains = androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/$ndk/toolchains/llvm/prebuilt") }
    val libs = outputs.files
    doLast {
        val prebuilt = toolchains.get().asFile
        val objcopy = prebuilt.listFiles().orEmpty().map { it.resolve("bin/llvm-objcopy") }.firstOrNull { it.isFile }
            ?: throw GradleException("llvm-objcopy not found under $prebuilt")
        val built = libs.asFileTree.matching { include("**/libtermux.so") }.files
        if (built.isEmpty()) throw GradleException("$name produced no libtermux.so to clean up")
        built.forEach { lib ->
            val process = ProcessBuilder(objcopy.path, "--strip-debug", "--remove-section=.comment", lib.path)
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() != 0) throw GradleException("llvm-objcopy failed on $lib: $output")
        }
    }
}

dependencies {
    api(projects.core.linux)
    // Brings androidx.annotation (the vendored view uses @Nullable and @RequiresApi).
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
