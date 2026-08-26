package org.schabi.newpipe.player.bulletComments;

import android.content.Context;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonWriter;

import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists translated danmaku on disk so re-entering a video can re-use the translation without
 * downloading and re-translating. Each video gets one file under {@code files/danmaku/}, keyed by
 * a stable hash of the stream url.
 *
 * <p>Each entry stores the <b>original</b> and the <b>translation</b> separately (a {@link Record}).
 * The displayed text is built at load time from the current "show original" setting, so toggling
 * that switch reflects on already-cached data without re-translating. A small {@code index.json}
 * holds per-entry metadata so the management screen can list/delete without reading every item
 * file. Fully self-contained.</p>
 */
public final class DanmakuCache {

    private DanmakuCache() {
    }

    private static final String INDEX_NAME = "index.json";

    /** One raw cached item: original + translation (kept independently of the display toggle). */
    public static final class Record {
        public final String original;
        public final String translated;
        public final long durationMs;
        public final String position;
        public final int color;
        public final double fontSize;

        public Record(final String original, final String translated, final long durationMs,
                      final String position, final int color, final double fontSize) {
            this.original = original == null ? "" : original;
            this.translated = translated == null ? "" : translated;
            this.durationMs = durationMs;
            this.position = position;
            this.color = color;
            this.fontSize = fontSize;
        }
    }

    /** One cache entry's metadata, for the management screen. */
    public static final class CacheEntry {
        public final String key;
        public final String url;
        public final int count;
        public final long sizeBytes;
        public final long modified;

        CacheEntry(final String key, final String url, final int count,
                   final long sizeBytes, final long modified) {
            this.key = key;
            this.url = url;
            this.count = count;
            this.sizeBytes = sizeBytes;
            this.modified = modified;
        }
    }

    private static File root(final Context c) {
        return new File(c.getFilesDir(), "danmaku");
    }

    private static File indexPath(final Context c) {
        return new File(root(c), INDEX_NAME);
    }

    private static File fileFor(final Context c, final String key) {
        return new File(root(c), key + ".json");
    }

    /** Stable, filesystem-safe key from the stream url (same video -> same cache). */
    public static String videoKey(final int serviceId, final String url) {
        final String s = url == null ? "s" + serviceId : serviceId + "|" + url;
        return Integer.toHexString(s.hashCode());
    }

    public static boolean has(final Context c, final String key) {
        return fileFor(c, key).exists();
    }

