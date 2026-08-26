package org.schabi.newpipe.player.subtitles;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Local cache for fetched subtitle content. Stored under {@code files/subtitles/<hash>} so a
 * subtitle key that may contain path separators is never used directly as a filename.
 */
public final class SubtitleCache {

    private SubtitleCache() {
    }

    private static File dir(final Context c) {
        final File d = new File(c.getFilesDir(), "subtitles");
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

    /** Save raw subtitle content (ASS or SRT). Returns true on success. */
    public static boolean save(final Context c, final String id, final String content) {
        try {
            final File f = new File(dir(c), key(id) + ".sub");
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
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
            final File f = new File(dir(c), key(id) + ".sub");
            if (!f.exists()) {
                return null;
            }
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
            return new String(b, StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return null;
        }
    }

    public static boolean exists(final Context c, final String id) {
        return new File(dir(c), key(id) + ".sub").exists();
    }

    /** Delete a single cached entry. */
    public static boolean delete(final Context c, final String id) {
        return new File(dir(c), key(id) + ".sub").delete();
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
