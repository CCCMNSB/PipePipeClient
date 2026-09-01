package org.schabi.newpipe.player.bulletComments;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.player.subtitles.AssSubtitleParser;
import org.schabi.newpipe.player.subtitles.SubtitleCache;
import org.schabi.newpipe.player.subtitles.SubtitleLine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the <b>online danmaku</b> for a video from the shared repository.
 *
 * <p>The danmaku is an ASS file (same format as online subtitles) stored under
 * {@code danmaku/<videoId>.ass} in the subtitle repository. Each line carries a
 * {@code \move(x1,y1,x2,y2)} tag for the rolling effect, and the text is
 * {@code translated\Noriginal} (bilingual).
 *
 * <p>This class fetches the ASS, caches it in {@link SubtitleCache} (under a
 * {@code danmaku:}-prefixed key to avoid conflicts with subtitle entries), and
 * parses it with {@link AssSubtitleParser} into {@link SubtitleLine}s. The
 * {@link org.schabi.newpipe.player.subtitles.SubtitleOverlayView} renders
 * {@code \move} lines as rolling danmaku, and non-{\code \move} lines as
 * regular subtitles — both coexist in the same overlay, exactly like the PC
 * userscript.</p>
 */
public final class OnlineDanmakuLoader {

    private static final String TAG = "OnlineDanmakuLoader";
    private static final String SUBTITLE_FOLDER = "subtitles";
    private static final String DANMAKU_FOLDER = "danmaku";
    /** Key prefix so danmaku cache entries never collide with subtitle entries. */
    private static final String DANMAKU_KEY_PREFIX = "danmaku:";

    private OnlineDanmakuLoader() {
    }

    /** The repository's {@code danmaku/} base URL, derived from the subtitle base URL setting. */
    static String baseUrl(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String base = prefs.getString(context.getString(R.string.subtitle_base_url_key),
                "https://raw.githubusercontent.com/CCCMNSB/subtitles/main/" + SUBTITLE_FOLDER);
        if (base != null && base.endsWith("/" + SUBTITLE_FOLDER)) {
            base = base.substring(0, base.length() - ("/" + SUBTITLE_FOLDER).length());
        }
        if (base == null || base.isEmpty()) {
            base = "https://raw.githubusercontent.com/CCCMNSB/subtitles/main";
        }
        return base.replaceAll("/+$", "") + "/" + DANMAKU_FOLDER;
    }

    /** Extract a stable video id from the stream url (YouTube 11-char, Bilibili BV...). */
    public static String videoId(final String url) {
        if (url == null) {
            return null;
        }
        final int i = url.indexOf("v=");
        if (i >= 0 && url.length() > i + 2) {
            String v = url.substring(i + 2);
            final int amp = v.indexOf('&');
            if (amp >= 0) {
                v = v.substring(0, amp);
            }
            if (v.length() == 11) {
                return v;
            }
        }
        final int yb = url.indexOf("youtu.be/");
        if (yb >= 0) {
            String v = url.substring(yb + "youtu.be/".length());
            final int s = v.indexOf('/');
            if (s >= 0) {
                v = v.substring(0, s);
            }
            if (v.length() == 11) {
                return v;
            }
        }
        final int bv = url.indexOf("BV");
        if (bv >= 0) {
            String v = url.substring(bv);
            final int end = v.indexOf('/');
            if (end >= 0) {
                v = v.substring(0, end);
            }
            if (v.startsWith("BV") && v.length() >= 10) {
                return v;
            }
        }
        return null;
    }

    /**
     * Fetch {@code danmaku/<videoId>.ass} (or serve from cache) and parse it into
     * {@link SubtitleLine}s. Returns {@code null} when the video has no danmaku
     * in the repository (HTTP 404 / empty) so the caller can report an error.
     */
    static List<SubtitleLine> load(final Context context, final int serviceId,
                                    final String url) throws IOException {
        final String id = videoId(url);
        if (id == null) {
            return null;
        }

        // Cache key: prefixed to avoid collision with subtitle cache entries.
        final String cacheKey = DANMAKU_KEY_PREFIX + id;

        // Try cache first.
        final String cached = SubtitleCache.load(context, cacheKey);
        if (cached != null) {
            return AssSubtitleParser.parse(cached);
        }

        final String danmakuUrl = baseUrl(context) + "/" + id + ".ass";
        try {
            final Downloader dl = NewPipe.getDownloader();
            final Response res = dl.get(danmakuUrl);
            final int code = res.responseCode();
            if (code >= 200 && code < 300) {
                final String text = res.responseBody();
                if (text == null || text.trim().isEmpty()) {
                    return null;
                }
                // Cache the raw ASS content.
                SubtitleCache.save(context, cacheKey, text);
                return AssSubtitleParser.parse(text);
            } else {
                Log.d(TAG, "Danmaku " + danmakuUrl + " -> HTTP " + code);
                return null;
            }
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Online danmaku request failed: " + e.getMessage(), e);
        }
    }

    /** Force-refresh: bypass cache and re-fetch from the network. */
    static List<SubtitleLine> loadForceRefresh(final Context context, final int serviceId,
                                                final String url) throws IOException {
        final String id = videoId(url);
        if (id == null) {
            return null;
        }
        final String cacheKey = DANMAKU_KEY_PREFIX + id;

        final String danmakuUrl = baseUrl(context) + "/" + id + ".ass";
        try {
            final Downloader dl = NewPipe.getDownloader();
            final Response res = dl.get(danmakuUrl);
            final int code = res.responseCode();
            if (code >= 200 && code < 300) {
                final String text = res.responseBody();
                if (text == null || text.trim().isEmpty()) {
                    return null;
                }
                SubtitleCache.save(context, cacheKey, text);
                return AssSubtitleParser.parse(text);
            } else {
                return null;
            }
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Online danmaku request failed: " + e.getMessage(), e);
        }
    }
}
