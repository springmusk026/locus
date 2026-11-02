# Locus on F-Droid

Status: **prepared, not submitted.** This page lists what F-Droid requires, what the repo now does about each requirement, a draft of the fdroiddata recipe, what's still unverified, and how to submit.

Sources, read on 2026-10-31:

- [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Anti-Features](https://f-droid.org/docs/Anti-Features/)
- [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/)
- [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)
- [All About Descriptions, Graphics, and Screenshots](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)
- [Submitting to F-Droid Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
- [fdroiddata CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md)
- [fdroiddata config/categories.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/config/categories.yml)

Read on 2026-11-01: [IzzyOnDroid inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), [Android developer verification](https://developer.android.com/developer-verification) with its [console](https://developer.android.com/developer-verification/guides/android-developer-console) and [open-source registration](https://developer.android.com/developer-verification/guides/open-source-app-registration) guides, fdroidserver's [checkupdates.py](https://gitlab.com/fdroid/fdroidserver/-/blob/master/fdroidserver/checkupdates.py).

## Requirements and where Locus stands

| Requirement (source) | Locus |
|---|---|
| FLOSS app, built from source with a FLOSS toolchain ([Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/): "All applications in the repository must be Free, Libre and Open Source Software"; "The complete application building process requires a 100% FLOSS toolchain") | AGPL-3.0-or-later, Gradle + AGP + OpenJDK. No Play Services, Firebase or analytics. |
| Binary dependencies built from source, or from a trusted Maven repo and freely licensed ("Library dependencies must be built from source or provided in a trusted Maven repo"; trusted repos "include Maven Central, Google Maven…", but "Those binaries must still be freely licensed, simply being included in one of those repositories is not enough") | All dependencies come from Maven Central and Google Maven. **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android:0.19.0`) ships prebuilt `liblitertlm_jni.so`; see [Unverified](#unverified). |
| Prebuilt binaries in the source tree ("All binary dependencies … must originate either from source compilation or Debian repository downloads") | `core/linux/src/main/jniLibs/*/lib*.so` (proot, its loader, talloc) are checked in. The recipe deletes them (`rm`) and rebuilds them from pinned sources with `tools/build-proot.sh`. |
| No downloading executables without consent: "Applications must not download additional executable binary files (e.g. add-ons, auto-updates, etc.) without explicit user consent. Consent means it needs to be opt-in (it must not be harder to decline than to accept or presented in a way users are likely to press accept without reading) and structured in a way that clearly explains to users that they're choosing to bypass F-Droid's checks if they activate it." | The Alpine Linux minirootfs and `apk` packages are downloaded from alpinelinux.org only when the user taps **Download and install Linux** in setup (also reachable from Settings → About & privacy → Run setup again). The step says the programs come from alpinelinux.org and that F-Droid hasn't checked them; **Continue without Linux** is a full-width button, one tap. The agent can't trigger it: `run_command` and `linux_service_start` return an error telling the agent to ask the user. The image's SHA-256 is pinned in `LinuxEnvironment`. |
| Anti-features disclosed ([Anti-Features](https://f-droid.org/docs/Anti-Features/); the Build Metadata Reference says to mention the reasons in the description) | `NonFreeNet`, explained at the end of `full_description.txt`. See [Anti-features](#anti-features). |
| Version in source, tags per release ([Quick Start](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/): "Each official release commit in the application's upstream Git repository should have a tag") | `versionCode`/`versionName` are in `app/build.gradle.kts` (base code 4 / 0.1.3); each per-ABI APK's code is `versionCode * 10 + 3` (arm64-v8a) or `+ 4` (x86_64). The release workflow fails unless the tag is `v<versionName>`. Procedure: [RELEASING.md](RELEASING.md). |
| Fastlane metadata ([All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/): `short_description.txt` "max 80 chars, mandatory", `full_description.txt` "max 4000 chars, mandatory", `title.txt` "max 50 chars", `changelogs/<versionCode>.txt` "max 500 chars") | `fastlane/metadata/android/en-US/`: title, short description (62 chars, no trailing dot), full description (~2,600 chars, only allowed tags `b`, `ul`, `li`), `changelogs/<versionCode>.txt` (one per APK: from 0.1.4 each ABI has its own code, so `53.txt` and `54.txt`, same text; the filename "must correspond to versionCode, literally, no padding"), `images/icon.png` (512 px, from `docs/icon.svg` via `tools/render-icon.py`), 9 phone screenshots. |
| Reproducible builds and the dependency-info block ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/)) | `dependenciesInfo { includeInApk = false; includeInBundle = false }` is set. Checked: the 0.1.1 APK's signing block had the dependency-info block (ID `0x504b4453`), a new release build doesn't. Plan below. |

## Anti-features

Definitions from [Anti-Features](https://f-droid.org/docs/Anti-Features/), and the decisions:

- **NonFreeNet: applied.** "This Anti-Feature is applied to apps that promote or depend entirely on a proprietary network service." Locus doesn't depend entirely on one (it works with a self-hosted OpenAI-compatible server or the on-device model), but setup and the model picker promote proprietary services: Anthropic, OpenAI, ChatGPT sign-in, hosted providers, and integrations such as GitHub, Vercel, Cloudflare, Firecrawl, Tavily and Brave. "Promote" is enough.
- **NonFreeAdd: not applied.** "…apps that, although Free Software themselves, promote other Non-Free applications or plugins." The ChatGPT sign-in uses a paid plan of a network service, not an app or plugin, so NonFreeNet covers it. A reviewer may see this differently; adding it costs nothing if asked.
- **TetheredNet: not applied.** "…apps that depend entirely on a service which is impossible (or not easy) to replace". The provider is a setting, and any OpenAI-compatible URL works (Ollama, llama.cpp, vLLM, LM Studio).
- **NonFreeDep: not applied.** "…apps that require things that are not Free Software in order to run." Nothing non-free has to be installed. If LiteRT-LM's native library turns out to be non-free, that's an inclusion problem rather than an anti-feature (see the fallback).
- **NonFreeAssets: not applied.** The provider and model logos are from LobeHub Icons, MIT-licensed (`core/designsystem/NOTICE.md`); the app icon and other art are Locus's own. Trademarks aren't a copyright licence question, but a reviewer could ask.
- **Tracking: not applied.** No analytics, telemetry or crash reporting. Network requests only go to services the user configures; the models.dev catalogue is off until turned on.
- **Ads, KnownVuln, NoSourceSince, DisabledAlgorithm: not applicable.**

## Draft fdroiddata metadata

`metadata/dev.locus.yml` (draft; `fdroid rewritemeta` will reorder it):

```yaml
Categories:
  - AI Chat
  - Development
License: AGPL-3.0-or-later
AuthorName: Basanta
SourceCode: https://github.com/springmusk026/locus
IssueTracker: https://github.com/springmusk026/locus/issues
Changelog: https://github.com/springmusk026/locus/blob/HEAD/CHANGELOG.md

AutoName: Locus

RepoType: git
Repo: https://github.com/springmusk026/locus.git

AntiFeatures:
  - NonFreeNet

Builds:
  - versionName: 0.1.4
    versionCode: 53
    commit: <full commit hash of tag v0.1.4>
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y make python3 curl
    gradle:
      - yes
    srclibs:
      - termux-proot@4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f
    rm:
      - core/linux/src/main/jniLibs/arm64-v8a/libproot.so
      - core/linux/src/main/jniLibs/arm64-v8a/libproot-loader.so
      - core/linux/src/main/jniLibs/arm64-v8a/libtalloc.so
      - core/linux/src/main/jniLibs/x86_64/libproot.so
      - core/linux/src/main/jniLibs/x86_64/libproot-loader.so
      - core/linux/src/main/jniLibs/x86_64/libtalloc.so
    prebuild: sed -i -e 's/else signingConfigs.named("debug").get()/else null/' build.gradle.kts
    build: PROOT_SRC=$$termux-proot$$ ANDROID_NDK_HOME=$$NDK$$ bash ../tools/build-proot.sh
    ndk: 29.0.14206865
    gradleprops:
      - locus.abi=arm64-v8a
    binary: https://github.com/springmusk026/locus/releases/download/v%v/locus-v%v-arm64-v8a.apk

  - versionName: 0.1.4
    versionCode: 54
    commit: <full commit hash of tag v0.1.4>
    subdir: app
    sudo:
      - apt-get update
      - apt-get install -y make python3 curl
    gradle:
      - yes
    srclibs:
      - termux-proot@4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f
    rm:
      - core/linux/src/main/jniLibs/arm64-v8a/libproot.so
      - core/linux/src/main/jniLibs/arm64-v8a/libproot-loader.so
      - core/linux/src/main/jniLibs/arm64-v8a/libtalloc.so
      - core/linux/src/main/jniLibs/x86_64/libproot.so
      - core/linux/src/main/jniLibs/x86_64/libproot-loader.so
      - core/linux/src/main/jniLibs/x86_64/libtalloc.so
    prebuild: sed -i -e 's/else signingConfigs.named("debug").get()/else null/' build.gradle.kts
    build: PROOT_SRC=$$termux-proot$$ ANDROID_NDK_HOME=$$NDK$$ bash ../tools/build-proot.sh
    ndk: 29.0.14206865
    gradleprops:
      - locus.abi=x86_64
    binary: https://github.com/springmusk026/locus/releases/download/v%v/locus-v%v-x86_64.apk

AllowedAPKSigningKeys: 0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v[0-9.]+$
VercodeOperation:
  - 10 * %c + 3
  - 10 * %c + 4
CurrentVersion: 0.1.4
CurrentVersionCode: 54
```

`srclibs/termux-proot.yml`:

```yaml
RepoType: git
Repo: https://github.com/termux/proot.git
```

Notes on the recipe:

- **Categories**: both names are in fdroiddata's [config/categories.yml](https://gitlab.com/fdroid/fdroiddata/-/blob/master/config/categories.yml) ("AI Chat", "Development"). "Categories must be a list of items, even if there is just one."
- **commit**: the Build Metadata Reference says "Don't use branch name or tag name. The full commit hash should be used." Fill in `git rev-parse v0.1.4^{commit}` once the tag exists. `checkupdates` writes later builds itself. 0.1.4 stands for the first release with per-ABI APKs; v0.1.3 and earlier shipped one universal APK and aren't in the recipe.
- **One build per ABI** (`gradleprops`, `binary`, `VercodeOperation`): Locus's release produces one APK per ABI, `locus-v<version>-arm64-v8a.apk` and `locus-v<version>-x86_64.apk` (see [RELEASING.md](RELEASING.md#version-codes-and-apks)). Each build entry passes `-Plocus.abi=<abi>` ("_gradleprops_: List of Gradle properties to pass via the command line to Gradle"), so Gradle produces only that ABI's APK, and points `binary` at the matching release asset (`%v` and `%c` are the only placeholders, so the ABI is spelled out per build rather than in a top-level `Binaries`). `VercodeOperation` is "Especially useful with apps that we want to compile for different ABIs"; its two operations match `abiDigits` in `app/build.gradle.kts`, `10 * %c + 3` for arm64-v8a and `10 * %c + 4` for x86_64, in the same order as the build entries. `checkupdates` copies the last builds (`copy.deepcopy(builds[-len(vercodes):])` in fdroidserver's `checkupdates.py`) and zips them with the computed codes, so each new build keeps its ABI's `gradleprops` and `binary`. With two operations the default ArchivePolicy keeps 6 APKs (3 versions).
- **Checked locally**: a release build with `-Plocus.abi=arm64-v8a` gives an arm64-v8a APK whose entries (names and CRCs, outside `META-INF`) are identical to the one from a build of both ABIs, so building one ABI at a time doesn't change the APK.
- **rm / build**: `rm` deletes the checked-in libraries before the scanner runs; `build` regenerates them before Gradle runs. The paths in `rm` are relative to the repo root, while `build` runs in `subdir` (`app`), hence `../tools/`. `$$termux-proot$$` and `$$NDK$$` are the srclib and NDK path substitutions; `ndk:` takes "either of the two official version schemes, e.g. r21e or 21.4.7075529".
- **talloc** isn't a srclib: talloc is released as tarballs from the Samba tree, with no standalone git repo. `build-proot.sh` downloads `talloc-2.4.3.tar.gz` and checks its pinned SHA-256 (`dc46c40b…54dd`). If reviewers want a srclib, `TALLOC_SRC` can point at an unpacked tree (for example the Samba git tag `talloc-2.4.3`, subdirectory `lib/talloc`; untested).
- **prebuild**: without a release keystore, `app/build.gradle.kts` signs local release builds with the debug key. The `sed` makes F-Droid's build unsigned, which is what signature copying expects. It's a recipe-only change; upstream could instead skip the debug fallback when a Gradle property is set.
- **UpdateCheckMode**: `Tags` reads `versionName`/`versionCode` from the tagged source. The regex skips pre-release tags such as `v0.2.0-beta.1`. `AutoUpdateMode: Version` "enables auto-update"; with Tags, no pattern is needed.
- **AntiFeatures**: the Build Metadata Reference documents a list. Newer fdroiddata files sometimes use a map with a reason per anti-feature; `fdroid rewritemeta`/`lint` will say which it wants.
- **sudo**: F-Droid's build VM is Debian. The packages above are what `build-proot.sh` needs beyond the base image (`git`, `tar` and `bash` are already there); the rest are probably already installed too, so this line may be unnecessary.

## Reproducible builds plan

Goal: F-Droid publishes **Locus's own signed APK** from the GitHub release, so F-Droid and GitHub installs update over each other.

How it works ([Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/), "Publishing APKs with the upstream developer's signature"): with `Binaries` (here a `binary` per build) and `AllowedAPKSigningKeys` set, F-Droid builds the APK from source, downloads `locus-v<version>-<abi>.apk`, copies its signature onto its own unsigned build (apksigcopier), and publishes the developer-signed APK only if that verifies: "Publishing only takes place if there is a proper match." Otherwise that version is skipped. "F-Droid will never attempt to publish APKs signed by F-Droid" in this mode.

- Each build's `binary: https://github.com/springmusk026/locus/releases/download/v%v/locus-v%v-<abi>.apk` matches the release workflow's asset names (`locus-${TAG}-<abi>.apk`). "This url must be versioned".
- `AllowedAPKSigningKeys: 0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b`, "a lower case hex value of the SHA-256 fingerprint of the signing certificate". Checked against the v0.1.1 release APK with `apksigner verify --print-certs` (one signer, v2 scheme).

What has to match, and what's done:

| Risk (Reproducible Builds section) | State |
|---|---|
| Dependency-info block in the signing block | Disabled in `app/build.gradle.kts`. |
| Native libraries: "Anything built using the NDK will be much more sensitive"; exact NDK version; build paths ("Embedded build paths"); `.comment` differs between macOS and Linux NDKs since r26 | `build-proot.sh` pins NDK r29 (CI and recipe use 29.0.14206865), uses `-ffile-prefix-map`, strips, and removes `.comment`. Two clean macOS builds produced identical files. **Linux vs macOS output is unverified**, and **the committed `libproot.so` doesn't match the current script** (it predates static talloc and still needs `libtalloc.so`). See next steps. |
| Mismatched toolchains ("more than one JDK version/distribution") | Release CI uses Temurin 21. The recipe doesn't choose a JDK; F-Droid's image has 21 (Debian Trixie) and 25. Check which `fdroid build` uses. |
| VCS info (`META-INF/version-control-info.textproto`, AGP 8.3+): "upload release APKs built after tagging, from a clean tree at the actual tagged commit" | The release workflow builds from a clean checkout of the tag. Don't build release APKs from a dirty tree. |
| Baseline profiles (`assets/dexopt/baseline.prof`/`.profm` are in the APK): "baseline.prof not deterministic" | Not changed. If the first verification fails on these files, disable the ArtProfile tasks as the page shows, or rely on the page's other workarounds. |
| R8 nondeterminism, resource shrinker | `isMinifyEnabled` and `isShrinkResources` are on. The page says to update R8 if DEX differs by core count; AGP's bundled R8 is recent. Watch the first diff. |
| `apksigner` from build-tools ≥ 35 "outputs unverifiable APKs" for apksigcopier | Release CI signs through AGP's own signer, not the `apksigner` CLI. If verification fails with a signing-block error, this is the first thing to check. |

If reproducibility can't be reached quickly, drop the `binary` and `AllowedAPKSigningKeys` lines: F-Droid then signs its own builds with its key. That works, but F-Droid and GitHub APKs can't update over each other.

## Unverified

1. **LiteRT-LM's native library.** `litertlm-android-0.19.0.aar` contains `jni/arm64-v8a/liblitertlm_jni.so` (22.7 MB) and `jni/x86_64/liblitertlm_jni.so` (26.8 MB), stripped, plus Apache-2.0 `LICENSE` and a 2.2 MB `THIRD_PARTY_NOTICE.txt`. The POM declares Apache-2.0 and the [source](https://github.com/google-ai-edge/LiteRT-LM) is Apache-2.0. Not verified: whether that JNI library can be built from the public source (the documented Bazel build produces the CLI, not the AAR; the repo also has a `prebuilt/` folder of GPU accelerator libraries), and whether F-Droid accepts it as a "freely licensed" binary from Google Maven. Also unchecked: whether `fdroid scanner` flags `com.google.ai.edge` as non-free.
2. **Other Maven native libraries** in the APK: `libsqliteJni.so` (androidx.sqlite bundled), `libdatastore_shared_counter.so` (DataStore), `libandroidx.graphics.path.so` (Compose graphics). These are AndroidX on Google Maven and Apache-2.0; F-Droid normally accepts AndroidX, but they're listed here for completeness.
3. **The proot build on F-Droid's server**: the script has run on macOS only. The new `Native libs` workflow (`.github/workflows/native-libs.yml`, run by hand) builds on Ubuntu with NDK 29.0.14206865 and compares with the committed files. Whether fdroidserver knows NDK `29.0.14206865` (it installs NDKs from its own list) is also unverified.
4. **Static talloc at runtime**: the current script links talloc into `libproot.so`; that build hasn't been tested on a phone yet.
5. **Gemma weights**: on-device models are downloaded from Hugging Face only when the user taps download. They're model weights, not executables, so the download rule shouldn't apply, but the Gemma licence terms weren't checked here; a reviewer could ask about NonFreeNet/NonFreeAssets for them.
6. **AntiFeatures format** (list vs map with reasons) and whether reviewers also want **NonFreeAdd** for ChatGPT sign-in.
7. **fdroidserver handling of an already signed release APK**: the recipe sidesteps it with the `prebuild` `sed`.

## Fallback: a `foss` flavour without on-device models

Only if F-Droid rejects LiteRT-LM:

1. Add product flavours in `app/build.gradle.kts`, `full` (today's app) and `foss`, and depend on `:core:ondevice` only in `full` (`"fullImplementation"(projects.core.ondevice)`).
2. Move the Hilt bindings that provide on-device models into `app/src/full/`, and add `app/src/foss/` bindings that provide an empty catalogue, so the model picker hides "On this phone".
3. Recipe: `gradle: [foss]`. `Binaries` must then point at a `foss` APK. The release workflow would build and publish `locus-v%v-foss.apk` as well, signed with the same key; the foss and full APKs share an application ID, so a user can switch between them.
4. Mention in `full_description.txt` that the F-Droid build has no on-device model.

This touches DI wiring across modules, so it's not done pre-emptively.

## Android developer verification

From [Android developer verification](https://developer.android.com/developer-verification): "September 30, 2026: Protections begin for all users who install apps from participating stores in Brazil, Indonesia, Singapore, and Thailand on certified devices running Android 7+", and "Verification capability will be expanded globally for all apps on certified Android devices in 2027." Unregistered apps still install, but only after the user turns on the "advanced flow". Registration needs the owner's account; nothing here has been registered.

Checklist (owner):

- [ ] **Account**: an [Android Developer Console](https://android.google.com/developerconsole/developers) account, for "developers distributing apps exclusively outside of Google Play" (Play Console's developer verification tab does the same, for apps also on Google Play). The [full distribution guide](https://developer.android.com/developer-verification/guides/full-distribution) lists the identity checks and fee; the limited distribution account (no ID, no fee) only covers up to 20 devices, so it doesn't fit a public app.
- [ ] **Package name**: `dev.locus`. Don't register `dev.locus.debug`: debug builds are never distributed.
- [ ] **Key**: the release key's SHA-256 certificate fingerprint, `0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b` (same as `AllowedAPKSigningKeys` above). Check it with `apksigner verify --print-certs locus-v<version>-arm64-v8a.apk`.
- [ ] **Prove ownership**: `dev.locus` already has installs, so the [console guide](https://developer.android.com/developer-verification/guides/android-developer-console) says "you must sign an APK with your private key and upload it. The Android Developer Console provides a snippet to add to the APK's asset folder for this challenge." Put the snippet in `app/src/main/assets/` (the [open-source guide](https://developer.android.com/developer-verification/guides/open-source-app-registration) names the file `adi-registration.properties`), build a release with the release key, upload it, and keep or remove the file in a normal release afterwards. It's a plain asset, so it doesn't affect reproducible builds as long as it's committed.
- [ ] **F-Droid**: with the reproducible-builds plan above, F-Droid publishes Locus's own signed APKs, so the one key above covers GitHub, F-Droid and IzzyOnDroid installs. The open-source guide: "If the repository uses your signing key", the normal package registration is enough. Only if F-Droid ends up signing with its own key (the fallback without `binary`), also register F-Droid's key for `dev.locus` with the guide's re-signed flow: download F-Droid's APK, add its fingerprint ("Add key" while under 50 installs from F-Droid, otherwise "Select key"), add the console's snippet to `assets/`, ship a version through F-Droid, and upload that F-Droid APK.

## IzzyOnDroid

From the [IzzyOnDroid inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/) (read 2026-11-01), what's met and what still blocks a submission:

| Policy | Locus |
|---|---|
| "usually reserves up to 30 megabytes per app", "the upper size limit for single `.apk` files" | **Blocking until the next release.** v0.1.3's universal APK is 29,868,073 bytes. The per-ABI APKs from this repo's release build are about 17.1 MB (arm64-v8a) and 18.0 MB (x86_64). The policy doesn't say how IzzyOnDroid picks one APK when a release has several; ask for `locus-v%v-arm64-v8a.apk` in the request. |
| "We are strongly opposed to apps which are fully or in part created by generative AI tools." "Vibe-coded apps will be rejected." "LLM-generated documentation is allowed, but the code should be free of it." "Any lack of transparency discovered by us, can lead to the project being degraded to rejected state." | **Blocking.** Most of Locus's commits are co-authored by an AI coding assistant (the `Co-Authored-By` trailers say so). Under this policy the app is likely to be rejected; if it's submitted anyway, say so up front rather than let reviewers find it. This is the owner's call. |
| "there should be no proprietary components", tolerated "if they are essential for the app's core functionality"; no trackers | No trackers or analytics. LiteRT-LM's prebuilt JNI library (Apache-2.0, from Google Maven) is the open question, as for F-Droid ([Unverified](#unverified)). |
| No downloading executables "without explicit user consent", opt-in, "not harder to decline than to accept" | Met: the Linux download is opt-in, see the table at the top. |
| "*.apk files must be signed by its developer(s) using a release key", no `android:debuggable` or `android:testOnly` | Met: release APKs are signed with Locus's key and aren't debuggable; debug builds are a separate app and not published. |
| APKs from "GitHub tagged `releases/`" | Met. |
| Fastlane metadata: "At least short description, full description, an icon, and some screenshots" | Met (`fastlane/metadata/android/en-US/`). |
| `android:usesCleartextTraffic` "should be avoided wherever possible and only be set were absolutely necessary" | `network_security_config.xml` allows cleartext so self-hosted model servers on the local network (Ollama, LM Studio) work over `http://`. Explain this in the request. |
| Reproducible builds preferred (checked with rbtlog) | Optional. The same per-ABI APKs F-Droid would verify. |

The policy page links IzzyOnDroid's repodata issue tracker on Codeberg; check there how to file an inclusion request.

## Submission steps (not done)

Do these in order. Nothing has been submitted.

1. **Native libraries**: run the **Native libs** workflow on GitHub. If it reports a difference (it will: the committed `libproot.so` is older than the script), download its `jniLibs` artifact, test Linux on a phone (install Linux, `run_command`, a service), then commit those files. Commit only Linux-built files: they're what F-Droid will produce.
2. **Release 0.1.4** following [RELEASING.md](RELEASING.md): write `changelogs/53.txt` and `changelogs/54.txt`, tag `v0.1.4`, and let the workflow publish `locus-v0.1.4-arm64-v8a.apk` and `locus-v0.1.4-x86_64.apk`.
3. **Check locally** with fdroidserver (Debian/Ubuntu, or the `registry.gitlab.com/fdroid/fdroidserver:buildserver` image): fork [fdroiddata](https://gitlab.com/fdroid/fdroiddata), create branch `dev.locus`, add `metadata/dev.locus.yml` and `srclibs/termux-proot.yml` from above with the real commit hash, then, as the contributing guide says:
   ```sh
   fdroid readmeta
   fdroid rewritemeta dev.locus
   fdroid checkupdates --allow-dirty dev.locus
   fdroid lint dev.locus
   fdroid build -v -l dev.locus
   ```
   Then compare the unsigned build with the release APK (`apksigcopier compare` or `diffoscope`) and fix differences per [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds/).
4. **Open a merge request** on fdroiddata titled `New App: dev.locus`, filling in the template, from a non-protected branch, commits squashed. Or, for a first-time contributor, open a [Request for Packaging](https://gitlab.com/fdroid/rfp/-/issues) issue instead and link this page.
5. In the MR description, explain the Linux opt-in (with a screenshot of the setup step), the LiteRT-LM question, and that proot/talloc are rebuilt from source.
6. After merge, builds appear in roughly "24 to 48 hours" (Quick Start). Future releases need only a new tag (RELEASING.md).
