package app.seamlessclip.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyDetectorTest {
    private val pkg = "app.seamlessclip"
    private fun line(p: String) =
        "E/ClipboardService( 1520): Denying clipboard access to $p, application is not in focus nor is it a system service for user 0"

    @Test
    fun matchesOurPackageOnly() {
        val d = CopyDetector(pkg)
        assertTrue(d.isDenialForUs(line(pkg)))
        assertFalse(d.isDenialForUs(line("app.seamlessclip.debug")))
        assertFalse(d.isDenialForUs(line("app.seamlessclipper")))
        assertFalse(d.isDenialForUs(line("com.other.app")))
        assertFalse(d.isDenialForUs("I/ActivityManager( 1520): Start proc app.seamlessclip"))
    }

    @Test
    fun matchesThreadtimeFormatAndLineEnd() {
        val d = CopyDetector(pkg)
        assertTrue(d.isDenialForUs("09-23 13:05:19.123  1520  1700 E ClipboardService: Denying clipboard access to $pkg, application is not in focus"))
        assertTrue(d.isDenialForUs("E/ClipboardService: Denying clipboard access to $pkg"))
    }

    @Test
    fun debouncesBursts() {
        val d = CopyDetector(pkg, debounceMs = 300)
        val hits = listOf(0L, 10L, 250L, 400L, 650L, 1000L).count { d.onLine(line(pkg), it) }
        // 0 fires; 10,250 swallowed; 400 fires; 650 swallowed (250ms after 400); 1000 fires
        assertEquals(3, hits)
    }

    @Test
    fun ignoresNonMatchingLinesWithoutResettingDebounce() {
        val d = CopyDetector(pkg, debounceMs = 300)
        assertTrue(d.onLine(line(pkg), 0))
        assertFalse(d.onLine(line("com.other"), 500))
        assertTrue(d.onLine(line(pkg), 301))
    }
}
