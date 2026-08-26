package org.schabi.newpipe.subtitles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.player.subtitles.SubtitleCache;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Fetches the subtitle manifest {@code index.json} from the subtitle repository and turns every
 * {@code {"id": <videoId>, "title": <title>}} entry into a {@link SubtitleVideoItem}.
 *
 * <p>The repository owns the ordering of the array (newest first), so the list keeps the manifest
 * order rather than sorting itself. A missing title falls back to the video id.</p>
 */
public final class SubtitleRepoFetcher {

    /** Manifest listing every published subtitle, newest first (separate {@code index/} folder). */
    public static final String INDEX_JSON_URL =
            "https://raw.githubusercontent.com/CCCMNSB/subtitles/main/index/index.json";

    /** Raw base URL of the subtitle folder, used by the subtitle loader. */
    public static final String SUBTITLE_BASE_URL =
            "https://raw.githubusercontent.com/CCCMNSB/subtitles/main/subtitles";

    private SubtitleRepoFetcher() {
    }

    /** Compute the manifest (index.json) URL from the configured repository base URL. */
    public static String indexUrl(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String base = prefs.getString(context.getString(R.string.subtitle_base_url_key),
                SUBTITLE_BASE_URL);
        if (base != null && base.endsWith("/subtitles")) {
            return base.substring(0, base.length() - "/subtitles".length()) + "/index/index.json";
        }
        return (base == null ? SUBTITLE_BASE_URL : base) + "/index/index.json";
    }

    /** The manifest URL currently in effect (for detecting repository switches). */
    public static String currentIndexUrl(final Context context) {
        return indexUrl(context);
    }

    /**
     * Fetch (or serve from the TTL cache) the subtitle manifest and return the items sorted newest
     * first (by {@code date}) when dates are present, otherwise in manifest order.
     *
     * @throws IOException on an HTTP error or when no fresh cached manifest exists
     */
    public static List<SubtitleVideoItem> fetchRepoSubtitles(final Context context)
            throws IOException {
        final String indexUrl = indexUrl(context);
        @Nullable String body = SubtitleCache.loadIndexIfFresh(context, indexUrl);
        if (body == null) {
            final Response response;
            try {
                response = NewPipe.getDownloader().get(indexUrl);
            } catch (final IOException e) {
                throw e;
            } catch (final Exception e) {
                throw new IOException("Subtitle manifest request failed", e);
            }
            final int code = response.responseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("Subtitle manifest returned HTTP " + code);
            }
            body = response.responseBody();
            if (body == null || body.isEmpty()) {
                throw new IOException("Empty response from subtitle manifest");
            }
            SubtitleCache.saveIndex(context, body, indexUrl);
        }

        try {
            final JsonArray array = JsonParser.array().from(body);
            final List<SubtitleVideoItem> items = new ArrayList<>();
            final int size = array.size();
            for (int i = 0; i < size; i++) {
                final Object raw = array.get(i);
                if (!(raw instanceof JsonObject)) {
                    continue;
                }
                final JsonObject entry = (JsonObject) raw;
                final String id = entry.getString("id");
                if (id == null || id.isEmpty()) {
                    continue;
                }
                String title = entry.getString("title");
                if (title == null || title.isEmpty()) {
                    title = id;
                }
                items.add(new SubtitleVideoItem(id, title, thumbnailUrlFor(id),
                        parseDate(entry.getString("date"))));
            }
            // Newest first when the manifest carries dates; otherwise keep manifest order.
            final boolean anyDate = items.stream().anyMatch(SubtitleVideoItem::hasDate);
            if (anyDate) {
                Collections.sort(items,
                        Comparator.comparingLong((SubtitleVideoItem it) -> it.dateMs).reversed());
            }
            return items;
        } catch (final Exception e) {
            throw new IOException("Failed to parse subtitle manifest JSON", e);
        }
    }

    /** Parse a manifest date to epoch millis, or -1 if absent/unparseable. */
    private static long parseDate(final String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        try {
            return OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (final Exception ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (final Exception ignored) {
            // fall through
        }
        return -1;
    }

    /** YouTube thumbnail URL for a video id (no extra API call). */
    public static String thumbnailUrlFor(final String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
    }

    /** Canonical YouTube watch URL for a video id. */
    public static String watchUrlFor(final String videoId) {
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
