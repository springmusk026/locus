# Locus on F-Droid and IzzyOnDroid

Status: **closed as too new; resubmit on or after 2027-01-31.** Locus was submitted on 2026-11-05 as fdroiddata [!55897](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55897) (`New App: dev.locus`, from branch `dev.locus` of [springmusk026/fdroiddata](https://gitlab.com/springmusk026/fdroiddata)). A maintainer closed it the same day: "App is not ready yet, see Policy Quality point 6". That point of the [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/) says "The application should have been actively developed for at least 3 months, unless: The application has been proven to have some users. The author has been maintaining other apps on F-Droid. The application is developed only for an event." The Locus repository was created on 2026-10-31. Nothing was raised about the recipe, the build or the anti-features. 0.1.6 reproduces against its release APKs: `apksigcopier compare` passed for both arm64-v8a and x86_64 on a fresh clone of the tag ([Reproducible builds](#reproducible-builds)), so F-Droid can verify and publish Locus's own signed APKs. The recipe in [`docs/fdroid/`](#fdroiddata-recipe-as-submitted) is the one submitted. Until F-Droid, see [Until then](#until-then-not-started). What's left is in [Submission steps](#submission-steps-owner). Google's developer verification is in [DISTRIBUTION.md](DISTRIBUTION.md).

Sources, read on 2026-10-31 and checked again on 2026-11-03:

- F-Droid: [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/), [Anti-Features](https://f-droid.org/docs/Anti-Features/), [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/), [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/), [All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/), [Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/), [fdroiddata CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md), [config/categories.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/config/categories.yml), the [RFP issue template](https://gitlab.com/fdroid/rfp/-/blob/master/.gitlab/issue_templates/Default.md), fdroidserver's [scanner.py](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/scanner.py) and its [SUSS list](https://fdroid.gitlab.io/fdroid-suss/suss.json), [checkupdates.py](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/checkupdates.py).
- IzzyOnDroid: [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), the repodata [App Inclusion Request template](https://codeberg.org/IzzyOnDroid/repodata/src/branch/main/.forgejo/issue_template/app-inclusion-request.yaml).

## Requirements and where Locus stands

| Requirement (source) | Locus |
|---|---|
| FLOSS app, built with a FLOSS toolchain ([Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/): "All applications in the repository must be Free, Libre and Open Source Software"; "The complete application building process requires a 100% FLOSS toolchain") | AGPL-3.0-or-later; Gradle, AGP, OpenJDK, the NDK. No Play Services, Firebase or analytics: no dependency is in fdroidserver's non-free list (SUSS, checked 2026-11-03). |
| Library binaries from source or a trusted Maven repo, and freely licensed ("Those binaries must still be freely licensed, simply being included in one of those repositories is not enough") | Maven Central and Google Maven only. The native libraries that come from Maven are listed in [Binaries](#binaries-in-the-apk), with their licences. |
| No prebuilt binaries in the source tree ("All binary dependencies … must originate either from source compilation or Debian repository downloads") | The only ones are proot and its loader in `core/linux/src/main/jniLibs`. The recipe deletes them and rebuilds them from pinned sources; see [Binaries](#binaries-in-the-source-tree). |
| No downloading executables without consent ("opt-in … structured in a way that clearly explains to users that they're choosing to bypass F-Droid's checks") | Alpine Linux and its packages come from alpinelinux.org only when the user taps **Download and install Linux** in setup (or Settings → About & privacy → Run setup again). The step says F-Droid hasn't checked them; **Continue without Linux** is one full-width tap; the agent can't start the download. The image's SHA-256 is pinned in `LinuxEnvironment`. The GitHub update check is off by default and its switch says it bypasses store checks ([updates](design/updates.md)). |
| Anti-features disclosed, reasons in the description | `NonFreeNet`, explained at the end of `full_description.txt`; see [Anti-features](#anti-features). |
| Version in source, a tag per release | `versionCode`/`versionName` in `app/build.gradle.kts`; the release workflow fails unless the tag is `v<versionName>` ([RELEASING.md](RELEASING.md)). |
| Fastlane metadata ([All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)) | Complete; see [Fastlane metadata](#fastlane-metadata). |
| Reproducible builds ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)) | **Verified** against the 0.1.6 release APKs: see [Reproducible builds](#reproducible-builds). |

## Binaries

### Binaries in the source tree

`git ls-files` lists exactly these binary-looking files:

| File | What it is | What F-Droid does |
|---|---|---|
| `core/linux/src/main/jniLibs/{arm64-v8a,x86_64}/libproot.so` | proot (GPL-2.0-or-later, [termux/proot](https://github.com/termux/proot) at `4dba3afb…`) with talloc 2.4.3 (LGPL-3.0-or-later) linked in statically; an executable named like a library so Android extracts it | **Would be rejected as a prebuilt.** The recipe's `rm` deletes it and `build` runs `tools/build-proot.sh`, which rebuilds it from the `termux-proot` srclib and the talloc tarball (SHA-256 pinned in the script). |
| `core/linux/src/main/jniLibs/{arm64-v8a,x86_64}/libproot-loader.so` | proot's loader, from the same build | Same: deleted and rebuilt. |
| `core/ondevice/src/main/jniLibs/{arm64-v8a,x86_64}/lib*.so` | llama.cpp (MIT, [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) tag `b12066`, `713319e2…`): `libllama.so`, `libggml*.so` (arm64: seven CPU variants loaded at run time), Locus's JNI bridge `liblocusllama.so` (AGPL, `core/ondevice/src/main/cpp`), and the NDK's `libc++_shared.so` | **Would be rejected as prebuilts.** The recipe's `rm` deletes the folder and `build` runs `tools/build-llama.sh`, which rebuilds them from the `llama-cpp` srclib ([design](design/llamacpp.md#reproducible-and-from-source-on-f-droid)). |
| `gradle/wrapper/gradle-wrapper.jar` | Gradle wrapper | The scanner removes it itself (`scanner.py` handles `gradle-wrapper.jar`, `gradlew`); F-Droid uses its own Gradle. |
| `bench/tasks/multi/organize-inbox/workspace/inbox/archive.tar.gz` | A benchmark fixture: 4 bytes of ASCII text, not an archive | The scanner flags any `*.gz` by name, so the recipe `scanignore`s it. |

`libtalloc.so` is gone: talloc is now linked into `libproot.so` (`tools/build-proot.sh` builds a static `libtalloc.a`), so the APK no longer needs a separately shipped talloc. `LinuxEnvironment.linkSharedLibraries()` still handles an old APK that has one. The terminal's `libtermux.so` isn't checked in: Gradle compiles it from `core/terminal/src/main/jni/termux.c` with ndk-build.

**How the proot recipe works** (`tools/build-proot.sh`): it checks the srclib checkout (`PROOT_SRC`) is at the pinned commit and copies it under `.build-native/`, downloads `talloc-2.4.3.tar.gz` and checks its SHA-256, cross-compiles talloc with waf (cross answers in the script) into a static archive, builds proot and its loader with the NDK's clang for API 26 with 16 KB page alignment, `-ffile-prefix-map` and no 32-bit loader, strips both files, removes `.comment`, and checks the alignment. It needs GNU awk (below), make, python3, curl, git and the NDK.

**The committed files are a Linux build of the script.** The **Native libs** workflow (`.github/workflows/native-libs.yml`) deletes them, runs the script on `ubuntu-latest` with NDK 29.0.14206865 and fails if the result differs from what's committed. It now also runs on every pull request that touches the script, the libraries or the workflow. On 2026-11-03 it ran four times on this branch: the first run (on 0.1.5's tree) showed that the committed `libproot.so` was stale (dynamic talloc); after committing the workflow's output, three runs on two commits rebuilt identical files.

**llama.cpp works the same way** (`tools/build-llama.sh`, docs/design/llamacpp.md): it checks the srclib checkout (`LLAMA_SRC`) is at the pinned commit, or downloads the tag's tarball and checks its SHA-256. It copies the tree without `.git` under `.build-native/`, fixes llama.cpp's build info from the pin instead of git, and configures with CMake and Ninja and the NDK for API 26: shared ggml with `GGML_BACKEND_DL`, all arm64 CPU variants, one SSE4.2 build for x86_64, `-ffile-prefix-map` for the checkout and the NDK, `--build-id=none` and 16 KB pages. Then it strips the libraries, removes `.comment`, and fails on a build ID, a leaked build path or a misaligned segment. It also fails if `GgufArchitectures.kt` no longer matches llama.cpp's architecture list. The **Native libs** workflow's `llama` job rebuilds the libraries on `ubuntu-latest` and compares them with the committed ones, like the `proot` job. On 2026-11-03 a macOS build matched the Linux rebuild byte for byte, after Locus's own git commit was kept out of ggml's build info.

**A bug this found.** proot's Makefile computes `offset_to_pokedata_workaround` (arm64 only) by running `loader/loader-info.awk` over `readelf -s`. That script uses GNU awk's `strtonum()` and `\y`; macOS's awk doesn't fail on them but prints 0. The `libproot.so` shipped up to 0.1.5 was built on a Mac and has offset 0 (0x460 is right), so proot's pokedata workaround would jump to the loader's entry point instead of its stub. The workaround only runs on some arm64 kernels, which may be why nobody saw it. The script now refuses to run without GNU awk and fails if the offset comes out as 0; the recipe installs `gawk`. With gawk, a macOS build of the x86_64 files matched the Linux build byte for byte, so build paths don't leak.

### Binaries in the APK

| Library | From | Licence | Built from source? |
|---|---|---|---|
| `libproot.so`, `libproot-loader.so` | this repo | GPL-2.0-or-later, LGPL-3.0-or-later (talloc) | Yes, by the recipe. |
| `libtermux.so` | this repo (vendored Termux) | Apache-2.0 | Yes, by Gradle (ndk-build). |
| `liblocusllama.so`, `libllama.so`, `libggml*.so`, `libc++_shared.so` (≈14.6 MB arm64 uncompressed) | this repo: llama.cpp `b12066` plus Locus's JNI bridge | MIT (llama.cpp), AGPL-3.0-or-later (bridge), Apache-2.0 with LLVM exception (libc++) | Yes, by the recipe (`tools/build-llama.sh`). |
| `liblitertlm_jni.so` (22.7 MB arm64) | `com.google.ai.edge.litertlm:litertlm-android:0.19.0`, Google Maven | Apache-2.0 (POM and `LICENSE` in the AAR). Its `THIRD_PARTY_NOTICE.txt` lists only free licences (MIT, BSD, zlib, Apache-2.0, Unicode, GPL-3.0 with the Bison exception) | No: Google builds it with its internal Blaze (paths `third_party/odml/litert_lm/…` in the binary). The public [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) repo has the JNI target (`kotlin/java/com/google/ai/edge/litertlm/jni:litertlm_jni`) and its dependencies are public (ML Drift is Apache-2.0 at [google-ai-edge/ml-drift](https://github.com/google-ai-edge/ml-drift); Dawn is BSD-3). The public Bazel build links a prebuilt `libwebgpu_dawn.so` from the repo's `prebuilt/` folder instead of compiling Dawn in, so a from-source build wouldn't be byte-identical to the AAR. |
| `libsqliteJni.so`, `libdatastore_shared_counter.so`, `libandroidx.graphics.path.so` | AndroidX (Google Maven) | Apache-2.0 | No; AndroidX from Google Maven is the usual accepted case. |

The AAR's Java code is only `com/google/ai/edge/litertlm` and depends on Gson, kotlin-reflect and coroutines, nothing from Play. fdroidserver's SUSS list flags `com.google.ai.edge.litert:litert:2…` (it pulls in Play Core) and `com.google.ai.edge.aicore`, but **not** `com.google.ai.edge.litertlm`, so the scanner shouldn't stop on it. It's still the most likely question from a reviewer: a freely licensed binary from a trusted Maven repo is allowed by the policy, but F-Droid prefers source builds. If they won't accept it, see [Fallback](#fallback-a-foss-flavour-without-on-device-models).

## Reproducible builds

Goal: F-Droid publishes **Locus's own signed APK** from the GitHub release, so F-Droid and GitHub installs update over each other. With a `binary` URL per build and `AllowedAPKSigningKeys`, F-Droid builds the APK, copies the signature of the published APK onto its unsigned build, and publishes it only if that verifies ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/): "F-Droid verifies reproducible builds using APK signature copying against upstream build and our rebuild"). v2/v3 signatures cover every byte of the APK, so everything except the signing block must be identical.

### How it was checked (2026-11-03)

Each build ran alone through the local build lock, from a fresh `git clone` in a scratch directory, with `--no-build-cache -Pkotlin.incremental=false`, Gradle 9.8.2, AGP 9.5.0, NDK 29.0.14206865, Android Studio's JBR 21 on macOS (arm64). CI builds use Temurin 21 on `ubuntu-latest`. The comparison is the one signature copying needs: the APK's bytes before the APK Signing Block, the central directory, and the end-of-central-directory record (minus the central directory offset the block shifts) must all be equal. It was done with a small script, since `apksigcopier` and `diffoscope` aren't installed here; entries that differed were unpacked and compared with `aapt2 dump xmltree`, `dexdump -d` and `llvm-readelf`.

| Comparison | Result |
|---|---|
| v0.1.5 release APK (arm64-v8a) vs a local build of the `v0.1.5` tag, both ABIs | Only `lib/arm64-v8a/libtermux.so` differed (macOS vs Linux NDK). |
| v0.1.5 release vs a local build of the tag with `-Plocus.abi=arm64-v8a` (what the recipe does) | Also `classes.dex`, `AndroidManifest.xml` and `assets/dexopt/baseline.prof` differed. |
| After the fixes below: two clean local arm64-v8a builds in different directories, one of them after a two-ABI build and `:app:clean` (as the release workflow now does) | **Identical.** |
| After the fixes: a local macOS build vs CI's Linux `release-r8` APK of the same commit (`14c73ff4`) | **Identical** (`entries`, central directory, EOCD). |
| After the fixes, the recipe's flow: a local **unsigned** build (`-Plocus.unsigned=true`) with the prebuilt proot deleted and replaced by the Native libs workflow's fresh rebuild, vs CI's signed Linux APK of the same commit (`4fee1fa2`) | **Identical.** Copying CI's signing block onto the unsigned build gave a file equal to CI's APK, and `apksigner verify` accepts it (v2). This is exactly F-Droid's check, with the CI runner's debug key standing in for the release key. |
| After merging the two-job release workflow (`dca24f99`): a local unsigned arm64-v8a build from a fresh clone vs the release workflow's `release-unsigned` APK from a dry run of the same commit | **Identical** (whole file). The dry run's signing job then confirmed its signed APK is that unsigned build plus a signing block. Two clean local builds were also identical to each other. |
| **0.1.6, 2026-11-05**: the published `locus-v0.1.6-arm64-v8a.apk` and `locus-v0.1.6-x86_64.apk`, signed with the release key, vs unsigned builds of each ABI from a fresh clone of the tag `v0.1.6` (`2d3aba8e`) | **`apksigcopier compare` passed for both ABIs.** This is F-Droid's check, with the real release key. |

Build from a normal clone, not a worktree: AGP can't read a worktree's `.git` file, so `META-INF/version-control-info.textproto` says `NO_VALID_GIT_FOUND` instead of the commit, and that entry alone then differs. The R8 mapping (not in the APK) can list some synthetic Compose lambdas in a different order on macOS and Linux; the lines are the same. The release workflow compares its two mappings on one runner, where they're identical.

### What wasn't reproducible, and the fixes

1. **The dex depended on which ABIs were built together.** `BuildConfig.VERSION_CODE` is one value for all of a variant's split APKs; with both ABIs it's 64, with only arm64-v8a it's 63. `UpdateBuildModule` put it into `UpdateBuild`, so the arm64-v8a release APK (built with both ABIs) said 64 in its dex, and F-Droid's one-ABI build said 63. Nothing read the field (the update check reads the installed code from `PackageManager`), so it's gone, and `BuildConfig.VERSION_CODE` is no longer referenced. `baseline.prof` follows the dex, so it now matches too.
2. **The merged manifest depended on it as well.** With two splits AGP writes the two `activity-alias` elements (`AskLocusText`, `AssistLocus`) in source order; with one split, in the other order. Deterministic but different. Fix: the release workflow now builds each ABI on its own (`:app:clean :app:assembleRelease -Plocus.abi=<abi>`), exactly like the recipe, and checks the two R8 mappings are identical (they are: the dex no longer depends on the ABI).
3. **`libtermux.so` differed between macOS and Linux.** AGP didn't strip it ("Unable to strip the following libraries"), so it kept DWARF that names the NDK's install path, and `.comment` held each NDK's own clang and lld version strings ("Since NDK r26, the Clang version string in the comment section is different when building on MacOS and Linux"), so the build ID differed too. Fix: link with `-Wl,--build-id=none` and, after ndk-build, run the NDK's `llvm-objcopy --strip-debug --remove-section=.comment` on it (`core/terminal/build.gradle.kts`). The page recommends both `--build-id=none` and removing `.comment`.
4. **proot** was stale and mis-built on macOS; see [above](#binaries-in-the-source-tree).

### What the page warns about, and where Locus stands

| Risk ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)) | State |
|---|---|
| Timestamps | None found: every entry is dated 1981-01-01 (AGP's fixed time). |
| Baseline profiles ("Sometimes the `baselin.prof` file is not reproducible") | Matched in every comparison above once the dex matched. If it ever differs, the page's fix is to disable the `ArtProfile` tasks. |
| R8 ("some R8 optimizations are non-deterministic", core count) | R8 9.5.28 (newer than the page's 8.8 threshold). Identical dex on a Mac and a GitHub runner with different core counts. |
| VCS info ("upload release APKs built after tagging, from a clean tree at the actual tagged commit") | The release workflow builds from a clean checkout of the tag. A `git worktree` checkout writes `NO_VALID_GIT_FOUND` instead of the revision, so compare clones, not worktrees. |
| Native code: NDK version, build paths, `.comment` | NDK 29.0.14206865 pinned in `core/terminal`, `tools/build-proot.sh`, CI and the recipe; `-ffile-prefix-map`; `.comment` removed; no build ID in `libtermux.so`. |
| Mismatched JDKs | JBR 21 and Temurin 21 gave identical APKs. F-Droid's builder chooses its own JDK 21; not verified on F-Droid itself. |
| `apksigner` ≥ 35 makes APKs `apksigcopier` can't verify | From 0.1.6 the release workflow signs with the `apksigner` CLI (build-tools 36.1.0) in a separate job ([RELEASING.md](RELEASING.md#how-the-workflow-signs)). apksigner 35+ redoes the zip padding unless told not to ([apksigcopier issue 105](https://github.com/obfusk/apksigcopier/issues/105)), so the workflow passes `--alignment-preserved true` and then checks that the signed APK is the unsigned build byte for byte plus the signing block. Up to 0.1.5, AGP's own signer signed the APKs (v2 only, one signer, certificate `0fc4ab07…490e1b`). If F-Droid's verification fails on the signing block, start here. |
| Dependency-info block | Off (`dependenciesInfo { includeInApk = false }`); no block `0x504b4453` in the APK. |

**Releases up to 0.1.5 don't reproduce** (items 1–4). 0.1.6 is the first release built from this source: the release workflow builds its APKs the way F-Droid does, and both of its APKs passed `apksigcopier compare` (table above).

**To check a release yourself**: `gh release download v<version>`, build the tag with `./gradlew :app:assembleRelease -Plocus.abi=arm64-v8a -Plocus.unsigned=true` in a fresh clone, then `apksigcopier compare locus-v<version>-arm64-v8a.apk --unsigned app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk` (or `diffoscope`). CI's `release-r8` job keeps its APK as the `locus-release-arm64-v8a-debugkey` artifact for the same purpose.

## fdroiddata recipe (as submitted)

These are the files of MR [!55897](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55897) (closed as too new; see the status at the top), copied from branch `dev.locus` of the fork: formatted by `fdroid rewritemeta` (which orders the fields, wraps long lines and drops comments), with the 0.1.6 commit filled in. For the resubmission, update them in the fork to the latest release and copy them back here, so the two stay the same.

- [`docs/fdroid/metadata/dev.locus.yml`](fdroid/metadata/dev.locus.yml) → `metadata/dev.locus.yml`
- [`docs/fdroid/srclibs/termux-proot.yml`](fdroid/srclibs/termux-proot.yml) → `srclibs/termux-proot.yml`
- [`docs/fdroid/srclibs/llama-cpp.yml`](fdroid/srclibs/llama-cpp.yml) → `srclibs/llama-cpp.yml`

Notes on the recipe:

- **Version**: 0.1.6 (base `versionCode` 7, APK codes 73 and 74), the first release that reproduces, at `2d3aba8e2c7558dbf16a380c92b115f67b28f203` (`git rev-parse v0.1.6^{commit}`); the Build Metadata Reference says "Don't use branch name or tag name. The full commit hash should be used." After that, `checkupdates` adds builds itself.
- **One build per ABI**: each entry passes `locus.abi=<abi>` in `gradleprops` and points `binary` at that ABI's release asset. `VercodeOperation` `10 * %c + 3` and `10 * %c + 4` mirrors `abiDigits` in `app/build.gradle.kts`, in the same order as the build entries; `checkupdates` copies the last builds and pairs them with the computed codes, so each new build keeps its ABI's props and URL.
- **`locus.unsigned=true`**: without a release keystore, `app/build.gradle.kts` signs local release builds with the debug key; this property makes the build unsigned, which is what signature copying expects. The release workflow's build job uses the same property and a separate job signs afterwards ([RELEASING.md](RELEASING.md#how-the-workflow-signs)), so F-Droid's build and the published APK come from the same Gradle invocation. The property exists from 0.1.6, the first version the recipe builds, so the `prebuild` `sed` the earlier draft used for 0.1.4 and 0.1.5 is gone.
- **`locus.updateCheck` stays unset.** F-Droid's APK must be the GitHub APK byte for byte, so it can't compile the check out. The check is opt-in and is switched off at run time when an F-Droid client installed Locus ([updates](design/updates.md)). Only if F-Droid signs Locus itself (no `binary`, no `AllowedAPKSigningKeys`) add `locus.updateCheck=false` to `gradleprops`.
- **`rm` / `build` / `srclibs`**: `rm` deletes proot and the llama.cpp libraries before the scanner runs; `build` (which runs before Gradle) rebuilds proot with `$$termux-proot$$` and llama.cpp for the entry's ABI with `$$llama-cpp$$` (`ABIS=<abi>`), both with `$$NDK$$`. `rm` paths are relative to the repo root, `build` runs in `subdir: app`, hence `../tools/`. `scanignore` covers the benchmark's text fixture.
- **talloc** is downloaded by the script (pinned SHA-256) rather than a srclib: talloc is released as tarballs from the Samba tree. If reviewers want a srclib, `TALLOC_SRC` takes an unpacked tree (e.g. the Samba tag `talloc-2.4.3`, `lib/talloc`; untested).
- **`sudo`**: `gawk` is required (see the pokedata bug above); Debian's default `awk` is mawk. `cmake` and `ninja-build` build llama.cpp (CMake 3.22 or later). The others are probably preinstalled.
- **`AntiFeatures`** uses the map-with-reasons form current fdroiddata files use; the Build Metadata Reference still documents a plain list. `fdroid rewritemeta`/`lint` will say if it wants the other.
- **Categories** "AI Chat" and "Development" are both in fdroiddata's `config/categories.yml`.
- **`ndk: 29.0.14206865`**: whether fdroidserver's NDK list already has this revision wasn't checked; `fdroid build` says so if not.

## Anti-features

From [Anti-Features](https://f-droid.org/docs/Anti-Features/):

- **NonFreeNet: declared.** "promotes or depends entirely on a non-free network service". Locus doesn't depend on one (a self-hosted OpenAI-compatible server or the on-device model works), but setup and the model picker promote OpenAI, Anthropic, ChatGPT sign-in and other hosted providers, and integrations such as GitHub, Vercel, Cloudflare, Firecrawl, Tavily and Brave.
- **NonFreeDep: not declared.** "needs a non-libre app to work". Nothing non-free has to be installed. LiteRT-LM is a library inside the APK, Apache-2.0 with only free third-party licences; if F-Droid objects to its prebuilt JNI library, that's an inclusion question, not this anti-feature.
- **NonFreeAdd: not declared.** "promotes other non-libre apps or plugins". The ChatGPT sign-in is a network service, covered by NonFreeNet. Cheap to add if a reviewer asks.
- **TetheredNet: not declared.** The provider is a setting; any OpenAI-compatible URL works.
- **NonFreeAssets: not declared.** Provider logos are LobeHub Icons (MIT, `core/designsystem/NOTICE.md`); other art is Locus's own. On-device Gemma weights are downloaded only on request and aren't in the APK; Gemma's terms weren't reviewed here.
- **Tracking: not declared.** No analytics, telemetry or crash reporting; the update check is opt-in and off by default, which the Tracking definition exempts.
- **Ads, KnownVuln, NoSourceSince, DisabledAlgorithm**: not applicable.

## Fastlane metadata

`fastlane/metadata/android/en-US/`, checked against [All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/):

| File | State |
|---|---|
| `title.txt` | "Locus" (≤ 50). |
| `short_description.txt` | 62 characters (≤ 80, mandatory). |
| `full_description.txt` | 3,645 characters (≤ 4,000, mandatory); only `b`, `ul`, `li`; ends with the Linux download explanation and the NonFreeNet reason. |
| `changelogs/<versionCode>.txt` | `3`, `4`, `53`/`54`, `63`/`64`, `73`/`74`, each ≤ 500 characters. Each release adds its own pair (RELEASING.md). |
| `images/icon.png` | 512 × 512. |
| `images/featureGraphic.png` | **Added**: 1024 × 500, scaled and cropped from `docs/poster.png` (the project's own art, same screens). |
| `images/phoneScreenshots/` | 9 screenshots, 720 × 1450, from 2026-10-31 (0.1.3/0.1.4 UI). |

**For the owner to supply** (not invented here): new phone screenshots of 0.1.5's main additions, which the current set doesn't show: voice mode, the terminal with tabs and extra keys, Linux in the Workspace, Telegram remote, an interactive artifact, 0.1.6's on-device models screen, and the Linux download step in setup (reviewers will ask to see the consent screen). Take them on the phone at the same size, name them `10-….png` onward. Optional: `images/tenInchScreenshots/` if a tablet layout is worth showing.

## IzzyOnDroid

From the [App Inclusion Policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), checked 2026-11-03:

| Policy | Locus |
|---|---|
| "up to 30 megabytes per app", also "the upper size limit for single `.apk` files" | Met: the arm64-v8a APK is about 18.5 MB, x86_64 about 19.5 MB. Ask for the arm64-v8a asset. |
| APK "signed by its developer(s) using a release key", no `android:debuggable`/`android:testOnly` | Met: release key, not debuggable; debug builds are a separate app id and aren't published. |
| APKs from GitHub tagged releases | Met. |
| "no proprietary components", no trackers | No trackers or analytics; LiteRT-LM as [above](#binaries-in-the-apk). |
| Fastlane folder in the repo ("short description, full description, an icon, and some screenshots") | Met: `fastlane/metadata/android/en-US/` at the repo root, the standard path. |
| Self-updating needs opt-in and an explanation; the scanner flags `REQUEST_INSTALL_PACKAGES` | Opt-in, off by default, explained; turned off at run time for F-Droid-client installs. |
| Downloads of executables need explicit consent | Met (the Linux step). |
| Reproducible builds preferred | Reproducible from 0.1.6. |
| "We are strongly opposed to apps which are fully or in part created by generative AI tools." "Vibe-coded apps will be rejected." The request form asks for the AI assistance level | **The blocker.** Locus's code is largely written with Claude (the `Co-Authored-By` trailers say so). The request must say so honestly; IzzyOnDroid will likely decline. This is the owner's decision. |
| How new the app is (F-Droid closed its MR as developed for less than 3 months) | Expect a similar bar: the written policy (read 2026-11-05) states no minimum age, but a 2026-10-31 repository is very new. Ask in the request, or wait until 2027-01-31 as for F-Droid. |

Permissions the request should explain: `RECORD_AUDIO` (voice mode), `REQUEST_INSTALL_PACKAGES` (opt-in update check), `READ_CALENDAR` (calendar tool, asked when used), `ACCESS_LOCAL_NETWORK` and cleartext traffic (self-hosted model servers such as Ollama on the LAN), `FOREGROUND_SERVICE_*`, `POST_NOTIFICATIONS`, `SET_ALARM`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` (background tasks and schedules).

### Paste-ready: IzzyOnDroid App Inclusion Request

Open an issue at <https://codeberg.org/IzzyOnDroid/repodata/issues/new> with the **App Inclusion Request** template, title `[AppRequest] Locus`. Field by field:

- **Guidelines**: tick "I am the developer of the app", "The app is not already listed…", "The Fastlane folder is available…". Tick "complies with the App Inclusion Policy" only if you're comfortable with the AI section below.
- **Link to the source code**: `https://github.com/springmusk026/locus`
- **Link to app in another app store**: leave empty (or the F-Droid link once it exists).
- **License used**: `AGPL-3.0-or-later`
- **Categories**: Development, Internet
- **Summary**: `An AI agent that works on your phone, with your own model keys, files, Linux and browser.`
- **Description**:

  ```
  Locus is a local-first AI agent for Android. You bring your own model: an API key for a hosted provider, any OpenAI-compatible server (Ollama, llama.cpp, LM Studio, vLLM), or an on-device model (LiteRT-LM). The agent works with files in a workspace folder, an optional Alpine Linux shell (proot), a browser it can read and act in, and Android features such as notifications, the calendar and alarms. Every tool is set to Allow, Ask or Deny, and an activity log shows everything it did. There is no analytics, telemetry or crash reporting.

  Please use the arm64-v8a APK from each release (locus-v<version>-arm64-v8a.apk, about 18.5 MB); an x86_64 APK is also attached for Chromebooks and emulators. Fastlane metadata is in fastlane/metadata/android/en-US.
  ```
- **Build instructions**:

  ```
  git clone https://github.com/springmusk026/locus.git && cd locus && git checkout v<version>
  # Optional: rebuild the prebuilt proot (needs NDK 29.0.14206865 and gawk)
  rm core/linux/src/main/jniLibs/*/lib*.so && tools/build-proot.sh
  # Optional: rebuild llama.cpp (needs NDK 29.0.14206865, CMake and Ninja)
  rm core/ondevice/src/main/jniLibs/*/lib*.so && tools/build-llama.sh
  ./gradlew :app:assembleRelease -Plocus.abi=arm64-v8a -Plocus.unsigned=true
  # -> app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
  # Releases from 0.1.6 are reproducible: compare with the release APK using apksigcopier.
  ```
- **Assistance Level**: `Dominant – Most code or content was "AI"-generated`
- **"AI" Tool(s)**: `Claude (Claude Code)`
- **What did the tools help with, and how?**:

  ```
  Most of the code, tests and documentation were written by Claude through Claude Code, working from my specifications and design notes, and every commit says so in a Co-Authored-By trailer. I decide the features and design, review the changes and test the app on my phone; CI runs unit tests, lint and an R8 release build on every pull request.
  ```
- **AI Accountability**: tick only what's true. "Reviewed and edited all outputs" and "ran manual tests and manually verified all changes" are claims about every change; leave them unticked if that isn't so.
- **Further Notices**:

  ```
  Things your scanner may flag, and why:
  - REQUEST_INSTALL_PACKAGES: an opt-in update check against GitHub releases, off by default; its switch says it bypasses app-store checks, and it turns itself off when Locus was installed by an F-Droid client.
  - Downloads executables: only when the user taps "Download and install Linux" in setup, which says the Alpine programs come from alpinelinux.org and haven't been checked by a store. "Continue without Linux" is one tap.
  - Native libraries: libproot.so/libproot-loader.so (proot, GPL-2.0, built from source by tools/build-proot.sh in CI), libtermux.so (built from source), llama.cpp's libllama.so/libggml*.so with Locus's liblocusllama.so (MIT/AGPL, built from source by tools/build-llama.sh in CI), liblitertlm_jni.so (Google's LiteRT-LM, Apache-2.0, from Google Maven), AndroidX libraries.
  - Cleartext traffic is allowed so self-hosted model servers on the local network work over http://.
  - Non-free network services: setup suggests hosted AI providers; self-hosted and on-device models work instead.
  ```

## Paste-ready: F-Droid Request for Packaging

F-Droid's own path is a merge request to fdroiddata with the recipe above ([Submission steps](#submission-steps-owner), step 3). If you'd rather ask first, open an issue at <https://gitlab.com/fdroid/rfp/-/issues/new> with the default template, title `Locus`, and fill it in as:

````
* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy/?title=Inclusion_Policy).
* [x] The app is not already [listed](https://gitlab.com/search?scope=issues&group_id=28397) in the repo or issue tracker.
* [x] The app has not already [been requested](https://gitlab.com/search?scope=issues&project_id=2167965)
* [x] The upstream app source code repo contains the app metadata _(summary/description/images/changelog/etc)_ in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure
* [x] The original app author has been notified, and does not oppose the inclusion.
* [ ] Optionally [donated](https://f-droid.org/donate/) to support the maintenance of this app in F-Droid.

---------------------

#### APPLICATION ID: dev.locus

```yaml
Categories:
 - AI Chat
 - Development
License: AGPL-3.0-or-later
AuthorName: Basanta
SourceCode: https://github.com/springmusk026/locus
IssueTracker: https://github.com/springmusk026/locus/issues
AutoName: Locus
RepoType: git
Repo: https://github.com/springmusk026/locus.git
```

Why do you want this app added to F-Droid: I'm the developer. Locus is a free (AGPL) AI agent that works with whatever model the user chooses, including self-hosted and on-device ones, with no tracking. A complete build recipe with reproducible builds (upstream signature, AllowedAPKSigningKeys) is ready at https://github.com/springmusk026/locus/blob/main/docs/fdroid/metadata/dev.locus.yml, with notes in https://github.com/springmusk026/locus/blob/main/docs/FDROID.md.

Summary: An AI agent that works on your phone, with your own models, files and tools

Description: Locus is an AI agent for your phone. You choose the model: a hosted provider with your own key, any OpenAI-compatible server you run (Ollama, llama.cpp, LM Studio, vLLM), or an on-device model. The agent can work with files in a workspace folder, an optional Alpine Linux shell, a built-in browser, and Android features such as notifications, calendar and alarms. Every tool is set to Allow, Ask or Deny, and an activity log shows what it did. No analytics, telemetry or crash reporting.

Things to review: proot and its loader, and llama.cpp's libraries, are checked in but the recipe deletes them and rebuilds them from source (srclibs termux-proot and llama-cpp, tools/build-proot.sh and tools/build-llama.sh); the on-device runtime is Google's LiteRT-LM (Apache-2.0) from Google Maven with a prebuilt JNI library; Alpine Linux is downloaded only after an explicit opt-in step; anti-feature NonFreeNet.
````

(The inner YAML block is part of the template; GitLab renders nested fences fine in an issue body.)

## Until then (not started)

Two ways to offer F-Droid-style installs before the resubmission. Both are written down here only; neither is set up.

- **A self-hosted F-Droid repository on GitHub Pages.** fdroidserver can publish a repository of the release APKs (`fdroid init`, put the signed `locus-v<version>-<abi>.apk` files in `repo/`, `fdroid update`, then publish `repo/` to a `gh-pages` branch). Users add its URL and fingerprint in the F-Droid client and get updates there. The APKs keep Locus's own signature, so installs move to the official F-Droid build later without reinstalling. Cost: a repo signing key to keep safe, and a release step (or a workflow job) per release. The update check already defers to an F-Droid client that installed Locus ([updates](design/updates.md)).
- **Obtainium.** It already works with the GitHub releases, and the README says how to add Locus. What's left is only to point to it more prominently (for example a "Get it on Obtainium" link in the README and release notes).

## Fallback: a `foss` flavour without on-device models

Only if F-Droid rejects LiteRT-LM:

1. Add product flavours in `app/build.gradle.kts`, `full` (today's app) and `foss`, and depend on `:core:ondevice` only in `full`.
2. Move the Hilt bindings that provide on-device models into `app/src/full/`, and add `app/src/foss/` bindings that provide an empty catalogue, so the model picker hides "On this phone".
3. Recipe: `gradle: [foss]`, `binary` pointing at a `foss` APK that the release workflow also builds and signs with the same key.
4. Say in `full_description.txt` that the F-Droid build has no on-device model.

It touches DI wiring across modules, so it isn't done pre-emptively.

## Still open

1. **LiteRT-LM** acceptance, as above.
2. **F-Droid's own builder**: JDK choice and whether it knows NDK 29.0.14206865; `fdroid build` will tell.
3. ~~**Signature copying with the real release key**~~: checked on 0.1.6; `apksigcopier compare` passed for both ABIs.
4. **F-Droid's CI and `fdroid build`** never ran on the recipe: the MR was closed before a maintainer triggered its pipeline (the GitLab account isn't phone- or card-verified, so CI waits for a maintainer). The resubmission is the first real test of items 1 and 2.

## Submission steps (owner)

F-Droid was submitted and closed as too new; IzzyOnDroid and developer verification aren't done. In order:

1. ~~**Test Linux on the phone** with the Linux-built proot.~~ Done: confirmed on the phone on 2026-11-03 via #171 (the static-talloc build with the right pokedata offset). Installing Linux, a command, a service, the terminal, the health page and Repair all passed.
2. ~~**Release 0.1.6** and check it reproduces.~~ Done on 2026-11-05: `apksigcopier compare` passed for arm64-v8a and x86_64 against the release APKs, on a fresh clone of the tag.
3. **F-Droid**: submitted on 2026-11-05 as [!55897](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/55897) and closed the same day under Quality point 6 (developed for less than 3 months). **Resubmit on or after 2027-01-31** with the same recipe, updated to the latest release, from the fork's `dev.locus` branch: set `versionName`, `versionCode` and `commit` (`git rev-parse v<version>^{commit}`) in both build entries and `CurrentVersion`/`CurrentVersionCode`, rerun the commands below, and open a new MR (or ask for !55897 to be reopened), saying the app is now past the 3 months. CI on it waits for a maintainer to trigger it, because the GitLab account isn't phone- or card-verified; ask for that on the MR, as the note on !55897 did. Copy any change back into `docs/fdroid/`. How the first submission was made: fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), branch `dev.locus`, copy the three files from `docs/fdroid/`, fill in the commit hash (`git rev-parse v0.1.6^{commit}`), and run, as CONTRIBUTING.md says (Debian/Ubuntu or the `registry.gitlab.com/fdroid/fdroidserver:buildserver` image):
   ```sh
   fdroid readmeta
   fdroid rewritemeta dev.locus
   fdroid checkupdates --allow-dirty dev.locus
   fdroid lint dev.locus
   fdroid build -v -l dev.locus
   ```
   Open a merge request titled `New App: dev.locus` from a non-protected branch, commits squashed. In its description, explain the Linux opt-in (with the screenshot of the setup step), proot being rebuilt, and LiteRT-LM. Or open the [RFP](#paste-ready-f-droid-request-for-packaging) first. Builds appear about 24–48 hours after merge; later releases only need a tag.
4. **IzzyOnDroid** (optional): open the [request](#paste-ready-izzyondroid-app-inclusion-request). Expect a newness bar similar to F-Droid's on top of the [AI policy](#izzyondroid); its written App Inclusion Policy (read 2026-11-05) doesn't state a minimum age, so ask, or wait until the F-Droid resubmission date too.
5. **Developer verification**: [DISTRIBUTION.md](DISTRIBUTION.md).
