package org.schabi.newpipe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.services.youtube.YoutubeApiDecoder;
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptDecoder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class WebViewJavaScriptDecoder implements YoutubeJavaScriptDecoder {
    private static final String TAG = "WebViewJsDecoder";
    private static final String PLAYER_URL =
            "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js";
    private static final String REMOTE_URL = "https://api.pipepipe.dev/decoder/decode";
    private static final long TIMEOUT_MS = 30_000L;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private boolean ready;
    private String loadedPlayerId;

    public WebViewJavaScriptDecoder(final Context context) {
        this.context = context.getApplicationContext();
    }

    @Nonnull
    @Override
    public synchronized YoutubeApiDecoder.BatchDecodeResult decodeBatch(
            @Nonnull final String playerId,
            @Nullable final List<String> signatures,
            @Nullable final List<String> throttlingParameters) throws ParsingException {
        final long totalStart = SystemClock.elapsedRealtime();
        ensureReady();
        final JSONObject request = new JSONObject();
        final JSONArray requests = new JSONArray();
        try {
            if (throttlingParameters != null && !throttlingParameters.isEmpty()) {
                requests.put(makeRequest("n", throttlingParameters));
            }
            if (signatures != null && !signatures.isEmpty()) {
                requests.put(makeRequest("sig", signatures));
            }
            request.put("playerId", playerId);
            request.put("requests", requests);
            final long localStart = SystemClock.elapsedRealtime();
            if (!playerId.equals(loadedPlayerId)) {
                uploadPlayer(playerId, fetchPlayer(playerId));
            }
            final JSONObject localJson = evaluate(request);
            final long localMs = SystemClock.elapsedRealtime() - localStart;
            final YoutubeApiDecoder.BatchDecodeResult local = parseResult(localJson,
                    throttlingParameters != null && !throttlingParameters.isEmpty(),
                    signatures != null && !signatures.isEmpty());
            Log.i(TAG, "player=" + playerId
                    + " cold=" + localJson.optBoolean("cold")
                    + " v8=" + localJson.optLong("elapsedMs") + "ms"
                    + " local=" + localMs + "ms");

            if (BuildConfig.DEBUG) {
                compareRemote(playerId, signatures, throttlingParameters, totalStart, local);
            }
            return local;
        } catch (final ParsingException e) {
            throw e;
        } catch (final Exception e) {
            throw new ParsingException("Local V8 decoding failed", e);
        }
    }

    private void compareRemote(final String playerId,
                               @Nullable final List<String> signatures,
                               @Nullable final List<String> throttlingParameters,
                               final long totalStart,
                               final YoutubeApiDecoder.BatchDecodeResult local) {
        try {
            final long remoteStart = SystemClock.elapsedRealtime();
            final YoutubeApiDecoder.BatchDecodeResult remote = decodeRemote(
                    playerId, signatures, throttlingParameters);
            final long remoteMs = SystemClock.elapsedRealtime() - remoteStart;
            final boolean equal = local.getSignatures().equals(remote.getSignatures())
                    && local.getNParameters().equals(remote.getNParameters());
            Log.i(TAG, "remote=" + remoteMs + "ms"
                    + " total=" + (SystemClock.elapsedRealtime() - totalStart) + "ms"
                    + " equal=" + equal);
            if (!equal) {
                Log.e(TAG, "local=" + resultString(local)
                        + " remote=" + resultString(remote));
            }
        } catch (final Exception e) {
            Log.w(TAG, "remote comparison failed", e);
        }
    }

    private void ensureReady() throws ParsingException {
        if (ready) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        mainHandler.post(() -> {
            try {
                createWebView(latch, error);
            } catch (final Throwable e) {
                error.set(e);
                latch.countDown();
            }
        });
        await(latch, "V8 initialization");
        if (error.get() != null) {
            throw new ParsingException("Could not initialize WebView V8", error.get());
        }
        ready = true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView(final CountDownLatch latch,
                               final AtomicReference<Throwable> error) {
        webView = new WebView(context);
        final WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(final WebView view, final String url) {
                view.evaluateJavascript("if(!Object.hasOwn){Object.hasOwn=function(o,p){"
                                + "return Object.prototype.hasOwnProperty.call(o,p)}};"
                                + "if(!Array.prototype.at){Array.prototype.at=function(i){"
                                + "i=Math.trunc(i)||0;if(i<0)i+=this.length;return this[i]}};"
                                + loadAsset("ejs/yt.solver.lib.min.js")
                                + ";var meriyah=lib.meriyah,astring=lib.astring;",
                        ignored -> view.evaluateJavascript(
                                loadAsset("ejs/yt.solver.core.min.js"),
                                ignoredCore -> view.evaluateJavascript(
                                        "typeof jsc==='function'", value -> {
                                            if (!"true".equals(value)) {
                                                error.set(new IllegalStateException(
                                                        "EJS initialization returned " + value));
                                            }
                                            latch.countDown();
                                        })));
            }
        });
        webView.loadDataWithBaseURL("https://localhost/", "<html></html>",
                "text/html", "UTF-8", null);
    }

    private JSONObject evaluate(final JSONObject request) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final String script = "(function(i){try{var s=performance.now(),cold="
                + "window.__ejsPlayerId!==i.playerId;if(cold){var r=jsc({type:'player',"
                + "player:window.__ejsPlayer,requests:i.requests,output_preprocessed:true});"
                + "window.__ejsSolvers={};Function('_result',r.preprocessed_player)("
                + "window.__ejsSolvers);window.__ejsPlayerId=i.playerId;window.__ejsPlayer=null;"
                + "delete r.preprocessed_player}else{r={type:'result',responses:i.requests.map("
                + "function(q){var f=window.__ejsSolvers[q.type];if(!f)return{type:'error',"
                + "error:'Failed to extract '+q.type+' function'};try{var d={};q.challenges."
                + "forEach(function(v){d[v]=f(v)});return{type:'result',data:d}}catch(e){return{"
                + "type:'error',error:e instanceof Error?e.message+'\\n'+e.stack:String(e)}}})}}"
                + "return JSON.stringify({"
                + "ok:true,cold:cold,elapsedMs:Math.round(performance.now()-s),result:r})}catch(e){"
                + "return JSON.stringify({ok:false,error:String(e),stack:e&&e.stack})}})("
                + request + ")";
        mainHandler.post(() -> webView.evaluateJavascript(script, value -> {
            try {
                if (value == null || "null".equals(value)) {
                    throw new IllegalStateException("V8 returned null");
                }
                result.set(new JSONArray("[" + value + "]").getString(0));
            } catch (final Throwable e) {
                error.set(e);
            } finally {
                latch.countDown();
            }
        }));
        await(latch, "V8 decoding");
        if (error.get() != null) {
            throw new ParsingException("Could not read V8 result", error.get());
        }
        final JSONObject wrapper = new JSONObject(result.get());
        if (!wrapper.optBoolean("ok")) {
            throw new ParsingException("V8 error: " + wrapper.optString("error")
                    + "\n" + wrapper.optString("stack"));
        }
        return wrapper;
    }

    private void uploadPlayer(final String playerId, final String player) throws Exception {
        evaluateRaw("window.__ejsPlayer=''");
        final int chunkSize = 128 * 1024;
        for (int start = 0; start < player.length(); start += chunkSize) {
            final int end = Math.min(start + chunkSize, player.length());
            evaluateRaw("window.__ejsPlayer+=" + JSONObject.quote(player.substring(start, end)));
        }
        evaluateRaw("if(window.__ejsPlayer.length!==" + player.length()
                + "){throw new Error(window.__ejsPlayer.length)}");
        Log.i(TAG, "uploaded player=" + playerId + " chars=" + player.length());
        loadedPlayerId = playerId;
    }

    private void evaluateRaw(final String script) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();
        mainHandler.post(() -> webView.evaluateJavascript(
                "(function(){try{" + script + ";return ''}catch(e){return String(e)}})()",
                value -> {
                    if (value != null && !"\"\"".equals(value)) {
                        error.set(value);
                    }
                    latch.countDown();
                }));
        await(latch, "V8 player upload");
        if (error.get() != null) {
            throw new ParsingException("Could not upload player to V8: " + error.get());
        }
    }

    private String fetchPlayer(final String playerId) throws ParsingException {
        try {
            return DownloaderImpl.getInstance().get(String.format(PLAYER_URL, playerId))
                    .responseBody();
        } catch (final Exception e) {
            throw new ParsingException("Could not fetch YouTube player", e);
        }
    }

    private YoutubeApiDecoder.BatchDecodeResult decodeRemote(
            final String playerId,
            @Nullable final List<String> signatures,
            @Nullable final List<String> throttlingParameters) throws Exception {
        final StringBuilder url = new StringBuilder(REMOTE_URL).append("?player=")
                .append(playerId);
        appendValues(url, "n", throttlingParameters);
        appendValues(url, "sig", signatures);
        final Map<String, List<String>> headers = new HashMap<>();
        headers.put("User-Agent", Collections.singletonList("PipePipe/4.9.0"));
        final JSONObject json = new JSONObject(
                DownloaderImpl.getInstance().get(url.toString(), headers).responseBody());
        return parseResult(new JSONObject().put("result", json),
                throttlingParameters != null && !throttlingParameters.isEmpty(),
                signatures != null && !signatures.isEmpty());
    }

    private static void appendValues(final StringBuilder url, final String name,
                                     @Nullable final List<String> values) throws Exception {
        if (values == null || values.isEmpty()) {
            return;
        }
        url.append('&').append(name).append('=');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                url.append(',');
            }
            url.append(URLEncoder.encode(values.get(i), StandardCharsets.UTF_8.name()));
        }
    }

    private static YoutubeApiDecoder.BatchDecodeResult parseResult(final JSONObject wrapper,
                                                                    final boolean hasN,
                                                                    final boolean hasSig)
            throws Exception {
        final JSONObject result = wrapper.getJSONObject("result");
        if (!"result".equals(result.optString("type"))) {
            throw new ParsingException("Decoder returned " + result);
        }
        final Map<String, String> signatures = new HashMap<>();
        final Map<String, String> throttlingParameters = new HashMap<>();
        final JSONArray responses = result.getJSONArray("responses");
        for (int i = 0; i < responses.length(); i++) {
            final JSONObject response = responses.getJSONObject(i);
            if (!"result".equals(response.optString("type"))) {
                throw new ParsingException("Decoder response returned " + response);
            }
            final Map<String, String> destination = hasN && (i == 0 || !hasSig)
                    ? throttlingParameters : signatures;
            final JSONObject data = response.getJSONObject("data");
            final java.util.Iterator<String> keys = data.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                destination.put(key, data.getString(key));
            }
        }
        return new YoutubeApiDecoder.BatchDecodeResult(signatures, throttlingParameters);
    }

    private static JSONObject makeRequest(final String type, final List<String> values)
            throws Exception {
        return new JSONObject().put("type", type).put("challenges", new JSONArray(values));
    }

    private static String resultString(final YoutubeApiDecoder.BatchDecodeResult result) {
        return "sig=" + result.getSignatures() + " n=" + result.getNParameters();
    }

    private String loadAsset(final String path) {
        try (InputStream in = context.getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(final CountDownLatch latch, final String operation)
            throws ParsingException {
        try {
            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw new ParsingException(operation + " timed out");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParsingException(operation + " interrupted", e);
        }
    }

}
