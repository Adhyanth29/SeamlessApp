package app.seamlessclip.data

import android.content.Context
import android.util.Log
import app.seamlessclip.protocol.Protocol

/** Persists the single paired PC. The pairing key itself is wrapped by [SecretBox]. */
class PairingStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pairing", Context.MODE_PRIVATE)

    fun load(): PairingInfo? {
        val serverId = prefs.getString(KEY_SERVER_ID, null) ?: return null
        val wrappedKey = prefs.getString(KEY_WRAPPED_KEY, null) ?: return null
        val key = try {
            SecretBox.decrypt(wrappedKey)
        } catch (e: Exception) {
            Log.w(TAG, "Stored pairing key unreadable; pairing must be redone", e)
            return null
        }
        return PairingInfo(
            serverId = serverId,
            pcName = prefs.getString(KEY_PC_NAME, null) ?: "PC",
            hosts = prefs.getString(KEY_HOSTS, null).orEmpty().split(',').filter { it.isNotBlank() },
            port = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT),
            key = key,
        )
    }

    fun save(info: PairingInfo) {
        prefs.edit()
            .clear()
            .putString(KEY_SERVER_ID, info.serverId)
            .putString(KEY_PC_NAME, info.pcName)
            .putString(KEY_HOSTS, info.hosts.joinToString(","))
            .putInt(KEY_PORT, info.port)
            .putString(KEY_WRAPPED_KEY, SecretBox.encrypt(info.key))
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** Address that most recently completed a handshake; tried first next time. */
    var lastGoodHost: String?
        get() = prefs.getString(KEY_LAST_GOOD_HOST, null)
        set(value) = prefs.edit().putString(KEY_LAST_GOOD_HOST, value).apply()

    private companion object {
        const val TAG = "PairingStore"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_PC_NAME = "pc_name"
        const val KEY_HOSTS = "hosts"
        const val KEY_PORT = "port"
        const val KEY_WRAPPED_KEY = "wrapped_key"
        const val KEY_LAST_GOOD_HOST = "last_good_host"
    }
}
