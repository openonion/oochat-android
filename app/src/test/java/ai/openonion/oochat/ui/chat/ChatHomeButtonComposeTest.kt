package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ControlCenterApp
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's Home button in the top bar: shown when the agent has a Home page that opens on a
 * screen of its own, hidden when that page is already beside the chat. The chat is the real
 * [ChatScreen] over a real [ChatViewModel], as in [ChatScreenRecompositionTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatHomeButtonComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val homeButton = "Open agent home"
    private lateinit var connectUseCase: RecompFakeConnectUseCase
    private lateinit var viewModel: ChatViewModel

    @Before
    fun setUp() {
        connectUseCase = RecompFakeConnectUseCase()
        viewModel = chatViewModelOver(connectUseCase)
    }

    private fun showChat(homeInLayout: Boolean) {
        composeRule.setContent {
            ConnectOnionTheme {
                ChatScreen(
                    connectToAgentId = "0x1a2b3c4d5e6f7a8b",
                    viewModel = viewModel,
                    homeInLayout = homeInLayout
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `an agent with a Dashboard gets no Home button, as the Dashboard is always in the layout`() {
        connectUseCase.dashboard.value = "<p>home</p>"
        showChat(homeInLayout = false)

        composeRule.onNodeWithContentDescription(homeButton).assertDoesNotExist()
    }

    @Test
    fun `the Home button is hidden when Home is already in the chat's layout`() {
        connectUseCase.controlCenter.value = ControlCenterApp(
            url = "https://r-1.apps.example.net/index.html",
            revision = "sha256:" + "a".repeat(64)
        )
        showChat(homeInLayout = true)

        composeRule.onNodeWithContentDescription(homeButton).assertDoesNotExist()
    }

    @Test
    fun `an agent whose Control Center the device cannot host still gets the Home button`() {
        connectUseCase.controlCenter.value = ControlCenterApp(
            url = "https://r-1.apps.example.net/index.html",
            revision = "sha256:" + "a".repeat(64)
        )
        showChat(homeInLayout = false)

        // That screen is where the notice says why the app cannot be shown.
        composeRule.onNodeWithContentDescription(homeButton).assertIsDisplayed()
    }

    @Test
    fun `an agent with no Home page gets no Home button`() {
        showChat(homeInLayout = false)

        composeRule.onNodeWithContentDescription(homeButton).assertDoesNotExist()
    }
}
