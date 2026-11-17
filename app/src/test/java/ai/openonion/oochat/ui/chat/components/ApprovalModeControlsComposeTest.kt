package ai.openonion.oochat.ui.chat.components

import ai.openonion.oochat.domain.model.ApprovalMode
import ai.openonion.oochat.domain.model.SessionModes
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ApprovalModeChip] must read differently while [ApprovalMode] is only
 * requested versus agent-confirmed — see
 * [ai.openonion.oochat.data.repository.ConnectionRepository.modePending]'s
 * doc for the two server-side windows this covers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalModeControlsComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a pending chip shows the pending indicator`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = true,
                    pending = true,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mode change pending").assertExists()
    }

    @Test
    fun `a confirmed chip shows no pending indicator`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.SAFE,
                    enabled = true,
                    pending = false,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Mode change pending").assertDoesNotExist()
    }

    // ── Host-managed modes (W16AAPPLE-18) ──────────────────────────────

    private val hostModes = SessionModes(
        available = listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO, ApprovalMode.FULL_ACCESS),
        hostManaged = true
    )

    @Test
    fun `a full access chip shows the Host's remaining turns and offers exit`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = ApprovalMode.FULL_ACCESS,
                    enabled = true,
                    turnsLeft = 3,
                    onCycle = {},
                    onOpenSheet = {}
                )
            }
        }

        composeRule.onNodeWithText("full access · 3 left · exit").assertExists()
    }

    @Test
    fun `the sheet offers the Host's modes, and full access with no turn field`() {
        var picked: Pair<ApprovalMode, Int?>? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { mode, turns -> picked = mode to turns },
                    onDismiss = {},
                    modes = hostModes
                )
            }
        }

        composeRule.onNodeWithText("Read only").assertExists()
        composeRule.onNodeWithText("Auto").assertExists()
        composeRule.onNodeWithText("Safe").assertDoesNotExist()
        composeRule.onNodeWithText("Ultra work").assertDoesNotExist()
        composeRule.onNodeWithText("Turns").assertDoesNotExist()

        composeRule.onNodeWithText("Start full access").performScrollTo().performClick()
        assertEquals("full access was sent without a confirmation", null, picked)

        composeRule.onNodeWithText("Enable bounded full access?").assertExists()
        composeRule.onNodeWithText("Enable").performScrollTo().performClick()
        assertEquals(ApprovalMode.FULL_ACCESS to null, picked)
    }

    // ── Full access confirmation (W16AAPPLE-42) ─────────────────────────

    @Test
    fun `cancelling the confirmation sends nothing and offers full access again`() {
        var picked: ApprovalMode? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { mode, _ -> picked = mode },
                    onDismiss = {},
                    modes = hostModes
                )
            }
        }

        composeRule.onNodeWithText("Start full access").performScrollTo().performClick()
        composeRule.onNodeWithText("Cancel").performScrollTo().performClick()

        assertEquals(null, picked)
        composeRule.onNodeWithText("Enable bounded full access?").assertDoesNotExist()
        composeRule.onNodeWithText("Start full access").assertExists()
    }

    @Test
    fun `picking another mode while asked sends that mode, not full access`() {
        var picked: ApprovalMode? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { mode, _ -> picked = mode },
                    onDismiss = {},
                    modes = hostModes
                )
            }
        }

        composeRule.onNodeWithText("Start full access").performScrollTo().performClick()
        composeRule.onNodeWithText("Read only").performScrollTo().performClick()

        assertEquals(ApprovalMode.READ_ONLY, picked)
    }

    @Test
    fun `a Host without full access shows no unattended block`() {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.AUTO,
                    onSelect = { _, _ -> },
                    onDismiss = {},
                    modes = hostModes.copy(available = listOf(ApprovalMode.READ_ONLY, ApprovalMode.AUTO))
                )
            }
        }

        composeRule.onNodeWithText("Full access").assertDoesNotExist()
        composeRule.onNodeWithText("Ultra work").assertDoesNotExist()
    }

    @Test
    fun `leaving full access returns to auto`() {
        var picked: ApprovalMode? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(
                    current = ApprovalMode.FULL_ACCESS,
                    onSelect = { mode, _ -> picked = mode },
                    onDismiss = {},
                    modes = hostModes.copy(turnsLeft = 5)
                )
            }
        }

        composeRule.onNodeWithText("Exit to auto mode").performScrollTo().performClick()
        assertEquals(ApprovalMode.AUTO, picked)
    }

    @Test
    fun `the legacy sheet still offers ultra work with its turn field`() {
        var picked: Pair<ApprovalMode, Int?>? = null
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeSheet(current = ApprovalMode.SAFE, onSelect = { mode, turns -> picked = mode to turns }, onDismiss = {})
            }
        }

        composeRule.onNodeWithText("Ultra work").assertExists()
        // Its turn budget is its explicit step; the full access question is not added to it.
        composeRule.onNodeWithText("Start ultra work").performScrollTo().performClick()
        assertEquals(ApprovalMode.ULW to 100, picked)
    }

    // ── TalkBack (W16AAPPLE-48) ────────────────────────────────────────

    private fun chip(
        mode: ApprovalMode,
        turnsLeft: Int? = null,
        visibleText: String,
        enabled: Boolean = true,
        onCycle: () -> Unit = {}
    ) {
        composeRule.setContent {
            ConnectOnionTheme {
                ApprovalModeChip(
                    mode = mode,
                    enabled = enabled,
                    turnsLeft = turnsLeft,
                    onCycle = onCycle,
                    onOpenSheet = {}
                )
            }
        }
        composeRule.onNode(hasText(visibleText)).assertExists()
    }

    private fun described(text: String) = SemanticsMatcher("is described as \"$text\"") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
    }

    /** What TalkBack adds after the role: "double-tap to <label>". */
    private fun tapDoes(label: String) = SemanticsMatcher("a tap does \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    @Test
    fun `the chip is announced as a button`() {
        chip(ApprovalMode.SAFE, visibleText = "safe")

        composeRule.onNode(hasClickAction() and hasText("safe"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun `the chip says it is the approval mode, and its click says it changes the mode`() {
        chip(ApprovalMode.SAFE, visibleText = "safe")

        composeRule.onNode(hasClickAction() and hasText("safe"))
            .assert(described("Approval mode: safe"))
            .assert(tapDoes("change mode"))
    }

    @Test
    fun `a full access chip says how many turns are left, and its click says it exits`() {
        chip(ApprovalMode.FULL_ACCESS, turnsLeft = 3, visibleText = "full access · 3 left · exit")

        composeRule.onNode(hasClickAction() and hasText("full access · 3 left · exit"))
            .assert(described("Approval mode: full access, 3 turns left"))
            .assert(tapDoes("exit"))
    }

    @Test
    fun `one turn left is not plural`() {
        chip(ApprovalMode.FULL_ACCESS, turnsLeft = 1, visibleText = "full access · 1 left · exit")

        composeRule.onNode(hasClickAction() and hasText("full access · 1 left · exit"))
            .assert(described("Approval mode: full access, 1 turn left"))
    }

    @Test
    fun `an unattended chip with no turn count still has a click that exits`() {
        chip(ApprovalMode.ULW, visibleText = "ultra · exit")

        composeRule.onNode(hasClickAction() and hasText("ultra · exit"))
            .assert(described("Approval mode: ultra"))
            .assert(tapDoes("exit"))
    }

    @Test
    fun `the double-tap TalkBack sends changes the mode`() {
        var cycled = 0
        chip(ApprovalMode.SAFE, visibleText = "safe", onCycle = { cycled++ })

        // The action TalkBack calls for a double-tap, which performClick (a touch) does not exercise.
        composeRule.onNode(hasClickAction() and hasText("safe")).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, cycled)
    }

    @Test
    fun `a disabled chip still says what it is, and is announced as disabled`() {
        var cycled = 0
        chip(ApprovalMode.SAFE, visibleText = "safe", enabled = false, onCycle = { cycled++ })

        composeRule.onNode(hasText("safe"))
            .assert(described("Approval mode: safe"))
            .assertIsNotEnabled()
        assertEquals(0, cycled)
    }
}
