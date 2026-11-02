package dev.locus

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * The "Ask Locus" quick settings tile: opens Locus on home with the ask bar focused
 * (docs/design/android-bridge.md). An active tile (it never needs to listen for state), and a plain
 * button, so it is always shown as available.
 */
class AskTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java)
            .setAction(LaunchActions.ASK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val open = {
            if (Build.VERSION.SDK_INT >= 34) {
                // From Android 14 only the PendingIntent form is allowed (the Intent one throws).
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                openBefore34(intent)
            }
        }
        // On a locked phone, unlock first: Locus's chats aren't shown over the lock screen.
        if (isLocked) unlockAndRun(open) else open()
    }

    /** Only below Android 14, where the Intent form is the one that works. */
    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openBefore34(intent: Intent) = startActivityAndCollapse(intent)
}
