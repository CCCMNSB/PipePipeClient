package org.schabi.newpipe.player.bulletComments;

import android.content.Context;
import android.util.Log;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.common.model.RemoteModelManager;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.TranslateRemoteModel;
import com.pipepipe.translator.DanmakuTranslator;

import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfo;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.util.ExtractorHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One-shot "download + translate" job, launched from a player button and reported with
 * progress. Steps: (1) ensure the ML Kit model is present, (2) pull the full comment list,
 * (3) translate every unique text, (4) hand back a timestamp-ordered, translated list for the
 * player to overlay. Runs on a background thread; results are reported to {@link Listener}.
 */
public final class DanmakuDownloadHelper {

    private DanmakuDownloadHelper() {
    }

    public interface Listener {
        void onProgress(String stage, int current, int total);
        void onDone(List<BulletCommentsInfoItem> translated);
        void onError(String message);
    }

    public static void start(final Context context, final int serviceId, final String url,
                             final Listener listener) {
        new Thread(() -> {
            try {
                final android.content.SharedPreferences prefs = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(context);
                final String provider = prefs.getString(context.getString(
                        org.schabi.newpipe.R.string.danmaku_translation_provider_key), "mlkit");
                // How many messages between progress toasts (download + translate).
                final int interval = parseInterval(prefs.getString(context.getString(
                        org.schabi.newpipe.R.string.danmaku_progress_interval_key), "100"));

                // 1) Ensure the on-device model is available (ML Kit only). Only notify if the
                // model actually needs downloading; if it is already present there is no toast.
                if (!"llm".equals(provider) && !"deepseek".equals(provider)) {
                    if (ensureMlKitModels()) {
                        listener.onProgress("下载翻译模型", 1, 1);
                    }
                    Log.d("DanmakuDownload", "ML Kit models ready");
                }

                // 2) Pull the full comment list. For YouTube use the dedicated replay downloader
                // (bypasses the extractor, which treats a live archive as a live stream); else
                // fall back to the extractor's initial page / fetchAll.
                listener.onProgress("下载弹幕", 0, -1);
                List<BulletCommentsInfoItem> source = null;
                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                    final String videoId = extractVideoId(url);
                    if (videoId != null) {
                        try {
                            final int[] lastReported = {0};
                            source = org.schabi.newpipe.player.bulletComments.YouTubeReplayDanmakuFetcher
                                    .fetchAll(videoId, (pages, collected) -> {
                                        // Throttle download progress to once per interval messages.
                                        if (collected - lastReported[0] >= interval
                                                || collected == 0) {
                                            lastReported[0] = collected;
                                            listener.onProgress("下载弹幕", collected, -1);
                                        }
                                    });
                        } catch (final Exception e) {
                            Log.e("DanmakuDownload", "YouTubeReplayDanmakuFetcher failed", e);
                        }
                    }
                }
                if (source == null || source.isEmpty()) {
                    final BulletCommentsInfo info =
                            ExtractorHelper.getBulletCommentsInfo(serviceId, url, false).blockingGet();
                    final BulletCommentsExtractor extractor = info.getBulletCommentsExtractor();
                    extractor.reconnect();
                    source = extractor.fetchAll();
                    if (source == null || source.isEmpty()) {
                        source = info.getRelatedItems();
                    }
                }
                Log.d("DanmakuDownload", "downloaded " + source.size() + " comments");
                if (source != null) {
                    listener.onProgress("下载弹幕", source.size(), -1);
                }

                // 3) Translate every unique text (cache by text) with progress.
                final DanmakuTranslator translator = DanmakuTranslationBridge.getTranslator(context);
                final String target = DanmakuTranslationBridge.getTargetLang(context);
                final boolean showOriginal = DanmakuTranslationBridge.showOriginal(context);
                final Map<String, String> cache = new ConcurrentHashMap<>();
                final List<BulletCommentsInfoItem> out = new ArrayList<>();
                final List<DanmakuCache.Record> records = new ArrayList<>();
                final int total = source.size();

                // Batch engines (Google Web, LLM) translate many texts per request -> far fewer
                // calls. LLM runs the chunks concurrently for speed. Engines without batching
                // (ML Kit) fall back to per-text in the loop below.
                final boolean canBatch =
                        translator instanceof com.pipepipe.translator.LlmTranslator;
                if (canBatch) {
                    final List<String> toTranslate = new ArrayList<>();
                    final java.util.Set<String> seenTexts = new java.util.HashSet<>();
                    for (final BulletCommentsInfoItem item : source) {
                        final String text = item.getCommentText();
                        if (text != null && !text.isEmpty() && seenTexts.add(text)) {
                            toTranslate.add(text);
                        }
                    }
                    final int chunkSize = 40;
                    final List<List<String>> chunks = new ArrayList<>();
                    for (int start = 0; start < toTranslate.size(); start += chunkSize) {
                        chunks.add(toTranslate.subList(start, Math.min(start + chunkSize,
                                toTranslate.size())));
                    }
                    // Cloud LLMs (DeepSeek) handle a modest number of concurrent batches fine, so
                    // run 3 threads to parallelize. The earlier "429" concern was a misdiagnosis
                    // (the request had been routed to the local host, not DeepSeek).
                    final boolean concurrent =
                            translator instanceof com.pipepipe.translator.LlmTranslator;
                    final ExecutorService pool = concurrent ? Executors.newFixedThreadPool(16) : null;
                    final AtomicInteger done = new AtomicInteger(0);
                    final List<Future<?>> futures = new ArrayList<>();
                    try {
                        for (final List<String> chunk : chunks) {
                            final Runnable task = () -> {
                                List<String> trs = null;
                                try {
                                    trs = translator.translateBatch(chunk, target);
                                } catch (final Exception e) {
                                    trs = null;
                                }
                                if (trs == null || trs.size() != chunk.size()) {
                                    // Batch did not align -> translate this chunk one-by-one.
                                    trs = new ArrayList<>();
                                    for (final String t : chunk) {
                                        String tr = null;
                                        try {
                                            tr = translator.translate(t, target);
                                        } catch (final Exception e) {
                                            tr = null;
                                        }
                                        trs.add((tr == null || tr.trim().isEmpty()) ? t : tr);
                                    }
                                }
                                for (int k = 0; k < chunk.size(); k++) {
                                    cache.put(chunk.get(k), cleanTranslation(trs.get(k)));
                                }
                                final int processed = done.addAndGet(chunk.size());
                                listener.onProgress("翻译", Math.min(processed, toTranslate.size()),
                                        Math.max(1, toTranslate.size()));
                            };
                            if (concurrent) {
                                futures.add(pool.submit(task));
                            } else {
                                task.run();
                            }
                        }
                        if (concurrent) {
                            for (final Future<?> f : futures) {
                                f.get();
                            }
                        }
                    } catch (final Exception e) {
                        Log.e("DanmakuDownload", "concurrent translate failed", e);
                    } finally {
                        if (pool != null) {
                            pool.shutdown();
                        }
                    }
                }

                int i = 0;
                for (final BulletCommentsInfoItem item : source) {
                    final String text = item.getCommentText();
                    if (text != null && !text.isEmpty()) {
                        String translated = cache.get(text);
                        if (translated == null) {
                            translated = translator.translate(text, target);
                            if (translated == null || translated.trim().isEmpty()) {
                                translated = text;
                            }
                            cache.put(text, translated);
                        }
                        translated = cleanTranslation(translated);
                        item.setCommentText(translated + "\n" + text);
                        // Keep original + translation separately so the "show original" toggle can
                        // be changed later without re-translating.
                        records.add(new DanmakuCache.Record(text, translated,
                                item.getDuration() == null ? 0 : item.getDuration().toMillis(),
                                item.getPosition() == null
                                        ? BulletCommentsInfoItem.Position.REGULAR.name()
                                        : item.getPosition().name(),
                                item.getArgbColor(), item.getRelativeFontSize()));
                    }
                    out.add(item);
                    i++;
                    // Report progress only when this loop actually translates (ML Kit per-item).
                    // For batch engines (Google Web / LLM) the translations were already reported by
                    // the batch phase; this loop only assigns cached results, so don't re-spam the
                    // counter (it used to reset to 0/total and looked like re-translating).
                    if (!canBatch && (i % interval == 0 || i == total)) {
                        listener.onProgress("翻译", i, Math.max(1, total));
                    }
                }

                // 4) Cache the translated list so the next time this video is opened we can load
                // it instantly without downloading and re-translating.
                if (records != null && !records.isEmpty()) {
                    DanmakuCache.save(context, DanmakuCache.videoKey(serviceId, url), url, records);
                    Log.d("DanmakuDownload", "cached " + records.size() + " translated comments");
                }

                // 5) Done.
                Log.d("DanmakuDownload", "translation done: " + out.size() + " messages, calling onDone");
                listener.onProgress("完成", total, total);
                listener.onDone(out);
            } catch (final Exception e) {
                Log.e("DanmakuDownload", "download+translate failed", e);
                listener.onError(String.valueOf(e));
            }
        }, "danmaku-download").start();
    }

    /**
     * Download the ML Kit source/target models if they are missing. Returns {@code true} if at
     * least one model had to be downloaded (so the caller can show a notification), {@code false}
     * if they were already present.
     */
    private static boolean ensureMlKitModels() throws Exception {
        final RemoteModelManager manager = RemoteModelManager.getInstance();
        final DownloadConditions conditions = new DownloadConditions.Builder().build();
        boolean anyDownloaded = false;
        for (final String lang : new String[]{
                TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE}) {
            final TranslateRemoteModel model = new TranslateRemoteModel.Builder(lang).build();
            if (!Boolean.TRUE.equals(
                    com.google.android.gms.tasks.Tasks.await(manager.isModelDownloaded(model)))) {
                com.google.android.gms.tasks.Tasks.await(manager.download(model, conditions));
                anyDownloaded = true;
            }
        }
        return anyDownloaded;
    }

    private static String extractVideoId(final String url) {
        if (url == null) {
            return null;
        }
        final int i = url.indexOf("v=");
        if (i >= 0 && url.length() > i + 2) {
            final String v = url.substring(i + 2);
            final int amp = v.indexOf('&');
            return amp >= 0 ? v.substring(0, amp) : v;
        }
        if (url.contains("youtu.be/")) {
            return url.substring(url.lastIndexOf('/') + 1);
        }
        return null;
    }

    private static int parseInterval(final String s) {
        try {
            final int v = Integer.parseInt(s.trim());
            return v >= 1 ? v : 100;
        } catch (final Exception e) {
            return 100;
        }
    }

    /**
     * Keep a translation on one line: collapse any newlines the model left in (a batch line can
     * occasionally come back multi-line), and trim. Prevents the "original+translation+original"
     * sandwich look when combined with the two-line display.
     */
    private static String cleanTranslation(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.isEmpty() ? s.trim() : t;
    }
}
