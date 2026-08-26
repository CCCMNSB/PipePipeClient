package com.pipepipe.translator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Front door for danmaku translation. VOD goes through {@link #preload(Collection)} (translate
 * the whole list in timestamp order), while live/replay go through
 * {@link #resolve(String, TranslationListener)}. Danmaku are translated in BATCHES when the
 * backend supports it (so a slow LLM can keep pace with messages that live only a few seconds
 * on screen); otherwise it falls back to one request per text. Result notifications fire on a
 * worker thread; the caller hops to the UI thread.
 */
public class DanmakuTranslationManager {

    /** Called once a translation becomes available for a requested text. */
    public interface TranslationListener {
        void onTranslated(String text, String translation);
    }

    private static final int BATCH_SIZE = 8;

    private final boolean enabled;
    private final DanmakuTranslator translator;
    private final String targetLang;
    private final RateLimiter limiter;
    private final ExecutorService executor;
    private final Map<String, String> cache;
    private final Set<String> inFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, List<TranslationListener>> pending = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private volatile boolean running = true;

    public DanmakuTranslationManager(final DanmakuTranslator translator,
                                     final String targetLang,
                                     final int concurrency,
                                     final long minIntervalMillis,
                                     final int maxCacheSize,
                                     final boolean enabled) {
        this.enabled = enabled;
        this.translator = translator;
        this.targetLang = targetLang;
        this.limiter = new RateLimiter(minIntervalMillis);
        this.executor = Executors.newFixedThreadPool(Math.max(1, concurrency));
        this.cache = Collections.synchronizedMap(new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(final Map.Entry<String, String> eldest) {
                return size() > maxCacheSize;
            }
        });
        for (int i = 0; i < Math.max(1, concurrency); i++) {
            executor.submit(this::batchLoop);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Return the translation for {@code text} if already cached, otherwise schedule a
     * background translation and return {@code text} unchanged. When available, {@code listener}
     * is fired on a worker thread.
     */
    public String resolve(final String text, final TranslationListener listener) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        final String cached = cache.get(text);
        if (cached != null && !cached.isEmpty()) {
            return cached; // caller applies the translated text itself.
        }
        if (listener != null) {
            pending.computeIfAbsent(text, k -> new CopyOnWriteArrayList<>()).add(listener);
        }
        enqueue(text);
        return text;
    }

    public String resolve(final String text) {
        return resolve(text, null);
    }

    public void preload(final Collection<String> texts) {
        if (!enabled || texts == null) {
            return;
        }
        for (final String text : texts) {
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (cache.containsKey(text)) {
                continue;
            }
            enqueue(text);
        }
    }

    private void enqueue(final String text) {
        if (!inFlight.add(text)) {
            return;
        }
        queue.offer(text);
    }

    private void batchLoop() {
        while (running) {
            try {
                final List<String> batch = new ArrayList<>(BATCH_SIZE);
                final String first = queue.poll(2, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH_SIZE - 1);
                limiter.acquire();

                final List<String> results = translator.translateBatch(batch, targetLang);
                if (results != null && results.size() == batch.size()) {
                    for (int i = 0; i < batch.size(); i++) {
                        finish(batch.get(i), results.get(i));
                    }
                } else {
                    for (final String text : batch) {
                        try {
                            finish(text, translator.translate(text, targetLang));
                        } catch (Exception e) {
                            android.util.Log.e("DanmakuTranslator",
                                    "translate failed for [" + text + "]", e);
                            forListeners(text).clear();
                        } finally {
                            inFlight.remove(text);
                        }
                    }
                }
            } catch (Exception e) {
                // Pool got interrupted; loop again if still running.
                Thread.interrupted();
            }
        }
    }

    private void finish(final String text, final String result) {
        try {
            if (result != null && !result.trim().isEmpty()) {
                final String trimmed = result.trim();
                cache.put(text, trimmed);
                fireListeners(text, trimmed);
            } else {
                forListeners(text).clear();
            }
        } finally {
            inFlight.remove(text);
        }
    }

    private List<TranslationListener> forListeners(final String text) {
        return pending.computeIfAbsent(text, k -> new CopyOnWriteArrayList<>());
    }

    private void fireListeners(final String text, final String translation) {
        final List<TranslationListener> listeners = pending.remove(text);
        if (listeners != null) {
            for (final TranslationListener l : listeners) {
                l.onTranslated(text, translation);
            }
        }
    }

    public void shutdown() {
        running = false;
        executor.shutdown();
    }

    /**
     * Drop every cached translation and all queued/in-flight work. Called when the playing
     * video changes so each video's cache is fresh and memory stays tied to the current video
     * (the LRU cap is only a safety net; per-video clear is what really bounds memory).
     */
    public void clear() {
        cache.clear();
        pending.clear();
        inFlight.clear();
        queue.clear();
    }
}
