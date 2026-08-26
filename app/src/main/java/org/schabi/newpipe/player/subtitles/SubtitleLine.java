package org.schabi.newpipe.player.subtitles;

import androidx.annotation.Nullable;

/**
 * A single parsed subtitle line (from an ASS or SRT file), in playback-agnostic form.
 *
 * <p>Rendering ignores the Aegisub pixel space and instead carries, per line, the anchor
 * (ASS {@code Alignment}, 1-9) and a normalized vertical position so it can be placed relative
 * to any screen size, plus the text and border colors. This lets a dialogue honor the position
 * fixed in Aegisub (staggered speaker lanes) while still scaling to the actual video surface.
 */
public final class SubtitleLine {
    public final long startMs;
    public final long endMs;
    public final String text;
    /** Speaker / actor label (ASS Name field), may be null. */
    @Nullable public final String speaker;
    /** ASS alignment 1-9 (default 2 = bottom center). */
    public final int alignment;
    /** Vertical anchor as a fraction of the video height (0=top, 1=bottom); 0.9 default. */
    public final float yFraction;
    /** Primary (text) color in ARGB. */
    public final int color;
    /** Outline/border color in ARGB (used as the per-speaker border when distinct). */
    public final int outlineColor;
    /** Font size relative to video height (PlayResY units / 1000). */
    public final float fontSizeRelative;

    public SubtitleLine(final long startMs, final long endMs, final String text,
                        @Nullable final String speaker, final int alignment, final float yFraction,
                        final int color, final int outlineColor, final float fontSizeRelative) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.speaker = speaker;
        this.alignment = alignment;
        this.yFraction = yFraction;
        this.color = color;
        this.outlineColor = outlineColor;
        this.fontSizeRelative = fontSizeRelative;
    }

    /** Right edge alignment (1,4,7); bottom (1,2,3); top (7,8,9); center = 5. */
    public boolean isBottomAnchored() {
        return alignment >= 1 && alignment <= 3;
    }

    public boolean isTopAnchored() {
        return alignment >= 7 && alignment <= 9;
    }
}
