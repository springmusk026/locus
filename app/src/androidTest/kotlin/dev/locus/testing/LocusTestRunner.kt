package dev.locus.testing

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.CustomTestApplication
import dev.locus.LocusStartup

/**
 * The instrumentation runner (app/build.gradle.kts): runs every test in [LocusTestApplication_Application],
 * Hilt's test application built on [LocusTestApplicationBase].
 * https://developer.android.com/training/dependency-injection/hilt-testing#instrumented-tests
 */
class LocusTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application =
        super.newApplication(cl, LocusTestApplication_Application::class.java.name, context)
}

/**
 * What Hilt's test application extends. Unlike plain HiltTestApplication it starts the way
 * LocusApplication does: a confirmed restore is applied in attachBaseContext, before anything opens
 * Locus's files, so RestoreTest exercises the real startup path.
 *
 * Per Hilt's rules (https://dagger.dev/hilt/testing#custom-test-application) it has no @Inject fields
 * and doesn't touch the graph, which exists only once a test's HiltAndroidRule runs. The rest of
 * startup ([LocusStartup.afterInjection]) runs from [LocusTestRule] after injection instead. Not
 * done here: making the process non-dumpable and installing the crash recorder, which would get in
 * the way of the test tools.
 */
open class LocusTestApplicationBase : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        rescheduleTasks = LocusStartup.beforeProviders(base)
    }

    companion object {
        /** Whether this process's start owes a reschedule of scheduled tasks (a restore ran). */
        @Volatile var rescheduleTasks: Boolean = false
            private set
    }
}

@CustomTestApplication(LocusTestApplicationBase::class)
interface LocusTestApplication
