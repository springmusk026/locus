package dev.locus.core.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.database.DatabaseCheck
import dev.locus.core.datastore.DataStoreFiles
import dev.locus.core.datastore.SecretStore
import dev.locus.core.datastore.VaultCheck
import dev.locus.core.datastore.VaultState
import dev.locus.core.datastore.userMessage
import dev.locus.core.linux.HealthLevel
import dev.locus.core.linux.LinuxEnvironment
import dev.locus.core.linux.LinuxHealth
import dev.locus.core.linux.LinuxProbe
import dev.locus.core.linux.LinuxReport
import dev.locus.core.llm.LlmException
import dev.locus.core.model.ApiFormat
import dev.locus.core.model.ProviderConfig
import dev.locus.core.model.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** How a self-test check came out. */
enum class SelfTestLevel { Pass, Warn, Fail, Skipped }

/** What a check's button does; the self-test screen runs each one. Every one is reversible or confirmed first. */
enum class SelfTestFix(val label: String) {
    /** Run the check again (a passing failure: the key before the first unlock, a timeout). */
    RunAgain("Try again"),

    /** The vault's confirmed reset (a copy of the old keys is kept): asks in a confirmation sheet first. */
    ResetSavedKeys("Reset keys"),

    /** Android's notification settings for Locus. */
    NotificationSettings("Turn on"),

    /** Settings → About & privacy → Diagnostics → Storage. */
    OpenStorage("Free space"),

    /** The Linux health screen, where each Linux problem has its own fix. */
    OpenLinux("Open Linux"),

    /** Settings → Models, to check a provider's key or address. */
    OpenModels("Open Models"),
}

/** One line of the self-test: [id] is stable per check ("vault", "provider:<id>"). */
data class SelfTestResult(
    val id: String,
    val title: String,
    val level: SelfTestLevel,
    val detail: String,
    val fix: SelfTestFix? = null,
)

/** A check the self-test will run: its [id] and [title] show as a pending row before it runs. */
class SelfTestCheck(val id: String, val title: String, val run: suspend () -> SelfTestResult)

/**
 * Locus's self-test (docs/design/diagnostics.md, "Self-test"): local checks of what Locus needs to work,
 * each a pass, warning or failure with a fix where one exists. Every check but the providers' stays on
 * the phone. The provider check is opt-in: it sends one request to each configured provider's own
 * address with its own key (the model list), and nothing else, and its results are scrubbed of the
 * vault's values before they're shown. Nothing here is recorded, sent elsewhere or given to a model.
 */
