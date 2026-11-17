package ai.openonion.oochat.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What running a skill from the Home page does — see [runSkillFromHome].
 */
class RunSkillFromHomeTest {

    private val sent = mutableListOf<String>()
    private var wentBack = 0

    private fun run(besideChat: Boolean) =
        runSkillFromHome("/summarise today", besideChat, { sent += it }, { wentBack++ })

    @Test
    fun `from a screen of its own the message is sent and the conversation is shown`() {
        run(besideChat = false)

        assertEquals(listOf("/summarise today"), sent)
        assertEquals(1, wentBack)
    }

    @Test
    fun `beside the chat the message is sent and the page stays where it is`() {
        run(besideChat = true)

        assertEquals(listOf("/summarise today"), sent)
        assertEquals(0, wentBack)
    }
}
