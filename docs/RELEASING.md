# Releasing Locus

The version lives in source, in `app/build.gradle.kts`:

```kotlin
versionCode = 6
versionName = "0.1.5"
```

Every build reads it from there: local builds, the GitHub release workflow and F-Droid's build server, which builds from the tagged commit and takes the version from source ([Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/#UpdateCheckMode), `UpdateCheckMode: Tags`). Nothing overrides it from the environment.

| Release | Tag | versionCode | versionName |
|---|---|---|---|
| 0.1.0 | `v0.1.0` | 1 | 0.1.0 |
| 0.1.1 | `v0.1.1` | 2 | 0.1.1 |
| 0.1.2 | `v0.1.2` | 3 | 0.1.2 |
| 0.1.3 | `v0.1.3` | 4 | 0.1.3 |
| 0.1.4 | `v0.1.4` | 5 (APKs 53, 54) | 0.1.4 |
| 0.1.5 | `v0.1.5` | 6 (APKs 63, 64) | 0.1.5 |

(0.1.0 and 0.1.1 were stamped by CI from the tag; their tagged source still says `1` / `"0.1.0"`. 0.1.2 is the first release whose tag carries its own version, so it's the first one F-Droid can build.)

## Version codes and APKs

Up to 0.1.3 a release was one universal APK whose versionCode was the number above. From 0.1.4, each release is **one APK per ABI** ([Configure multiple APKs](https://developer.android.com/build/configure-apk-splits), `splits { abi { … } }` in `app/build.gradle.kts`):

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
5. **The Release workflow** (`.github/workflows/release.yml`, see [How the workflow signs](#how-the-workflow-signs)):
   - fails unless the tag is exactly `v` + `versionName` and both per-ABI changelogs exist;
   - runs the tests and builds unsigned release APKs in one job. A second job signs them with Locus's key, attests their build provenance and publishes `locus-<tag>-arm64-v8a.apk` and `locus-<tag>-x86_64.apk` as a GitHub release, with `SHA256SUMS.txt` and the R8 mapping (one mapping: the code is the same in both APKs).
   - publishes no debug APK. A debuggable APK lets anyone with adb read the app's data, so debug builds are never signed with the release key and are a separate app (`dev.locus.debug`, "Locus Debug") that can't install over Locus. Use the R8 mapping to read release stack traces.
   If the check fails, fix the version or delete the tag (`git push origin :refs/tags/v0.1.2`), then tag again.
6. **F-Droid** (once Locus is in F-Droid): nothing to do. With `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, F-Droid's `checkupdates` finds the new tag, reads the version from source and adds a build ([fdroiddata CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md): "F-Droid will build new versions from tags automatically"). If reproducible builds are set up (`Binaries:` in the F-Droid metadata, see [FDROID.md](FDROID.md)), F-Droid downloads `locus-v<versionName>-<abi>.apk` from the GitHub release and publishes it only if its own build matches byte for byte. So:
   - don't rename the release assets;
   - don't re-upload or rebuild an APK after the tag;
   - build the published APK from a clean checkout of the tag (the workflow does).

## How the workflow signs

`release.yml` has two jobs, so the signing key and the build never share a machine:

| Job | Does | Can reach |
|---|---|---|
| **Unsigned build** | Checks the tag, runs the tests and `./gradlew :app:assembleRelease -Plocus.unsigned=true`, then uploads `locus-<tag>-<abi>.apk` (unsigned) and the mapping as the `release-unsigned` workflow artifact. | The repository, read-only. No secrets. |
| **Sign and publish** | Downloads that artifact and signs each APK with `apksigner`. Checks the signed APK is the unsigned one plus a signing block, writes `SHA256SUMS.txt`, attests build provenance and runs `gh release create`. Checks out no code and runs no Gradle. | The keystore secrets (in the `release` environment), `contents: write`, and an OIDC token for the attestation. |

So nothing the build runs (Gradle, its plugins, every dependency, the tests) can read the key or write to the repository. The signing job runs only `apksigner`, `sha256sum` and `gh`.

**Signing details.** `-Plocus.unsigned=true` builds the release APKs without any signing config (`app-<abi>-release-unsigned.apk`). The signing job uses `apksigner` from build-tools 36.1.0 with the same scheme AGP used up to 0.1.5: v2 only, no v1, v3 or v4 (`apksigner verify --print-certs` on the 0.1.5 APK shows v2 alone). `--alignment-preserved true` stops apksigner 35+ from redoing the zip padding. A step in the job then checks that every byte before the signing block, and the central directory, match the unsigned build. That is what F-Droid's signature copying needs ([FDROID.md](FDROID.md#reproducible-builds-plan)). Checked locally on 0.1.5 source: without `--alignment-preserved` the zip entries change; with it, only the signing block is added.

**Local builds** are unchanged. `./gradlew :app:assembleRelease` signs with your debug key. Set `LOCUS_KEYSTORE_FILE`, `LOCUS_KEYSTORE_PASSWORD` and `LOCUS_KEY_ALIAS` to sign with a real key. `-Plocus.unsigned=true` gives unsigned APKs, as CI and F-Droid build them.

### Signing key

The workflow reads `LOCUS_KEYSTORE_B64` (the keystore, base64), `LOCUS_KEYSTORE_PASSWORD` and `LOCUS_KEY_ALIAS` as secrets, in the sign job only. Without `LOCUS_KEYSTORE_B64` (a fork), it signs with a throwaway key and says so in the release notes.

The sign job runs in a `release` [environment](https://docs.github.com/en/actions/deployment/targeting-different-environments/using-environments-for-deployment), which GitHub creates on first use. Owner's checklist, in Settings → Environments → `release`:

- [ ] Move the three secrets from repository secrets into the environment's secrets, then delete the repository copies. Repository secrets are readable by any workflow on any branch that someone with write access pushes; environment secrets only by jobs that the environment's rules let in.
- [ ] Deployment branches and tags: "Selected branches and tags", with the tag rule `v*` and the branch `main` (a release run by hand runs on `main`).
- [ ] Optionally, required reviewers, so each release waits for a click before the key is used.

A dry run uses no environment and reads no secrets.

### Dry run

Actions → Release → Run workflow, pick a branch, tick **Dry run**. The workflow builds `v<versionName>` from that branch, signs it with a key it generates on the spot, and uploads the signed APKs, the mapping and `SHA256SUMS.txt` as the `release-dry-run` artifact. It creates no tag, no release and no attestation, and needs no environment. Use it to check changes to `release.yml`. From the command line:

```sh
gh workflow run release.yml --ref <branch> -f dry-run=true
```

### Verifying a release

Each release's APKs and mapping carry a [build provenance attestation](https://docs.github.com/en/actions/security-for-github-actions/using-artifact-attestations/using-artifact-attestations-to-establish-provenance-for-builds) (`actions/attest-build-provenance`). It's signed through Sigstore and says which workflow, commit and run built the file. To check a downloaded APK (GitHub CLI 2.49 or newer):

```sh
gh attestation verify locus-v0.1.6-arm64-v8a.apk \
  --repo springmusk026/locus \
  --signer-workflow springmusk026/locus/.github/workflows/release.yml
```

It prints the commit and the workflow run; the commit should be the one the tag points to. Also check the signing key, which is what Android checks on every update:

```sh
apksigner verify --print-certs locus-v0.1.6-arm64-v8a.apk   # SHA-256 digest 0fc4ab07…490e1b
sha256sum -c --ignore-missing SHA256SUMS.txt
```

Releases up to 0.1.5 have no attestation.

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
