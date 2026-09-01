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
    /** Horizontal anchor from \pos (as a fraction of width, 0..1), or -1 = use alignment margin. */
    public final float xFraction;

    // ─── \move (rolling danmaku) ───────────────────────────────────────────
    /** True if this line carries a \move tag (rolling position). */
    public final boolean hasMove;
    /** \move start X as a fraction of PlayResX (can be >1 or <0 for off-screen). */
    public final float moveXF1;
    /** \move start Y as a fraction of PlayResY. */
    public final float moveYF1;
    /** \move end X as a fraction of PlayResX. */
    public final float moveXF2;
    /** \move end Y as a fraction of PlayResY. */
    public final float moveYF2;
    /** \move start time offset in ms (0 = line start). */
    public final long moveT1;
    /** \move end time offset in ms (duration = endMs - startMs if omitted). */
    public final long moveT2;

    public SubtitleLine(final long startMs, final long endMs, final String text,
                        @Nullable final String speaker, final int alignment, final float yFraction,
                        final int color, final int outlineColor, final float fontSizeRelative,
                        final float xFraction,
                        final boolean hasMove, final float moveXF1, final float moveYF1,
                        final float moveXF2, final float moveYF2, final long moveT1, final long moveT2) {
        this.startMs = startMs;
        this.endMs = endMs;
        this.text = text;
        this.speaker = speaker;
        this.alignment = alignment;
        this.yFraction = yFraction;
        this.color = color;
        this.outlineColor = outlineColor;
        this.fontSizeRelative = fontSizeRelative;
        this.xFraction = xFraction;
        this.hasMove = hasMove;
        this.moveXF1 = moveXF1;
        this.moveYF1 = moveYF1;
        this.moveXF2 = moveXF2;
        this.moveYF2 = moveYF2;
        this.moveT1 = moveT1;
        this.moveT2 = moveT2;
    }
    // Convenience for callers without a \pos x (defaults to -1 = use alignment, no \move).
    public SubtitleLine(final long startMs, final long endMs, final String text,
                        @Nullable final String speaker, final int alignment, final float yFraction,
                        final int color, final int outlineColor, final float fontSizeRelative,
                        final float xFraction) {
        this(startMs, endMs, text, speaker, alignment, yFraction, color, outlineColor,
                fontSizeRelative, xFraction, false, 0f, 0f, 0f, 0f, 0L, 0L);
    }

    // Convenience for callers without a \pos x (defaults to -1 = use alignment, no \move).
    public SubtitleLine(final long startMs, final long endMs, final String text,
                        @Nullable final String speaker, final int alignment, final float yFraction,
                        final int color, final int outlineColor, final float fontSizeRelative) {
        this(startMs, endMs, text, speaker, alignment, yFraction, color, outlineColor,
                fontSizeRelative, -1f, false, 0f, 0f, 0f, 0f, 0L, 0L);
    }

    /** Right edge alignment (1,4,7); bottom (1,2,3); top (7,8,9); center = 5. */
    public boolean isBottomAnchored() {
        return alignment >= 1 && alignment <= 3;
    }

    public boolean isTopAnchored() {
        return alignment >= 7 && alignment <= 9;
    }
}
