package dev.locus

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTileService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The "Ask Locus" quick settings tile: a tap opens MainActivity on home's ask bar, after unlocking a
 * locked phone. From Android 14 the tile must hand Android a PendingIntent; Robolectric's TileService
 * doesn't take one, so [CollapsingTileShadow] keeps it for the test to look at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, shadows = [AskTileServiceTest.CollapsingTileShadow::class])
class AskTileServiceTest {
    /** Robolectric's tile shadow, plus the Android 14+ startActivityAndCollapse(PendingIntent). */
    @Implements(TileService::class)
    class CollapsingTileShadow : ShadowTileService() {
        var collapsedTo: PendingIntent? = null

        @Implementation(minSdk = 34)
        protected fun startActivityAndCollapse(pendingIntent: PendingIntent) {
            collapsedTo = pendingIntent
        }
    }

    private fun tile(): Pair<AskTileService, CollapsingTileShadow> {
        val tile = Robolectric.buildService(AskTileService::class.java).create().get()
        return tile to Shadow.extract(tile)
    }

    private fun assertOpensAsk(shadow: CollapsingTileShadow) {
        val pending = assertNotNull(shadow.collapsedTo)
        val pendingShadow = shadowOf(pending)
        assertTrue(pendingShadow.isActivity)
        // Immutable: whoever gets hold of it can't change where it goes.
        assertTrue(pendingShadow.isImmutable)
        val intent = pendingShadow.savedIntent
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(LaunchActions.ASK, intent.action)
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(flags, intent.flags and flags)
        // MainActivity takes it as the ask action.
        assertEquals(LaunchActions.ASK, LaunchActions.resolve(intent.action))
    }

    @Test
    fun `a tap opens home's ask bar`() {
        val (tile, shadow) = tile()
        tile.onClick()
        assertOpensAsk(shadow)
    }

    @Test
    fun `on a locked phone it unlocks first`() {
        val (tile, shadow) = tile()
        shadow.setLocked(true)
        tile.onClick()
        assertFalse(tile.isLocked)
        assertOpensAsk(shadow)
    }

    @Test
    fun `nothing opens before a tap, and the tile always shows as available`() {
        val (tile, shadow) = tile()
        tile.onStartListening()
        assertEquals(Tile.STATE_INACTIVE, tile.qsTile.state)
        assertNull(shadow.collapsedTo)
    }
}
