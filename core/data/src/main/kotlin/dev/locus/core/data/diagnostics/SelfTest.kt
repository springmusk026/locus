package dev.locus.core.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.SecretScrubber
import dev.locus.core.data.R
import dev.locus.core.data.auth.ProviderAccess
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.database.DatabaseCheck
import dev.locus.core.datastore.DataStoreFiles
import dev.locus.core.datastore.SecretStore
import dev.locus.core.datastore.VaultCheck
import dev.locus.core.datastore.VaultState
import dev.locus.core.linux.EngineText
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
enum class SelfTestFix(@param:StringRes val labelRes: Int) {
    /** Run the check again (a passing failure: the key before the first unlock, a timeout). */
    RunAgain(R.string.data_selftest_fix_run_again),

    /** The vault's confirmed reset (a copy of the old keys is kept): asks in a confirmation sheet first. */
    ResetSavedKeys(R.string.data_selftest_fix_reset_keys),

    /** Android's notification settings for Locus. */
    NotificationSettings(R.string.data_selftest_fix_notifications),

    /** Settings → About & privacy → Diagnostics → Storage. */
    OpenStorage(R.string.data_selftest_fix_storage),

    /** The Linux health screen, where each Linux problem has its own fix. */
    OpenLinux(R.string.data_selftest_fix_linux),

    /** Settings → Models, to check a provider's key or address. */
    OpenModels(R.string.data_selftest_fix_models),
}

/** One line of the self-test: [id] is stable per check ("vault", "provider:<id>"). Its text is only shown. */
data class SelfTestResult(
    val id: String,
    val title: EngineText,
    val level: SelfTestLevel,
    val detail: EngineText,
    val fix: SelfTestFix? = null,
)

/** A check the self-test will run: its [id] and [title] show as a pending row before it runs. */
class SelfTestCheck(val id: String, val title: EngineText, val run: suspend () -> SelfTestResult)

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
        if (providers) cloudProviders().forEach { p -> add(SelfTestCheck(providerId(p.id), EngineText.Plain(p.name)) { provider(p) }) }
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
            null -> if (outcome.getOrNull() == null) {
                (PROVIDER_TIMEOUT_MS / 1000).toInt().let { ProviderError(EngineText.Plural(R.plurals.data_selftest_provider_timeout, it), null) }
            } else {
                null
            }
            is LlmException -> ProviderError(EngineText.Plain(scrubber.scrub(e.message.orEmpty())), e.httpStatus)
            else -> ProviderError(EngineText.Plain(scrubber.scrub(e.message ?: e::class.java.simpleName)), null)
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

/** A provider check's failure, already scrubbed of secrets: the server's own words, or Locus's. */
data class ProviderError(val message: EngineText, val httpStatus: Int?)

/** How each check's facts become a result: no Android here, so it's tested on plain values. */
object SelfTestRules {
    val VAULT_TITLE: EngineText get() = EngineText.Res(R.string.data_selftest_vault_title)
    val DATABASE_TITLE: EngineText get() = EngineText.Res(R.string.data_selftest_database_title)
    val STORAGE_TITLE: EngineText get() = EngineText.Res(R.string.data_selftest_storage_title)
    val NOTIFICATIONS_TITLE: EngineText get() = EngineText.Res(R.string.data_selftest_notifications_title)
    val LINUX_TITLE: EngineText get() = EngineText.Res(R.string.data_selftest_linux_title)

    private fun res(id: Int, vararg args: Any) = EngineText.Res(id, *args)

    fun vault(check: VaultCheck): SelfTestResult {
        val id = SelfTest.VAULT
        return when (val state = check.state) {
            VaultState.Ready -> when (val problem = check.problem) {
                null -> SelfTestResult(
                    id, VAULT_TITLE, SelfTestLevel.Pass,
                    when (val n = check.entries ?: 0) {
                        0 -> res(R.string.data_selftest_vault_ok_empty)
                        else -> EngineText.Plural(R.plurals.data_selftest_vault_ok, n)
                    },
                )
                else -> SelfTestResult(id, VAULT_TITLE, SelfTestLevel.Fail, EngineText.Plain(problem), SelfTestFix.RunAgain)
            }
            // The same words as VaultState.userMessage, which other features show in English.
            VaultState.Locked -> SelfTestResult(id, VAULT_TITLE, SelfTestLevel.Warn, res(R.string.data_selftest_vault_locked), SelfTestFix.RunAgain)
            is VaultState.NeedsReset -> SelfTestResult(
                id, VAULT_TITLE, SelfTestLevel.Fail,
                res(R.string.data_selftest_vault_needs_reset, state.reason),
                SelfTestFix.ResetSavedKeys,
            )
            VaultState.NotOpened, is VaultState.Unavailable -> SelfTestResult(
                id, VAULT_TITLE, SelfTestLevel.Fail,
                (state as? VaultState.Unavailable)?.reason?.let { res(R.string.data_selftest_vault_unavailable_reason, it) }
                    ?: res(R.string.data_selftest_vault_unavailable),
                SelfTestFix.RunAgain,
            )
        }
    }

