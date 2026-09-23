package app.seamlessclip.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.UUID

class AppPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Put text received from the PC onto the phone clipboard. */
    var applyFromPc: Boolean
        get() = prefs.getBoolean("apply_from_pc", true)
        set(value) = prefs.edit().putBoolean("apply_from_pc", value).apply()

    /** Post a notification when text arrives from the PC. */
    var notifyOnReceive: Boolean
        get() = prefs.getBoolean("notify_on_receive", true)
        set(value) = prefs.edit().putBoolean("notify_on_receive", value).apply()

    /**
     * Send phone copies to the PC automatically (no tap). Needs the one-time ADB grant, see
     * AutoCopyWatcher. Off by default: it lets the app read system logs, so it's for trusted devices.
     */
    var autoSendFromPhone: Boolean
        get() = prefs.getBoolean("auto_send_from_phone", false)
        set(value) = prefs.edit().putBoolean("auto_send_from_phone", value).apply()

    /** Start the sync service after reboot / app update. */
    var startOnBoot: Boolean
        get() = prefs.getBoolean("start_on_boot", true)
        set(value) = prefs.edit().putBoolean("start_on_boot", value).apply()

    val clientId: String
        get() = prefs.getString("client_id", null)
            ?: UUID.randomUUID().toString().also { prefs.edit().putString("client_id", it).apply() }

    /** Name the PC shows for this phone, e.g. "Pixel 8". */
    val deviceName: String
        get() = Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
}
