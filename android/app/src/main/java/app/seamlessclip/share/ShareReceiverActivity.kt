package app.seamlessclip.share

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Invisible target for "Send to PC" in the share sheet (including the Android 13+ clipboard
 * overlay's Share button) and in the text-selection toolbar (ACTION_PROCESS_TEXT).
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        sendWithFeedback(this, text)
        finish()
    }
}