@Singleton
class SelfTest @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val secrets: SecretStore,
    private val database: DatabaseCheck,
    private val linux: LinuxEnvironment,
    private val settings: SettingsRepository,
    private val access: ProviderAccess,
) {
    /**
     * The checks to run, in order. With [providers], one more per cloud provider: those reach the
     * network, so they run only when the user asks.
     */
    suspend fun checks(providers: Boolean): List<SelfTestCheck> = buildList {
        add(SelfTestCheck(VAULT, SelfTestRules.VAULT_TITLE) { vault() })
        add(SelfTestCheck(DATABASE, SelfTestRules.DATABASE_TITLE) { database() })
        add(SelfTestCheck(STORAGE, SelfTestRules.STORAGE_TITLE) { storage() })
        add(SelfTestCheck(NOTIFICATIONS, SelfTestRules.NOTIFICATIONS_TITLE) { notifications() })
        add(SelfTestCheck(LINUX, SelfTestRules.LINUX_TITLE) { linux() })
        if (providers) cloudProviders().forEach { p -> add(SelfTestCheck(providerId(p.id), p.name) { provider(p) }) }
    }

    /** The providers the opt-in check would contact (on-device ones need no network). */
    suspend fun cloudProviders(): List<ProviderConfig> = settings.current().providers.filter { it.format != ApiFormat.ON_DEVICE }

    private suspend fun vault(): SelfTestResult =
        SelfTestRules.vault(secrets.selfCheck(DataStoreFiles.file(context, DataStoreFiles.SECRETS)))

    private suspend fun database(): SelfTestResult {
        val problems = runCatchingCancellable { database.quickCheck() }
        return SelfTestRules.database(problems.getOrNull(), problems.exceptionOrNull()?.let { it::class.java.simpleName })
    }

    private suspend fun storage(): SelfTestResult = withContext(Dispatchers.IO) {
        val fs = runCatching { StatFs(context.filesDir.path) }.getOrNull()
        SelfTestRules.storage(fs?.availableBytes, fs?.totalBytes)
    }

    private fun notifications(): SelfTestResult {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            null
        }
        return SelfTestRules.notifications(NotificationManagerCompat.from(context).areNotificationsEnabled(), permission)
    }

    /** Linux's own health check ([LinuxProbe], [LinuxHealth]), summed up in one line; its screen has the details and fixes. */
    private suspend fun linux(): SelfTestResult {
        if (!linux.isInstalled) return SelfTestRules.linux(installed = false, report = null)
        val report = runCatchingCancellable { LinuxProbe(linux).report() }.getOrNull()
        return SelfTestRules.linux(installed = true, report = report)
    }

    private suspend fun provider(p: ProviderConfig): SelfTestResult {
        // Nothing is sent for a provider that can't answer anyway (no key, not signed in, address to confirm).
        access.missing(p)?.let { return SelfTestRules.provider(p, missing = it, listed = null, error = null) }
        val outcome = runCatchingCancellable { withTimeoutOrNull(PROVIDER_TIMEOUT_MS) { access.listModels(p, null).size } }
        val scrubber = SecretScrubber(runCatchingCancellable { secrets.valuesForScrubbing() }.getOrDefault(emptyList()))
        val error = when (val e = outcome.exceptionOrNull()) {
            null -> if (outcome.getOrNull() == null) ProviderError("No answer in ${PROVIDER_TIMEOUT_MS / 1000} seconds.", null) else null
            is LlmException -> ProviderError(scrubber.scrub(e.message.orEmpty()), e.httpStatus)
            else -> ProviderError(scrubber.scrub(e.message ?: e::class.java.simpleName), null)
        }
        return SelfTestRules.provider(p, missing = null, listed = outcome.getOrNull(), error = error)
    }

    companion object {
        const val VAULT = "vault"
        const val DATABASE = "database"
        const val STORAGE = "storage"
        const val NOTIFICATIONS = "notifications"
        const val LINUX = "linux"
        const val PROVIDER_TIMEOUT_MS = 20_000L

        fun providerId(id: String) = "provider:$id"
    }
}

/** A provider check's failure, already scrubbed of secrets. */
data class ProviderError(val message: String, val httpStatus: Int?)

/** How each check's facts become a result: no Android here, so it's tested on plain values. */
object SelfTestRules {
    const val VAULT_TITLE = "Keystore and saved keys"
    const val DATABASE_TITLE = "Database"
    const val STORAGE_TITLE = "Free storage"
    const val NOTIFICATIONS_TITLE = "Notifications"
    const val LINUX_TITLE = "Linux"

    fun vault(check: VaultCheck): SelfTestResult {
        val id = SelfTest.VAULT
        return when (val state = check.state) {
            VaultState.Ready -> when (val problem = check.problem) {
                null -> SelfTestResult(
                    id, VAULT_TITLE, SelfTestLevel.Pass,
                    "The Keystore key opens, encrypts and decrypts. " +
                        when (val n = check.entries ?: 0) {
                            0 -> "No keys are saved yet."
                            1 -> "1 saved value decrypts."
                            else -> "$n saved values decrypt."
                        },
                )
                else -> SelfTestResult(id, VAULT_TITLE, SelfTestLevel.Fail, problem, SelfTestFix.RunAgain)
            }
            VaultState.Locked -> SelfTestResult(id, VAULT_TITLE, SelfTestLevel.Warn, state.userMessage().orEmpty(), SelfTestFix.RunAgain)
            is VaultState.NeedsReset -> SelfTestResult(
                id, VAULT_TITLE, SelfTestLevel.Fail,
                "Locus can't open your saved keys (${state.reason}). Resetting makes new keys; a copy of the old ones is kept in case they can be recovered.",
                SelfTestFix.ResetSavedKeys,
            )
            VaultState.NotOpened, is VaultState.Unavailable -> SelfTestResult(
                id, VAULT_TITLE, SelfTestLevel.Fail,
                "Locus can't open your saved keys right now" + ((state as? VaultState.Unavailable)?.reason?.let { " ($it)" }.orEmpty()) +
                    ". Nothing was changed; this can pass on its own.",
                SelfTestFix.RunAgain,
            )
        }
    }

