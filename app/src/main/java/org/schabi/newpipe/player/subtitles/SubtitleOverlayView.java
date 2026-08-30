package org.schabi.newpipe.player.subtitles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Draws active subtitle lines over the video surface, driven by the player's playback position.
 *
 * <p>Each parsed line carries an ASS alignment + normalized vertical position (fixed in Aegisub, so
 * different speakers appear at different lanes) and a border color. When the subtitle does not pin
 * its own outline color (e.g. black default), the view assigns a distinct border color per speaker
 * so speakers are visually separated. Text is drawn with the project's CJK font (or the configured
 * one), scaled to the view size, and clamped on-screen.
 *
 * <p><b>Overlap prevention</b> (ported from the PC-side ass-subtitle-overlay userscript):
 * concurrent lines at the same position are displaced vertically so they don't paint on top of
 * each other. Placement is stable: once a line's position is resolved it is cached for the line's
 * lifetime; new lines avoid already-occupied vertical intervals. \pos-anchored lines are fixed
 * (only occupy, never move). Direction: top-anchored lines search downward, bottom/center search
 * upward.
 */
public final class SubtitleOverlayView extends View {
    private final List<SubtitleLine> lines = new ArrayList<>();
    private long positionMs = -1;
    private final TextPaint paint = new TextPaint();
    private Typeface font;
    private float fontScale = 1.0f;

    /** Position cache: once a line's Y is resolved, it stays fixed until the line expires. */
    private final HashMap<SubtitleLine, Float> stackYCache = new HashMap<>();

    // ─── per-frame layout record ─────────────────────────────────────────────
    private static final class Layout {
        final SubtitleLine line;
        final int textSize;
        final float lineH;
        final int numLines;
        final float blockH;
        /** Top of the visual block in screen pixels (0 = top of view). */
        float baseTop;
        /** Final resolved top (after overlap avoidance). */
        float finalTop;

        Layout(final SubtitleLine line, final int textSize, final float lineH,
               final int numLines, final float blockH, final float baseTop) {
            this.line = line;
            this.textSize = textSize;
            this.lineH = lineH;
            this.numLines = numLines;
            this.blockH = blockH;
            this.baseTop = baseTop;
            this.finalTop = baseTop;
        }

        /** Bottom of the visual block. */
        float bottom() { return finalTop + blockH; }
    }

    /** Set the subtitle font-size scale (1.0 = default); clamps to a sane range. */
    public void setFontScale(final float scale) {
        this.fontScale = Math.max(0.6f, Math.min(2.0f, scale));
        invalidate();
    }

    public SubtitleOverlayView(final Context context) {
        super(context);
        init();
    }

