package ai.openonion.oochat.ui.rem

import ai.openonion.oochat.domain.model.RemNotebook
import ai.openonion.oochat.domain.model.RemOutcome
import ai.openonion.oochat.domain.model.RemPage
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performFirstLinkClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** W16AAPPLE-69: the Rem screen's notebook and every state around it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val notebook = RemNotebook(
        listOf(
            RemPage(
                "people/sam.md",
                "people",
                "Sam Lee",
                "# Sam Lee\n\nOwns the **launch** plan.\n\nWorks on [Apollo](../projects/apollo.md).",
                "2026-11-01T22:00:00+00:00"
            ),
            RemPage("projects/apollo.md", "projects", "Apollo", "Ships on Friday.")
        )
    )

    private var retried = 0

    private fun show(outcome: RemOutcome?, ownAddress: String? = null) {
        composeRule.setContent {
            var state by remember { mutableStateOf(RemUiState(outcome = outcome, ownAddress = ownAddress)) }
            ConnectOnionTheme {
                RemScreen(
                    state = state,
                    onNavigateBack = {},
                    onRetry = { retried++ },
                    onOpenPage = { state = state.copy(openPageId = it) },
                    onClosePage = { state = state.copy(openPageId = null) }
                )
            }
        }
    }

    @Test
    fun `the screen is called Rem, never Wiki`() {
        show(RemOutcome.Loaded(notebook))

        composeRule.onNodeWithText("Rem").assertIsDisplayed()
        composeRule.onNodeWithText("Wiki", substring = true, ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun `the contents list pages under their categories`() {
        show(RemOutcome.Loaded(notebook))

        composeRule.onNodeWithText("People").assertIsDisplayed()
        composeRule.onNodeWithText("Projects").assertIsDisplayed()
        composeRule.onNodeWithText("Sam Lee").assertIsDisplayed()
        composeRule.onNodeWithText("Updated 2026-11-01").assertIsDisplayed()
    }

    @Test
    fun `a page opens as rendered Markdown`() {
        show(RemOutcome.Loaded(notebook))

        composeRule.onNodeWithText("Sam Lee").performClick()

        // The bold marker is rendered, not shown as asterisks.
        composeRule.onNodeWithText("Owns the launch plan.").assertIsDisplayed()
        composeRule.onNodeWithText("**", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a page without its own heading gets its title`() {
        show(RemOutcome.Loaded(notebook))

        composeRule.onNodeWithText("Apollo").performClick()

        composeRule.onNodeWithText("Apollo").assertIsDisplayed()
        composeRule.onNodeWithText("Ships on Friday.").assertIsDisplayed()
    }

    @Test
    fun `a non-owner is told why, with the exact command for the owner`() {
        show(RemOutcome.NotOwner, ownAddress = "0xabc123")

        composeRule.onNodeWithText("Only the owner can read this Rem").assertIsDisplayed()
        composeRule.onNodeWithText("co trust admin add 0xabc123").assertIsDisplayed()
    }

    @Test
    fun `a Host with no notebook names co rem's command, not co wiki's`() {
        show(RemOutcome.NoNotebook)

        composeRule.onNodeWithText("co rem init").assertIsDisplayed()
    }

    @Test
    fun `a failure never shows the Host's own words, and offers a retry`() {
        show(RemOutcome.Failed("Signed co rem request required"))

        composeRule.onNodeWithText("Signed co rem request required").assertDoesNotExist()
        composeRule.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `a Host that lost this connection asks for a reconnect, not another try`() {
        show(RemOutcome.SessionLost)

        composeRule.onNodeWithText("Reconnect to read Rem").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `a notebook too large to send offers no Try again`() {
        show(RemOutcome.TooLarge)

        composeRule.onNodeWithText("Rem is too large to open here").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertDoesNotExist()
    }

    @Test
    fun `only the states a retry can fix offer one`() {
        val noRetry = listOf(RemOutcome.TooLarge, RemOutcome.SessionLost, RemOutcome.HostTooOld)
        val retry = listOf(
            RemOutcome.NotConnected,
            RemOutcome.NotOwner,
            RemOutcome.NoNotebook,
            RemOutcome.NoAnswer,
            RemOutcome.Failed("x")
        )

        noRetry.forEach { assertEquals("$it", false, it.message(ownAddress = null).canRetry) }
        retry.forEach { assertEquals("$it", true, it.message(ownAddress = null).canRetry) }
    }

    @Test
    fun `a link to another page of the notebook opens that page`() {
        show(RemOutcome.Loaded(notebook))
        composeRule.onNodeWithText("Sam Lee").performClick()

        composeRule.onNodeWithText("Works on Apollo.").performFirstLinkClick()

        composeRule.onNodeWithText("Ships on Friday.").assertIsDisplayed()
    }

    @Test
    fun `while the read is in flight the screen says so`() {
        show(outcome = null)

        composeRule.onNodeWithText("Reading this agent's Rem…").assertIsDisplayed()
    }
}
