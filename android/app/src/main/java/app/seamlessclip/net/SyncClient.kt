package app.seamlessclip.net

import android.util.Log
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingInfo
import app.seamlessclip.data.PairingStore
import app.seamlessclip.protocol.Framing
import app.seamlessclip.protocol.Messages
import app.seamlessclip.protocol.Protocol
import app.seamlessclip.protocol.SessionCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

/**
 * Keeps a connection to the paired PC alive: dial, handshake (docs/PROTOCOL.md), exchange clips,
 * reconnect with backoff. Run [runForever] in the service scope; cancel the job to stop.
 */
class SyncClient(
    private val pairingStore: PairingStore,
    private val prefs: AppPrefs,
    private val onClipFromPc: (pcName: String, text: String) -> Unit,
) {
    private val random = SecureRandom()

    suspend fun runForever() = withContext(Dispatchers.IO) {
        var backoffMs = INITIAL_BACKOFF_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            val pairing = pairingStore.load()
            if (pairing == null) {
                SyncHub.setState(ConnectionState.NotPaired)
                return@withContext
            }

            var reason = "PC not reachable"
            var hadSession = false
            for ((host, port) in candidates(pairing)) {
                currentCoroutineContext().ensureActive()
                SyncHub.setState(ConnectionState.Connecting(pairing.pcName, host))
                try {
                    runSession(host, port, pairing) {
                        hadSession = true
                        pairingStore.lastGoodHost = host
                    }
                    reason = "Connection closed"
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PairingRejectedException) {
                    reason = "PC rejected this phone – scan the pairing QR again"
                    Log.w(TAG, "Rejected by $host:$port")
                } catch (e: Exception) {
                    if (hadSession) reason = "Connection lost"
                    Log.i(TAG, "Session with $host:$port ended: $e")
                }
                if (hadSession) break
            }

            SyncHub.setState(ConnectionState.Disconnected(pairing.pcName, reason))
            if (hadSession) backoffMs = INITIAL_BACKOFF_MS
            SyncHub.awaitReconnectSignal(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    /** Beacon-discovered address first, then the last address that worked, then the QR's list. */
    private fun candidates(pairing: PairingInfo): List<Pair<String, Int>> = buildList {
        SyncHub.discoveredEndpoint()?.let(::add)
        pairingStore.lastGoodHost?.let { add(it to pairing.port) }
        pairing.hosts.forEach { add(it to pairing.port) }
    }.distinct()

    private suspend fun runSession(host: String, port: Int, pairing: PairingInfo, onAuthenticated: () -> Unit) =
        coroutineScope {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                // 1. Server hello (plaintext)
                val serverHello = readJson(Framing.read(input, Protocol.MAX_HANDSHAKE_FRAME))
                if (serverHello.optString("proto") != Protocol.NAME || serverHello.optInt("v") != Protocol.VERSION) {
                    throw IOException("Not a SeamlessClip v${Protocol.VERSION} server")
                }
                if (serverHello.optString("serverId") != pairing.serverId) {
                    throw IOException("A different PC is listening at $host")
                }
                val serverNonce = Base64.getDecoder().decode(serverHello.getString("nonce"))
                if (serverNonce.size != Protocol.HANDSHAKE_NONCE_LENGTH) throw IOException("Bad server nonce")

                // 2. Client hello (plaintext)
                val clientNonce = ByteArray(Protocol.HANDSHAKE_NONCE_LENGTH).also(random::nextBytes)
                Framing.write(output, Messages.clientHello(prefs.clientId, clientNonce).toString().toByteArray(Charsets.UTF_8))

                // 3-6. Encrypted auth exchange
                val cipher = SessionCipher(pairing.key, serverNonce, clientNonce, isServer = false)
                val writeLock = Mutex()
                suspend fun send(message: JSONObject) = writeLock.withLock {
                    Framing.write(output, cipher.seal(message.toString().toByteArray(Charsets.UTF_8)))
                }

                send(Messages.auth(prefs.deviceName))
                val authOk = try {
                    readJson(cipher.open(Framing.read(input, Protocol.MAX_HANDSHAKE_FRAME)))
                } catch (e: EOFException) {
                    // The server hangs up without a word when our auth frame doesn't decrypt.
                    throw PairingRejectedException()
                } catch (e: GeneralSecurityException) {
                    throw PairingRejectedException()
                }
                if (authOk.optString("type") != Protocol.TYPE_AUTH_OK) throw IOException("Unexpected handshake reply")
                val pcName = authOk.optString("device").ifBlank { pairing.pcName }

                onAuthenticated()
                SyncHub.setState(ConnectionState.Connected(pcName, host))
                Log.i(TAG, "Connected to $pcName at $host:$port")
                socket.soTimeout = READ_TIMEOUT_MS

                val writer = launch {
                    try {
                        launch {
                            while (true) {
                                delay(PING_INTERVAL_MS)
                                send(Messages.ping())
                            }
                        }
                        for (clip in SyncHub.outbox) {
                            if (clip.isStale()) continue
                            send(Messages.clip(clip.text, prefs.deviceName))
                            SyncHub.record(ClipEvent(outgoing = true, peer = pcName, preview = SyncHub.preview(clip.text), timeMillis = System.currentTimeMillis()))
                        }
                    } finally {
                        // Unblocks the reader below when we're cancelled or a write failed.
                        socket.close()
                    }
                }

                try {
                    while (isActive) {
                        val message = readJson(cipher.open(Framing.read(input, Protocol.MAX_FRAME)))
                        when (message.optString("type")) {
                            Protocol.TYPE_CLIP -> {
                                val text = message.optString("text")
                                if (text.isNotEmpty()) onClipFromPc(message.optString("device").ifBlank { pcName }, text)
                            }
                            Protocol.TYPE_PING -> launch { send(Messages.pong()) }
                            Protocol.TYPE_PONG -> Unit
                            else -> Log.d(TAG, "Ignoring message type ${message.optString("type")}")
                        }
                    }
                } finally {
                    writer.cancel()
                }
            } finally {
                socket.close()
            }
        }

    private fun readJson(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))

    private class PairingRejectedException : IOException("Pairing key rejected by PC")

    private companion object {
        const val TAG = "SyncClient"
        const val CONNECT_TIMEOUT_MS = 4_000
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 45_000 // > 2 × ping interval: silence this long means the link is dead
        const val PING_INTERVAL_MS = 20_000L
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
