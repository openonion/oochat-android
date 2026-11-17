package ai.openonion.oochat.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * One side of [ChatWithHome]. The [modifier] is the pane's share of the Row, and it has to land on
 * a layout that is the Row's own child. A screen that applies its modifier further in (the chat
 * does, under its navigation drawer) would otherwise ignore it, fill the whole Row, and leave the
 * other pane no width at all.
 */
@Composable
internal fun Pane(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) { content() }
}
