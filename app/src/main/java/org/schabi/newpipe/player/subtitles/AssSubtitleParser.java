package org.schabi.newpipe.player.subtitles;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses an ASS or SRT file into a list of {@link SubtitleLine}.
 *
 * <p>ASS handling follows the Aegisub convention the project expects: each {@code Dialogue} gets
 * a speaker label (the {@code Name} field, falling back to the {@code Style} name) and a fixed
 * position (the {@code Style}'s {@code Alignment} + {@code MarginV}, or an inline {@code \pos} /
 * {@code \an} override). Colors are un-wrapped from the ASS {@code &HAABBGGRR} form into ARGB.
 */
public final class AssSubtitleParser {

    private AssSubtitleParser() {
    }

    /** Parse content that is either ASS ({@code [Script Info]}) or SRT. */
    public static List<SubtitleLine> parse(final String content) {
        if (content == null) {
            return new ArrayList<>();
        }
        if (content.contains("[Script Info]")) {
            return parseAss(content);
        }
        return parseSrt(content);
    }

    /** Whether the given filename/content looks like ASS (vs. plain SRT). */
    public static boolean isAss(final String content) {
        return content != null && content.contains("[Script Info]");
    }

    // ------------------------------------------------------------------ ASS

    private static List<SubtitleLine> parseAss(final String content) {
        int playResY = 1080;
        int playResX = 1920;
        final Map<String, Style> styles = new HashMap<>();
        final List<String> eventLines = new ArrayList<>();
        String[] eventFormat = null;
        String section = "";

        for (final String rawLine : content.split("\r?\n")) {
            final String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            if (line.startsWith("PlayResX:")) {
                final int v = parseInt(line.substring(line.indexOf(':') + 1));
                if (v > 0) {
                    playResX = v;
                }
            }
            if (line.startsWith("PlayResY:")) {
                final int v = parseInt(line.substring(line.indexOf(':') + 1));
                if (v > 0) {
                    playResY = v;
                }
            } else if ("V4+ Styles".equals(section) && line.startsWith("Style:")) {
                final Style s = parseStyle(line, playResY);
                if (s != null) {
                    styles.put(s.name, s);
                }
            } else if ("Events".equals(section) && line.startsWith("Format:") && eventFormat == null) {
                eventFormat = line.substring(line.indexOf(':') + 1).split(",");
            } else if ("Events".equals(section) && line.startsWith("Dialogue:")) {
                eventLines.add(line);
            }
        }

        final int idxStart = index(eventFormat, "Start");
        final int idxEnd = index(eventFormat, "End");
        final int idxStyle = index(eventFormat, "Style");
        final int idxName = index(eventFormat, "Name");
        final int idxText = index(eventFormat, "Text");
        if (idxStart < 0 || idxEnd < 0 || idxText < 0) {
            return new ArrayList<>();
        }

        final List<SubtitleLine> result = new ArrayList<>();
        for (final String e : eventLines) {
            final ParsedDialogue d = parseDialogue(e, idxStart, idxEnd, idxStyle, idxName,
                    idxText, styles, playResY, playResX);
            if (d != null && !d.text.isEmpty()) {
                result.add(d.line);
            }
        }
        return result;
    }

    private static void applyOverride(final ParsedDialogue d) {
        // Overrides inside the text that affect placement.
        final String text = d.text;
        int an = intIn(text, "\\an");
        if (an >= 1 && an <= 9) {
            d.alignment = an;
        }
        final Pos pos = posIn(text);
        if (pos != null) {
            d.hasPos = true;
            d.posX = pos.x;
            d.posY = pos.y;
        }
        // \N / \n -> literal newline; strip {..} style overrides for display text.
        d.text = text.replace("\\N", "\n").replace("\\n", "\n")
                .replaceAll("\\{[^}]*\\}", "").trim();
    }

