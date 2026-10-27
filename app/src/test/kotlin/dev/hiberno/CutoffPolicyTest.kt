package dev.hiberno

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoffPolicyTest {
    private val hib = setOf("com.spam")

    @Test fun blocksBackgroundWakeOfHibernatedApp() {
        val p = CutoffPolicy()
        assertTrue(p.shouldBlock("com.spam", "broadcast", hib, 1))
        assertTrue(p.shouldBlock("com.spam", "service", hib, 1))
        assertFalse(p.shouldBlock("com.other", "service", hib, 1))
    }

    @Test fun activityLaunchWakesUntilNextRound() {
        val p = CutoffPolicy()
        assertFalse(p.shouldBlock("com.spam", "top-activity", hib, 1))
        assertFalse(p.shouldBlock("com.spam", "service", hib, 1))
        assertTrue(p.shouldBlock("com.spam", "service", hib, 2))
    }

    @Test fun unknownHostingTypeFailsOpen() {
        assertFalse(CutoffPolicy().shouldBlock("com.spam", null, hib, 1))
    }

    @Test fun scriptRejectsShellInjection() {
        val s = HibernateService.buildScript(setOf("com.ok.app", "x; rm -rf /"), keepActive = true)
        assertTrue("am force-stop com.ok.app" in s)
        assertTrue("isForeground=true" in s)
        assertFalse("rm -rf" in s)
        assertFalse("isForeground" in HibernateService.buildScript(setOf("com.ok.app"), keepActive = false))
    }

    @Test fun parsesStoppedAndSkipped() {
        val (stopped, skipped) = HibernateService.parseResult(listOf("+com.a", "-com.b", "+com.c", "noise"))
        assertEquals(setOf("com.a", "com.c"), stopped)
        assertEquals(setOf("com.b"), skipped)
    }

    @Test fun countsWakeupsSinceLastRun() {
        val log = listOf(
            "--------- beginning of main",
            "  1700000000.100  1000  1234 I HibernoCutoff: com.a",
            "  1700000100.200  1000  1234 I HibernoCutoff: com.a",
            "  1700000100.300  1000  1234 I HibernoCutoff: com.b",
        )
        assertEquals(mapOf("com.a" to 1, "com.b" to 1), MainActivity.parseWakeups(log, 1700000050.0))
    }
}
