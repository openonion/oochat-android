package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.UserMessageState
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Controls that were named but not announced as what they are (W16AAPPLE-48): TalkBack says
 * "button" only for a node with that role, and "collapsed" or "expanded" only for one that
 * reports it. Also a control whose name did not say which of several it was. The plain
 * presence of a name is checked by
 * [ai.openonion.oochat.ui.chat.ChatControlsLabelledComposeTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ControlRolesComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun `Stop is announced as a button`() {
        composeRule.setContent {
            ConnectOnionTheme { StopButton(onClick = {}) }
        }

        composeRule.onNode(hasClickAction() and hasContentDescription("Stop")).assert(isButton)
    }

    @Test
    fun `the resend row of a message that was not sent is announced as a button`() {
        composeRule.setContent {
            ConnectOnionTheme {
                UserBubble(
                    item = ChatItem.User("m1", "hello", state = UserMessageState.FAILED),
                    timestamp = null,
                    onResend = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Not sent. Tap to try again.").assert(isButton)
    }

    @Test
    fun `an expandable row is a button that says whether it is open`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ExpandableSection(
                    previewContent = { expanded -> Text(if (expanded) "open preview" else "closed preview") },
                    expandedContent = { Text("the detail") }
                )
            }
        }
        val row = composeRule.onNode(hasClickAction() and hasText("closed preview"))

        row.assert(isButton)
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))

        row.performClick()

        composeRule.onNode(hasClickAction() and hasText("open preview"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test
    fun `each attached image has a remove button that says which image it removes`() {
        composeRule.setContent {
            ConnectOnionTheme {
                AttachmentThumbnail(Uri.parse("content://test/image"), onRemove = {}, index = 1, total = 3)
            }
        }

        composeRule.onNodeWithContentDescription("Remove image 2 of 3").assertExists()
    }

    @Test
    fun `a single attached image has a plain remove button`() {
        composeRule.setContent {
            ConnectOnionTheme {
                AttachmentThumbnail(Uri.parse("content://test/image"), onRemove = {})
            }
        }

        composeRule.onNodeWithContentDescription("Remove image").assertExists()
    }
}
