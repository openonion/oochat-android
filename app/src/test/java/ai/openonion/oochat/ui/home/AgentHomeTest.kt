package ai.openonion.oochat.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an agent's Home is on a device — see [AgentHome].
 */
class AgentHomeTest {

    private var asked = 0

    private fun home(hasDashboard: Boolean, hasApp: Boolean, deviceHostsApps: Boolean = true) =
        AgentHome(hasDashboard, hasApp) {
            asked++
            deviceHostsApps
        }

    private fun assertHome(home: AgentHome, hasPage: Boolean, opensControlCenter: Boolean, canSitBesideChat: Boolean) {
        assertEquals("has a page", hasPage, home.hasPage)
        assertEquals("opens the Control Center", opensControlCenter, home.opensControlCenter)
        assertEquals("can sit beside the chat", canSitBesideChat, home.canSitBesideChat)
    }

    @Test
    fun `an agent with no Home has nothing to open or to put beside the chat`() {
        assertHome(home(hasDashboard = false, hasApp = false), hasPage = false, opensControlCenter = false, canSitBesideChat = false)
    }

    @Test
    fun `a Dashboard opens as the Dashboard and can sit beside the chat`() {
        assertHome(home(hasDashboard = true, hasApp = false), hasPage = true, opensControlCenter = false, canSitBesideChat = true)
    }

    @Test
    fun `a Control Center the device can host opens as the Control Center and can sit beside the chat`() {
        assertHome(home(hasDashboard = false, hasApp = true), hasPage = true, opensControlCenter = true, canSitBesideChat = true)
    }

    @Test
    fun `a Control Center the device cannot host still opens, to say why, but stays behind the Home button`() {
        assertHome(
            home(hasDashboard = false, hasApp = true, deviceHostsApps = false),
            hasPage = true,
            opensControlCenter = true,
            canSitBesideChat = false
        )
    }

    @Test
    fun `with both, a device that can host the app gets the Control Center`() {
        assertHome(home(hasDashboard = true, hasApp = true), hasPage = true, opensControlCenter = true, canSitBesideChat = true)
    }

    @Test
    fun `with both, a device that cannot host the app gets the Dashboard`() {
        assertHome(
            home(hasDashboard = true, hasApp = true, deviceHostsApps = false),
            hasPage = true,
            opensControlCenter = false,
            canSitBesideChat = true
        )
    }

    @Test
    fun `the WebView is not asked about for an agent with no Control Center`() {
        val noApp = home(hasDashboard = true, hasApp = false)
        noApp.hasPage
        noApp.opensControlCenter
        noApp.canSitBesideChat

        assertEquals("asked", 0, asked)
    }

    @Test
    fun `the WebView is only asked about to choose between a Control Center and a Dashboard`() {
        home(hasDashboard = false, hasApp = true).opensControlCenter
        assertEquals("asked for a Control Center alone", 0, asked)

        home(hasDashboard = true, hasApp = true).opensControlCenter
        assertEquals("asked for both", 1, asked)
    }

    @Test
    fun `the WebView is not asked about to know whether there is a Home page`() {
        home(hasDashboard = false, hasApp = true).hasPage
        home(hasDashboard = true, hasApp = true).hasPage

        assertEquals("asked", 0, asked)
    }
}
