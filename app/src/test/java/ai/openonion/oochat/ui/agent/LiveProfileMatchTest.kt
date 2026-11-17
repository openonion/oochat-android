package ai.openonion.oochat.ui.agent

import ai.openonion.oochat.domain.model.AgentLiveProfile
import ai.openonion.oochat.domain.model.AgentProfile
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * W16AAPPLE-71: which saved agent the live profile is shown on. The match
 * used to compare against this device's own address, so it never held and
 * the agent screen never showed a live profile.
 */
class LiveProfileMatchTest {

    private val address = "0x" + "ab".repeat(32)
    private val agent = AgentProfile(id = "a1", address = address, name = "Agent", serverUrl = "https://oo.openonion.ai", createdAt = 0L)
    private fun profile(address: String?) = AgentLiveProfile(sessionId = "s1", name = "Agent", address = address, model = null)

    @Test
    fun `the connected agent's profile is shown on its own screen`() {
        val live = profile(address)
        assertSame(live, liveProfileFor(agent, live))
    }

    @Test
    fun `the match ignores the case the address was typed in`() {
        val live = profile(address.uppercase().replace("0X", "0x"))
        assertSame(live, liveProfileFor(agent, live))
    }

    @Test
    fun `another agent's profile is not shown`() {
        assertNull(liveProfileFor(agent, profile("0x" + "cd".repeat(32))))
    }

    @Test
    fun `nothing is shown without a profile, an address or a saved agent`() {
        assertNull(liveProfileFor(agent, null))
        assertNull(liveProfileFor(agent, profile(null)))
        assertNull(liveProfileFor(null, profile(address)))
    }
}