    private static ParsedDialogue parseDialogue(final String line,
                                                final int idxStart, final int idxEnd,
                                                final int idxStyle, final int idxName,
                                                final int idxText,
                                                final Map<String, Style> styles,
                                                final int playResY, final int playResX) {
        // Dialogue: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        final String body = line.substring(line.indexOf(':') + 1);
        final String[] parts = splitDialogue(body);
        // Text is the last part and may contain commas.
        final int maxIndex = Math.max(idxText, Math.max(idxStart, Math.max(idxEnd, idxStyle)));
        if (parts.length <= maxIndex) {
            return null;
        }
        final String start = get(parts, idxStart, "");
        final String end = get(parts, idxEnd, "");
        final String styleName = get(parts, idxStyle, "Default");
        final String name = get(parts, idxName, "").trim();
        final String text = get(parts, idxText, "");

        final long startMs = parseAssTime(start);
        final long endMs = parseAssTime(end);
        if (endMs <= startMs) {
            return null;
        }

        final Style st = styles.get(styleName);
        final int alignment = st != null ? st.alignment : 2;
        float yFraction = st != null ? st.yFraction(playResY) : 0.9f;
        final int color = st != null ? st.primary : 0xFFFFFFFF;
        final int outline = st != null ? st.outline : 0xFF000000;
        final float fontSizeRelative = st != null ? st.fontSizeRelative : 0.074f;

        final ParsedDialogue d = new ParsedDialogue();
        d.text = text;
        d.alignment = alignment;
        d.yFraction = yFraction;
        d.hasPos = st != null && st.hasPos;
        d.posY = st != null ? st.posY : -1;

        applyOverride(d);
        if (d.hasPos && d.posY >= 0) {
            yFraction = d.posY / (float) playResY;
        }
        float xFraction = -1f;
        if (d.hasPos && d.posX >= 0) {
            xFraction = d.posX / (float) playResX;
        }

        // Speaker: Name field, else the Style name (so "说话人1..N" styles identify speakers).
        String speaker = !name.isEmpty() ? name : styleName;
        if ("Default".equals(speaker) || "一般".equals(speaker)) {
            speaker = null;
        }

        d.line = new SubtitleLine(startMs, endMs, d.text, speaker, d.alignment, yFraction,
                color, outline, fontSizeRelative, xFraction);
        return d;
    }

    private static Style parseStyle(final String line, final int playResY) {
        final String body = line.substring(line.indexOf(':') + 1);
        final String[] p = body.split(",");
        if (p.length < 20) {
            return null;
        }
        final Style s = new Style();
        s.name = p[0].trim();
        s.fontSizeRelative = (parseFloat(p[2]) / (float) playResY);
        s.primary = parseAssColor(get(p, 3, "&H00FFFFFF"));
        s.secondary = parseAssColor(get(p, 4, "&H000000FF"));
        s.outline = parseAssColor(get(p, 5, "&H00000000"));
        s.alignment = parseInt(get(p, 18, "2"));
        final int marginV = parseInt(get(p, 21, "10"));
        s.marginV = marginV;
        s.yFractionFromMargin(playResY);
        return s;
    }

    // ------------------------------------------------------------------ SRT

