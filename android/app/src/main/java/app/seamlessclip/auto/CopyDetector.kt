package app.seamlessclip.auto

/**
 * Recognises the line Android's ClipboardService logs when it refuses to tell a background app
 * about a new clip, e.g. (logcat -v brief):
 *
 *   E/ClipboardService( 1234): Denying clipboard access to app.seamlessclip, application is not in focus ...
 *
 * Every copy anywhere on the phone produces one of these for our registered clipboard listener,
 * which makes it a reliable "something was just copied" signal.
 */
class CopyDetector(private val packageName: String, private val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {
    private val marker = "Denying clipboard access to $packageName"
    private var lastTriggerAt = Long.MIN_VALUE / 2

    /** True if [line] is a denial for exactly our package (not e.g. `app.seamlessclip.debug`). */
    fun isDenialForUs(line: String): Boolean {
        val index = line.indexOf(marker)
        if (index < 0) return false
        val next = line.getOrNull(index + marker.length) ?: return true
        return !(next.isLetterOrDigit() || next == '.' || next == '_')
    }

    /**
     * Feed every logcat line; returns true when a copy should be handled. Bursts of denial lines
     * (one per listener / per retry) within [debounceMs] count as a single copy.
     */
    fun onLine(line: String, nowMs: Long): Boolean {
        if (!isDenialForUs(line)) return false
        if (nowMs - lastTriggerAt < debounceMs) return false
        lastTriggerAt = nowMs
        return true
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 300L

        /**
         * Only ClipboardService error lines, from the system buffer (where Slog writes) and main.
         * `-T 1` starts at the newest line so old copies are never replayed.
         */
        val LOGCAT_COMMAND = listOf("logcat", "-b", "system,main", "-v", "brief", "-T", "1", "ClipboardService:E", "*:S")
    }
}
