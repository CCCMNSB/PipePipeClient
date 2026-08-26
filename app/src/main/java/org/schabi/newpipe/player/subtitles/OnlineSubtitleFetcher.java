package org.schabi.newpipe.player.subtitles;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches subtitle content from a remote repository by "id" and caches it.
 *
 * <p>The id is a repo-relative key (may include sub-paths, e.g. {@code <folder>/<name>}), and the
 * base URL is the raw root (e.g. {@code .../raw.githubusercontent.com/CCCMNSB/subtitles/main}).
 * The fetcher tries {@code <base>/<id>.ass} first, then {@code .srt}, caches the successful
 * download, and returns the parsed lines. Once cached, this returns instantly without hitting the
 * network unless {@code forceRefresh} is set, so the player stays responsive on replay.
 */
public final class OnlineSubtitleFetcher {

    private static final String TAG = "OnlineSubtitleFetcher";

    private OnlineSubtitleFetcher() {
    }

    /** Fetch (or serve from cache) subtitle lines for the given base URL + id. */
    public static List<SubtitleLine> fetch(final Context context, final String baseUrl,
                                           final String id, final boolean forceRefresh) {
        if (id == null || id.trim().isEmpty()) {
            return new ArrayList<>();
        }
        final String key = id.trim();

        if (!forceRefresh) {
            final String cached = SubtitleCache.load(context, key);
            if (cached != null) {
                return AssSubtitleParser.parse(cached);
            }
        }

        for (final String url : candidates(baseUrl, key)) {
            try {
                final Downloader dl = NewPipe.getDownloader();
                final Response r = dl.get(url);
                final int code = r.responseCode();
                if (code >= 200 && code < 300) {
                    final String content = r.responseBody();
                    if (content != null && !content.isEmpty()) {
                        SubtitleCache.save(context, key, content);
                        return AssSubtitleParser.parse(content);
                    }
                } else {
                    Log.d(TAG, "Subtitle " + url + " -> HTTP " + code);
                }
            } catch (final IOException e) {
                Log.w(TAG, "Subtitle fetch failed for " + url, e);
            } catch (final Exception e) {
                Log.w(TAG, "Subtitle fetch error for " + url, e);
            }
        }
        return new ArrayList<>();
    }

    private static List<String> candidates(final String baseUrl, final String id) {
        final List<String> out = new ArrayList<>();
        if (id.startsWith("http://") || id.startsWith("https://")) {
            out.add(id);
            return out;
        }
        final String base = trimTrailingSlash(baseUrl);
        out.add(base + "/" + id + ".ass");
        out.add(base + "/" + id + ".srt");
        return out;
    }

    private static String trimTrailingSlash(final String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** URL-encode a single path segment (keeps slashes). */
    @NonNull
    private static String encode(final String seg) {
        return URLEncoder.encode(seg, StandardCharsets.UTF_8);
    }
}