    private static List<SubtitleLine> parseSrt(final String content) {
        final List<SubtitleLine> result = new ArrayList<>();
        final String[] blocks = content.split("\r?\n\\s*\r?\n");
        for (final String block : blocks) {
            final String[] lines = block.split("\r?\n");
            int timeIdx = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("-->")) {
                    timeIdx = i;
                    break;
                }
            }
            if (timeIdx < 0) {
                continue;
            }
            final String[] times = lines[timeIdx].split("-->");
            if (times.length < 2) {
                continue;
            }
            final long startMs = parseSrtTime(times[0].trim());
            final long endMs = parseSrtTime(times[1].trim());
            final StringBuilder sb = new StringBuilder();
            for (int i = timeIdx + 1; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(lines[i]);
                }
            }
            final String text = sb.toString().trim();
            if (text.isEmpty()) {
                continue;
            }
            // Optional "Speaker: " prefix.
            String speaker = null;
            String display = text;
            final int colon = text.indexOf(':');
            if (colon > 0 && colon < 30) {
                final String maybe = text.substring(0, colon).trim();
                if (maybe.matches("[A-Za-z0-9 _-]{1,20}")) {
                    speaker = maybe;
                    display = text.substring(colon + 1).trim();
                }
            }
            result.add(new SubtitleLine(startMs, endMs, display, speaker, 2, 0.9f,
                    0xFFFFFFFF, 0xFF000000, 0.074f));
        }
        return result;
    }

    // ------------------------------------------------------------------ utils

    private static long parseAssTime(final String t) {
        // H:MM:SS.cc  (centiseconds)
        try {
            final String[] a = t.split(":");
            if (a.length != 3) {
                return 0;
            }
            final int h = Integer.parseInt(a[0]);
            final int m = Integer.parseInt(a[1]);
            final String sec = a[2];
            final int s = Integer.parseInt(sec.substring(0, 2));
            final int cs = sec.length() > 2 ? Integer.parseInt(sec.substring(3)) : 0;
            return ((h * 3600L) + (m * 60L) + s) * 1000L + cs * 10L;
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static long parseSrtTime(final String t) {
        // HH:MM:SS,mmm
        try {
            final String a = t.replace(',', '.');
            final String[] parts = a.split(":");
            if (parts.length != 3) {
                return 0;
            }
            final int h = Integer.parseInt(parts[0]);
            final int m = Integer.parseInt(parts[1]);
            final String sec = parts[2];
            final int s = Integer.parseInt(sec.substring(0, 2));
            final int ms = Integer.parseInt(sec.substring(3));
            return ((h * 3600L) + (m * 60L) + s) * 1000L + ms;
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static int parseAssColor(final String hex) {
        // &HAABBGGRR  (alpha, blue, green, red)
        String h = hex.trim();
        if (h.startsWith("&H") || h.startsWith("&h")) {
            h = h.substring(2);
        }
        h = h.replace("&", "");
        if (h.length() == 6) {
            h = "00" + h; // opaque (ASS alpha 0x00 = fully visible)
        }
        if (h.length() < 8) {
            return 0xFFFFFFFF;
        }
        try {
            final int aa = Integer.parseInt(h.substring(0, 2), 16);
            final int bb = Integer.parseInt(h.substring(2, 4), 16);
            final int gg = Integer.parseInt(h.substring(4, 6), 16);
            final int rr = Integer.parseInt(h.substring(6, 8), 16);
            final int alpha = 0xFF - aa; // ASS alpha inverted vs. ARGB
            return (alpha << 24) | (rr << 16) | (gg << 8) | bb;
        } catch (final RuntimeException e) {
            return 0xFFFFFFFF;
        }
    }

    private static int parseInt(final String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static float parseFloat(final String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (final RuntimeException e) {
            return 0;
        }
    }

    private static int index(final String[] format, @NonNull final String field) {
        if (format == null) {
            return -1;
        }
        for (int i = 0; i < format.length; i++) {
            if (format[i].trim().equalsIgnoreCase(field)) {
                return i;
            }
        }
        return -1;
    }

    private static String get(final String[] parts, final int i, final String def) {
        return (i >= 0 && i < parts.length) ? parts[i] : def;
    }

    private static String[] splitDialogue(final String body) {
        // Split into at most 10 fields; Text (last) keeps its commas.
        final String[] p = body.split(",", 10);
        return p;
    }

    private static int intIn(final String text, final String key) {
        try {
            final int i = text.indexOf(key);
            if (i < 0) {
                return -1;
            }
            int j = i + key.length();
            while (j < text.length() && (Character.isDigit(text.charAt(j)) || j == i + key.length())) {
                j++;
            }
            return Integer.parseInt(text.substring(i + key.length(), j));
        } catch (final RuntimeException e) {
            return -1;
        }
    }

    private static Pos posIn(final String text) {
        final int i = text.indexOf("\\pos(");
        if (i < 0) {
            return null;
        }
        final int end = text.indexOf(')', i);
        if (end < 0) {
            return null;
        }
        try {
            final String[] xy = text.substring(i + 5, end).split(",");
            if (xy.length == 2) {
                return new Pos(Float.parseFloat(xy[0]), Float.parseFloat(xy[1]));
            }
        } catch (final RuntimeException e) {
            // ignore
        }
        return null;
    }

    private static final class Pos {
        final float x, y;
        Pos(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Style {
        String name;
        float fontSizeRelative;
        int primary;
        int secondary;
        int outline;
        int alignment = 2;
        int marginV = 10;
        boolean hasPos;
        float posY = -1;

        float yFraction(final int playResY) {
            return yFractionFromMargin(playResY);
        }

        float yFractionFromMargin(final int playResY) {
            if (playResY <= 0) {
                return 0.9f;
            }
            final float mar = marginV / (float) playResY;
            switch (alignment) {
                case 7: case 8: case 9:
                    return Math.min(1f, mar); // from top
                case 1: case 2: case 3:
                    return Math.max(0.05f, 1f - mar); // from bottom
                default:
                    return 0.5f;
            }
        }
    }

    private static final class ParsedDialogue {
        String text = "";
        int alignment = 2;
        float yFraction = 0.9f;
        boolean hasPos;
        float posY = -1;
        float posX = -1;
        SubtitleLine line;
    }
}
