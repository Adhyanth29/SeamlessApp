package app.seamlessclip.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.net.SyncHub
import app.seamlessclip.share.ClipboardSendActivity

/** Quick Settings tile: copy something, pull down the shade, tap the tile. */
class SendClipboardTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val state = SyncHub.state.value
        tile.state = if (state is ConnectionState.Connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = when (state) {
            is ConnectionState.Connected -> state.pcName
            ConnectionState.NotPaired -> "Not paired"
            else -> "Not connected"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launchSender() } else launchSender()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchSender() {
        val intent = Intent(this, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
