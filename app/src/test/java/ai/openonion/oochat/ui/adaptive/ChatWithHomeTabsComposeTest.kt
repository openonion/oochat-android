package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat and Home as two tabs on a phone — see [ChatWithHome]. Each tab gets the whole screen, so
 * the one not showing is out of sight; what it must not be is gone, or the scroll position, the
 * draft and the page would all start again each time the tab is chosen. The panes here are
 * stand-ins that hold exactly those things.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomeTabsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var placement by mutableStateOf(HomePlacement.TABBED)
    private var tab by mutableStateOf(HomeTab.CHAT)
    private val tabsChosen = mutableListOf<HomeTab>()
    private var homeBuilds = 0

    /** A chat with a long transcript to scroll and a draft being typed. */
    private val chat: @Composable (Modifier) -> Unit = { modifier ->
        var draft by remember { mutableStateOf("") }
        Column(modifier.testTag("chat")) {
            LazyColumn(Modifier.weight(1f).testTag("transcript"), state = rememberLazyListState()) {
                items((0 until 100).toList()) { Text("message $it", Modifier.height(48.dp)) }
            }
            BasicTextField(draft, { draft = it }, Modifier.testTag("draft"))
        }
    }

    /** A page with state of its own: a counter. */
    private val home: @Composable (Modifier) -> Unit = { modifier ->
        var taps by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { homeBuilds++ }
        Box(modifier.testTag("home")) {
            Text("taps: $taps", Modifier.testTag("taps").clickable { taps++ })
        }
    }

    private fun show(initial: HomePlacement = HomePlacement.TABBED) {
        placement = initial
        composeRule.setContent {
            ChatWithHome(
                placement = placement,
                tab = tab,
                onTabChange = {
                    tabsChosen += it
                    tab = it
                },
                homeLabel = "Home",
                chat = chat,
                home = home
            )
        }
    }

    private fun chooseTab(name: String) = composeRule.onNodeWithText(name).performClick()

    @Test
    fun `a phone gets a Chat tab and a Home tab, with the chat showing`() {
        show()

        composeRule.onNodeWithText("Chat").assertIsDisplayed()
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithTag("chat").assertIsDisplayed()
    }

    @Test
    fun `Home is built once, when its tab is first opened, and not before or again`() {
        show()
        composeRule.waitForIdle()
        assertEquals("not built while the chat is all there is", 0, homeBuilds)

        chooseTab("Home")
        composeRule.waitForIdle()
        assertEquals("built when its tab is first opened", 1, homeBuilds)

        chooseTab("Chat")
        chooseTab("Home")
        composeRule.waitForIdle()
        assertEquals("kept, not built again", 1, homeBuilds)
    }

    @Test
    fun `a window with Home beside the chat or none has no tabs`() {
        show(HomePlacement.BESIDE_CHAT)
        composeRule.onNodeWithText("Chat").assertDoesNotExist()
        composeRule.onNodeWithText("Home").assertDoesNotExist()

        composeRule.runOnIdle { placement = HomePlacement.OWN_SCREEN }
        composeRule.onNodeWithText("Chat").assertDoesNotExist()
        composeRule.onNodeWithText("Home").assertDoesNotExist()
    }

    @Test
    fun `choosing the Home tab asks for it and shows Home in place of the chat`() {
        show()

        chooseTab("Home")

        assertEquals(listOf(HomeTab.HOME), tabsChosen)
        composeRule.onNodeWithTag("home").assertIsDisplayed()
        composeRule.onNodeWithTag("chat").assertDoesNotExist()
    }

    @Test
    fun `choosing the Chat tab again shows the chat in place of Home`() {
        show()
        chooseTab("Home")

        chooseTab("Chat")

        assertEquals(listOf(HomeTab.HOME, HomeTab.CHAT), tabsChosen)
        composeRule.onNodeWithTag("chat").assertIsDisplayed()
        composeRule.onNodeWithTag("home").assertDoesNotExist()
    }

    @Test
    fun `the chat keeps its scroll position and its draft while Home is showing`() {
        show()
        composeRule.onNodeWithTag("transcript").performScrollToIndex(60)
        composeRule.onNodeWithTag("draft").performTextInput("half a thought")
        composeRule.onNodeWithText("message 60").assertIsDisplayed()

        chooseTab("Home")
        chooseTab("Chat")

        composeRule.onNodeWithText("message 60").assertIsDisplayed()
        composeRule.onNodeWithTag("draft").assertTextEquals("half a thought")
    }

    @Test
    fun `Home keeps its state while the chat is showing`() {
        show()
        chooseTab("Home")
        composeRule.onNodeWithTag("taps").performClick()
        composeRule.onNodeWithTag("taps").performClick()

        chooseTab("Chat")
        chooseTab("Home")

        composeRule.onNodeWithTag("taps").assertTextEquals("taps: 2")
    }

    @Test
    fun `choosing a tab takes the focus out of the chat, so its keyboard goes`() {
        show()
        composeRule.onNodeWithTag("draft").requestFocus()
        composeRule.onNodeWithTag("draft").assertIsFocused()

        chooseTab("Home")
        chooseTab("Chat")

        composeRule.onNodeWithTag("draft").assertIsNotFocused()
    }

    @Test
    fun `the chat keeps its draft when the window changes between tabs and side by side`() {
        show()
        composeRule.onNodeWithTag("draft").performTextInput("half a thought")

        // A foldable unfolded, then folded again.
        composeRule.runOnIdle { placement = HomePlacement.BESIDE_CHAT }
        composeRule.onNodeWithTag("draft").assertTextEquals("half a thought")
        composeRule.runOnIdle { placement = HomePlacement.TABBED }
        composeRule.onNodeWithTag("draft").assertTextEquals("half a thought")
    }
}