    /** Persist a set of original+translation records for a video and refresh the index. */
    public static void save(final Context c, final String key, final String url,
                            final List<Record> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            final File dir = root(c);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            final JsonObject obj = new JsonObject();
            obj.put("url", url == null ? "" : url);
            obj.put("count", records.size());
            final JsonArray arr = new JsonArray();
            for (final Record r : records) {
                final JsonObject o = new JsonObject();
                o.put("orig", r.original);
                o.put("trans", r.translated);
                o.put("durationMs", r.durationMs);
                o.put("position", r.position);
                o.put("color", r.color);
                o.put("fontSize", r.fontSize);
                arr.add(o);
            }
            obj.put("items", arr);
            write(fileFor(c, key), JsonWriter.string(obj));
            updateIndex(c, key, url, records.size());
        } catch (final IOException e) {
            android.util.Log.e("DanmakuCache", "save failed", e);
        }
    }

    /**
     * Load a previously saved video's records, building the display text from {@code showOriginal}
     * (translated + "\n" + original, or just translated). Returns {@code null} when absent/corrupt.
     * Old caches that only stored a single {@code text} field are returned as-is (no toggle).
     */
    public static List<BulletCommentsInfoItem> load(final Context c, final String key,
                                                    final boolean showOriginal) {
        final File f = fileFor(c, key);
        if (!f.exists()) {
            return null;
        }
        try {
            final JsonObject obj = JsonParser.object().from(read(f));
            final String url = obj.getString("url", "");
            final JsonArray arr = obj.getArray("items");
            if (arr == null) {
                return null;
            }
            final List<BulletCommentsInfoItem> out = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                final JsonObject o = arr.getObject(i);
                final String text = o.getString("text", null);
                // New format stores orig + trans separately; old format only has "text".
                final boolean hasSeparate = o.has("orig") || o.has("trans");
                final String display;
                if (hasSeparate) {
                    final String orig = o.getString("orig", "");
                    final String trans = o.getString("trans", "");
                    display = showOriginal ? trans + "\n" + orig : trans;
                } else {
                    display = text == null ? "" : text; // legacy: keep as-is
                }
                final BulletCommentsInfoItem item = new BulletCommentsInfoItem(0, url, display);
                item.setCommentText(display);
                item.setDuration(Duration.ofMillis(o.getLong("durationMs", 0)));
                item.setPosition(parsePosition(hasSeparate
                        ? o.getString("position", "REGULAR") : "REGULAR"));
                item.setArgbColor((int) o.getLong("color", 0xFFFFFFFFL));
                item.setRelativeFontSize(o.getDouble("fontSize", 1.0));
                out.add(item);
            }
            return out;
        } catch (final Exception e) {
            android.util.Log.e("DanmakuCache", "load failed for " + key, e);
            return null;
        }
    }

    /** Delete one video's cache (and its index entry). */
    public static void delete(final Context c, final String key) {
        final File f = fileFor(c, key);
        if (f.exists()) {
            f.delete();
        }
        removeFromIndex(c, key);
    }

    /** Delete every cached video. */
    public static void clearAll(final Context c) {
        final File dir = root(c);
        final File[] files = dir.listFiles(f ->
                f.getName().endsWith(".json") && !f.getName().equals(INDEX_NAME));
        if (files != null) {
            for (final File f : files) {
                f.delete();
            }
        }
        final File idx = indexPath(c);
        if (idx.exists()) {
            idx.delete();
        }
    }

    /** Metadata for every cached video (from the index). */
    public static List<CacheEntry> list(final Context c) {
        final List<CacheEntry> out = new ArrayList<>();
        final File idx = indexPath(c);
        if (!idx.exists()) {
            return out;
        }
        try {
            final JsonArray arr = JsonParser.array().from(read(idx));
            for (int i = 0; i < arr.size(); i++) {
                final JsonObject o = arr.getObject(i);
                out.add(new CacheEntry(
                        o.getString("key", ""),
                        o.getString("url", ""),
                        (int) o.getLong("count", 0),
                        o.getLong("size", 0),
                        o.getLong("modified", 0)));
            }
        } catch (final Exception e) {
            android.util.Log.e("DanmakuCache", "list failed", e);
        }
        return out;
    }

    /** Total bytes used by all cache files (excluding the tiny index). */
    public static long totalSize(final Context c) {
        final File dir = root(c);
        if (!dir.exists()) {
            return 0;
        }
        long total = 0;
        final File[] files = dir.listFiles(f ->
                f.getName().endsWith(".json") && !f.getName().equals(INDEX_NAME));
        if (files != null) {
            for (final File f : files) {
                total += f.length();
            }
        }
        return total;
    }

    public static int totalCount(final Context c) {
        return list(c).size();
    }

    // ----- index helpers -----

    private static void updateIndex(final Context c, final String key, final String url,
                                    final int count) throws IOException {
        final JsonArray arr = readIndex(c);
        JsonObject entry = null;
        for (int i = 0; i < arr.size(); i++) {
            final JsonObject o = arr.getObject(i);
            if (key.equals(o.getString("key", ""))) {
                entry = o;
                break;
            }
        }
        if (entry == null) {
            entry = new JsonObject();
            arr.add(entry);
        }
        entry.put("key", key);
        entry.put("url", url == null ? "" : url);
        entry.put("count", count);
        entry.put("size", fileFor(c, key).length());
        entry.put("modified", System.currentTimeMillis());
        write(indexPath(c), JsonWriter.string(arr));
    }

    private static void removeFromIndex(final Context c, final String key) {
        final JsonArray arr = readIndex(c);
        final JsonArray kept = new JsonArray();
        for (int i = 0; i < arr.size(); i++) {
            final JsonObject o = arr.getObject(i);
            if (!key.equals(o.getString("key", ""))) {
                kept.add(o);
            }
        }
        try {
            write(indexPath(c), JsonWriter.string(kept));
        } catch (final IOException e) {
            android.util.Log.e("DanmakuCache", "index update failed", e);
        }
    }

    private static JsonArray readIndex(final Context c) {
        final File idx = indexPath(c);
        if (!idx.exists()) {
            return new JsonArray();
        }
        try {
            return JsonParser.array().from(read(idx));
        } catch (final Exception e) {
            return new JsonArray();
        }
    }

    private static void write(final File f, final String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(final File f) throws IOException {
        final byte[] buf = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < buf.length) {
                final int n = fis.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        }
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static BulletCommentsInfoItem.Position parsePosition(final String s) {
        try {
            return BulletCommentsInfoItem.Position.valueOf(s);
        } catch (final Exception e) {
            return BulletCommentsInfoItem.Position.REGULAR;
        }
    }
}
