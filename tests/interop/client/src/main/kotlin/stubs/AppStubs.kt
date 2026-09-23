// Same public shape as the Android app's classes, minus SharedPreferences/Keystore.
package app.seamlessclip.data

class PairingInfo(
    val serverId: String,
    val pcName: String,
    val hosts: List<String>,
    val port: Int,
    val key: ByteArray,
)

class PairingStore(private val info: PairingInfo?) {
    fun load(): PairingInfo? = info
    var lastGoodHost: String? = null
}

class AppPrefs {
    val clientId: String = "interop-client"
    val deviceName: String = "Interop Pixel"
}
