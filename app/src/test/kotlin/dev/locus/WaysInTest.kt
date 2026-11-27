package dev.locus

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.XmlResourceParser
import android.service.quicksettings.TileService
import androidx.test.core.app.ApplicationProvider
import dev.locus.core.data.automation.AutomationContract
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ways into Locus that the merged manifest and res/xml declare (docs/design/android-bridge.md):
 * each intent other apps and the system send reaches the component that handles it, and the static
 * shortcuts only use actions MainActivity takes. A renamed action or a lost intent filter would
 * otherwise only show on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WaysInTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager = app.packageManager

    /** The activities (or activity aliases) an implicit [intent] reaches inside Locus. */
    private fun activitiesFor(intent: Intent): List<String> =
        pm.queryIntentActivities(intent.setPackage(app.packageName), 0).map { it.activityInfo.name }

    @Test
    fun `shares from other apps reach MainActivity`() {
        val text = Intent(Intent.ACTION_SEND).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT)
        assertEquals(listOf(MainActivity::class.java.name), activitiesFor(text))
        val files = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").addCategory(Intent.CATEGORY_DEFAULT)
        assertEquals(listOf(MainActivity::class.java.name), activitiesFor(files))
    }

    @Test
    fun `selected text and the assist gesture reach their aliases`() {
        val selected = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").addCategory(Intent.CATEGORY_DEFAULT)
        assertEquals(listOf("dev.locus.AskLocusText"), activitiesFor(selected))
        val assist = Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT)
        assertEquals(listOf("dev.locus.AssistLocus"), activitiesFor(assist))
    }

    @Test
    fun `every automation action reaches AutomationActivity`() {
        val actions = listOf(
            AutomationContract.ACTION_START_CHAT,
            AutomationContract.ACTION_RUN_WORKFLOW,
            AutomationContract.ACTION_RUN_TASK,
            AutomationContract.ACTION_EVENT,
        )
        for (action in actions) {
            assertEquals(listOf(AutomationActivity::class.java.name), activitiesFor(Intent(action).addCategory(Intent.CATEGORY_DEFAULT)), action)
        }
        // Exported without a permission, so apps that can't declare RUN_TASK can still ask; the activity confirms.
        val info = pm.getActivityInfo(ComponentName(app, AutomationActivity::class.java), 0)
        assertTrue(info.exported)
        assertEquals(null, info.permission)
    }

    @Test
    fun `the tile is bound only by the system`() {
        val tiles = pm.queryIntentServices(Intent(TileService.ACTION_QS_TILE).setPackage(app.packageName), 0)
        assertEquals(listOf(AskTileService::class.java.name), tiles.map { it.serviceInfo.name })
        assertEquals(android.Manifest.permission.BIND_QUICK_SETTINGS_TILE, tiles.single().serviceInfo.permission)
    }

    @Test
    fun `static shortcuts open MainActivity with actions it takes`() {
        val shortcuts = mutableMapOf<String, String>()
        var id: String? = null
        val ns = "http://schemas.android.com/apk/res/android"
        val xml: XmlResourceParser = app.resources.getXml(R.xml.shortcuts)
        while (xml.next() != XmlResourceParser.END_DOCUMENT) {
            if (xml.eventType != XmlResourceParser.START_TAG) continue
            when (xml.name) {
                "shortcut" -> id = xml.getAttributeValue(ns, "shortcutId")
                "intent" -> {
                    assertEquals(MainActivity::class.java.name, xml.getAttributeValue(ns, "targetClass"))
                    shortcuts[checkNotNull(id)] = xml.getAttributeValue(ns, "action")
                }
            }
        }
        // The ids ShareViewModel.reportShortcutUsed reports for each action.
        assertEquals(
            mapOf("new_chat" to LaunchActions.NEW_CHAT, "voice" to LaunchActions.VOICE, "workspace" to LaunchActions.WORKSPACE),
            shortcuts,
        )
        shortcuts.values.forEach { assertEquals(it, LaunchActions.resolve(it)) }
    }
}
