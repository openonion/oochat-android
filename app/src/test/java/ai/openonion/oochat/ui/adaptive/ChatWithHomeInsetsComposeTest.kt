package ai.openonion.oochat.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tabs sit under the status bar, so the chat under them must not leave room for it a second
 * time. The chat's own top bar pads for the status bar, as the stand-in here does, and would
 * otherwise leave a gap as tall as the status bar between itself and the tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatWithHomeInsetsComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `the chat's top bar sits directly under the tabs, with no second gap for the status bar`() {
        WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
        composeRule.setContent {
            ChatWithHome(
                placement = HomePlacement.TABBED,
                tab = HomeTab.CHAT,
                onTabChange = {},
                homeLabel = "Home",
                chat = { modifier ->
                    Box(modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                        Box(Modifier.fillMaxWidth().height(40.dp).testTag("top bar"))
                    }
                },
                home = {}
            )
        }
        composeRule.runOnUiThread {
            val statusBar = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(composeRule.activity.window.decorView, statusBar)
        }
        composeRule.waitForIdle()

        val tab = composeRule.onAllNodes(isSelectable())[0].getBoundsInRoot()
        val topBar = composeRule.onNodeWithTag("top bar").getBoundsInRoot()
        assertTrue("the status bar's inset reached the tabs", tab.top > 10.dp)
        assertTrue("the top bar starts where the tabs end, not a status bar lower", topBar.top - tab.bottom < 8.dp)
    }
}
