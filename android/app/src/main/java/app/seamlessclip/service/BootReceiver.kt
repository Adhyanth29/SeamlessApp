package app.seamlessclip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingStore

/** Restarts the sync service after reboot or app update, if the user wants that and a PC is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppPrefs(context).startOnBoot) return
        if (PairingStore(context).load() == null) return
        SyncService.start(context)
    }
}
