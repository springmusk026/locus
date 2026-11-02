# Releasing Locus

The version lives in source, in `app/build.gradle.kts`:

```kotlin
versionCode = 4
versionName = "0.1.3"
```

Every build reads it from there: local builds, the GitHub release workflow and F-Droid's build server, which builds from the tagged commit and takes the version from source ([Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/#UpdateCheckMode), `UpdateCheckMode: Tags`). Nothing overrides it from the environment.

| Release | Tag | versionCode | versionName |
|---|---|---|---|
| 0.1.0 | `v0.1.0` | 1 | 0.1.0 |
| 0.1.1 | `v0.1.1` | 2 | 0.1.1 |
| 0.1.2 | `v0.1.2` | 3 | 0.1.2 |
| 0.1.3 | `v0.1.3` | 4 | 0.1.3 |

(0.1.0 and 0.1.1 were stamped by CI from the tag; their tagged source still says `1` / `"0.1.0"`. 0.1.2 is the first release whose tag carries its own version, so it's the first one F-Droid can build.)

## Version codes and APKs

Up to 0.1.3 a release was one universal APK whose versionCode was the number above. From the next release, each release is **one APK per ABI** ([Configure multiple APKs](https://developer.android.com/build/configure-apk-splits), `splits { abi { … } }` in `app/build.gradle.kts`):

| Asset | ABI | versionCode | For |
|---|---|---|---|
| `locus-v<versionName>-arm64-v8a.apk` | arm64-v8a | `versionCode * 10 + 3` | nearly every phone |
| `locus-v<versionName>-x86_64.apk` | x86_64 | `versionCode * 10 + 4` | x86_64 Chromebooks, emulators |

So `versionCode = 5` gives 53 and 54. The number in `app/build.gradle.kts` stays the base code and still goes up by one per release; the APKs' codes are derived from it (`abiDigits` and `androidComponents.onVariants`). Why this scheme:

- The version decides first: every APK of a newer release has a higher code than every APK of an older one, so updates always work, and 53 is higher than v0.1.3's universal 4, so those installs update to the per-ABI APK.
- F-Droid builds one APK per ABI and expresses exactly this with `VercodeOperation: [10 * %c + 3, 10 * %c + 4]` ([Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/#VercodeOperation): "Especially useful with apps that we want to compile for different ABIs"). See [FDROID.md](FDROID.md).
- Within a release x86_64 is higher, so a store prefers the native APK on an x86_64 device that can also run ARM through translation. Digits 1 and 2 are reserved for armeabi-v7a and x86.

**Only 64-bit.** The Linux runtime (proot, `core/linux/src/main/jniLibs`) and LiteRT-LM (`litertlm-android`) ship arm64-v8a and x86_64 libraries only, so Locus can't run on a 32-bit-only device. The armeabi-v7a and x86 libraries that SQLite (`libsqliteJni.so`, ~1.2 MB each), DataStore and Compose graphics bring were dead weight in the universal APK; no APK includes them now.

**No universal APK.** It would hold both LiteRT-LM libraries (22.7 MB + 26.8 MB uncompressed), the bulk of today's 30 MB universal APK, right at IzzyOnDroid's per-APK limit; it would also be a third APK for F-Droid to verify, with a versionCode that would have to sort below the per-ABI ones. x86_64 phones are effectively gone, so the arm64-v8a APK is the one to link to, and the release notes say so.

Sizes measured from `./gradlew :app:assembleRelease` on 2026-11-01 (0.1.3 source): arm64-v8a 17,053,000 bytes, x86_64 18,037,083 bytes, against 29,999,537 bytes for the universal APK from the same source.

`./gradlew :app:assembleRelease -Plocus.abi=arm64-v8a` builds just one ABI; F-Droid's recipe does that once per build entry. Debug builds are split the same way (`app-arm64-v8a-debug.apk`); `installDebug` picks the device's ABI.

## Steps

1. **Bump the version** in `app/build.gradle.kts`:
   - `versionCode`: the previous code plus one. It must only ever go up; Android refuses to update to a lower code.
   - `versionName`: the new [semantic version](https://semver.org/), without a `v`. Pre-releases use a suffix, e.g. `0.2.0-beta.1`.
2. **Write the changelogs:**
   - `CHANGELOG.md`: rename `## [Unreleased]` to `## [<versionName>] - <YYYY-MM-DD>` and start a new empty `## [Unreleased]` above it.
   - `fastlane/metadata/android/en-US/changelogs/<code>.txt`: a plain-text summary for F-Droid's "What's new", at most 500 characters ([All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)). The file name is the APK's versionCode, with no padding, and each ABI has its own: for `versionCode = 5`, write `53.txt` and `54.txt` with the same text.
3. **Commit** (`Release 0.1.2`) and merge to `main`.
4. **Tag that commit** `v<versionName>` and push the tag:
   ```sh
   git tag -a v0.1.2 -m "Locus 0.1.2"
   git push origin v0.1.2
   ```
   Or run the Release workflow by hand with the tag as input; it tags the commit it runs on.
5. **The Release workflow** (`.github/workflows/release.yml`):
   - fails unless the tag is exactly `v` + `versionName` and both per-ABI changelogs exist;
   - runs the tests, builds the release APKs (signed with Locus's key) and publishes `locus-<tag>-arm64-v8a.apk` and `locus-<tag>-x86_64.apk` as a GitHub release with `SHA256SUMS.txt` and the R8 mapping (one mapping: the code is the same in both APKs).
   - publishes no debug APK. A debuggable APK lets anyone with adb read the app's data, so debug builds are never signed with the release key and are a separate app (`dev.locus.debug`, "Locus Debug") that can't install over Locus. Use the R8 mapping to read release stack traces.
   If the check fails, fix the version or delete the tag (`git push origin :refs/tags/v0.1.2`), then tag again.
6. **F-Droid** (once Locus is in F-Droid): nothing to do. With `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, F-Droid's `checkupdates` finds the new tag, reads the version from source and adds a build ([fdroiddata CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md): "F-Droid will build new versions from tags automatically"). If reproducible builds are set up (`Binaries:` in the F-Droid metadata, see [FDROID.md](FDROID.md)), F-Droid downloads `locus-v<versionName>-<abi>.apk` from the GitHub release and publishes it only if its own build matches byte for byte. So:
   - don't rename the release assets;
   - don't re-upload or rebuild an APK after the tag;
   - build the published APK from a clean checkout of the tag (the workflow does).

## Developer verification

Google's [Android developer verification](https://developer.android.com/developer-verification) starts on 2026-09-30 for installs on certified devices in Brazil, Indonesia, Singapore and Thailand and goes global in 2027; an unregistered app then needs the user to turn on the "advanced flow". Owner's checklist (nothing is registered yet; details and sources in [FDROID.md](FDROID.md#android-developer-verification)):

- [ ] Create an [Android Developer Console](https://android.google.com/developerconsole/developers) account (full distribution).
- [ ] Register package `dev.locus` with the release key's SHA-256 fingerprint `0fc4ab07aa5522dfabac92d818fd5cf075ead96da4e2b40a757ac80541490e1b`. Not `dev.locus.debug`: the debug app id is separate and never distributed.
- [ ] Prove key ownership: add the console's snippet to `app/src/main/assets/` (`adi-registration.properties`), build a release signed with the release key, upload it.
- [ ] Nothing extra for F-Droid while it publishes Locus's own signed APKs (reproducible builds). If F-Droid ever signs with its own key, register that key for `dev.locus` too, following the [open-source registration guide](https://developer.android.com/developer-verification/guides/open-source-app-registration).

## Rules of thumb

- Never move or reuse a tag once it's pushed: F-Droid may already have built it.
- One tag per versionCode. If a release is broken, ship the next patch version.
- The prebuilt proot libraries in `core/linux/src/main/jniLibs` must be what `tools/build-proot.sh` produces from its pinned sources; F-Droid rebuilds them and the APKs only match if they're identical. If you change the script or its pins, run it and commit the output in the same change.