    /** [problems] from `PRAGMA quick_check` (empty: fine), or null when the check itself failed with [error]. */
    fun database(problems: List<String>?, error: String?): SelfTestResult = when {
        problems == null -> SelfTestResult(
            SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Fail,
            "The integrity check couldn't run${error?.let { " ($it)" }.orEmpty()}.", SelfTestFix.RunAgain,
        )
        problems.isEmpty() -> SelfTestResult(SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Pass, "The integrity check (quick_check) found no problems.")
        else -> SelfTestResult(
            SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Fail,
            "The integrity check found ${problems.size} problem${if (problems.size == 1) "" else "s"}: ${problems.take(3).joinToString("; ").take(400)}. " +
                "Save a backup in Settings → Backup & restore while Locus still reads your chats.",
        )
    }

    fun storage(free: Long?, total: Long?): SelfTestResult {
        if (free == null || total == null || total <= 0) {
            return SelfTestResult(SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Warn, "Android didn't say how much space is free.", SelfTestFix.RunAgain)
        }
        val line = "${LinuxHealth.bytes(free)} free of ${LinuxHealth.bytes(total)}."
        return when {
            free < LinuxHealth.STORAGE_PROBLEM_BYTES -> SelfTestResult(
                SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Fail,
                "$line Saving chats, files and downloads may fail.", SelfTestFix.OpenStorage,
            )
            free < LinuxHealth.STORAGE_WARNING_BYTES -> SelfTestResult(
                SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Warn,
                "$line Large downloads (an on-device model, Linux tools) may not fit.", SelfTestFix.OpenStorage,
            )
            else -> SelfTestResult(SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Pass, line)
        }
    }

    /** [permission] is POST_NOTIFICATIONS on Android 13+, null before (no runtime permission). */
    fun notifications(enabled: Boolean, permission: Boolean?): SelfTestResult =
        if (enabled && permission != false) {
            SelfTestResult(SelfTest.NOTIFICATIONS, NOTIFICATIONS_TITLE, SelfTestLevel.Pass, "On. Approvals, questions and finished tasks can reach you.")
        } else {
            SelfTestResult(
                SelfTest.NOTIFICATIONS, NOTIFICATIONS_TITLE, SelfTestLevel.Warn,
                "Off. While Locus isn't on screen, approvals and questions wait unseen and scheduled tasks can't tell you they're done.",
                SelfTestFix.NotificationSettings,
            )
        }

    fun linux(installed: Boolean, report: LinuxReport?): SelfTestResult {
        if (!installed) return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, SelfTestLevel.Skipped, "Not installed, so not checked.")
        if (report == null) {
            return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, SelfTestLevel.Fail, "Linux's health check couldn't run.", SelfTestFix.OpenLinux)
        }
        val checks = LinuxHealth.checks(report)
        val bad = checks.count { it.level != HealthLevel.Ok }
        val level = when (LinuxHealth.overall(checks)) {
            HealthLevel.Ok -> SelfTestLevel.Pass
            HealthLevel.Warning -> SelfTestLevel.Warn
            HealthLevel.Problem -> SelfTestLevel.Fail
        }
        val detail = if (bad == 0) {
            "${LinuxHealth.headline(checks)}: ${checks.size} checks passed."
        } else {
            "${LinuxHealth.headline(checks)}. $bad of ${checks.size} checks need attention; each has its fix on the Linux screen."
        }
        return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, level, detail, SelfTestFix.OpenLinux.takeIf { bad > 0 })
    }

    /** [missing]: why nothing was sent; else [listed] models, or the [error]. */
    fun provider(p: ProviderConfig, missing: String?, listed: Int?, error: ProviderError?): SelfTestResult {
        val id = SelfTest.providerId(p.id)
        return when {
            missing != null -> SelfTestResult(id, p.name, SelfTestLevel.Fail, "Not checked: $missing", SelfTestFix.OpenModels)
            error == null && listed != null ->
                SelfTestResult(id, p.name, SelfTestLevel.Pass, "Reachable with its key: ${listed} model${if (listed == 1) "" else "s"} listed.")
            error?.httpStatus == 401 || error?.httpStatus == 403 ->
                SelfTestResult(id, p.name, SelfTestLevel.Fail, "The key was refused (HTTP ${error.httpStatus}). ${error.message}".trim(), SelfTestFix.OpenModels)
            // Some servers (a local llama.cpp, a proxy) don't list models but still chat.
            error?.httpStatus == 404 || error?.httpStatus == 405 ->
                SelfTestResult(id, p.name, SelfTestLevel.Warn, "It answered but doesn't list models (HTTP ${error.httpStatus}); chats may still work.")
            else -> SelfTestResult(
                id, p.name, SelfTestLevel.Fail,
                "Couldn't reach ${p.baseUrl}: ${error?.message?.take(300) ?: "no answer"}", SelfTestFix.RunAgain,
            )
        }
    }
}
