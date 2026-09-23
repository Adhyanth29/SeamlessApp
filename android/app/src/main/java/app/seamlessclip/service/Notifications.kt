package app.seamlessclip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import app.seamlessclip.R
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.share.ClipboardSendActivity
import app.seamlessclip.ui.MainActivity

object Notifications {
    const val CHANNEL_STATUS = "status"
    const val CHANNEL_RECEIVED = "received"
    const val ID_STATUS = 1
    const val ID_RECEIVED = 2

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.channel_status_desc)
                    setShowBadge(false)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECEIVED, context.getString(R.string.channel_received), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.channel_received_desc)
                    setShowBadge(false)
                }
        )
    }

    /** The ongoing foreground-service notification, with a one-tap "Send clipboard" action. */
    fun status(context: Context, state: ConnectionState): Notification {
        val (title, text) = when (state) {
            is ConnectionState.Connected -> "Connected to ${state.pcName}" to "Copies on your PC appear here automatically"
            is ConnectionState.Connecting -> "Connecting to ${state.pcName}…" to state.host
            is ConnectionState.Disconnected -> "${state.pcName} not reachable" to state.reason
            ConnectionState.NotPaired -> "Not paired" to "Open SeamlessClip to pair with your PC"
            ConnectionState.Idle -> "SeamlessClip" to "Starting…"
        }

        val sendClipboard = PendingIntent.getActivity(
            context, 1,
            Intent(context, ClipboardSendActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 2,
            Intent(context, SyncService::class.java).setAction(SyncService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val icon = Icon.createWithResource(context, R.drawable.ic_stat_clip)
        val builder = Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(icon, "Send clipboard to PC", sendClipboard).build())
            .addAction(Notification.Action.Builder(icon, "Stop", stop).build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    /** Clipboard text can be a password or OTP: never show it on the lock screen. */
    fun received(context: Context, pcName: String, preview: String): Notification {
        val redacted = Notification.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle("Copied from $pcName")
            .build()
        return Notification.Builder(context, CHANNEL_RECEIVED)
            .setSmallIcon(R.drawable.ic_stat_clip)
            .setContentTitle("Copied from $pcName")
            .setContentText(preview)
            .setContentIntent(openApp(context))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(redacted)
            .setAutoCancel(true)
            .setTimeoutAfter(10_000)
            .build()
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
