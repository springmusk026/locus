package dev.locus.core.data.logins

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.locus.core.browser.SiteLogins
import dev.locus.core.datastore.SecretStore
import dev.locus.core.datastore.SiteLoginCodec
import dev.locus.core.model.SiteLogin
import dev.locus.core.model.SiteOrigin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Site logins in the encrypted vault (docs/design/browser.md, "Saved logins"): one vault entry per login
 * (`site-login:<id>`, the whole record as JSON), so each login's origin is sealed by the vault's
 * authenticated encryption together with its password. Nothing about them is in Room or the settings.
 * One login per origin: saving another for the same origin replaces it.
 */
@Singleton
class SiteLoginStore @Inject constructor(private val vault: SecretStore) : SiteLogins {
    private val lock = Mutex()

    /** Bumped on every change, so the Saved logins screen reloads. */
    private val version = MutableStateFlow(0)
    val changes: Flow<Int> = version.asStateFlow()

    var clock: () -> Long = System::currentTimeMillis

    override suspend fun all(): List<SiteLogin> =
        vault.entries(SecretStore.SITE_LOGIN_PREFIX).values.mapNotNull(SiteLoginCodec::decode).sortedBy { it.title.lowercase() }

    override suspend fun forOrigin(origin: String): SiteLogin? {
        val exact = SiteOrigin.of(origin) ?: return null
        return all().firstOrNull { it.origin == exact }
    }

    override suspend fun markUsed(id: String) = lock.withLock {
        val current = vault.get(SecretStore.siteLogin(id))?.let(SiteLoginCodec::decode) ?: return@withLock
        vault.put(SecretStore.siteLogin(id), SiteLoginCodec.encode(current.copy(lastUsed = clock())))
    }

    override suspend fun save(origin: String, username: String, password: String) {
        val exact = SiteOrigin.of(origin) ?: return
        val existing = forOrigin(exact)
        upsert(
            existing?.copy(username = username, password = password)
                ?: SiteLogin(UUID.randomUUID().toString(), exact, username, password, createdAt = clock()),
        )
    }

    /**
     * Adds or replaces [login] (by id), after checking it: an http(s) origin (stored exactly), HTTPS unless
     * it is the phone's loopback, a password, and a TOTP key that reads. Another login for the same origin
     * is replaced, so a page never has two. Throws [IllegalArgumentException] with what's wrong.
     */
    suspend fun upsert(login: SiteLogin): SiteLogin = lock.withLock {
        val origin = SiteOrigin.of(login.origin) ?: throw IllegalArgumentException("Enter the site's address, like https://github.com.")
        require(SiteOrigin.secureEnough(origin)) { "Logins are only saved for https:// sites (or this phone's own localhost)." }
        require(login.password.isNotEmpty()) { "Enter the password." }
        val totp = login.totpSecret?.trim()?.takeIf { it.isNotEmpty() }
        require(totp == null || dev.locus.core.browser.Totp.valid(totp)) { "That authenticator key can't be read. Paste the setup key (letters A–Z and 2–7) or the otpauth:// link." }
        val clean = login.copy(origin = origin, username = login.username.trim(), totpSecret = totp, label = login.label.trim())
        all().filter { it.origin == origin && it.id != clean.id }.forEach { vault.put(SecretStore.siteLogin(it.id), null) }
        vault.put(SecretStore.siteLogin(clean.id), SiteLoginCodec.encode(clean))
        version.value++
        clean
    }

    suspend fun delete(id: String) = lock.withLock {
        vault.put(SecretStore.siteLogin(id), null)
        version.value++
    }

    override suspend fun neverOffer(origin: String): Boolean = vault.get(SecretStore.siteLoginNever(origin)) != null

    override suspend fun setNeverOffer(origin: String) {
        vault.put(SecretStore.siteLoginNever(origin), "1")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SiteLoginsModule {
    @Binds
    abstract fun bindSiteLogins(impl: SiteLoginStore): SiteLogins
}
