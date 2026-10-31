# Releasing Locus

The version lives in source, in `app/build.gradle.kts`:

```kotlin
versionCode = 3
versionName = "0.1.2"
```

Every build reads it from there: local builds, the GitHub release workflow and F-Droid's build server, which builds from the tagged commit and takes the version from source ([Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/#UpdateCheckMode), `UpdateCheckMode: Tags`). Nothing overrides it from the environment.

| Release | Tag | versionCode | versionName |
|---|---|---|---|
| 0.1.0 | `v0.1.0` | 1 | 0.1.0 |
| 0.1.1 | `v0.1.1` | 2 | 0.1.1 |
| 0.1.2 (next) | `v0.1.2` | 3 | 0.1.2 |

(0.1.0 and 0.1.1 were stamped by CI from the tag; their tagged source still says `1` / `"0.1.0"`. 0.1.2 is the first release whose tag carries its own version, so it's the first one F-Droid can build.)

## Steps

1. **Bump the version** in `app/build.gradle.kts`:
   - `versionCode`: the previous code plus one. It must only ever go up; Android refuses to update to a lower code.
   - `versionName`: the new [semantic version](https://semver.org/), without a `v`. Pre-releases use a suffix, e.g. `0.2.0-beta.1`.
2. **Write the changelogs:**
   - `CHANGELOG.md`: rename `## [Unreleased]` to `## [<versionName>] - <YYYY-MM-DD>` and start a new empty `## [Unreleased]` above it.
   - `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: a plain-text summary for F-Droid's "What's new", at most 500 characters ([All About Descriptions](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)). The file name is the versionCode, with no padding.
3. **Commit** (`Release 0.1.2`) and merge to `main`.
4. **Tag that commit** `v<versionName>` and push the tag:
   ```sh
   git tag -a v0.1.2 -m "Locus 0.1.2"
   git push origin v0.1.2
   ```
   Or run the Release workflow by hand with the tag as input; it tags the commit it runs on.
5. **The Release workflow** (`.github/workflows/release.yml`):
   - fails unless the tag is exactly `v` + `versionName` and `changelogs/<versionCode>.txt` exists;
   - runs the tests, builds the release APK (signed with Locus's key) and a debug APK, and publishes them as a GitHub release with `SHA256SUMS.txt` and the R8 mapping.
   If the check fails, fix the version or delete the tag (`git push origin :refs/tags/v0.1.2`), then tag again.
6. **F-Droid** (once Locus is in F-Droid): nothing to do. With `AutoUpdateMode: Version` and `UpdateCheckMode: Tags`, F-Droid's `checkupdates` finds the new tag, reads the version from source and adds a build ([fdroiddata CONTRIBUTING.md](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md): "F-Droid will build new versions from tags automatically"). If reproducible builds are set up (`Binaries:` in the F-Droid metadata, see [FDROID.md](FDROID.md)), F-Droid downloads `locus-v<versionName>.apk` from the GitHub release and publishes it only if its own build matches byte for byte. So:
   - don't rename the release assets;
   - don't re-upload or rebuild an APK after the tag;
   - build the published APK from a clean checkout of the tag (the workflow does).

## Rules of thumb

- Never move or reuse a tag once it's pushed: F-Droid may already have built it.
- One tag per versionCode. If a release is broken, ship the next patch version.
- The prebuilt proot libraries in `core/linux/src/main/jniLibs` must be what `tools/build-proot.sh` produces from its pinned sources; F-Droid rebuilds them and the APKs only match if they're identical. If you change the script or its pins, run it and commit the output in the same change.
