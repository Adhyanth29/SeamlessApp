package app.seamlessclip.share

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Android 10+ only lets the focused app read the clipboard. This transparent activity is launched
 * from the Quick Settings tile / notification action, waits for window focus, reads the clipboard
 * once, queues it for the PC, and disappears.
 */
class ClipboardSendActivity : Activity() {
    private var handled = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety net in case focus never arrives (e.g. launched behind the keyguard).
        handler.postDelayed({ if (!handled) finish() }, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        sendWithFeedback(this, text)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val FOCUS_TIMEOUT_MS = 3_000L
    }
}
