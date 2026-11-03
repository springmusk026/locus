# CI

`.github/workflows/ci.yml` checks every pull request. It used to be one job that ran unit tests,
lint, a debug build and a release build one after another (18–20 minutes). It is now four Gradle jobs
that run side by side, plus two small jobs around them. Instrumentation tests
(`instrumentation.yml`) and releases (`release.yml`) are separate workflows and are unchanged.

## Jobs

| Job | What it runs | NDK |
|---|---|---|
| `changes` | Lists the PR's files; says whether anything besides docs changed | – |
| `unit-tests` | `testDebugUnitTest :core:agent:test :core:llm:test :core:model:test`, then the Kover reports (artifact `coverage`) | no |
| `lint` | `lintDebug` | no |
| `debug-apk` | `:app:assembleDebug` (artifact `locus-debug-apk`) | yes |
| `release-r8` | `:app:assembleRelease -Plocus.abi=arm64-v8a`: R8 plus `verifyReleaseR8KeptNames` | yes |
| `build` | Needs all of the above; the one required check | – |

The four Gradle jobs are a [matrix](https://docs.github.com/en/actions/using-jobs/using-a-matrix-for-your-jobs)
with `fail-fast: false`, so one failure doesn't hide another. `build`
[needs](https://docs.github.com/en/actions/using-jobs/using-jobs-in-a-workflow#defining-prerequisite-jobs)
every job and runs with `if: always()`, so branch protection, `gh pr checks` and the merge tooling see
one check called `build`, as before. It fails unless every Gradle job passed, or all of them were skipped
because the PR changes only docs.

The release R8 build runs on every PR and is part of `build`. A missing keep rule once broke SSH in
release builds only. R8 is the slowest step (about 5 minutes), so `release-r8` is the job that decides
how long CI takes.

Only `debug-apk` and `release-r8` install the NDK: `core:terminal` runs ndk-build (libtermux.so) only
when an APK is packaged. Unit tests and lint never run `buildNdkBuild*`. `sdkmanager` installs the NDK
in about 10 seconds, which is faster than restoring a 1–2 GB cache entry, so the NDK is not cached.

## Gradle caching

- `gradle.properties` already turns on the Gradle build cache (`org.gradle.caching`), the configuration
  cache and parallel execution. KSP tasks still run one at a time, on purpose (`Ksp.kt`,
  google/ksp#3286).
- [`gradle/actions/setup-gradle`](https://github.com/gradle/actions/blob/v6.4.1/docs/setup-gradle.md)
  saves Gradle User Home after each job, including `caches/build-cache-1`, the local build cache.
  Cache keys include the job id and a hash of the matrix values, so each Gradle job gets its own
  entry. By default only the default branch writes to the cache; PRs only read from it
  ([cache-read-only](https://github.com/gradle/actions/blob/v6.4.1/docs/setup-gradle.md#using-the-cache-read-only)).
  Now that main isn't rebuilt on push, the nightly run on main is what refreshes these entries.
- Lint analysis tasks are incremental and cacheable
  ([AGP 7.0: lint tasks can be UP-TO-DATE](https://developer.android.com/build/releases/past-releases/agp-7-0-0-release-notes#lint-behavior-changes)).
  Their results come from the build cache for modules a PR doesn't touch.
- The configuration cache works locally and inside each CI job. setup-gradle has a
  [`cache-encryption-key`](https://github.com/gradle/actions/blob/v6.4.1/docs/setup-gradle.md#cache-encryption-key)
  input for configuration-cache data it stores in the Actions cache; it isn't set here. Configuring
  the build takes about 15 seconds per job, so carrying the configuration cache between jobs would
  save little.

## Draft pull requests

The workflow runs on `pull_request` types `opened`, `synchronize`, `reopened` and
`ready_for_review`. Every job has `if: github.event.pull_request.draft != true`. While a PR is a
draft, the run shows as skipped and nothing is built. Marking it ready for review starts a full run,
and that run reports `build`.

## Docs-only changes

A PR that changes only files under `docs/` or Markdown files at the top level (`README.md`,
`CHANGELOG.md`…) skips the four Gradle jobs. `build` still runs and passes.

This doesn't use `paths-ignore` on the workflow. GitHub's
[Handling skipped but required checks](https://docs.github.com/en/pull-requests/collaborating-with-pull-requests/collaborating-on-repositories-with-code-quality-features/troubleshooting-required-status-checks#handling-skipped-but-required-checks)
says a workflow skipped by path filtering leaves its checks "Pending", which blocks merging, while a
job skipped by a conditional "reports Success". So the `changes` job lists the PR's files with the REST
API (`gh api …/pulls/N/files`). It needs no third-party action. The Gradle jobs then have
`if: needs.changes.outputs.code == 'true'`. A Markdown file anywhere else, such as a `NOTICE.md` or a
skill, counts as code.

## Main is not rebuilt on push

There is no `push` trigger. Main only receives squash merges of PR heads that were tested up to date
with main. When a squash merge is made from a head that already contains main, its tree is exactly
the tree that head had, and CI already ran on that tree. Building main again would repeat the same
work with the same inputs.

This relies on the merge tooling: it merges only green PRs that are up to date with main. GitHub
does not enforce this for this repository, because there is no branch protection or ruleset that
requires up-to-date branches. A merge made by hand from a stale branch, or with a merge or rebase
commit, gives main a tree no run has seen. Two runs on main cover that:

- **Nightly** (`schedule`, 02:00 UTC): the full suite on main. It also refreshes the Gradle caches
  that PRs read. GitHub turns off scheduled workflows in a public repository after 60 days without
  activity.
- **On demand**: Actions → CI → Run workflow (`workflow_dispatch`), on main or any branch.

Nothing depends on a CI artifact from main. Releases build their own APKs from the tag
(`release.yml`, a full `assembleRelease` with R8 and the kept-names check). The README badge shows
the latest run on main, which is now the nightly run.

## Cancellation

Pushing again to a PR cancels that PR's run that is still going (`cancel-in-progress` for
`pull_request` events). Nightly and manual runs are not cancelled.
