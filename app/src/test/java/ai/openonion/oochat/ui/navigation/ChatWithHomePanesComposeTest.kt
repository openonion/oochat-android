package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.chat.RecompFakeConnectUseCase
import ai.openonion.oochat.ui.chat.chatViewModelOver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val app = ControlCenterApp(url = "https://r-1.apps.example.net/index.html", revision = "sha256:" + "a".repeat(64))
    private lateinit var connectUseCase: RecompFakeConnectUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        connectUseCase = RecompFakeConnectUseCase()
        viewModel = chatViewModelOver(connectUseCase)
    }

    @Composable
    private fun PanesUnderTest(deviceHostsApps: Boolean) {
        ChatWithHomePanes(
            viewModel = viewModel,
            hostsApps = { deviceHostsApps },
            chat = { _, homeInLayout -> Text("chat, Home in layout: $homeInLayout") },
            home = { opensControlCenter, besideChat, onNavigateBack ->
                Column {
                    Text(if (opensControlCenter) "Control Center pane" else "Dashboard pane")
                    Text("Home beside: $besideChat")
                    Text("back to the chat", Modifier.clickable(onClick = onNavigateBack))
                }
            }
        )
    }

    private fun show(deviceHostsApps: Boolean = true) {
        composeRule.setContent { PanesUnderTest(deviceHostsApps) }
        composeRule.waitForIdle()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with a Dashboard gets it beside the chat`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()
        composeRule.onNodeWithText("Home beside: true").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with a Control Center the device can host gets it beside the chat`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = true)

        composeRule.onNodeWithText("Control Center pane").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet an agent with no Home gets nothing beside the chat`() {
        show()

        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
        composeRule.onNodeWithText("Control Center pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home in layout: false").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w900dp-h800dp")
    fun `on a tablet a Control Center the device cannot host stays behind the Home button`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = false)

        composeRule.onNodeWithText("Control Center pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home in layout: false").assertIsDisplayed()
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
    fun `on a phone the chat is told Home is a tab of its own, not drawn on its screen`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone an agent with a Dashboard gets a Dashboard tab that is built when first opened`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard").assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `an agent with a Control Center the device can host gets a tab named for it`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = true)

        composeRule.onNodeWithText("Control Center").assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard").assertDoesNotExist()

        composeRule.onNodeWithText("Control Center").performClick()

        composeRule.onNodeWithText("Control Center pane").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `the Home tab shows Home as a screen of its own, in place of the chat`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()

        composeRule.onNodeWithText("Dashboard").performClick()

        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()
        composeRule.onNodeWithText("Home beside: false").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home in layout: true").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `going back from Home, as a skill run does, returns to the chat`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()
        composeRule.onNodeWithText("Dashboard").performClick()

        composeRule.onNodeWithText("back to the chat").performClick()

        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
        composeRule.onNodeWithText("Dashboard pane").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `the back button returns from the Home tab to the chat`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()
        composeRule.onNodeWithText("Dashboard").performClick()

        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `the tab showing is kept when the screen is recreated, as it is by a rotation`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { PanesUnderTest(deviceHostsApps = true) }
        composeRule.onNodeWithText("Dashboard").performClick()
        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Dashboard pane").assertIsDisplayed()
        composeRule.onNodeWithText("chat, Home in layout: true").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `a Home that goes while its tab is showing does not bring the tab back when it returns`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        show()
        composeRule.onNodeWithText("Dashboard").performClick()

        // The agent reconnects: its Home is cleared, then sent again.
        composeRule.runOnIdle { connectUseCase.dashboard.value = null }
        composeRule.onNodeWithText("chat, Home in layout: false").assertIsDisplayed()
        composeRule.runOnIdle { connectUseCase.dashboard.value = "<p>home</p>" }

        composeRule.onNodeWithText("chat, Home in layout: true").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone an agent with no Home has no tabs`() {
        show()

        composeRule.onNodeWithText("Chat").assertDoesNotExist()
        composeRule.onNodeWithText("Dashboard").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp")
    fun `on a phone a Control Center the device cannot host has no tab`() {
        connectUseCase.controlCenter.value = app
        show(deviceHostsApps = false)

        composeRule.onNodeWithText("Control Center").assertDoesNotExist()
    }
}
