package app.seamlessclip.auto

import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/** Result of one background clipboard read. */
data class GrabbedClip(val text: String, val sensitive: Boolean)

/**
 * Reads the clipboard from the background. Android 10+ allows clipboard reads only while one of
 * the app's windows has input focus, so we add a 1×1 transparent, focusable overlay window
 * ("display over other apps"), read as soon as it gains focus, and remove it again (~tens of ms).
 * No activity launch is needed, which also sidesteps background-activity-start limits.
 */
object ClipboardGrabber {
    private const val TAG = "ClipboardGrabber"
    private const val FOCUS_TIMEOUT_MS = 1_500L
    /** Same key as ClipDescription.EXTRA_IS_SENSITIVE (API 33); password managers set it. */
    private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    private val main = Handler(Looper.getMainLooper())
    private var inFlight = false

    /** Must be called on the main thread. [onResult] gets null if nothing could be read. */
    fun grab(context: Context, onResult: (GrabbedClip?) -> Unit) {
        if (inFlight) return
        if (!Settings.canDrawOverlays(context)) {
            onResult(null)
            return
        }
        inFlight = true

        val appContext = context.applicationContext
        val windowManager = appContext.getSystemService(WindowManager::class.java)
        var finished = false
        lateinit var view: View

        fun finish(result: GrabbedClip?) {
            if (finished) return
            finished = true
            inFlight = false
            main.removeCallbacksAndMessages(view)
            runCatching { windowManager.removeViewImmediate(view) }
            onResult(result)
        }

        view = object : View(appContext) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) finish(read(appContext))
            }
        }

        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) but never intercepts touches.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "SeamlessClip clipboard reader"
        }

        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "Could not add overlay window", e)
            inFlight = false
            onResult(null)
            return
        }
        main.postAtTime({ finish(null) }, view, android.os.SystemClock.uptimeMillis() + FOCUS_TIMEOUT_MS)
    }

    private fun read(context: Context): GrabbedClip? = try {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        val sensitive = clip?.description?.extras?.getBoolean(EXTRA_IS_SENSITIVE, false) == true
        text?.takeIf { it.isNotEmpty() }?.let { GrabbedClip(it, sensitive) }
    } catch (e: Exception) {
        Log.w(TAG, "Clipboard read failed", e)
        null
    }
}
