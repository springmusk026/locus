package dev.locus.core.datastore

import androidx.datastore.core.DataStore
import dev.locus.core.model.AppSettings
import dev.locus.core.model.IntegrityKey
import dev.locus.core.model.PolicySeal
import dev.locus.core.model.ReviewerMode
import dev.locus.core.model.SealCheck
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
