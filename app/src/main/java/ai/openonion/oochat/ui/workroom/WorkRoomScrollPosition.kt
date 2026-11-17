package ai.openonion.oochat.ui.workroom

/** Saved position for a Work Room list. */
data class WorkRoomScrollPosition(
    val firstVisibleItemKey: String? = null,
    val firstVisibleItemIndex: Int = 0,
    val firstVisibleItemScrollOffset: Int = 0
) {
    /** Clamp restored scroll values. */
    fun normalised(): WorkRoomScrollPosition = copy(
        firstVisibleItemIndex = firstVisibleItemIndex.coerceAtLeast(0),
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset.coerceAtLeast(0)
    )
}
