package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageListViewportComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun messages(prefix: String) = (1..14).map { number ->
        ChatItem.User("$prefix-$number", "$prefix message $number")
    }

    @Test
    fun `the chat restores its viewport after leaving and reopening`() {
        val initial = messages("history")
        var items by mutableStateOf(initial)
        var showing by mutableStateOf(true)
        var savedPosition by mutableStateOf(MessageListScrollPosition())

        composeRule.setContent {
            ConnectOnionTheme {
                if (showing) {
                    MessageList(
                        items = items,
                        timestamps = items.associate { it.id to 1_700_000_000_000L },
                        wasCleared = false,
                        onRespond = { _, _ -> },
                        onApprove = { _, _, _, _, _ -> },
                        onOnboard = { _, _, _ -> },
                        onPlanReviewResponse = { _, _ -> },
                        onUlwResponse = { _, _, _ -> },
                        conversationKey = "session-a",
                        scrollPosition = savedPosition,
                        onScrollPositionChanged = { savedPosition = it },
                        modifier = Modifier.requiredHeight(320.dp)
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToIndex(8)
        composeRule.waitForIdle()
        val beforeLeaving = savedPosition
        assertNotNull(beforeLeaving.firstVisibleItemKey)

        composeRule.runOnIdle { showing = false }
        composeRule.waitForIdle()
        items = listOf(ChatItem.User("inserted", "history message inserted")) + initial
        composeRule.runOnIdle { showing = true }
        composeRule.waitForIdle()

        assertEquals(beforeLeaving.firstVisibleItemKey, savedPosition.firstVisibleItemKey)
        composeRule.onNodeWithText("history message 8").assertExists()
    }

    @Test
    fun `the chat keeps a separate viewport for each conversation`() {
        val first = messages("first")
        val second = messages("second")
        var conversation by mutableStateOf("session-a")
        var savedPositions by mutableStateOf(emptyMap<String, MessageListScrollPosition>())

        composeRule.setContent {
            ConnectOnionTheme {
                val items = if (conversation == "session-a") first else second
                MessageList(
                    items = items,
                    timestamps = items.associate { it.id to 1_700_000_000_000L },
                    wasCleared = false,
                    onRespond = { _, _ -> },
                    onApprove = { _, _, _, _, _ -> },
                    onOnboard = { _, _, _ -> },
                    onPlanReviewResponse = { _, _ -> },
                    onUlwResponse = { _, _, _ -> },
                    conversationKey = conversation,
                    scrollPosition = savedPositions[conversation] ?: MessageListScrollPosition(),
                    onScrollPositionChanged = { position ->
                        savedPositions = savedPositions + (conversation to position)
                    },
                    modifier = Modifier.requiredHeight(320.dp)
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(hasScrollAction()).performScrollToIndex(6)
        composeRule.waitForIdle()
        val firstPosition = savedPositions.getValue("session-a")

        composeRule.runOnIdle { conversation = "session-b" }
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollAction()).performScrollToIndex(3)
        composeRule.waitForIdle()
        val secondPosition = savedPositions.getValue("session-b")

        composeRule.runOnIdle { conversation = "session-a" }
        composeRule.waitForIdle()

        assertEquals(firstPosition.firstVisibleItemKey, savedPositions.getValue("session-a").firstVisibleItemKey)
        assertEquals(secondPosition.firstVisibleItemKey, savedPositions.getValue("session-b").firstVisibleItemKey)
        composeRule.onNodeWithText("first message 6").assertExists()
    }
}
