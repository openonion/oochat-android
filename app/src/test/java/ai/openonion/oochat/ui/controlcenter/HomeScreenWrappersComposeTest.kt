package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.ui.chat.ChatViewModel
import ai.openonion.oochat.ui.chat.RecompFakeConnectUseCase
import ai.openonion.oochat.ui.chat.chatViewModelOver
import ai.openonion.oochat.ui.dashboard.DashboardScreenWrapper
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The two Home screens' wrappers tell their screen whether it is beside the chat, which decides
 * whether it has a way back to the chat. The chat's view model is the one the wrappers find in the
 * chat destination's store, as in the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenWrappersComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var chatEntry: ViewModelStoreOwner

    @Before
    fun setUp() {
        val viewModel = chatViewModelOver(RecompFakeConnectUseCase())
        val store = ViewModelStore()
        chatEntry = object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
        // The wrappers ask the store for a ChatViewModel and find this one.
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = viewModel as T
            }
        )[ChatViewModel::class.java]
    }

    private fun showControlCenter(besideChat: Boolean) {
        composeRule.setContent {
            ConnectOnionTheme {
                ControlCenterScreenWrapper(chatViewModelStoreOwner = chatEntry, onNavigateBack = {}, besideChat = besideChat)
            }
        }
        composeRule.waitForIdle()
    }

    private fun showDashboard(besideChat: Boolean) {
        composeRule.setContent {
            ConnectOnionTheme {
                DashboardScreenWrapper(chatViewModelStoreOwner = chatEntry, onNavigateBack = {}, besideChat = besideChat)
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `a Control Center on a screen of its own has the way back to the chat`() {
        showControlCenter(besideChat = false)

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `a Control Center beside the chat has no way back`() {
        showControlCenter(besideChat = true)

        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a Dashboard on a screen of its own has the way back to the chat`() {
        showDashboard(besideChat = false)

        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `a Dashboard beside the chat has no way back`() {
        showDashboard(besideChat = true)

        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
