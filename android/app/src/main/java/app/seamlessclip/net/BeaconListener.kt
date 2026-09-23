package app.seamlessclip.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import app.seamlessclip.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Listens for the PC's UDP discovery beacon (docs/PROTOCOL.md) so we can follow it across IP changes.
 * Only run while disconnected: holding a multicast lock costs battery.
 */
object BeaconListener {
    private const val TAG = "BeaconListener"

    suspend fun listen(context: Context) = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("seamlessclip-beacon")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 2_000 // wake up periodically to notice cancellation
            bind(InetSocketAddress(Protocol.BEACON_PORT))
        }
        Log.d(TAG, "Listening for beacons on UDP ${Protocol.BEACON_PORT}")
        try {
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val json = runCatching { JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) }
                    .getOrNull() ?: continue
                if (json.optString("proto") != Protocol.NAME) continue
                val host = packet.address?.hostAddress ?: continue
                SyncHub.onBeacon(json.optString("serverId"), host, json.optInt("port", Protocol.DEFAULT_PORT))
            }
        } finally {
            socket.close()
            lock?.release()
        }
    }
}
