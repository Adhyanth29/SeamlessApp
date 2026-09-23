package app.seamlessclip.net

import android.content.Context
import app.seamlessclip.protocol.Protocol
import app.seamlessclip.service.SyncService
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ConnectionState {
    /** Service not running. */
    data object Idle : ConnectionState
    data object NotPaired : ConnectionState
    data class Connecting(val pcName: String, val host: String) : ConnectionState
    data class Connected(val pcName: String, val host: String) : ConnectionState
    data class Disconnected(val pcName: String, val reason: String) : ConnectionState
}

data class ClipEvent(val outgoing: Boolean, val peer: String, val preview: String, val timeMillis: Long)

data class OutgoingClip(val text: String, val createdAtMillis: Long) {
    /** Don't surprise the user by overwriting the PC clipboard with something they shared minutes ago. */
    fun isStale(now: Long = System.currentTimeMillis()) = now - createdAtMillis > STALE_AFTER_MS

    private companion object {
        const val STALE_AFTER_MS = 60_000L
    }
}

/**
 * Process-wide glue between the UI/share entry points and [SyncService]/[SyncClient].
 * Everything here is thread-safe.
 */
object SyncHub {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<ClipEvent>>(emptyList())
    val history: StateFlow<List<ClipEvent>> = _history.asStateFlow()

    /** Bumped whenever the pairing changes so the service reconnects with the new details. */
    private val _pairingVersion = MutableStateFlow(0)
    val pairingVersion: StateFlow<Int> = _pairingVersion.asStateFlow()

    /** Latest clip waiting to go to the PC. Conflated: only the newest matters. */
    internal val outbox = Channel<OutgoingClip>(Channel.CONFLATED)

    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var discovered: Pair<String, Int>? = null

    /** Server id of the currently paired PC, so beacons from other PCs are ignored. */
    @Volatile
    var pairedServerId: String? = null

    /** Last text we wrote to the clipboard on behalf of the PC (loop prevention). */
    @Volatile
    var lastAppliedFromPc: String? = null

    fun setState(newState: ConnectionState) {
        _state.value = newState
    }

    /**
     * Queue [text] for the PC and make sure the service is running.
     * @return false if the text is empty or too large to send.
     */
    fun sendText(context: Context, text: String): Boolean {
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT_BYTES) return false
        outbox.trySend(OutgoingClip(text, System.currentTimeMillis()))
        SyncService.start(context)
        return true
    }

    fun onPairingChanged() {
        discovered = null
        _pairingVersion.update { it + 1 }
    }

    fun requestReconnect() {
        reconnectSignal.trySend(Unit)
    }

    /** Waits up to [timeoutMs] or until something suggests reconnecting now would succeed. */
    internal suspend fun awaitReconnectSignal(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { reconnectSignal.receive() }
    }

    internal fun onBeacon(serverId: String, host: String, port: Int) {
        if (serverId != pairedServerId || port !in 1..65535) return
        val endpoint = host to port
        if (discovered != endpoint) {
            discovered = endpoint
            if (_state.value !is ConnectionState.Connected) requestReconnect()
        }
    }

    internal fun discoveredEndpoint(): Pair<String, Int>? = discovered

    fun record(event: ClipEvent) {
        _history.update { (listOf(event) + it).take(MAX_HISTORY) }
    }

    fun preview(text: String): String {
        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= 80) singleLine else singleLine.take(77) + "…"
    }

    private const val MAX_HISTORY = 20
}
