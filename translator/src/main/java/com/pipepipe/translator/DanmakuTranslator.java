package com.pipepipe.translator;

import java.util.List;

/**
 * A single pluggable translation backend. Implementations must be thread-safe:
 * {@link DanmakuTranslationManager} may call them from a background pool.
 *
 * <p>Return {@code null} (or throw) on any failure; the caller falls back to the
 * original text so a transient API error never breaks playback.</p>
 */
public interface DanmakuTranslator {

    /**
     * Translate one piece of text into {@code targetLang}.
     *
     * @param text       the source text (may contain newlines).
     * @param targetLang target language tag, e.g. {@code zh-CN}, {@code en}, {@code ja}.
     * @return the translated text, or {@code null} if it could not be translated.
     * @throws Exception on network/parse failure.
     */
    String translate(String text, String targetLang) throws Exception;

    /**
     * Translate a batch of texts in a single request (used by slow backends like LLMs so the
     * frames can keep pace with live danmaku). Return {@code null} if batching is not
     * supported, in which case the manager falls back to per-text {@link #translate}.
     *
     * @return a list with exactly the same size/order as {@code texts}, or {@code null}.
     */
    default List<String> translateBatch(List<String> texts, String targetLang) throws Exception {
        return null;
    }
}
