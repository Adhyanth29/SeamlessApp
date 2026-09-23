package app.seamlessclip.auto

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the automatic phone → PC feature is doing, for the UI and notification. */
sealed interface AutoCopyStatus {
    data object Off : AutoCopyStatus
    /** `adb shell pm grant app.seamlessclip android.permission.READ_LOGS` hasn't been run (or app not restarted since). */
    data object NeedsLogPermission : AutoCopyStatus
    /** "Display over other apps" is off, so we can't briefly take focus to read the clipboard. */
    data object NeedsOverlayPermission : AutoCopyStatus
    /**
     * Android 13+ only lets an app read device logs after a consent dialog, which can only be shown
     * while the app is on screen. Watching started in the background, so open the app once.
     */
    data object NeedsAppOpen : AutoCopyStatus
    /** Watching; [lastCopyAt] is null until the first copy proves log access actually works. */
    data class Watching(val lastCopyAt: Long?) : AutoCopyStatus
}

/**
 * Detects copies made in any app by tailing logcat for ClipboardService denials of our own
 * clipboard listener (see [CopyDetector]), then calls [onCopy]. Owned by SyncService.
 */
class AutoCopyWatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onCopy: () -> Unit,
) {
    private var job: Job? = null
    private var process: Process? = null
    private var startedInForeground = false

    val isRunning get() = job?.isActive == true

    /**
     * Start/stop/restart to match current settings and permissions.
     * @param inForeground true when called while one of our activities is on screen, which is the
     *   only time Android 13+ can show the "allow access to device logs" consent dialog.
     */
    fun reconcile(enabled: Boolean, inForeground: Boolean) {
        val blocker = when {
            !enabled -> AutoCopyStatus.Off
            !hasLogPermission(context) -> AutoCopyStatus.NeedsLogPermission
            !Settings.canDrawOverlays(context) -> AutoCopyStatus.NeedsOverlayPermission
            else -> null
        }
        if (blocker != null) {
            stop()
            _status.value = blocker
            return
        }

        val confirmed = (_status.value as? AutoCopyStatus.Watching)?.lastCopyAt != null
        val needsConsentRetry = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            inForeground && !startedInForeground && !confirmed
        if (isRunning && !needsConsentRetry) return

        stop()
        start(inForeground)
    }

    private fun start(inForeground: Boolean) {
        startedInForeground = inForeground
        _status.value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !inForeground) {
            AutoCopyStatus.NeedsAppOpen // may still work if consent was given recently; first copy will tell
        } else {
            AutoCopyStatus.Watching(lastCopyAt = null)
        }

        job = scope.launch {
            val detector = CopyDetector(context.packageName)
            while (isActive) {
                try {
                    withContext(Dispatchers.IO) {
                        val proc = ProcessBuilder(CopyDetector.LOGCAT_COMMAND).redirectErrorStream(true).start()
                        process = proc
                        Log.i(TAG, "Watching logcat for copies (foreground=$inForeground)")
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (!isActive) break
                                if (detector.onLine(line, System.currentTimeMillis())) {
                                    withContext(Dispatchers.Main) {
                                        _status.value = AutoCopyStatus.Watching(System.currentTimeMillis())
                                        onCopy()
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "logcat watcher failed", e)
                } finally {
                    process?.destroy()
                    process = null
                }
                // logcat exited (e.g. killed by the system); try again shortly.
                delay(RESTART_DELAY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "AutoCopyWatcher"
        private const val RESTART_DELAY_MS = 5_000L

        private val _status = MutableStateFlow<AutoCopyStatus>(AutoCopyStatus.Off)
        val status: StateFlow<AutoCopyStatus> = _status.asStateFlow()

        fun hasLogPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

        const val ADB_SETUP_COMMANDS =
            "adb shell pm grant app.seamlessclip android.permission.READ_LOGS\n" +
                "adb shell appops set app.seamlessclip SYSTEM_ALERT_WINDOW allow\n" +
                "adb shell am force-stop app.seamlessclip"
    }
}
