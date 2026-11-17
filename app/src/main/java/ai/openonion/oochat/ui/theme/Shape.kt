package ai.openonion.oochat.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's radii: small, from one scale (W16AAPPLE-49). Nothing is rounder than
 * [extraLarge] apart from a circle, so cards, sheets, bubbles and fields all read as
 * the same few sharp-cornered shapes. Code that needs the radius itself, such as a
 * canvas drawing a dashed outline, reads these; everything else takes
 * `MaterialTheme.shapes`.
 */
object Radii {
    val extraSmall = 2.dp
    val small = 4.dp
    val medium = 6.dp
    val large = 8.dp
    val extraLarge = 12.dp

    // The one radius off the scale: the chat composer's, measured off the chat
    // design (W16AAPPLE-112) at about three-eighths of the box's height —
    // generously round without being a pill.
    val composer = 22.dp
}

/**
 * Central corner-radius scale for the app. Component code should reference these via
 * `MaterialTheme.shapes.{small,medium,large,...}` instead of writing a
 * RoundedCornerShape of its own, so the whole app's roundness can be tuned from one place.
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radii.extraSmall), // snackbars, waveform bars, a bubble's tail
    small = RoundedCornerShape(Radii.small), // small surfaces (log console, chips, image tiles)
    medium = RoundedCornerShape(Radii.medium), // cards, thumbnails
    large = RoundedCornerShape(Radii.large), // chat bubbles, fields, buttons, blocks
    extraLarge = RoundedCornerShape(Radii.extraLarge) // sheets, dialogs, menus
)

/**
 * Chat message bubble shape: fully rounded except a small "tail" corner on
 * the sender's side (bottom-end for the user, top-start for the agent).
 * Lives in the theme (not chat/components) because Settings' font-size
 * preview renders the same bubbles — radius comes from
 * [MessageBubbleToken.BorderRadius] so the whole app tunes in one place.
 */
fun bubbleShape(isUser: Boolean): RoundedCornerShape {
    val cornerRadius = MessageBubbleToken.BorderRadius
    val tailSize = Radii.extraSmall
    return if (isUser) {
        RoundedCornerShape(
            topStart = cornerRadius,
            topEnd = cornerRadius,
            bottomStart = cornerRadius,
            bottomEnd = tailSize
        )
    } else {
        RoundedCornerShape(
            topStart = tailSize,
            topEnd = cornerRadius,
            bottomStart = cornerRadius,
            bottomEnd = cornerRadius
        )
    }
}
