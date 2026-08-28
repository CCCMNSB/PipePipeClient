package org.schabi.newpipe.player.subtitles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;

import org.schabi.newpipe.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws active subtitle lines over the video surface, driven by the player's playback position.
 *
 * <p>Each parsed line carries an ASS alignment + normalized vertical position (fixed in Aegisub, so
 * different speakers appear at different lanes) and a border color. When the subtitle does not pin
 * its own outline color (e.g. black default), the view assigns a distinct border color per speaker
 * so speakers are visually separated. Text is drawn with the project's CJK font (or the configured
 * one), scaled to the view size, and clamped on-screen.
 */
public final class SubtitleOverlayView extends View {
    private final List<SubtitleLine> lines = new ArrayList<>();
    private long positionMs = -1;
    private final TextPaint paint = new TextPaint();
    private Typeface font;
    private float fontScale = 1.0f;

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
        // We draw a real ASS outline (stroke) ourselves; a shadow layer would muddy it into a
        // "white-black-white" look, so no shadow is applied.
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
            return;
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        // Sort by vertical position so stacked lines render in a stable order.
        act.sort((a, b) -> Float.compare(a.yFraction, b.yFraction));

        // Track last drawn y to avoid overlap for lines on the same lane.
        float lastY = -1;

        for (final SubtitleLine line : act) {
            final int textSize = Math.max(12,
                    (int) (Math.max((int) (h * 0.04f), (int) (h * line.fontSizeRelative)) * fontScale));
            paint.setTextSize(textSize);

            // Strictly use the ASS colors: text = PrimaryColour, border = OutlineColour.
            final int border = line.outlineColor;
            int fill = line.color;
            if ((fill >>> 24) == 0) {
                fill = 0xFF000000 | (fill & 0x00FFFFFF); // ensure opaque text
            }

            float y = line.yFraction;
            // Vertical: for bottom-anchored, yFraction measured from bottom; offset up by 0.5 font.
            final float textHalf = textSize / 2f;
            float yPx = isBottomAnchor(line) ? (h * y - textHalf - h * 0.03f)
                    : (h * y + textHalf + h * 0.03f);
            // Respect the Aegisub position exactly. The overlay now renders BELOW the controls, so
            // it never covers the progress bar; only keep the text inside the visible view.
            yPx = Math.max(textSize, Math.min(yPx, h - textSize));
            // Avoid exact overlap of two concurrent lines on the same lane (safety only).
            if (lastY >= 0 && Math.abs(yPx - lastY) < textSize * 0.9f) {
                yPx = Math.min(h - textSize, lastY + textSize * 0.9f);
            }
            lastY = yPx;

            // Horizontal anchor from ASS alignment (%3 ==1 left, ==2 center, ==0 right).
            // \pos(x,y) pins the anchor at that x; otherwise fall back to alignment margins.
            final String displayText = display(line.text);
            final int mod = line.alignment % 3;
            final boolean posAnchored = line.xFraction >= 0f;
            final float anchorX;
            if (posAnchored) {
                anchorX = line.xFraction * w;
            } else if (mod == 1) {
                anchorX = w * 0.06f;
            } else if (mod == 0) {
                anchorX = w * 0.94f;
            } else {
                anchorX = w / 2f;
            }

            drawOutlined(canvas, displayText, anchorX, yPx, fill, border, textSize, mod, w);
        }
    }

    private void drawOutlined(final Canvas canvas, final String text, final float anchorX,
                              final float y, final int fill, final int border, final int textSize,
                              final int mod, final int w) {
        if (text.isEmpty()) {
            return;
        }
        final String[] lines = text.split("\n");
        final float lineH = textSize * 1.25f;
        // For multi-line, roughly center the block on the anchor y (grow downward from the top line).
        float yy = y;
        if (lines.length > 1) {
            yy = y - (lines.length - 1) * lineH * 0.5f;
        }
        paint.setStyle(Paint.Style.FILL);
        for (final String lineText : lines) {
            // Anchor each line separately so wide multi-line blocks stay centered (measureText
            // ignores "\n" and a whole-block width pushed the anchor off-screen to the left).
            final float tw = paint.measureText(lineText);
            float x;
            if (mod == 1) {          // anchor is the line's LEFT edge
                x = anchorX;
            } else if (mod == 0) {   // anchor is the line's RIGHT edge
                x = anchorX - tw;
            } else {                 // anchor is the line's CENTER
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

    private boolean isBottomAnchor(final SubtitleLine l) {
        return l.isBottomAnchored();
    }
}
