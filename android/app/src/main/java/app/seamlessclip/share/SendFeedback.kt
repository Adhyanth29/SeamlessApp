package app.seamlessclip.share

import android.content.Context
import android.widget.Toast
import app.seamlessclip.data.PairingStore
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.net.SyncHub

/** Queues text for the PC and tells the user what will happen. */
internal fun sendWithFeedback(context: Context, text: String?) {
    val message = when {
        text.isNullOrEmpty() -> "Nothing to send"
        PairingStore(context).load() == null -> "Pair with your PC first (open SeamlessClip)"
        !SyncHub.sendText(context, text) -> "Too large to send (max 4 MB)"
        SyncHub.state.value is ConnectionState.Connected -> "Sent to PC"
        else -> "Will send when your PC is reachable"
    }
    Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
}
