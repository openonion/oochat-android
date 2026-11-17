package ai.openonion.oochat.ui.components

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app's own snackbar stands in for Material's, which has a 6dp shadow that can't be changed
 * from outside (W16AAPPLE-49). It must still do what a snackbar does: show the message, offer an
 * action when it has one, and tell whoever asked what the person did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppSnackbarHostComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var result: SnackbarResult? = null

    private fun show(message: String, actionLabel: String? = null) {
        val state = SnackbarHostState()
        composeRule.setContent {
            ConnectOnionTheme {
                LaunchedEffect(Unit) { result = state.showSnackbar(message, actionLabel) }
                AppSnackbarHost(state)
            }
        }
    }

    @Test
    fun `a message shows, with no action when it has none`() {
        show("Logs copied to clipboard")

        composeRule.onNodeWithText("Logs copied to clipboard").assertIsDisplayed()
        composeRule.onNode(hasClickAction()).assertDoesNotExist()
    }

    @Test
    fun `an action is offered and answers the caller when it is tapped`() {
        show("Conversation deleted", actionLabel = "Undo")
        composeRule.onNodeWithText("Conversation deleted").assertIsDisplayed()
        assertNull(result)

        composeRule.onNodeWithText("Undo").performClick()
        composeRule.waitForIdle()

        assertEquals(SnackbarResult.ActionPerformed, result)
    }
}
