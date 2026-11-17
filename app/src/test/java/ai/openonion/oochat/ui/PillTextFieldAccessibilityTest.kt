package ai.openonion.oochat.ui

import ai.openonion.oochat.ui.agent.components.AgentAddressField
import ai.openonion.oochat.ui.components.PillTextField
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for fix(a11y): PillTextField had zero semantics, so the
 * Server URL and Agent Address fields were announced by TalkBack
 * with no name at all — a sibling [ai.openonion.oochat.ui.agent.components.FieldLabel]
 * displayed the name but sat outside the field's own semantics node.
 *
 * Each test asserts the exact shape of the fix: one semantics node carries
 * both the field's name (`contentDescription`) and the editable value
 * (`hasSetTextAction`) — not two separate nodes — and that typing still
 * works and the typed text is still readable from that same node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PillTextFieldAccessibilityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `PillTextField name and editable value share one semantics node`() {
        composeRule.setContent {
            ConnectOnionTheme {
                var text by remember { mutableStateOf("") }
                PillTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "https://oo.openonion.ai",
                    label = "Server URL"
                )
            }
        }

        val field = composeRule.onNode(hasContentDescription("Server URL") and hasSetTextAction())
        field.performTextInput("hello")
        field.assertTextEquals("hello")
    }

    @Test
    fun `AgentAddressField name and editable value share one semantics node`() {
        composeRule.setContent {
            ConnectOnionTheme {
                var address by remember { mutableStateOf("") }
                // useDirectConnection = true skips the ExposedDropdownMenuBox
                // branch — irrelevant to this fix, and awkward under Robolectric.
                AgentAddressField(
                    value = address,
                    onValueChange = { address = it },
                    savedAgents = emptyList(),
                    useDirectConnection = true
                )
            }
        }

        val field = composeRule.onNode(hasContentDescription("Agent Address") and hasSetTextAction())
        field.performTextInput("0xabc123")
        field.assertTextEquals("0xabc123")
    }
}
