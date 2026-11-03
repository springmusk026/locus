package dev.locus.core.datastore

import androidx.datastore.core.DataStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import dev.locus.core.model.PolicySeal
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.SealCheck
import dev.locus.core.model.TelegramRemoteSettings
import dev.locus.core.model.ToolPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tool permissions in settings.json are sealed; changes made outside Locus fall back to safe settings. */
class SealedSettingsStoreTest {
    private class Key(var new: Boolean = false, var broken: Boolean = false) : IntegrityKey {
        override fun mac(data: ByteArray): ByteArray {
            check(!broken) { "keystore unavailable" }
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { 5 }, "HmacSHA256")) }.doFinal(data)
        }

        override fun createdThisRun(): Boolean = new
    }

    /** The file on disk, as another process would see and change it. */
    private class Disk(initial: AppSettings) : DataStore<AppSettings> {
        val state = MutableStateFlow(initial)
        private val lock = Mutex()
        override val data: Flow<AppSettings> = state
        override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings) = lock.withLock { transform(state.value).also { state.value = it } }
    }

    private val key = Key()
    private val alerts = IntegrityAlerts()

    private fun TestScope.store(disk: Disk, k: IntegrityKey = key) = SealedSettingsStore(disk, k, alerts, this)

    @Test
    fun `permissions the user sets are sealed and read back as they are`() = runTest {
        val disk = Disk(AppSettings())
        val store = store(disk)
        store.updateData { it.copy(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW), reviewerMode = ReviewerMode.REVIEW_ASKS) }
        assertEquals(SealCheck.VALID, PolicySeal.check(disk.state.value, key))
        assertEquals(mapOf("shell" to ToolPolicy.ALLOW), store.data.first().toolPolicies)
        // Unrelated updates elsewhere keep the seal.
        store.updateData { it.copy(autoTitle = false) }
        assertEquals(SealCheck.VALID, PolicySeal.check(disk.state.value, key))
        assertTrue(alerts.alerts.value.isEmpty())
    }

    @Test
    fun `an Allow written to settings_json by another process is ignored, reset and reported`() = runTest {
        val disk = Disk(AppSettings())
        val store = store(disk)
        store.updateData { it.copy(toolPolicies = mapOf("send_email" to ToolPolicy.DENY)) }
        disk.state.value = disk.state.value.copy(
            toolPolicies = mapOf("shell" to ToolPolicy.ALLOW, "send_email" to ToolPolicy.DENY),
            reviewerMode = ReviewerMode.REVIEW_ALL_BUT_READS,
            reviewerApprovesOutward = true,
        )
        val read = store.data.first()
        assertEquals(mapOf("send_email" to ToolPolicy.DENY), read.toolPolicies)
        assertEquals(ReviewerMode.OFF, read.reviewerMode)
        assertEquals(false, read.reviewerApprovesOutward)
        assertEquals(listOf<IntegrityAlert>(IntegrityAlert.ToolPermissions), alerts.alerts.value)
        // The safe settings are written back, sealed.
        advanceUntilIdle()
        assertEquals(mapOf("send_email" to ToolPolicy.DENY), disk.state.value.toolPolicies)
        assertEquals(SealCheck.VALID, PolicySeal.check(disk.state.value, key))
    }

    @Test
    fun `a remembered approval written by another process breaks the seal and drops every rule`() = runTest {
        val disk = Disk(AppSettings())
        val store = store(disk)
        val mine = dev.locus.core.model.RememberedApproval("r1", "run_command", dev.locus.core.model.ApprovalScopeKind.COMMAND, "./gradlew test")
        store.updateData { it.copy(rememberedApprovals = listOf(mine)) }
        assertEquals(listOf(mine), store.data.first().rememberedApprovals)
        // Linux writes "always allow sh" into settings.json.
        val forged = mine.copy(id = "r2", value = "sh")
        disk.state.value = disk.state.value.copy(rememberedApprovals = listOf(mine, forged))
        assertEquals(emptyList(), store.data.first().rememberedApprovals)
        assertEquals(listOf<IntegrityAlert>(IntegrityAlert.ToolPermissions), alerts.alerts.value)
        // Written back without them: the defaults again.
        advanceUntilIdle()
        assertEquals(emptyList(), disk.state.value.rememberedApprovals)
        assertEquals(SealCheck.DEFAULTS, PolicySeal.check(disk.state.value, key))
    }

    @Test
    fun `an update doesn't seal permissions it didn't check`() = runTest {
        val disk = Disk(AppSettings())
        val store = store(disk)
        disk.state.value = AppSettings(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW), policySeal = "v1:forged")
        store.updateData { it.copy(customInstructions = "hi") }
        assertEquals(emptyMap(), disk.state.value.toolPolicies)
        assertEquals("hi", disk.state.value.customInstructions)
        assertEquals(listOf<IntegrityAlert>(IntegrityAlert.ToolPermissions), alerts.alerts.value)
    }

    @Test
    fun `settings from before seals are kept and sealed once, when the key is new`() = runTest {
        val old = AppSettings(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW), reviewerMode = ReviewerMode.REVIEW_ASKS)
        val disk = Disk(old)
        val newKey = Key(new = true)
        assertEquals(old.toolPolicies, store(disk, newKey).data.first().toolPolicies)
        advanceUntilIdle()
        newKey.new = false
        assertEquals(SealCheck.VALID, PolicySeal.check(disk.state.value, newKey))
        assertTrue(alerts.alerts.value.isEmpty())
    }

    @Test
    fun `a Keystore that can't be used fails closed but other settings still save`() = runTest {
        val disk = Disk(AppSettings())
        val store = store(disk, Key(broken = true))
        store.updateData { it.copy(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW), autoTitle = false) }
        assertEquals(false, disk.state.value.autoTitle)
        assertEquals(emptyMap(), store.data.first().toolPolicies)
        assertEquals(listOf<IntegrityAlert>(IntegrityAlert.ToolPermissions), alerts.alerts.value)
    }

    @Test
    fun `a Telegram chat id left unsealed by an older Locus is migrated, not reported`() = runTest {
        // Before the chat id was sealed, settings with only it set counted as defaults and had no seal.
        val disk = Disk(AppSettings(telegramRemote = TelegramRemoteSettings(since = 10, chatId = "tg")))
        val read = store(disk).data.first()
        assertNull(read.telegramRemote.chatId)
        advanceUntilIdle()
        assertNull(disk.state.value.telegramRemote.chatId)
        assertEquals(SealCheck.DEFAULTS, PolicySeal.check(disk.state.value, key))
        assertTrue(alerts.alerts.value.isEmpty())
    }

    // ---- Endpoints: provider addresses, proxies, certificates, SSH machines --------------------------

    private val provider = dev.locus.core.model.ProviderConfig("p1", "Anthropic", dev.locus.core.model.ApiFormat.ANTHROPIC_MESSAGES, "https://api.anthropic.com", "m")
    private val host = dev.locus.core.model.SshHost("h1", "box", "box.example", 22, "me", fingerprint = "SHA256:abc")

    /** A separate key, as in the app (its own Keystore alias). */
    private class EndpointKey(var new: Boolean) : IntegrityKey {
        override fun mac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32) { 9 }, "HmacSHA256")) }.doFinal(data)
        override fun createdThisRun(): Boolean = new
    }

    @Test
    fun `an update with sealed permissions and providers seals endpoints without an alert`() = runTest {
        // As the previous version left it: permissions sealed, endpoints never sealed.
        val before = PolicySeal.sealed(AppSettings(toolPolicies = mapOf("shell" to ToolPolicy.ALLOW), providers = listOf(provider), sshHosts = listOf(host)), key)
        val disk = Disk(before)
        val endpoints = EndpointKey(new = true)
        val read = SealedSettingsStore(disk, key, alerts, this, endpoints).data.first()
        assertEquals(before, read)
        advanceUntilIdle()
        assertTrue(alerts.alerts.value.isEmpty())
        assertEquals(SealCheck.VALID, PolicySeal.check(disk.state.value, key))
        assertEquals(SealCheck.VALID, dev.locus.core.model.EndpointSeal.check(disk.state.value, endpoints))
        assertEquals("SHA256:abc", disk.state.value.sshHosts.single().fingerprint)
    }

    @Test
    fun `a provider address or SSH host key changed by another process is caught and made safe`() = runTest {
        val endpoints = EndpointKey(new = false)
        val disk = Disk(AppSettings())
        val store = SealedSettingsStore(disk, key, alerts, this, endpoints)
        store.updateData { it.copy(providers = listOf(provider), sshHosts = listOf(host)) }
        assertTrue(disk.state.value.endpointSeal.isNotEmpty())

        disk.state.value = disk.state.value.copy(
            providers = listOf(provider.copy(baseUrl = "https://relay.attacker.example")),
            sshHosts = listOf(host.copy(fingerprint = "SHA256:attacker")),
        )
        val read = store.data.first()
        assertTrue(read.providers.single().addressUnconfirmed)
        assertEquals("", read.sshHosts.single().fingerprint)
        assertEquals(listOf<IntegrityAlert>(IntegrityAlert.Endpoints), alerts.alerts.value)
        advanceUntilIdle()
        assertEquals(SealCheck.VALID, dev.locus.core.model.EndpointSeal.check(disk.state.value, endpoints))
        assertTrue(disk.state.value.providers.single().addressUnconfirmed)

        // Removing the seal doesn't make it a first use: the key already existed.
        disk.state.value = disk.state.value.copy(providers = listOf(provider.copy(baseUrl = "https://other.example")), endpointSeal = "")
        assertTrue(store.data.first().providers.single().addressUnconfirmed)
    }

    @Test
    fun `connector alerts accumulate and dismiss clears everything`() {
        alerts.report(IntegrityAlert.Connectors(listOf("b")))
        alerts.report(IntegrityAlert.ToolPermissions)
        alerts.report(IntegrityAlert.Connectors(listOf("a", "b")))
        alerts.report(IntegrityAlert.ToolPermissions)
        assertEquals(listOf(IntegrityAlert.ToolPermissions, IntegrityAlert.Connectors(listOf("a", "b"))), alerts.alerts.value)
        alerts.dismiss()
        assertTrue(alerts.alerts.value.isEmpty())
    }
}
