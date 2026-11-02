package dev.locus.buildlogic

import com.google.devtools.ksp.gradle.KspAATask
import org.gradle.api.Project
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/** Held by every KSP task in the build; one at a time. See [serializeKspTasks]. */
abstract class KspSerialService : BuildService<BuildServiceParameters.None>

/**
 * Runs KSP tasks one at a time, across all modules, while Gradle still runs everything else in parallel.
 *
 * KSP 2 runs each module's processing inside the Gradle daemon, and every module shares one copy of
 * its Analysis API classes and their static state. In KSP 2.3.13, a run that finishes clears the
 * shared jar handler cache while other runs are still resolving types, and those runs then lose
 * library and project classes: Hilt either fails ("'BackupManager' could not be resolved",
 * "cannot find required type androidx.work.ListenableWorker") or silently generates nothing, which
 * ships an APK that crashes on the first `@HiltViewModel`. https://github.com/google/ksp/issues/3286
 * (regression from https://github.com/google/ksp/commit/4933bd9891016c225c6b198ca59cda5f72a91abe;
 * fix proposed in https://github.com/google/ksp/pull/3290). KSP runs that don't overlap can't race.
 *
 * A run that loses classes this way can still succeed with no output, and Gradle then treats that
 * empty output as up to date (and caches it) until the task is rerun. [KSP_SERIAL_CACHE_KEY] is a
 * task input so outputs cached before this fix are never reused.
 *
 * Remove this once a KSP release contains the fix and a loop of clean parallel builds stays green.
 */
internal fun Project.serializeKspTasks() {
    val service = gradle.sharedServices.registerIfAbsent("locusKspSerial", KspSerialService::class.java) {
        maxParallelUsages.set(1)
    }
    tasks.withType(KspAATask::class.java).configureEach {
        usesService(service)
        inputs.property("locus.kspSerial", KSP_SERIAL_CACHE_KEY)
    }
}

/** Bump to discard every KSP output in local and CI build caches. */
private const val KSP_SERIAL_CACHE_KEY = 1
