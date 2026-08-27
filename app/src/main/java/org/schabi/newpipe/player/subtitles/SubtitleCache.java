package org.schabi.newpipe.player.subtitles;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Local cache for subtitle content and the subtitle index (manifest).
 *
 * <p>Per-video subtitle files are stored under {@code files/subtitles/<hash>.sub} (content) with a
 * {@code <hash>.id} sidecar holding the raw id for display. The subtitle manifest is cached under
 * {@code files/subtitle_index/index.json} plus a {@code .ts} timestamp so the list can be served
 * instantly within a TTL. {@link #list}/{@link #clearAll} back the cache-management UI.</p>
 */
public final class SubtitleCache {

    /** Short "no-request" window so rapid list re-opens don't hammer the server (ms). */
    public static final long INDEX_MIN_CHECK_MS = 5 * 60 * 1000L;

    private SubtitleCache() {
    }

    public static final class CacheEntry {
        public final String key;
        public final String label;
        public final long sizeBytes;
        CacheEntry(final String key, final String label, final long sizeBytes) {
            this.key = key;
            this.label = label;
            this.sizeBytes = sizeBytes;
        }
    }

    private static File subDir(final Context c) {
        final File d = new File(c.getFilesDir(), "subtitles");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    private static File indexDir(final Context c) {
        final File d = new File(c.getFilesDir(), "subtitle_index");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** Stable filesystem-safe key from the subtitle id. */
    public static String key(final String id) {
        if (id == null) {
            return "null";
        }
        return sha1(id);
    }

    private static File subFile(final Context c, final String id) {
        return new File(subDir(c), key(id) + ".sub");
    }

    private static File idFile(final Context c, final String id) {
        return new File(subDir(c), key(id) + ".id");
    }

    // ------------------------------------------------------------------ index

    /** Save the subtitle manifest plus its source url and ETag (for conditional GET). */
    public static void saveIndex(final Context c, final String content, final String url,
                                 final String etag) {
        try {
            final File json = new File(indexDir(c), "index.json");
            try (FileOutputStream fos = new FileOutputStream(json)) {
                fos.write((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            }
            final File u = new File(indexDir(c), "index.url");
            try (FileOutputStream fos = new FileOutputStream(u)) {
                fos.write((url == null ? "" : url).getBytes(StandardCharsets.UTF_8));
            }
            final File e = new File(indexDir(c), "index.etag");
            try (FileOutputStream fos = new FileOutputStream(e)) {
                fos.write((etag == null ? "" : etag).getBytes(StandardCharsets.UTF_8));
            }
            touchIndex(c);
        } catch (final Exception ex) {
            // ignore
        }
    }

    /** Cached manifest content if it was stored for {@code url}; otherwise {@code null}. */
    @Nullable
    public static String loadIndex(final Context c, final String url) {
        try {
            final File json = new File(indexDir(c), "index.json");
            if (!json.exists() || !urlMatches(c, url)) {
                return null;
            }
            return new String(readAll(json), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return null;
        }
    }

    /** Stored ETag if the cache was stored for {@code url}; otherwise {@code null}. */
    @Nullable
    public static String indexEtag(final Context c, final String url) {
        try {
            if (!urlMatches(c, url)) {
                return null;
            }
            final File e = new File(indexDir(c), "index.etag");
            if (!e.exists()) {
                return null;
            }
            final String s = new String(readAll(e), StandardCharsets.UTF_8);
            return s.isEmpty() ? null : s;
        } catch (final Exception e) {
            return null;
        }
    }

    /** Epoch ms of the last time the index was fetched/validated, or 0 if never. */
    public static long lastFetchMs(final Context c) {
        try {
            final File ts = new File(indexDir(c), "index.ts");
            if (!ts.exists()) {
                return 0;
            }
            return Long.parseLong(new String(readAll(ts), StandardCharsets.UTF_8).trim());
        } catch (final Exception e) {
            return 0;
        }
    }

    /** Record "now" as the last time the index was checked (after a 304). */
    public static void touchIndex(final Context c) {
        try {
            final File ts = new File(indexDir(c), "index.ts");
            try (FileOutputStream fos = new FileOutputStream(ts)) {
                fos.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            }
        } catch (final Exception e) {
            // ignore
        }
    }

    private static boolean urlMatches(final Context c, final String url) {
        final File u = new File(indexDir(c), "index.url");
        if (!u.exists()) {
            return false;
        }
        try {
            final String cachedUrl = new String(readAll(u), StandardCharsets.UTF_8);
            return url != null && url.equals(cachedUrl);
        } catch (final Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ per-video

    /** Save raw subtitle content (ASS or SRT), with the id sidecar for display. */
    public static boolean save(final Context c, final String id, final String content) {
        try {
            final File f = subFile(c, id);
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            if (id != null) {
                try (FileOutputStream fos = new FileOutputStream(idFile(c, id))) {
                    fos.write(id.getBytes(StandardCharsets.UTF_8));
                }
            }
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    /** Load cached content, or null if absent/failed. */
    @Nullable
    public static String load(final Context c, final String id) {
        try {
            final File f = subFile(c, id);
            if (!f.exists()) {
                return null;
            }
            return new String(readAll(f), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return null;
        }
    }

    public static boolean exists(final Context c, final String id) {
        return subFile(c, id).exists();
    }

    /** Delete a single cached subtitle entry. */
    public static boolean delete(final Context c, final String id) {
        final boolean a = subFile(c, id).delete();
        final boolean b = idFile(c, id).delete();
        return a || b;
    }

    // ------------------------------------------------------------------ listing / management

    public static List<CacheEntry> list(final Context c) {
        final List<CacheEntry> out = new ArrayList<>();
        final File dir = subDir(c);
        final File[] files = dir.listFiles((d, name) -> name.endsWith(".sub"));
        if (files != null) {
            for (final File f : files) {
                final String hash = f.getName().substring(0, f.getName().length() - 4);
                String label = hash;
                final File idf = new File(dir, hash + ".id");
                if (idf.exists()) {
                    try {
                        label = new String(readAll(idf), StandardCharsets.UTF_8);
                    } catch (final Exception e) {
                        // keep hash
                    }
                }
                out.add(new CacheEntry(hash, label, f.length()));
            }
        }
        final File indexJson = new File(indexDir(c), "index.json");
        if (indexJson.exists()) {
            out.add(new CacheEntry("__index__", "字幕清单 (index)", indexJson.length()));
        }
        Collections.sort(out, Comparator.comparing(e -> e.label));
        return out;
    }

    public static long totalSize(final Context c) {
        long total = 0;
        final File[] sub = subDir(c).listFiles();
        if (sub != null) {
            for (final File f : sub) {
                total += f.length();
            }
        }
        final File[] idx = indexDir(c).listFiles();
        if (idx != null) {
            for (final File f : idx) {
                total += f.length();
            }
        }
        return total;
    }

    public static void clearAll(final Context c) {
        final File[] sub = subDir(c).listFiles();
        if (sub != null) {
            for (final File f : sub) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        final File[] idx = indexDir(c).listFiles();
        if (idx != null) {
            for (final File f : idx) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    /** Delete an entry by the key used in {@link #list}. */
    public static boolean deleteByKey(final Context c, final String key) {
        if ("__index__".equals(key)) {
            final boolean a = new File(indexDir(c), "index.json").delete();
            final boolean b = new File(indexDir(c), "index.ts").delete();
            final boolean d = new File(indexDir(c), "index.url").delete();
            final boolean e = new File(indexDir(c), "index.etag").delete();
            return a || b || d || e;
        }
        final boolean a = new File(subDir(c), key + ".sub").delete();
        final boolean b = new File(subDir(c), key + ".id").delete();
        return a || b;
    }

    // ------------------------------------------------------------------ utils

    private static byte[] readAll(final File f) throws Exception {
        final byte[] b = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int off = 0;
            while (off < b.length) {
                final int r = fis.read(b, off, b.length - off);
                if (r < 0) {
                    break;
                }
                off += r;
            }
        }
        return b;
    }

    private static String sha1(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(40);
            for (final byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (final Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
