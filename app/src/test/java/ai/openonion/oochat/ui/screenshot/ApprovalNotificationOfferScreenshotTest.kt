package ai.openonion.oochat.ui.screenshot

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.chat.components.ApprovalNotificationOffer
import ai.openonion.oochat.ui.chat.components.ChatItemBubble
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The chat's one offer to turn on approval notifications (W16AAPPLE-44), as it
 * sits above the composer while the first approval waits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = RobolectricDeviceQualifiers.Pixel5)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ApprovalNotificationOfferScreenshotTest {

    @get:Rule(order = 0)
    val localeRule = DeterministicLocaleRule()

    @get:Rule(order = 1)
    val composeRule = createComposeRule()

    // In context: the approval card the offer appears with, so the review can
    // judge how much it adds to the screen at that moment.
    private val approval = ChatItem.ApprovalNeeded(
        id = "ap-1",
        tool = "bash",
        arguments = mapOf("command" to "npm run deploy -- --prod"),
        requestId = "req-1"
    )

    private fun capture(palette: Palette) =
        composeRule.captureThemed("approval_notification_offer", palette) {
            Column(Modifier.background(MaterialTheme.colorScheme.background)) {
                ChatItemBubble(
                    item = approval,
                    timestamp = null,
                    onRespond = {},
                    onApprove = { _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = {},
                    onUlwResponse = { _, _, _ -> }
                )
                ApprovalNotificationOffer(agentName = "Harbour", onTurnOn = {}, onNotNow = {})
            }
        }

    @Test
    fun `offer - light`() = capture(Palette.Light)

    @Test
    fun `offer - dark`() = capture(Palette.Dark)
}
