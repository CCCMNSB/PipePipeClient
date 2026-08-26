package com.pipepipe.translator;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Translator backed by the unofficial Google Translate "free" web endpoint:
 * <pre>
 *   https://translate.googleapis.com/translate_a/single?client=gtx&amp;sl=auto&amp;tl=&lt;lang&gt;&amp;dt=t&amp;q=&lt;text&gt;
 * </pre>
 *
 * <p>This is the same endpoint GTranslate web pages use; it requires no API key but is
 * not officially supported. It is cheap (a few ms) and good for high-volume live/VOD
 * danmaku. For production, swap in the Cloud Translation API (see LlmTranslator for the
 * pattern). To stay under the free endpoint's abuse detection, {@link #translateBatch}
 * merges many short texts into one request, keeps each request under a character budget,
 * and throttles between requests with a shared {@link RateLimiter}. If a batch cannot be
 * aligned back to its inputs it returns {@code null}, and the caller falls back to
 * per-text {@link #translate} so a translation is never misplaced.</p>
 */
public class GoogleWebTranslator implements DanmakuTranslator {

    private static final String ENDPOINT =
            "https://translate.googleapis.com/translate_a/single";

    /** Keep one request's raw text this short so the GET URL stays safe. */
    private static final int MAX_BATCH_CHARS = 1500;
    /** Also cap the number of messages per request. */
    private static final int MAX_BATCH_ITEMS = 100;
    /** Shared rate limiter so the whole app never hammers the endpoint. */
    private static final RateLimiter LIMITER = new RateLimiter(120);

    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public GoogleWebTranslator() {
        this(10_000, 10_000);
    }

    public GoogleWebTranslator(final int connectTimeoutMs, final int readTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String translate(final String text, final String targetLang) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        final String url = buildUrl(text, targetLang);
        LIMITER.acquire();
        final String body = getBody(url);
        if (body == null) {
            return null;
        }
        return parseResponse(body);
    }

    /**
     * Translate many short texts with the fewest requests. Splits the input into batches that
     * stay under {@value #MAX_BATCH_CHARS} characters, sends each batch as one request, and
     * aligns the returned segments back to the original inputs. Returns {@code null} (so the
     * caller falls back to per-text translation) if any input cannot be matched exactly.
     */
    @Override
    public List<String> translateBatch(final List<String> texts, final String targetLang)
            throws Exception {
        if (texts == null || texts.isEmpty()) {
            return new ArrayList<>();
        }
        final List<List<String>> batches = splitByBudget(texts);
        final List<String> results = new ArrayList<>();
        for (final List<String> batch : batches) {
            final List<String> translated = translateOneBatch(batch, targetLang);
            if (translated == null) {
                return null; // misalignment -> caller falls back to per-item
            }
            results.addAll(translated);
        }
        return results.size() == texts.size() ? results : null;
    }

    private List<String> translateOneBatch(final List<String> batch, final String targetLang)
            throws Exception {
        final String joined = String.join("\n", batch);
        final String url = buildUrl(joined, targetLang);
        LIMITER.acquire();
        final String body = getBody(url);
        if (body == null) {
            return null;
        }
        final Map<String, String> byOriginal = parseSegmentMap(body);
        final List<String> out = new ArrayList<>();
        for (final String s : batch) {
            final String tr = byOriginal.get(s);
            if (tr == null) {
                android.util.Log.d("GoogleWebT", "batch size=" + batch.size()
                        + " MISALIGNED -> fallback");
                return null; // a line did not come back 1:1 -> signal fallback
            }
            out.add(tr);
        }
        android.util.Log.d("GoogleWebT", "batch size=" + batch.size()
                + " aligned ok, segments=" + byOriginal.size());
        return out;
    }

    private static List<List<String>> splitByBudget(final List<String> texts) {
        final List<List<String>> batches = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int chars = 0;
        for (final String t : texts) {
            final String s = t == null ? "" : t;
            final int len = s.length();
            if (!batch.isEmpty() && (chars + len > MAX_BATCH_CHARS || batch.size() >= MAX_BATCH_ITEMS)) {
                batches.add(batch);
                batch = new ArrayList<>();
                chars = 0;
            }
            batch.add(s);
            chars += len;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private static String buildUrl(final String text, final String targetLang) throws Exception {
        final String encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        return ENDPOINT
                + "?client=gtx"
                + "&sl=auto"
                + "&tl=" + URLEncoder.encode(targetLang, StandardCharsets.UTF_8.name())
                + "&dt=t"
                + "&q=" + encoded;
    }

    private String getBody(final String url) throws Exception {
        final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"
                            + " (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");

            final int code = conn.getResponseCode();
            final InputStream stream = code >= 400
                    ? conn.getErrorStream()
                    : conn.getInputStream();
            if (stream == null) {
                return null;
            }
            final String body = readBody(stream, "gzip".equalsIgnoreCase(
                    conn.getContentEncoding()));
            return (code >= 400 || body == null || body.trim().isEmpty()) ? null : body;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Parse the nested gtx array into the concatenated translated string.
     * Structure (simplified): [[["translated0", "orig0", ...], ["translated1", ...]], ...]
     */
    public static String parseResponse(final String body) throws Exception {
        final JSONArray top = new JSONArray(body);
        if (top.length() == 0) {
            return null;
        }
        final Object first = top.opt(0);
        if (!(first instanceof JSONArray)) {
            return null;
        }
        final JSONArray segments = (JSONArray) first;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length(); i++) {
            final Object seg = segments.opt(i);
            if (seg instanceof JSONArray) {
                final String piece = ((JSONArray) seg).optString(0, null);
                if (piece != null) {
                    sb.append(piece);
                }
            }
        }
        final String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Map each source segment's echo ("original") to its translation, so a batched response can
     * be aligned back to the exact input lines. Concatenates if a line maps to several segments.
     */
    private static Map<String, String> parseSegmentMap(final String body) throws Exception {
        final Map<String, String> map = new LinkedHashMap<>();
        final JSONArray top = new JSONArray(body);
        if (top.length() == 0) {
            return map;
        }
        final Object first = top.opt(0);
        if (!(first instanceof JSONArray)) {
            return map;
        }
        final JSONArray segments = (JSONArray) first;
        for (int i = 0; i < segments.length(); i++) {
            final Object seg = segments.opt(i);
            if (seg instanceof JSONArray) {
                final JSONArray s = (JSONArray) seg;
                final String translated = s.optString(0, null);
                final String original = s.optString(1, null);
                if (original != null && translated != null) {
                    final String existing = map.get(original);
                    map.put(original, existing == null ? translated : existing + translated);
                }
            }
        }
        return map;
    }

    private static String readBody(final InputStream in, final boolean gzip) throws Exception {
        try (InputStream is = gzip ? new GZIPInputStream(in) : in;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
