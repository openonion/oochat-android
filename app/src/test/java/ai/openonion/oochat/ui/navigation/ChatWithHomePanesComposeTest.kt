package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.chat.RecompFakeConnectUseCase
import ai.openonion.oochat.ui.chat.chatViewModelOver
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Which Home the chat gets beside it — see [ChatWithHomePanes]. The chat is told whether Home is
 * beside it, and the Home pane is drawn only for an agent with a Home the device can show. The
 * panes are stand-ins that say which one was asked for; the view model is a real [ChatViewModel]
 * over fakes, as in [ai.openonion.oochat.ui.chat.ChatScreenRecompositionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomePanesComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64))
    private lateinit var connectUseCase: RecompFakeConnectUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        connectUseCase = RecompFakeConnectUseCase()
        viewModel = chatViewModelOver(connectUseCase)
    }

    private fun show(deviceHostsApps: Boolean = true) {
        composeRule.setContent {
            ChatWithHomePanes(
                viewModel = viewModel,
                hostsApps = { deviceHostsApps },
                chat = { _, homeBeside -> Text("chat, Home beside: $homeBeside") },
                home = { opensControlCenter -> Text(if (opensControlCenter) "Control Center pane" else "Dashboard pane") }
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with a Dashboard gets it beside the chat`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home beside: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with a Control Center the device can host gets it beside the chat`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = true)

        composeRule.onNodeWithText("Control Center pane").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home beside: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with no Home gets nothing beside the chat`() {
        show()

        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
        composeRule.onNodeWithText("Control Center pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home beside: false").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet a Control Center the device cannot host stays behind the Home button`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = false)

        composeRule.onNodeWithText("Control Center pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home beside: false").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with both gets the Dashboard when the device cannot host the Control Center`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = false)

        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()
        composeRule.onNodeWithText("Control Center pane").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone Home stays off the chat's screen`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home beside: false").assertIsDisplayed()
    }
}