    public SubtitleOverlayView(final Context context, @Nullable final AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setBackground(null);
        font = ResourcesCompat.getFont(getContext(), R.font.lxgw_wenkai);
        if (font == null) {
            font = Typeface.DEFAULT;
        }
        paint.setAntiAlias(true);
        paint.setTypeface(font);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setFont(final String name) {
        if (name == null) {
            font = Typeface.DEFAULT;
        } else {
            switch (name) {
                case "serif":
                    font = Typeface.SERIF;
                    break;
                case "monospace":
                    font = Typeface.MONOSPACE;
                    break;
                case "sans-serif":
                    font = Typeface.SANS_SERIF;
                    break;
                default:
                    final Typeface lx = ResourcesCompat.getFont(getContext(), R.font.lxgw_wenkai);
                    font = lx != null ? lx : Typeface.DEFAULT;
                    break;
            }
        }
        paint.setTypeface(font);
        invalidate();
    }

    public void setLines(final List<SubtitleLine> list) {
        lines.clear();
        if (list != null) {
            lines.addAll(list);
        }
        stackYCache.clear(); // new subtitle → clear all position caches
        invalidate();
    }

    public void setPositionMs(final long ms) {
        if (positionMs != ms) {
            positionMs = ms;
            invalidate();
        }
    }

    /** Toggle automatic per-speaker border coloring (kept for API compatibility; unused now). */
    public void setAutoColor(final boolean autoColor) {
        invalidate();
    }

    /** Extract the active lines at the current playback position. */
    private List<SubtitleLine> active() {
        if (positionMs < 0) {
            return new ArrayList<>();
        }
        final List<SubtitleLine> out = new ArrayList<>();
        for (final SubtitleLine l : lines) {
            if (l.startMs <= positionMs && positionMs < l.endMs) {
                out.add(l);
            }
        }
        return out;
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final List<SubtitleLine> act = active();
        if (act.isEmpty()) {
            stackYCache.clear();
            return;
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        // ── ① Compute per-line metrics ──────────────────────────────────────
        final List<Layout> layouts = new ArrayList<>(act.size());
        for (final SubtitleLine line : act) {
            final int textSize = Math.max(12,
                    (int) (Math.max((int) (h * 0.04f), (int) (h * line.fontSizeRelative)) * fontScale));
            paint.setTextSize(textSize);
            final String text = display(line.text);
            final String[] textLines = text.split("\n");
            final int numLines = Math.max(1, textLines.length);
            final float lineH = textSize * 1.25f;
            final float blockH = numLines * lineH;

            // Base top: where the block would sit without any overlap avoidance.
            // Convert from the "anchor fraction" to block-top pixels.
            float baseTop;
            if (line.xFraction >= 0f) {
                // \pos absolute: yFraction is the anchor point (top=0, bottom=h in ASS space).
                // Use the same bottom/top/center logic as non-pos.
                final float anchorPx = h * line.yFraction;
                if (line.isBottomAnchored()) {
                    baseTop = Math.max(0f, anchorPx - blockH);
                } else if (line.isTopAnchored()) {
                    baseTop = Math.min(h - blockH, anchorPx);
                } else {
                    baseTop = anchorPx - blockH / 2f;
                }
            } else {
                // Style alignment (no \pos): yFraction is the lane position.
                if (line.isBottomAnchored()) {
                    baseTop = Math.max(0f, h * line.yFraction - blockH);
                } else if (line.isTopAnchored()) {
                    baseTop = Math.min(h - blockH, h * line.yFraction);
                } else {
                    baseTop = h * line.yFraction - blockH / 2f;
                }
            }
            baseTop = Math.max(0f, Math.min(baseTop, h - blockH));

            layouts.add(new Layout(line, textSize, lineH, numLines, blockH, baseTop));
        }

        // ── ② Clear cache for lines that are no longer active ───────────────
        for (SubtitleLine cached : new ArrayList<>(stackYCache.keySet())) {
            if (cached.startMs > positionMs || positionMs >= cached.endMs) {
                stackYCache.remove(cached);
            }
        }

        // ── ③ Overlap resolution ─────────────────────────────────────────────
        // Occupied vertical intervals (top-exclusive, bottom-exclusive overlap check).
        final List<float[]> occupied = new ArrayList<>(); // each: {top, bottom}

        // Phase A: place \pos-anchored lines (fixed) + already-cached lines.
        for (final Layout a : layouts) {
            if (a.line.xFraction >= 0f) {
                // \pos lines are absolute: always at their base position, never displaced.
                a.finalTop = a.baseTop;
                occupied.add(new float[]{a.finalTop, a.finalTop + a.blockH});
            } else if (stackYCache.containsKey(a.line)) {
                // Cached line: keep its resolved position (stability).
                a.finalTop = stackYCache.get(a.line);
                occupied.add(new float[]{a.finalTop, a.finalTop + a.blockH});
            }
        }

        // Phase B: place new (uncached) non-pos lines, avoiding occupied intervals.
        final List<Layout> newOnes = new ArrayList<>();
        for (final Layout a : layouts) {
            if (a.line.xFraction < 0f && !stackYCache.containsKey(a.line)) {
                newOnes.add(a);
            }
        }
        // Sort: bottom (larger baseTop) first — they're the "anchors" that others should avoid.
        newOnes.sort((a, b) -> Float.compare(b.baseTop, a.baseTop));

        for (final Layout a : newOnes) {
            final float bH = a.blockH;
            // Direction: top-anchored (align >= 7) → search downward; else → search upward.
            final boolean downward = a.line.isTopAnchored();

            if (!overlaps(a.baseTop, a.baseTop + bH, occupied)) {
                a.finalTop = a.baseTop;
            } else if (downward) {
                // Search downward (y increases), clamp to h - bH.
                boolean found = false;
                for (float step = 8f; step <= h && !found; step += 8f) {
                    final float yy = Math.min(a.baseTop + step, h - bH);
                    if (!overlaps(yy, yy + bH, occupied)) {
                        a.finalTop = yy;
                        found = true;
                    }
                }
                if (!found) {
                    a.finalTop = Math.max(0f, Math.min(a.baseTop, h - bH));
                }
            } else {
                // Search upward (y decreases), clamp to 0.
                boolean found = false;
                for (float step = 8f; step <= h && !found; step += 8f) {
                    final float yy = Math.max(a.baseTop - step, 0f);
                    if (!overlaps(yy, yy + bH, occupied)) {
                        a.finalTop = yy;
                        found = true;
                    }
                }
                if (!found) {
                    a.finalTop = Math.max(0f, Math.min(a.baseTop, h - bH));
                }
            }
            a.finalTop = Math.max(0f, Math.min(a.finalTop, h - bH));
            stackYCache.put(a.line, a.finalTop);
            occupied.add(new float[]{a.finalTop, a.finalTop + bH});
        }

        // ── ④ Render ─────────────────────────────────────────────────────────
        for (final Layout a : layouts) {
            final SubtitleLine line = a.line;
            final int textSize = a.textSize;

            // Colors.
            final int border = line.outlineColor;
            int fill = line.color;
            if ((fill >>> 24) == 0) {
                fill = 0xFF000000 | (fill & 0x00FFFFFF);
            }

            // Convert block-top → baseline Y for drawOutlined (which centers around the baseline).
            final float yBaseline = a.finalTop + a.blockH / 2f;

            // Horizontal anchor.
            final int mod = line.alignment % 3;
            final float anchorX;
            if (line.xFraction >= 0f) {
                anchorX = line.xFraction * w;
            } else if (mod == 1) {
                anchorX = w * 0.06f;
            } else if (mod == 0) {
                anchorX = w * 0.94f;
            } else {
                anchorX = w / 2f;
            }

            drawOutlined(canvas, display(line.text), anchorX, yBaseline, fill, border,
                    textSize, mod, w);
        }
    }

    /** Check if rectangle [top, top+height) overlaps any occupied interval. */
    private static boolean overlaps(final float top, final float bottom, final List<float[]> occupied) {
        for (final float[] o : occupied) {
            if (top < o[1] && bottom > o[0]) {
                return true;
            }
        }
        return false;
    }

    private void drawOutlined(final Canvas canvas, final String text, final float anchorX,
                              final float y, final int fill, final int border, final int textSize,
                              final int mod, final int w) {
        if (text.isEmpty()) {
            return;
        }
        final String[] textLines = text.split("\n");
        final float lineH = textSize * 1.25f;
        // Center the multi-line block on the anchor y.
        float yy = y;
        if (textLines.length > 1) {
            yy = y - (textLines.length - 1) * lineH * 0.5f;
        }
        paint.setStyle(Paint.Style.FILL);
        for (final String lineText : textLines) {
            final float tw = paint.measureText(lineText);
            float x;
            if (mod == 1) {
                x = anchorX;
            } else if (mod == 0) {
                x = anchorX - tw;
            } else {
                x = anchorX - tw / 2f;
            }
            x = Math.max(0f, Math.min(x, Math.max(0f, w - tw)));
            drawOne(canvas, lineText, x, yy, fill, border, textSize);
            yy += lineH;
        }
    }

    private void drawOne(final Canvas canvas, final String text, final float x, final float y,
                          final int fill, final int border, final int textSize) {
        if (text.isEmpty()) {
            return;
        }
        final float stroke = Math.max(2f, textSize * 0.06f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(border);
        paint.setTypeface(font);
        final float[] offs = {0, -stroke, 0, stroke, -stroke, 0, stroke, 0,
                -stroke, -stroke, stroke, stroke, -stroke, stroke, stroke, -stroke};
        for (int i = 0; i < offs.length; i += 2) {
            canvas.drawText(text, x + offs[i], y + offs[i + 1], paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawText(text, x, y, paint);
    }

    private String display(final String text) {
        return text == null ? "" : text;
    }
}
