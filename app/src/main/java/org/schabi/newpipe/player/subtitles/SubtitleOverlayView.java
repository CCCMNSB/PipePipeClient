package org.schabi.newpipe.player.subtitles;

import android.content.Context;
import android.graphics.Bitmap;
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
 * <p><b>Performance:</b> all subtitle lines are <i>pre-rendered</i> into {@link Bitmap}s when
 * the subtitle is loaded (or when the view size changes). The per-frame {@code onDraw} contains
 * only a single {@code drawBitmap} call per active line (GPU-accelerated), with zero text
 * rendering, zero measureText, zero split — eliminating first-frame rendering latency.
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
    /** Font scale for \move (danmaku) lines, independent from subtitle fontScale. */
    private float danmakuFontScale = 1.0f;
    /** Whether to show the original text (second line after \n) for \move danmaku lines. */
    private boolean showOriginal = true;

    /** Position cache: once a line's Y is resolved, it stays fixed until the line expires. */
    private final HashMap<SubtitleLine, Float> stackYCache = new HashMap<>();

    /** Pre-rendered bitmap cache: each line's text block (text + 8-direction outline). */
    private final HashMap<SubtitleLine, Bitmap> bitmapCache = new HashMap<>();

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
        /** Pre-rendered bitmap for this line. */
        Bitmap bitmap;
        /** Max text width (for horizontal positioning). */
        float maxTextW;
        /** For \move lines: the resolved screen X center (as a fraction of width). */
        float moveScreenX;
        /** For \move lines: the resolved screen Y center (as a fraction of height). */
        float moveScreenY;

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

    // ─── Public API ──────────────────────────────────────────────────────────

    /** Set the subtitle font-size scale (1.0 = default); clamps to [0.6, 2.0]. */
    public void setFontScale(final float scale) {
        this.fontScale = Math.max(0.6f, Math.min(2.0f, scale));
        preRenderAll();
        invalidate();
    }

    /** Set the font-size scale for \move (danmaku) lines, independent from {@link #setFontScale}. */
    public void setDanmakuFontScale(final float scale) {
        this.danmakuFontScale = Math.max(0.6f, Math.min(2.0f, scale));
        preRenderAll();
        invalidate();
    }

    /** Set whether to show the original text (second \n line) for \move danmaku lines. */
    public void setShowOriginal(final boolean show) {
        if (this.showOriginal != show) {
            this.showOriginal = show;
            preRenderAll();
            invalidate();
        }
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
        paint.setStyle(Paint.Style.FILL);
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
        preRenderAll();
        invalidate();
    }

    public void setLines(final List<SubtitleLine> list) {
        lines.clear();
        if (list != null) {
            lines.addAll(list);
        }
        stackYCache.clear();
        // Pre-render all bitmaps so onDraw has zero rendering work.
        preRenderAll();
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

    // ─── Pre-rendering ───────────────────────────────────────────────────────

    /** Pre-render all subtitle lines into bitmaps (called on load / size change / font change). */
    private void preRenderAll() {
        final int h = getHeight();
        if (h == 0 || lines.isEmpty()) return;

        bitmapCache.clear();
        for (final SubtitleLine line : lines) {
            final int textSize = computeTextSize(line, h);
            final Bitmap bm = renderToBitmap(line, textSize);
            if (bm != null) {
                bitmapCache.put(line, bm);
            }
        }
    }

    @Override
    protected void onSizeChanged(final int w, final int h, final int oldw, final int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // Re-render bitmaps with the new size (textSize depends on h).
        if (h > 0 && !lines.isEmpty()) {
            preRenderAll();
            invalidate();
        }
    }

    /** Compute the text size for a line given the view height. */
    private int computeTextSize(final SubtitleLine line, final int h) {
        final float scale = line.hasMove ? danmakuFontScale : fontScale;
        return Math.max(12,
                (int) (Math.max((int) (h * 0.04f), (int) (h * line.fontSizeRelative)) * scale));
    }

    // ─── Per-frame rendering ─────────────────────────────────────────────────

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
            return; // Don't clear bitmapCache here — keep pre-rendered bitmaps for next play.
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        // ── ① Compute per-line metrics (no measureText / split / drawText) ──
        final List<Layout> layouts = new ArrayList<>(act.size());
        for (final SubtitleLine line : act) {
            final int textSize = computeTextSize(line, h);
            final float lineH = textSize * 1.25f;
            final int numLines = countLines(displayText(line));
            final float blockH = numLines * lineH;

            // Base top: where the block would sit without any overlap avoidance.
            float baseTop;
            if (line.xFraction >= 0f) {
                final float anchorPx = h * line.yFraction;
                if (line.isBottomAnchored()) {
                    baseTop = Math.max(0f, anchorPx - blockH);
                } else if (line.isTopAnchored()) {
                    baseTop = Math.min(h - blockH, anchorPx);
                } else {
                    baseTop = anchorPx - blockH / 2f;
                }
            } else {
                if (line.isBottomAnchored()) {
                    baseTop = Math.max(0f, h * line.yFraction - blockH);
                } else if (line.isTopAnchored()) {
                    baseTop = Math.min(h - blockH, h * line.yFraction);
                } else {
                    baseTop = h * line.yFraction - blockH / 2f;
                }
            }
            baseTop = Math.max(0f, Math.min(baseTop, h - blockH));

            // Get bitmap from pre-rendered cache (fallback: render on the fly).
            Bitmap bm = bitmapCache.get(line);
            if (bm == null) {
                bm = renderToBitmap(line, textSize);
                if (bm != null) {
                    bitmapCache.put(line, bm);
                }
            }

            Layout lay = new Layout(line, textSize, lineH, numLines, blockH, baseTop);
            lay.bitmap = bm;

            // \move lines: compute current position from playback time.
            if (line.hasMove) {
                // If no explicit t2 (== 0), the movement spans until the line ends.
                final long moveEnd = (line.moveT2 == 0) ? line.endMs : (line.startMs + line.moveT2);
                // If no explicit t1 (== 0), the movement starts at the line start.
                final long moveStart = line.startMs + line.moveT1;
                final long dur = Math.max(1L, moveEnd - moveStart);
                final long elapsed = positionMs - moveStart;
                final float progress = (float) Math.max(0L, Math.min(elapsed, dur)) / dur;
                lay.moveScreenX = line.moveXF1 + (line.moveXF2 - line.moveXF1) * progress;
                lay.moveScreenY = line.moveYF1 + (line.moveYF2 - line.moveYF1) * progress;
                // For \move, the block is centered on the move Y position.
                lay.finalTop = (line.moveYF1 + (line.moveYF2 - line.moveYF1) * progress) * h - blockH / 2f;
                lay.finalTop = Math.max(0f, Math.min(lay.finalTop, h - blockH));
            }

            layouts.add(lay);
        }

        // ── ② Clear stale cache for lines that are no longer active ─────────
        for (SubtitleLine cached : new ArrayList<>(stackYCache.keySet())) {
            if (cached.startMs > positionMs || positionMs >= cached.endMs) {
                stackYCache.remove(cached);
                bitmapCache.remove(cached);
            }
        }

        // ── ③ Overlap resolution ─────────────────────────────────────────────
        final List<float[]> occupied = new ArrayList<>();

        // Phase A: place \move lines (fixed by their move position), \pos-anchored lines,
        // and already-cached lines.
        for (final Layout a : layouts) {
            if (a.line.hasMove) {
                // \move lines are positioned by their computed move position (set in ①).
                // They occupy their vertical slot but do not participate in overlap avoidance.
                occupied.add(new float[]{a.finalTop, a.finalTop + a.blockH});
            } else if (a.line.xFraction >= 0f) {
                a.finalTop = a.baseTop;
                occupied.add(new float[]{a.finalTop, a.finalTop + a.blockH});
            } else if (stackYCache.containsKey(a.line)) {
                a.finalTop = stackYCache.get(a.line);
                occupied.add(new float[]{a.finalTop, a.finalTop + a.blockH});
            }
        }

        // Phase B: place new (uncached) non-pos, non-move lines, avoiding occupied intervals.
        final List<Layout> newOnes = new ArrayList<>();
        for (final Layout a : layouts) {
            if (!a.line.hasMove && a.line.xFraction < 0f && !stackYCache.containsKey(a.line)) {
                newOnes.add(a);
            }
        }
        newOnes.sort((a, b) -> Float.compare(b.baseTop, a.baseTop));

        for (final Layout a : newOnes) {
            final float bH = a.blockH;
            final boolean downward = a.line.isTopAnchored();

            if (!overlaps(a.baseTop, a.baseTop + bH, occupied)) {
                a.finalTop = a.baseTop;
            } else if (downward) {
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

        // ── ④ Render: single drawBitmap per line (GPU, zero text work) ──────
        for (final Layout a : layouts) {
            final Bitmap bm = a.bitmap;
            if (bm == null) continue;
            final SubtitleLine line = a.line;

            final float stroke = strokeFor(a.textSize);

            // Horizontal: compute left edge of the bitmap.
            float xLeft;
            if (line.hasMove) {
                // \move: center the bitmap on the computed move X position.
                xLeft = a.moveScreenX * w - bm.getWidth() / 2f;
            } else if (line.xFraction >= 0f) {
                xLeft = line.xFraction * w - bm.getWidth() / 2f;
            } else {
                final int mod = line.alignment % 3;
                if (mod == 1) {
                    xLeft = w * 0.06f - stroke;
                } else if (mod == 0) {
                    xLeft = w * 0.94f - bm.getWidth() + stroke;
                } else {
                    xLeft = w / 2f - bm.getWidth() / 2f;
                }
            }
            final float bmW = bm.getWidth();
            if (line.hasMove) {
                // \move: draw at the ACTUAL position (may be partially
                // off-screen). The canvas clips to the view bounds
                // automatically, so the danmaku appears to be "pushed in"
                // from the edge — exactly like Aegisub / desktop plugins.
                // Only skip when the entire bitmap is off-screen.
                if (xLeft + bmW <= 0f || xLeft >= w) continue;
                // No clamping — let the canvas handle the clipping.
            } else {
                // Regular subtitles: clamp to the visible area.
                xLeft = Math.max(0f, Math.min(xLeft, w - bmW));
            }

            // Vertical: position bitmap so first baseline aligns with finalTop + lineH/2.
            final float topPad = stroke + a.textSize * 0.85f;
            final float yTop = a.finalTop + a.lineH / 2f - topPad;

            canvas.drawBitmap(bm, xLeft, yTop, null);
        }
    }

    // ─── Bitmap rendering (pre-render, not per-frame) ────────────────────────

    /** Render a subtitle line's text block (with 8-direction outline) into a Bitmap. */
    private Bitmap renderToBitmap(final SubtitleLine line, final int textSize) {
        final String text = displayText(line);
        if (text.isEmpty()) return null;

        paint.setTextSize(textSize);
        paint.setTypeface(font);
        paint.setStyle(Paint.Style.FILL);

        final String[] textLines = text.split("\n");
        final int numLines = textLines.length;
        final float lineH = textSize * 1.25f;
        final float stroke = strokeFor(textSize);

        // Padding: enough to fit stroke + text ascenders/descenders (CJK-safe).
        final float topPad = stroke + textSize * 0.85f;
        final float botPad = stroke + textSize * 0.25f;

        // Measure max line width.
        float maxW = 0f;
        for (final String l : textLines) {
            final float lw = paint.measureText(l);
            if (lw > maxW) maxW = lw;
        }

        final int bmpW = (int) maxW + (int) (2f * stroke) + 2;
        final int bmpH = (int) (topPad + (numLines - 1) * lineH + botPad) + 2;
        if (bmpW < 1 || bmpH < 1) return null;

        final Bitmap bm = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888);
        final Canvas bmCanvas = new Canvas(bm);

        // Colors.
        int fill = line.color;
        if ((fill >>> 24) == 0) {
            fill = 0xFF000000 | (fill & 0x00FFFFFF);
        }
        final int border = line.outlineColor;

        // 8-direction offsets for the outline.
        final float[] offs = {
                0, -stroke, -stroke, 0, stroke, 0, 0, stroke,
                -stroke, -stroke, stroke, stroke, -stroke, stroke, stroke, -stroke
        };

        final int mod = line.alignment % 3;

        for (int i = 0; i < numLines; i++) {
            final String l = textLines[i];
            final float tw = paint.measureText(l);
            float tx;
            if (mod == 1) {
                tx = stroke; // left-aligned
            } else if (mod == 0) {
                tx = bmpW - stroke - tw; // right-aligned
            } else {
                tx = bmpW / 2f - tw / 2f; // centered
            }
            final float ty = topPad + i * lineH;

            // 8-direction outline.
            paint.setColor(border);
            for (int j = 0; j < offs.length; j += 2) {
                bmCanvas.drawText(l, tx + offs[j], ty + offs[j + 1], paint);
            }
            // Fill.
            paint.setColor(fill);
            bmCanvas.drawText(l, tx, ty, paint);
        }

        return bm;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private static float strokeFor(final int textSize) {
        return Math.max(1.5f, textSize * 0.05f);
    }

    /** Count newlines without creating a String[] (avoids per-frame allocation). */
    private static int countLines(final String s) {
        if (s == null || s.isEmpty()) return 1;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') count++;
        }
        return count;
    }

    /** Check if rectangle [top, bottom) overlaps any occupied interval. */
    private static boolean overlaps(final float top, final float bottom, final List<float[]> occupied) {
        for (final float[] o : occupied) {
            if (top < o[1] && bottom > o[0]) {
                return true;
            }
        }
        return false;
    }

    private String display(final String text) {
        return text == null ? "" : text;
    }

    /**
     * Get the display text for a specific line, respecting the showOriginal flag.
     * For \move (danmaku) lines with showOriginal=false, only the first line (translated)
     * is shown; the original text after \n is hidden.
     */
    private String displayText(final SubtitleLine line) {
        final String text = line.text == null ? "" : line.text;
        if (!showOriginal && line.hasMove) {
            final int nl = text.indexOf('\n');
            if (nl >= 0) {
                return text.substring(0, nl);
            }
        }
        return text;
    }
}
