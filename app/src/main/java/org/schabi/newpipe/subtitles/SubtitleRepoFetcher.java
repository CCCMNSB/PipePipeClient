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
 * {@code {"id": <videoId>, "title": <title>, "list": <collection>}} entry into a
 * {@link SubtitleVideoItem} ({@code list} is optional; entries without it are "uncategorized").
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
     * Fetch the subtitle manifest with a conditional GET (ETag). The full manifest body is only
     * downloaded when it actually changed (HTTP 200); a 304 "not modified" reuses the local cache and
     * transfers almost no data. A short no-request window avoids hammering on rapid list re-opens.
     *
     * @throws IOException on an HTTP error or network failure
     */
    public static List<SubtitleVideoItem> fetchRepoSubtitles(final Context context)
            throws IOException {
        return fetchRepoSubtitles(context, false);
    }

    /** @param force bypasses the short no-request window (used by the manual refresh button). */
    public static List<SubtitleVideoItem> fetchRepoSubtitles(final Context context,
                                                             final boolean force)
            throws IOException {
        final String indexUrl = indexUrl(context);

        // Always do a conditional GET: entering the list checks once, and the full body is only
        // downloaded when the manifest actually changed (a 304 "not modified" transfers almost
        // nothing). The ETag check is cheap, so no extra time-based window is applied.
        final String etag = SubtitleCache.indexEtag(context, indexUrl);
        final java.util.Map<String, java.util.List<String>> headers = new java.util.HashMap<>();
        if (etag != null) {
            headers.put("If-None-Match", java.util.Collections.singletonList(etag));
        }

        final Response response;
        try {
            response = NewPipe.getDownloader().get(indexUrl, headers.isEmpty() ? null : headers);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Subtitle manifest request failed", e);
        }
        final int code = response.responseCode();
        if (code == 304) {
            SubtitleCache.touchIndex(context);
            final String cached = SubtitleCache.loadIndex(context, indexUrl);
            if (cached != null) {
                return parseManifest(cached);
            }
            throw new IOException("Subtitle manifest 304 but no local cache");
        }
        if (code < 200 || code >= 300) {
            throw new IOException("Subtitle manifest returned HTTP " + code);
        }
        final String body = response.responseBody();
        if (body == null || body.isEmpty()) {
            throw new IOException("Empty response from subtitle manifest");
        }
        SubtitleCache.saveIndex(context, body, indexUrl, etagOf(response));
        return parseManifest(body);
    }

    private static List<SubtitleVideoItem> parseManifest(final String body) throws IOException {
        try {
            final List<SubtitleVideoItem> items = new ArrayList<>();
            if (isColumnarManifest(body)) {
                // 列式：{ schema:["id","title","date","list","author"], rows:[[...],...] }
                final JsonObject root = JsonParser.object().from(body);
                final JsonArray schema = root.getArray("schema");
                final JsonArray rows = root.getArray("rows");
                if (schema == null || rows == null) {
                    throw new IOException("Bad columnar manifest: missing schema/rows");
                }
                final int cols = schema.size();
                for (int r = 0; r < rows.size(); r++) {
                    final JsonArray row = rows.getArray(r);
                    final String id = columnValue(row, schema, cols, "id");
                    if (id == null || id.isEmpty()) {
                        continue;
                    }
                    final String title = orColumnDefault(
                            columnValue(row, schema, cols, "title"), id);
                    items.add(new SubtitleVideoItem(id, title,
                            isBilibiliId(id) ? bilibiliCoverUrl(id) : thumbnailUrlFor(id),
                            parseDate(columnValue(row, schema, cols, "date")),
                            columnValue(row, schema, cols, "list"),
                            columnValue(row, schema, cols, "author")));
                }
            } else {
                // 扁平数组：[{id,title,date,list,author}]
                final JsonArray array = JsonParser.array().from(body);
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
                    items.add(new SubtitleVideoItem(id, title,
                            isBilibiliId(id) ? bilibiliCoverUrl(id) : thumbnailUrlFor(id),
                            parseDate(entry.getString("date")),
                            entry.getString("list"),
                            entry.getString("author")));
                }
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

    /** True when the manifest uses the columnar {@code {schema, rows}} format (vs a flat array). */
    private static boolean isColumnarManifest(final String body) {
        final String t = body == null ? "" : body.trim();
        return t.startsWith("{") && t.contains("\"schema\"") && t.contains("\"rows\"");
    }

    /** Read a column value from a columnar row by matching the {@code schema} name to its index. */
    private static String columnValue(final JsonArray row, final JsonArray schema,
                                      final int cols, final String key) {
        for (int i = 0; i < cols; i++) {
            if (key.equals(schema.getString(i))) {
                if (i >= row.size()) {
                    return null;
                }
                final Object v = row.get(i);
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    private static String orColumnDefault(final String value, final String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** Extract the ETag header (case-insensitive) from a response, or null. */
    private static String etagOf(final Response response) {
        try {
            for (final java.util.Map.Entry<String, java.util.List<String>> e
                    : response.responseHeaders().entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase("etag")
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
        } catch (final Exception ignored) {
            // fall through
        }
        return null;
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
        // 兼容任意位年（如 5 位年 10000-01-01，ISO 要求带 + 号）：按 "-" 拆出 年/月/日
        try {
            final String[] p = s.trim().split("-");
            if (p.length == 3) {
                final int year = Integer.parseInt(p[0]);
                final int month = Integer.parseInt(p[1]);
                final int day = Integer.parseInt(p[2]);
                return LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC)
                        .toInstant().toEpochMilli();
            }
        } catch (final Exception ignored) {
            // fall through
        }
        return -1;
    }

    /** True if the id is a Bilibili (BV…) id; otherwise YouTube. */
    public static boolean isBilibiliId(final String videoId) {
        return videoId != null && videoId.toUpperCase().startsWith("BV");
    }

    /** Thumbnail URL for a video id. YouTube: i.ytimg.com; Bilibili has no derivable cover, resolved in the loop. */
    public static String thumbnailUrlFor(final String videoId) {
        if (isBilibiliId(videoId)) return null;
        return "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg";
    }

    /** Best-effort Bilibili cover (data.pic) from the view API; null on failure. */
    public static String bilibiliCoverUrl(final String videoId) {
        try {
            final Response r = NewPipe.getDownloader()
                    .get("https://api.bilibili.com/x/web-interface/view?bvid=" + videoId,
                            (java.util.Map<String, java.util.List<String>>) null);
            if (r.responseCode() == 200) {
                final JsonObject data = JsonParser.object().from(r.responseBody()).getObject("data");
                if (data != null) return data.getString("pic");
            }
        } catch (final Exception ignored) {
            // 拿不到封面就留空
        }
        return null;
    }

    /** Canonical watch URL for a video id: Bilibili for BV ids, otherwise YouTube. */
    public static String watchUrlFor(final String videoId) {
        if (isBilibiliId(videoId)) return "https://www.bilibili.com/video/" + videoId;
        return "https://www.youtube.com/watch?v=" + videoId;
    }
}
