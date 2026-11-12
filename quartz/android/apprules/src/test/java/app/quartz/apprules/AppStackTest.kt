package app.quartz.apprules

import app.quartz.apprules.OpenApps.Companion.DESTROYED
import app.quartz.apprules.OpenApps.Companion.PAUSED
import app.quartz.apprules.OpenApps.Companion.RESUMED
import app.quartz.apprules.OpenApps.Companion.STOPPED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppStackTest {
    private val mail = "com.example.mail"
    private val bank = "com.example.bank"
    private val launcher = "com.example.launcher"
    private val workApp = "com.example.work"
    private val data =
        AppRulesData(
            rules =
                listOf(
                    AppRule(id = "a", tunnelId = 1, apps = listOf(AppRef(mail, "Mail"))),
                    AppRule(id = "b", tunnelId = 2, apps = listOf(AppRef(bank, "Bank"))),
                    AppRule(
                        id = "c",
                        tunnelId = 3,
                        apps = listOf(AppRef(workApp, "Work", AppProfile.WORK, 10)),
                    ),
                )
        )
    private val mainProfile = { pkg: String -> pkg != workApp }

    private fun step(state: AppTunnelState, fg: String?, open: Set<String>?) =
        QuartzAppTunnel.step(state, AppActivity(fg, open), data, mainProfile)

    @Test
    fun `the tunnel stays while the app is in recents and stops once it's closed there`() {
        var s = step(AppTunnelState(), mail, setOf(mail, launcher))
        assertEquals(1, s.tunnelId)
        s = step(s, launcher, setOf(mail, launcher)) // home: Mail still in recents
        assertEquals(1, s.tunnelId)
        s = step(s, launcher, setOf(launcher)) // swiped away in recents
        assertNull(s.tunnelId)
    }

    @Test
    fun `the most recently used open rule app wins`() {
        var s = step(AppTunnelState(), mail, setOf(mail))
        s = step(s, bank, setOf(mail, bank))
        assertEquals(2, s.tunnelId)
        s = step(s, launcher, setOf(mail, bank, launcher))
        assertEquals(2, s.tunnelId)
        s = step(s, launcher, setOf(mail, launcher)) // Bank closed: back to Mail's tunnel
        assertEquals(1, s.tunnelId)
    }

    @Test
    fun `apps whose closing can't be seen count only while on screen`() {
        var s = step(AppTunnelState(), workApp, setOf(launcher))
        assertEquals(3, s.tunnelId)
        s = step(s, launcher, setOf(launcher))
        assertNull(s.tunnelId)
        // Without usage access nothing is known to be open: on screen only.
        s = step(AppTunnelState(), mail, null)
        assertEquals(1, s.tunnelId)
        assertNull(step(s, launcher, null).tunnelId)
    }

    @Test
    fun `screens are alive from resume until destroyed`() {
        val apps = OpenApps()
        apps.onEvent(mail, "Inbox", RESUMED)
        apps.onEvent(mail, "Compose", RESUMED)
        apps.onEvent(mail, "Inbox", STOPPED)
        apps.onEvent(mail, "Compose", DESTROYED) // back from Compose to Inbox
        apps.onEvent(mail, "Inbox", RESUMED)
        assertEquals(setOf(mail), apps.open())
        apps.onEvent(launcher, "Home", RESUMED)
        apps.onEvent(mail, "Inbox", PAUSED)
        apps.onEvent(mail, "Inbox", STOPPED)
        assertEquals(setOf(mail, launcher), apps.open())
        assertEquals(launcher, apps.foreground)
        apps.onEvent(mail, "Inbox", DESTROYED) // swiped away in recents
        assertEquals(setOf(launcher), apps.open())
    }

    @Test
    fun `closing isn't trusted on phones that never report destroyed`() {
        val apps = OpenApps()
        apps.onEvent(mail, "Inbox", RESUMED)
        apps.onEvent(mail, "Inbox", STOPPED)
        assertNull(apps.open())
    }
}
