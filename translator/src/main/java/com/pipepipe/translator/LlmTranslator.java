package com.pipepipe.translator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translator backed by any OpenAI-compatible {@code /chat/completions} endpoint
 * (OpenAI, DeepSeek, local Ollama/LM Studio, vLLM, etc.). The base URL should include
 * the version path, e.g. {@code https://api.openai.com/v1} or {@code https://api.deepseek.com}.
 * {@code /chat/completions} is appended.
 *
 * <p>LLMs are slower and costlier per call than a free web endpoint, so {@link #translateBatch}
 * groups several danmaku into one request so the translation can keep pace with live/replay
 * danmaku (each message lives only a few seconds on screen).</p>
 */
public class LlmTranslator implements DanmakuTranslator {

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    private static final Pattern LEADING_NUMBER = Pattern.compile("^\\s*\\d+[.).]\\s*");

    public LlmTranslator(final String baseUrl, final String apiKey, final String model) {
        this(baseUrl, apiKey, model, 15_000, 90_000);
    }

    public LlmTranslator(final String baseUrl,
                         final String apiKey,
                         final String model,
                         final int connectTimeoutMs,
                         final int readTimeoutMs) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public String translate(final String text, final String targetLang) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        final JSONObject system = new JSONObject()
                .put("role", "system")
                .put("content",
                        "You are a concise translation engine. Translate the user's text into "
                                + targetLang + ". Reply with ONLY the translation, nothing else. "
                                + "Do not output thinking, reasoning, chain-of-thought, explanations, "
                                + "quotes, or notes.");
        final JSONObject user = new JSONObject()
                .put("role", "user")
                .put("content", text);
        final JSONObject root = new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("reasoning_effort", "low")
                .put("messages", new JSONArray().put(system).put(user));
        final String response = post(root);
        return parseResponse(response);
    }

    @Override
    public List<String> translateBatch(final List<String> texts, final String targetLang)
            throws Exception {
        if (texts == null || texts.size() < 2) {
            return null; // fall back to per-text translate()
        }
        final StringBuilder userContent = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            userContent.append(i + 1).append(". ").append(texts.get(i)).append('\n');
        }
        final JSONObject system = new JSONObject()
                .put("role", "system")
                .put("content",
                        "You are a translation engine. Translate each numbered line below into "
                                + targetLang + ". Reply with exactly " + texts.size()
                                + " lines, each being only the translation of the corresponding "
                                + "numbered input line, in the same order. Do not number them, and do "
                                + "not output thinking, reasoning, chain-of-thought, explanations, "
                                + "or any extra text.");
        final JSONObject user = new JSONObject().put("role", "user").put("content", userContent.toString());
        final JSONObject root = new JSONObject()
                .put("model", model)
                .put("temperature", 0)
                .put("reasoning_effort", "low")
                .put("messages", new JSONArray().put(system).put(user));
        final String response = post(root);
        final String content = parseResponse(response);
        if (content == null) {
            return null;
        }
        final List<String> out = new ArrayList<>();
        for (String line : content.split("\n")) {
            String t = line.trim();
            final Matcher m = LEADING_NUMBER.matcher(t);
            if (m.find()) {
                t = t.substring(m.end()).trim();
            }
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        // Only accept a perfect alignment; otherwise fall back to per-text translation.
        return out.size() == texts.size() ? out : null;
    }

    private String post(final JSONObject root) {
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean transientFail = false;
            String body = null;
            int code = -1;
            try {
                final String url = endpoint();
                final HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                try {
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(connectTimeoutMs);
                    conn.setReadTimeout(readTimeoutMs);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");
                    if (apiKey != null && !apiKey.trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                    }
                    conn.setDoOutput(true);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    code = conn.getResponseCode();
                    final InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                    body = (stream != null) ? readBody(stream) : null;
                } finally {
                    conn.disconnect();
                }
            } catch (final Exception e) {
                transientFail = true;
            }

            // 429 (rate-limited) or a transient network error: back off briefly and retry.
            if (attempt < 2 && (transientFail || code == 429)) {
                try {
                    Thread.sleep(transientFail ? 500L : 1000L);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }
            if (transientFail) {
                android.util.Log.e("LlmTranslator", "translate request failed");
                return null;
            }
            return (code >= 400 || body == null || body.trim().isEmpty()) ? null : body;
        }
        return null;
    }

    private String endpoint() {
        String base = baseUrl;
        if (base == null || base.trim().isEmpty()) {
            base = "https://api.openai.com/v1";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /**
     * Extract {@code choices[0].message.content} and strip wrapping fenced code / quotes.
     */
    public static String parseResponse(final String body) throws Exception {
        if (body == null) {
            return null;
        }
        final JSONObject root = new JSONObject(body);
        final JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        final JSONObject first = choices.optJSONObject(0);
        if (first == null) {
            return null;
        }
        final JSONObject message = first.optJSONObject("message");
        if (message == null) {
            return null;
        }
        String content = message.optString("content", null);
        if (content == null) {
            return null;
        }
        content = content.trim();
        if (content.length() >= 6) {
            final String lower = content.toLowerCase();
            if (lower.startsWith("```") && lower.endsWith("```")) {
                content = content.substring(3, content.length() - 3).trim();
            }
        }
        if (content.length() >= 2 && content.startsWith("\"") && content.endsWith("\"")) {
            content = content.substring(1, content.length() - 1).trim();
        }
        return content.isEmpty() ? null : content;
    }

    private static String readBody(final InputStream in) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
