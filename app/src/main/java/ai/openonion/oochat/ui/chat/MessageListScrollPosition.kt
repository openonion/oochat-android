package ai.openonion.oochat.ui.chat

/** Saved position for the chat transcript. */
data class MessageListScrollPosition(
    val firstVisibleItemKey: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
) {
    /** Clamp restored scroll values. */
    fun normalised(): MessageListScrollPosition = copy(
        firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
    )
}
