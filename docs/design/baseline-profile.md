# Baseline profile and startup benchmark: plan

Status: proposal, not built (#331, part 3). This page is for deciding whether to do it and how often
it runs. Researched 2026-11-27.

## What it would give

A baseline profile is a list of classes and methods that ART compiles ahead of time when the app is
installed, instead of interpreting them on first use. Google recommends one for every Compose app,
since Compose ships as a library and starts interpreted
([Baseline Profiles overview](https://developer.android.com/topic/performance/baselineprofiles/overview)).
Locus has none (docs/research/reliability-security-audit-2026-11.md, item 9). Profiles are installed
only for release builds; debug builds don't use them.

A startup macrobenchmark measures cold start (time to initial display) with and without the profile,
so a regression shows as a number instead of a feeling.

## What it needs

| Piece | What | Notes |
|---|---|---|
| `:baselineprofile` module | `com.android.test` module, `targetProjectPath = ":app"`, plugin `androidx.baselineprofile` | Not `:bench`: that name is the agent benchmark (docs/benchmarks/README.md) |
| Generator test | `BaselineProfileRule().collect(packageName = "dev.locus") { startActivityAndWait(); ... }` | Startup, then the first chat screen and the drawer; `includeInStartupProfile = true` for the startup part only |
| Startup benchmark | `MacrobenchmarkRule` with `StartupTimingMetric`, `CompilationMode.None()` vs `CompilationMode.Partial(BaselineProfileMode.Require)` | Same module |
| `:app` | plugin `androidx.baselineprofile`, `baselineProfile(project(":baselineprofile"))`, `androidx.profileinstaller` | profileinstaller 1.4.0 is already in the graph transitively; the generated file goes to `app/src/release/generated/baselineProfiles/` and is committed |
| Versions (checked 2026-11-27) | `androidx.benchmark` / `androidx.baselineprofile` 1.5.0 (stable, 2026-09-09), `profileinstaller` 1.4.1 | 1.5.0-alpha01 dropped the need for `newDsl=false` on AGP 9 ([benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark)); 1.6.0 is alpha |
| App code | `ReportDrawnWhen { chats loaded }` in the first screen | So the profile and TTFD cover the first real content, not just the first frame |

Steps and plugin setup follow
[Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile).
The new dependencies and the new module need the owner's yes (project rule) and new checksums in
`gradle/verification-metadata.xml`.

## Does generation need an emulator?

Yes, or a phone. The generator is an instrumentation test. Google's page: run it "on a rooted
physical device, emulator, or Gradle Managed Device"; a managed device must use the `aosp` system
image "because you need root access", and since Macrobenchmark 1.2.0-alpha06 an API 33+ device works
without root. There is no JVM-only path.

- **Generation on CI** works on an emulator: the existing instrumentation workflow already boots an
  API 35 `google_apis` x86_64 emulator with KVM (`instrumentation.yml`), which is API 33+, so no root
  is needed. Alternatively a Gradle Managed Device (`aosp`, API 34) managed by the plugin.
- **Benchmark timings** on a shared emulator are noise: Macrobenchmark reports an `EMULATOR` error
  unless suppressed, and benchmark 1.4.0 added `skipBenchmarksOnEmulator` for this reason. Startup
  numbers worth comparing need a physical phone (the owner's, run locally with
  `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`), or Firebase Test Lab devices
  (supported by the plugin, but that's a Google account and billing, so not proposed).

So: generate on CI or locally, benchmark locally on a phone.

## How often

The profile only drifts when startup code changes, and a slightly stale profile still helps (missing
methods are just interpreted). Options:

1. **Before each release (recommended).** A maintainer runs `./gradlew :app:generateBaselineProfile`
   with the phone connected and commits the file, as part of the release checklist. Zero CI minutes.
2. **Monthly or manual workflow.** A `workflow_dispatch` (plus optional monthly cron) job that boots
   the emulator, generates, and opens a PR with the changed `baseline-prof.txt`. Needs
   `contents: write` and `pull-requests: write` for that one job.
3. **Nightly** (as #331 suggests). Not recommended while Actions minutes are scarce
   (docs/design/ci.md): the emulator workflow is already the costliest one.

No new job in PR CI in any option: the committed profile is just an input to `assembleRelease`, which
`release-r8` and `release.yml` already build.

## Estimated CI minutes

From the existing emulator workflow (`instrumentation.yml`: timeout 75 minutes, 20–40 minutes per run
for build, boot and the test suite):

| Step | Estimate |
|---|---|
| Build the non-minified release app and the test APK | 6–8 min (R8 is skipped for the generator variant, the NDK still builds) |
| Emulator boot (snapshot cached) | 1–2 min |
| Generation: a few journeys, 3 iterations each by default | 3–6 min |
| Total per run | about 12–16 min |

That is ~15 minutes a month with option 2, ~450 a month with option 3 (if main changes daily), and 0
with option 1. A startup benchmark on CI would add 5–10 minutes per run for numbers that an emulator
can't make reliable, so it isn't proposed for CI.

## Reproducible builds

F-Droid rebuilds the release and compares it (docs/FDROID.md). `assets/dexopt/baseline.prof` already
ships (from libraries' profiles) and matched once the dex matched; a committed app profile is an
input file like any source, so it doesn't change that. If it ever differs, docs/FDROID.md notes the
fix (disable the `ArtProfile` tasks).

## Decision needed

1. Yes or no to the new module and the `androidx.baselineprofile`, `benchmark-macro-junit4` and
   `profileinstaller` dependencies.
2. Which cadence: before each release (local phone), monthly workflow, or nightly.
