package app.seamlessclip.data

import android.net.Uri
import app.seamlessclip.protocol.Protocol
import java.util.Base64

/** Everything needed to reach and authenticate with one PC. Parsed from the pairing QR link. */
class PairingInfo(
    val serverId: String,
    val pcName: String,
    val hosts: List<String>,
    val port: Int,
    val key: ByteArray,
) {
    companion object {
        const val SCHEME = "seamlessclip"
        const val HOST = "pair"

        /** Parses `seamlessclip://pair?v=1&id=..&n=..&h=ip1,ip2&p=45700&k=<base64url>`; null if invalid. */
        fun parse(link: String): PairingInfo? {
            val uri = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
            if ((uri.getQueryParameter("v")?.toIntOrNull() ?: 1) != Protocol.VERSION) return null

            val serverId = uri.getQueryParameter("id")?.takeIf { it.isNotBlank() } ?: return null
            val pcName = uri.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "PC"
            val hosts = uri.getQueryParameter("h").orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (hosts.isEmpty()) return null
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: Protocol.DEFAULT_PORT
            if (port !in 1..65535) return null
            val key = runCatching { Base64.getUrlDecoder().decode(uri.getQueryParameter("k")) }.getOrNull()
                ?: return null
            if (key.size != Protocol.PAIRING_KEY_LENGTH) return null

            return PairingInfo(serverId, pcName, hosts, port, key)
        }
    }
}
