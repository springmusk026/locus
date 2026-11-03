# Linux setup in the Workspace

Status: built (Unreleased), device check pending. Item W2 in the wiring audit (WP-5a).

Code:

- `core/data/.../linux/LinuxSetup.kt`: the use case, with `LinuxPackage`, `PackageState` and `LinuxSetupState`.
- `LinuxSetupWorker.kt`: the job.
- `LinuxPanel.kt`: the card's state machine and the install estimate.
- `core/linux/.../LinuxEnvironment.kt`: progress, cancel, `repair()`, `diskUsage()` and the `androidKill` notice.
- `feature/workspace/.../linux/`: `LinuxCard` and `LinuxViewModel`.

Tests: `LinuxSetupTest` and `LinuxPanelTest` (core/data).

## Problem

Linux could only be installed from onboarding's Workspace step ("Settings → About & privacy → Run setup again → Workspace"). The install ran in `OnboardingViewModel.viewModelScope`, so two things went wrong:

- Leaving setup cancelled it halfway. `ensureInstalled` then caught the cancellation as a failure.
- Nothing else could start it. The Workspace screen, which the home door calls "Workspace files and Linux", showed no Linux status at all.

## Design

**One use case.** `LinuxSetup` (a singleton in core/data) owns installing Linux and the offered packages, plus repair, reinstall and removal, and the state the screens read. Onboarding and the Workspace card both call it. Neither holds install logic.

**Where the install runs.** Install and Reinstall download for seconds to minutes: 4 MB for Alpine, and up to about 40 MB more with tools. They run as a WorkManager job, `LinuxSetupWorker`. The job is expedited (`RUN_AS_NON_EXPEDITED_WORK_REQUEST` when out of quota) and runs in the foreground with a `dataSync` notification that shows progress and has a Cancel action.

- The job calls `LinuxSetup.execute`. That updates the same in-process `StateFlow` the card and onboarding collect, so the screens need no `WorkInfo` plumbing.
- Repair and Remove take a second or two and touch only local files, so they run in the application scope.
- One operation runs at a time.

Why a worker and not the alternatives:

- **The application scope alone.** That survives leaving the screen, but not leaving the app. Android may kill a cached process, and on Android 12+ the phantom-process killer stops CPU-heavy children of background apps. `apk add` under proot is exactly that.
- **A dedicated foreground service.** It would work, but would duplicate what WorkManager gives for free: a unique-work name so a second tap can't start a second install, cancellation, and restart after process death. `ModelDownloadWorker` already takes this route for model downloads, and the `dataSync` service type is already declared in core/ondevice's manifest.
- **Data sync is the right type.** Android's [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync) list "downloading files" under `dataSync`. WorkManager's [long-running workers guide](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) is the documented way to run one from a worker. If Android refuses the foreground start (the app went to the background first), the work still runs as ordinary expedited work.
- **No automatic retry.** A failure says why on the card, and the user decides to try again. The download is third-party software that F-Droid hasn't checked, so it must stay opt-in (see `LinuxEnvironment.ensureInstalled`).

**Cancel.**

- `LinuxEnvironment` checks for cancellation between download chunks and around unpacking. On a cancel it deletes the archive and the partial root filesystem and goes back to "not installed".
- A cancelled `run()` (for example during `apk add`) now stops its proot process instead of leaving it running.
- Cancelling a job that hasn't started yet resets the card at once.

**Card states** (`linuxPanel`, pure and tested):

| State | Shows |
|---|---|
| Unavailable | Why this phone can't run it. Nothing to tap. |
| Not installed | The third-party notice, tool chips, an estimate, and Install |
| Working | The step, a `Meter` (determinate while downloading or adding packages, indeterminate while unpacking), and Cancel for installs |
| Installed | <ul><li>Version, space used and running services, each with Stop</li><li>Add tools</li><li>Open terminal and Repair</li><li>Reinstall and Remove, each behind a `ConfirmSheet`</li><li>An `onOpenHealth` slot for the Linux health screen</li></ul> |
| Failed | `ErrorState` with the reason and Try again |
| Killed by Android | A caution `Banner` on the Installed state, with `AndroidKill.REASON` and `.FIX` from #113 and a link to the guide. Commands, services and terminal tabs all record kills. |

A running operation takes precedence over the status, so a reinstall that passes through "not installed" stays "Reinstalling". Unavailable takes precedence over everything.

**Estimates.** Alpine 3.24.3's minirootfs is 4.0 MB (`Content-Length` from dl-cdn.alpinelinux.org) and 8.5 MB unpacked. Package sizes come from pkgs.alpinelinux.org (v3.24, aarch64, 2026-11-02), rounded up for dependencies. The time assumes about 1 MB/s, plus proot's slower unpack and apk: 20 s for the base, 30 s per package and 15 s for `apk update`. It says "about" because it's rough.

**Repair** rewrites what Locus manages inside Linux (resolv.conf, hosts and the fake /proc files), re-creates its folders and re-links proot's libraries, then runs `cat /etc/alpine-release`. It keeps packages and the workspace. If BusyBox or the release file is gone, it says to reinstall. It doesn't empty /tmp, because running services and terminals use it.

**Reinstall** removes Linux and installs it again with the offered tools that were installed. **Remove** stops services and closes terminal tabs first. Neither touches the workspace.

**Paths to the card.**

- `LINUX_INSTALL_PATH` is now "Home → Files → Linux", and the button is `LINUX_INSTALL_ACTION` ("Install Linux"). The agent's not-installed tool result, `LinuxNotInstalledException`, local-connector errors and onboarding all quote them.
- The terminal's empty state offers "Set up Linux", which goes back to the Workspace instead of opening onboarding.

## Not done

- The chat doesn't turn the agent's "install Linux" message into a link. `ChatScreen` belongs to another work package. The message names the path instead.
- Stats beyond version, space used and services (package inventory, RAM, proot and DNS checks) belong to the Linux health screen, which opens from `onOpenHealth`.
- The notification has no "Open Linux" deep link. Tapping it opens the app.