    /** [problems] from `PRAGMA quick_check` (empty: fine), or null when the check itself failed with [error]. */
    fun database(problems: List<String>?, error: String?): SelfTestResult = when {
        problems == null -> SelfTestResult(
            SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Fail,
            error?.let { res(R.string.data_selftest_database_failed_reason, it) } ?: res(R.string.data_selftest_database_failed),
            SelfTestFix.RunAgain,
        )
        problems.isEmpty() -> SelfTestResult(SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Pass, res(R.string.data_selftest_database_ok))
        else -> SelfTestResult(
            SelfTest.DATABASE, DATABASE_TITLE, SelfTestLevel.Fail,
            EngineText.Plural(R.plurals.data_selftest_database_problems, problems.size, problems.size, problems.take(3).joinToString("; ").take(400)),
        )
    }

    fun storage(free: Long?, total: Long?): SelfTestResult {
        if (free == null || total == null || total <= 0) {
            return SelfTestResult(SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Warn, res(R.string.data_selftest_storage_unknown), SelfTestFix.RunAgain)
        }
        val freeText = LinuxHealth.bytesText(free)
        val totalText = LinuxHealth.bytesText(total)
        return when {
            free < LinuxHealth.STORAGE_PROBLEM_BYTES -> SelfTestResult(
                SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Fail,
                res(R.string.data_selftest_storage_full, freeText, totalText), SelfTestFix.OpenStorage,
            )
            free < LinuxHealth.STORAGE_WARNING_BYTES -> SelfTestResult(
                SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Warn,
                res(R.string.data_selftest_storage_low, freeText, totalText), SelfTestFix.OpenStorage,
            )
            else -> SelfTestResult(SelfTest.STORAGE, STORAGE_TITLE, SelfTestLevel.Pass, res(R.string.data_selftest_storage_ok, freeText, totalText))
        }
    }

    /** [permission] is POST_NOTIFICATIONS on Android 13+, null before (no runtime permission). */
    fun notifications(enabled: Boolean, permission: Boolean?): SelfTestResult =
        if (enabled && permission != false) {
            SelfTestResult(SelfTest.NOTIFICATIONS, NOTIFICATIONS_TITLE, SelfTestLevel.Pass, res(R.string.data_selftest_notifications_on))
        } else {
            SelfTestResult(
                SelfTest.NOTIFICATIONS, NOTIFICATIONS_TITLE, SelfTestLevel.Warn,
                res(R.string.data_selftest_notifications_off),
                SelfTestFix.NotificationSettings,
            )
        }

    fun linux(installed: Boolean, report: LinuxReport?): SelfTestResult {
        if (!installed) return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, SelfTestLevel.Skipped, res(R.string.data_selftest_linux_not_installed))
        if (report == null) {
            return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, SelfTestLevel.Fail, res(R.string.data_selftest_linux_failed), SelfTestFix.OpenLinux)
        }
        val checks = LinuxHealth.checks(report)
        val bad = checks.count { it.level != HealthLevel.Ok }
        val level = when (LinuxHealth.overall(checks)) {
            HealthLevel.Ok -> SelfTestLevel.Pass
            HealthLevel.Warning -> SelfTestLevel.Warn
            HealthLevel.Problem -> SelfTestLevel.Fail
        }
        val detail = if (bad == 0) {
            EngineText.Plural(R.plurals.data_selftest_linux_passed, checks.size, LinuxHealth.headline(checks), checks.size)
        } else {
            res(R.string.data_selftest_linux_attention, LinuxHealth.headline(checks), bad, checks.size)
        }
        return SelfTestResult(SelfTest.LINUX, LINUX_TITLE, level, detail, SelfTestFix.OpenLinux.takeIf { bad > 0 })
    }

    /** [missing]: why nothing was sent; else [listed] models, or the [error]. */
    fun provider(p: ProviderConfig, missing: String?, listed: Int?, error: ProviderError?): SelfTestResult {
        val id = SelfTest.providerId(p.id)
        val name = EngineText.Plain(p.name)
        return when {
            missing != null -> SelfTestResult(id, name, SelfTestLevel.Fail, res(R.string.data_selftest_provider_not_checked, missing), SelfTestFix.OpenModels)
            error == null && listed != null ->
                SelfTestResult(id, name, SelfTestLevel.Pass, EngineText.Plural(R.plurals.data_selftest_provider_listed, listed))
            error?.httpStatus == 401 || error?.httpStatus == 403 -> SelfTestResult(
                id, name, SelfTestLevel.Fail,
                if ((error.message as? EngineText.Plain)?.value?.isBlank() == true) res(R.string.data_selftest_provider_refused, error.httpStatus)
                else res(R.string.data_selftest_provider_refused_message, error.httpStatus, error.message),
                SelfTestFix.OpenModels,
            )
            // Some servers (a local llama.cpp, a proxy) don't list models but still chat.
            error?.httpStatus == 404 || error?.httpStatus == 405 ->
                SelfTestResult(id, name, SelfTestLevel.Warn, res(R.string.data_selftest_provider_no_list, error.httpStatus))
            else -> SelfTestResult(
                id, name, SelfTestLevel.Fail,
                res(
                    R.string.data_selftest_provider_unreachable, p.baseUrl,
                    error?.message?.let { m -> (m as? EngineText.Plain)?.let { EngineText.Plain(it.value.take(300)) } ?: m } ?: res(R.string.data_selftest_provider_no_answer),
                ),
                SelfTestFix.RunAgain,
            )
        }
    }
}
