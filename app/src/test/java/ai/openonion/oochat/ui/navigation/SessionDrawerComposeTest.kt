package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.AgentStatus
import ai.openonion.oochat.ui.chat.ChatNavDrawerContent
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The drawer as the chat screen wires it: new chat, switch, rename, delete.
 * Storage is covered by [ai.openonion.oochat.domain.usecase.ConversationHistoryUseCaseTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionDrawerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val logReader = "0x" + "ab".repeat(32)
    private val deployer = "0x" + "cd".repeat(32)

    private val sections = listOf(
        DrawerAgentSection(
            agentId = "agent-1", agentAddress = logReader, name = "Log reader", status = AgentStatus.Connected,
            sessions = listOf(DrawerEntry("conv-1", "Logs"), DrawerEntry("conv-2", "Warnings"))
        ),
        DrawerAgentSection(
            agentId = "agent-2", agentAddress = deployer, name = "Deployer", status = AgentStatus.Active,
            sessions = listOf(DrawerEntry("conv-3", "Release"))
        )
    )

    private class Calls {
        var newChats = 0
        val switchedTo = mutableListOf<String>()
        val navigated = mutableListOf<Triple<String, String?, Boolean>>()
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        var closes = 0
    }

    private fun setDrawer(openAgent: String = logReader, activeSession: String = "conv-1"): Calls {
        val calls = Calls()
        composeRule.setContent {
            ConnectOnionTheme {
                ChatNavDrawerContent(
                    agentSections = sections,
                    activeSessionId = activeSession,
                    targetAgentAddress = openAgent,
                    onCloseDrawer = { calls.closes++ },
                    onStartNewSession = { calls.newChats++ },
                    onSwitchToSession = { calls.switchedTo += it },
                    onDeleteSession = { calls.deleted += it },
                    onRenameSession = { id, title -> calls.renamed += id to title },
                    onNavigateToAgentList = {},
                    onNavigateToSettings = {},
                    onNavigateToAgentChat = { address, session, startNew -> calls.navigated += Triple(address, session, startNew) }
                )
            }
        }
        return calls
    }

    // ── new chat ────────────────────────────────────────────────────────

    @Test
    fun `new chat with the open agent starts one here and closes the drawer`() {
        val calls = setDrawer()

        composeRule.onNodeWithContentDescription("New chat with Log reader").performScrollTo().performClick()

        assertEquals(1, calls.newChats)
        assertTrue("staying with this agent, so no navigation", calls.navigated.isEmpty())
        assertEquals(1, calls.closes)
    }

    @Test
    fun `new chat with another agent opens that agent on a fresh chat`() {
        val calls = setDrawer()

        composeRule.onNodeWithContentDescription("New chat with Deployer").performScrollTo().performClick()

        assertEquals(listOf(Triple(deployer, null as String?, true)), calls.navigated)
        assertEquals(0, calls.newChats)
        assertEquals(1, calls.closes)
    }

    // ── switching ───────────────────────────────────────────────────────

    @Test
    fun `tapping another chat of the open agent switches to it in place`() {
        val calls = setDrawer()

        composeRule.onNodeWithText("Warnings").performScrollTo().performClick()

        assertEquals(listOf("conv-2"), calls.switchedTo)
        assertTrue(calls.navigated.isEmpty())
        assertEquals(1, calls.closes)
    }

    @Test
    fun `tapping a chat of another agent opens that agent on that chat`() {
        val calls = setDrawer()

        composeRule.onNodeWithText("Release").performScrollTo().performClick()

        assertEquals(listOf(Triple(deployer, "conv-3" as String?, false)), calls.navigated)
        assertTrue(calls.switchedTo.isEmpty())
    }

    @Test
    fun `every agent's chats are listed under it, and an agent with none says so`() {
        composeRule.setContent {
            ConnectOnionTheme {
                NavDrawer(
                    agentSections = sections + DrawerAgentSection("agent-3", "0x" + "ef".repeat(32), "Fresh", AgentStatus.Active, emptyList()),
                    activeEntryId = "conv-1",
                    themeMode = ai.openonion.oochat.domain.model.ThemeMode.SYSTEM,
                    onNewChatForAgent = {},
                    onSelectAgentSession = { _, _ -> },
                    onDeleteSession = {},
                    onRenameSession = { _, _ -> },
                    onOpenAgentList = {},
                    onOpenSettings = {},
                    onThemeModeChange = {}
                )
            }
        }

        listOf("Logs", "Warnings", "Release", "No chats yet").forEach {
            composeRule.onNodeWithText(it).performScrollTo().assertExists()
        }
    }

    // ── rename ──────────────────────────────────────────────────────────

    private fun openRename(title: String) {
        composeRule.onNodeWithContentDescription("Rename \"$title\"").performScrollTo().performClick()
        composeRule.onNodeWithText("Rename chat").assertExists()
    }

    private fun typeName(name: String) {
        composeRule.onNode(hasSetTextAction()).performTextReplacement(name)
    }

    private val save get() = composeRule.onNodeWithText("Save")

    @Test
    fun `the rename sheet opens on the current name with Save disabled`() {
        setDrawer()
        openRename("Logs")

        composeRule.onNode(hasSetTextAction()).assertTextEquals("Logs")
        save.assertIsNotEnabled()
    }

    @Test
    fun `Save stays disabled until the name actually changes`() {
        setDrawer()
        openRename("Logs")

        typeName("  Logs  ")
        save.assertIsNotEnabled()

        typeName("   ")
        save.assertIsNotEnabled()

        typeName("Log review")
        save.assertIsEnabled()

        typeName("Logs")
        save.assertIsNotEnabled()
    }

    @Test
    fun `saving hands over the trimmed name for that chat and closes the sheet`() {
        val calls = setDrawer()
        openRename("Warnings")

        typeName("  Warnings from Tuesday ")
        save.performClick()

        assertEquals(listOf("conv-2" to "Warnings from Tuesday"), calls.renamed)
        composeRule.onNodeWithText("Rename chat").assertDoesNotExist()
    }

    @Test
    fun `cancelling a rename changes nothing`() {
        val calls = setDrawer()
        openRename("Logs")

        typeName("Something else")
        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(calls.renamed.isEmpty())
        composeRule.onNodeWithText("Rename chat").assertDoesNotExist()
    }

    // ── delete ──────────────────────────────────────────────────────────

    @Test
    fun `delete asks first, naming the chat, and deletes nothing until confirmed`() {
        val calls = setDrawer()

        composeRule.onNodeWithContentDescription("Delete \"Release\"").performScrollTo().performClick()

        composeRule.onNodeWithText("Delete this chat?").assertExists()
        composeRule.onNodeWithText("\"Release\" and all its messages will be permanently deleted.").assertExists()
        assertTrue("nothing is deleted by the trash icon alone", calls.deleted.isEmpty())
    }

    @Test
    fun `confirming deletes that chat and nothing else`() {
        val calls = setDrawer()

        composeRule.onNodeWithContentDescription("Delete \"Warnings\"").performScrollTo().performClick()
        composeRule.onNodeWithText("Delete").performClick()

        assertEquals(listOf("conv-2"), calls.deleted)
        composeRule.onNodeWithText("Delete this chat?").assertDoesNotExist()
    }

    @Test
    fun `cancelling the confirmation keeps the chat`() {
        val calls = setDrawer()

        composeRule.onNodeWithContentDescription("Delete \"Logs\"").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performClick()

        assertTrue(calls.deleted.isEmpty())
        composeRule.onNodeWithText("Delete this chat?").assertDoesNotExist()
    }
}
