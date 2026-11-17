package ai.openonion.oochat.ui.adaptive

import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat has a navigation drawer. Showing Home in place of the chat and coming back must leave it
 * closed, as it was, rather than sliding it open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomeDrawerComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var tab by mutableStateOf(HomeTab.CHAT)
    private lateinit var drawerState: DrawerState

    @Test
    fun `the chat's drawer is still closed when its tab comes back`() {
        composeRule.setContent {
            ChatWithHome(
                placement = HomePlacement.TABBED,
                tab = tab,
                onTabChange = { tab = it },
                homeLabel = "Home",
                chat = { modifier ->
                    drawerState = rememberDrawerState(DrawerValue.Closed)
                    Pane(modifier) {
                        ModalNavigationDrawer(
                            drawerContent = { ModalDrawerSheet { Text("the drawer") } },
                            drawerState = drawerState
                        ) { Text("the conversation") }
                    }
                },
                home = { modifier -> Pane(modifier) { Text("the page") } }
            )
        }

        composeRule.onNodeWithText("Home").performClick()
        composeRule.onNodeWithText("Chat").performClick()

        composeRule.runOnIdle { assertEquals(DrawerValue.Closed, drawerState.currentValue) }
    }
}
