package ai.openonion.oochat.ui.theme

import androidx.compose.ui.unit.dp

// Component-level design tokens, consumed by the chat components they name.
// Tune here — components should read these instead of carrying their own
// inline copies of the same values.

object MessageBubbleToken {
    // Share of the row a bubble may take (the row is already inset by the
    // opposite sender's gutter). A fixed dp cap left ~30 characters per line on
    // a phone and 70% of an unfolded screen empty, and shrank further whenever
    // the user raised the font scale.
    const val MaxWidthFraction = 0.85f
    // Absolute ceiling for tablets/unfolded screens, where 85% of the row would
    // run past a comfortable reading measure.
    val MaxWidth = 420.dp
    // `large` on the shape scale; the tail corner is `extraSmall`.
    val BorderRadius = Radii.large
    val PaddingHorizontal = 14.dp
    val PaddingVertical = 10.dp
    // The user's bubble is a faint wash of `primary` over the surface — the
    // pale green-grey of the design in light, the same tint over dark.
    const val UserFillAlpha = 0.08f
}

object VoiceBubbleToken {
    // Slightly wider than a text bubble — this one also carries the transcript
    // row. MinWidth only guarantees the play button + waveform + duration fit;
    // it used to equal the text bubble's *max*, so every voice message came out
    // wider than every text message.
    const val MaxWidthFraction = 0.95f
    // Cap for a very short clip; the bubble grows towards MaxWidthFraction as
    // the recording gets longer, reaching it at FullWidthSeconds.
    const val MinWidthFraction = 0.55f
    const val FullWidthSeconds = 60f
    val MaxWidth = 460.dp
    val MinWidth = 180.dp
}

object ImageGridToken {
    val MaxWidth = 320.dp
    val ItemSpacing = 3.dp
    val ItemSize = 72.dp
    val BorderRadius = Radii.small
}

object InputBarToken {
    // See Radii.composer. A taller, multi-line field keeps the same corners.
    val BorderRadius = Radii.composer
    val BorderWidth = 1.5.dp
    val PaddingHorizontal = 14.dp
    val PaddingVertical = 6.dp
    // Matches the 48dp IconButtons sharing the pill row. The row is
    // bottom-aligned so multi-line text grows upward with the buttons pinned
    // to the floor; at 40dp a single line sat ~16dp below the pill's centre.
    val MinHeight = 48.dp
    val MaxHeight = 120.dp
}

object ButtonToken {
    val Compact = 48.dp // card/row/dialog-scoped actions
    val FullWidth = 56.dp // page-level primary actions
}
