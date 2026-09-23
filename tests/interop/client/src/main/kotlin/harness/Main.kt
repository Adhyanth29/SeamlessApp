package harness

import android.content.Context
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingInfo
import app.seamlessclip.data.PairingStore
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.net.SyncClient
import app.seamlessclip.net.SyncHub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.system.exitProcess

/**
 * Usage: <host> <port> <keyHex> <serverId> <mode>
 *   roundtrip: send a clip, wait for the PC's clip, echo it back as "ack:<text>".
 *   reject:    expect the PC to refuse us (wrong key) -> Disconnected with a rejection reason.
 *   connect:   just connect and stay connected briefly.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val (host, port, keyHex, serverId, mode) = args
    val key = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val pairing = PairingInfo(serverId, "InteropPC", listOf(host), port.toInt(), key)
    val ctx = Context()
    val fromPc = CompletableDeferred<String>()

    SyncHub.pairedServerId = serverId
    val client = SyncClient(PairingStore(pairing), AppPrefs()) { pc, text ->
        println("[client] clip from $pc: ${text.length} chars")
        fromPc.complete(text)
    }
    val job = launch { client.runForever() }

    try {
        when (mode) {
            "roundtrip" -> withTimeout(30_000) {
                SyncHub.sendText(ctx, "hello from phone ✓ 🎉\nline2")
                SyncHub.state.first { it is ConnectionState.Connected }
                println("[client] connected")
                val text = fromPc.await()
                SyncHub.sendText(ctx, "ack:$text")
                // Give the writer a moment to flush before we tear down.
                SyncHub.history.first { h -> h.count { it.outgoing } >= 2 }
                println("[client] PASS roundtrip")
            }
            "reject" -> withTimeout(20_000) {
                val s = SyncHub.state.first { it is ConnectionState.Disconnected } as ConnectionState.Disconnected
                check("rejected" in s.reason) { "expected rejection, got: ${s.reason}" }
                println("[client] PASS reject (${s.reason})")
            }
            "connect" -> withTimeout(40_000) {
                SyncHub.state.first { it is ConnectionState.Connected }
                println("[client] PASS connect")
            }
            else -> error("unknown mode $mode")
        }
    } catch (e: Throwable) {
        println("[client] FAIL: $e")
        exitProcess(1)
    }
    job.cancel()
    exitProcess(0)
}
