package org.schabi.newpipe.player.bulletComments;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.pipepipe.translator.DanmakuTranslator;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * On-device (offline) translator based on Google ML Kit. It auto-detects the source
 * language with {@link LanguageIdentification}, then translates with the per-language
 * on-device model. Models are downloaded once (needs network the first time), so
 * subsequent translations work fully offline and free with no API key.
 *
 * <p>Requires Google Play services (the "Google" build of PipePipe). If a model is not
 * downloaded or the language is unsupported, it returns {@code null}, so the caller shows
 * the original danmaku.</p>
 */
public class MlKitTranslator implements DanmakuTranslator {

    private final String sourceLang; // "auto", or a BCP-47 tag like "ja"/"zh"/"en"
    private final Map<String, Translator> translatorCache = new HashMap<>();
    private final Object cacheLock = new Object();

    public MlKitTranslator() {
        this("auto");
    }

    public MlKitTranslator(final String sourceLang) {
        this.sourceLang = (sourceLang == null || sourceLang.trim().isEmpty()) ? "auto" : sourceLang;
    }

    @Override
    public String translate(final String text, final String targetLang) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        final String target = normalizeLang(targetLang);
        if (target == null) {
            return null;
        }

        // Resolve the source: use the configured language if set, else auto-detect.
        String source;
        if (!"auto".equalsIgnoreCase(this.sourceLang)) {
            source = normalizeLang(this.sourceLang);
        } else {
            final String detected;
            try {
                detected = Tasks.await(
                        LanguageIdentification.getClient().identifyLanguage(text),
                        5000, TimeUnit.MILLISECONDS);
            } catch (final Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }
            source = normalizeLang(detected);
        }
        // Skip when the source is unknown or it already matches the target.
        if (source == null || source.equals(target)) {
            return null;
        }

        try {
            final String translated = Tasks.await(getTranslator(source, target).translate(text),
                    10000, TimeUnit.MILLISECONDS);
            return (translated == null || translated.trim().isEmpty()) ? null : translated.trim();
        } catch (final Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private Translator getTranslator(final String source, final String target) {
        final String key = source + "->" + target;
        synchronized (cacheLock) {
            Translator translator = translatorCache.get(key);
            if (translator == null) {
                final TranslatorOptions options = new TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build();
                translator = Translation.getClient(options);
                translatorCache.put(key, translator);
            }
            return translator;
        }
    }

    /**
     * Map a BCP-47 tag to the two-letter ML Kit language code. Returns {@code null} for
     * unknown/unsupported codes so the caller falls back to the original text.
     */
    static String normalizeLang(final String code) {
        if (code == null) {
            return null;
        }
        final String c = code.toLowerCase().trim();
        if (c.startsWith("zh")) {
            return TranslateLanguage.CHINESE;   // "zh"
        }
        if (c.startsWith("en")) {
            return TranslateLanguage.ENGLISH;   // "en"
        }
        if (c.startsWith("ja")) {
            return TranslateLanguage.JAPANESE;  // "ja"
        }
        if (c.startsWith("ko")) {
            return TranslateLanguage.KOREAN;    // "ko"
        }
        if (c.startsWith("fr")) {
            return TranslateLanguage.FRENCH;
        }
        if (c.startsWith("de")) {
            return TranslateLanguage.GERMAN;
        }
        if (c.startsWith("es")) {
            return TranslateLanguage.SPANISH;
        }
        if (c.startsWith("pt")) {
            return TranslateLanguage.PORTUGUESE;
        }
        if (c.startsWith("it")) {
            return TranslateLanguage.ITALIAN;
        }
        if (c.startsWith("ru")) {
            return TranslateLanguage.RUSSIAN;
        }
        if (c.startsWith("vi")) {
            return TranslateLanguage.VIETNAMESE;
        }
        if (c.startsWith("th")) {
            return TranslateLanguage.THAI;
        }
        if (c.startsWith("id")) {
            return TranslateLanguage.INDONESIAN;
        }
        if (c.startsWith("hi")) {
            return TranslateLanguage.HINDI;
        }
        if (c.startsWith("ar")) {
            return TranslateLanguage.ARABIC;
        }
        if (c.startsWith("tr")) {
            return TranslateLanguage.TURKISH;
        }
        if (c.startsWith("pl")) {
            return TranslateLanguage.POLISH;
        }
        // Fall back to the first two letters; if ML Kit doesn't support it the call fails
        // and we return null upstream.
        return c.length() >= 2 ? c.substring(0, 2) : null;
    }
}
