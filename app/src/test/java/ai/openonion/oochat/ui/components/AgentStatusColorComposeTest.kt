package ai.openonion.oochat.ui.components

import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An agent's status dot (W16AAPPLE-49): the palette has no accent to spare for "connecting", so
 * it is the brand green that is on the way to connected, and the dot pulses and the badge spins
 * to say so. It must stay apart from the grey of a disabled agent and the red of a failed one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentStatusColorComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val seen = mutableMapOf<String, Color>()

    private fun render() {
        composeRule.setContent {
            ConnectOnionTheme {
                seen["primary"] = MaterialTheme.colorScheme.primary
                seen["outline"] = MaterialTheme.colorScheme.outline
                seen["error"] = MaterialTheme.colorScheme.error
                seen["active"] = agentStatusColor(AgentStatus.Active)
                seen["connecting"] = agentStatusColor(AgentStatus.Connecting)
                seen["connected"] = agentStatusColor(AgentStatus.Connected)
                seen["disabled"] = agentStatusColor(AgentStatus.Disabled)
                seen["failed"] = agentStatusColor(AgentStatus.Error("no route"))
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `connecting is the brand green, as active and connected are`() {
        render()

        assertEquals(seen["primary"], seen["connecting"])
        assertEquals(seen["primary"], seen["active"])
        assertEquals(seen["primary"], seen["connected"])
    }

    @Test
    fun `connecting is not the grey of a disabled agent or the red of a failed one`() {
        render()

        assertNotEquals(seen["outline"], seen["connecting"])
        assertEquals(seen["outline"], seen["disabled"])
        assertEquals(seen["error"], seen["failed"])
    }
}
