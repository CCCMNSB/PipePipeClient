package org.schabi.newpipe.player.bulletComments;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.pipepipe.translator.DanmakuTranslator;
import com.pipepipe.translator.LlmTranslator;

import org.schabi.newpipe.R;

/**
 * Reads the danmaku-translation settings and returns the chosen {@link DanmakuTranslator}.
 * Minimal: the "download + translate" flow is triggered by a button in the player, so there is
 * no global on/off here. Translators are cheap; a new one is built when settings change.
 */
public final class DanmakuTranslationBridge {

    private DanmakuTranslationBridge() {
    }

    public static DanmakuTranslator getTranslator(final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        final String provider = prefs.getString(
                context.getString(R.string.danmaku_translation_provider_key), "mlkit");
        final String targetLang = prefs.getString(
                context.getString(R.string.danmaku_translation_target_lang_key), "zh-CN");
        final String sourceLang = prefs.getString(
                context.getString(R.string.danmaku_source_lang_key), "auto");

        final DanmakuTranslator translator;
        if ("google".equals(provider)) {
            translator = new com.pipepipe.translator.GoogleWebTranslator();
        } else if ("llm".equals(provider) || "deepseek".equals(provider)) {
            // Two LLM presets, switched by "source": cloud (online API) or local (LAN e.g. Ollama).
            final String source = prefs.getString(
                    context.getString(R.string.danmaku_llm_source_key), "cloud");
            final String apiKey;
            final String model;
            String baseUrl;
            if ("local".equals(source)) {
                baseUrl = prefs.getString(
                        context.getString(R.string.danmaku_llm_local_base_url_key), "");
                model = prefs.getString(
                        context.getString(R.string.danmaku_llm_local_model_key), "qwen2.5:7b");
                apiKey = prefs.getString(
                        context.getString(R.string.danmaku_llm_local_key_key), "ollama");
            } else {
                apiKey = prefs.getString(context.getString(R.string.llm_api_key_key), "");
                model = prefs.getString(
                        context.getString(R.string.llm_model_key), "deepseek-chat");
                baseUrl = prefs.getString(
                        context.getString(R.string.llm_base_url_key), "https://api.deepseek.com");
                // legacy "deepseek" preset -> DeepSeek base/model if not overridden
                if ("deepseek".equals(provider) && baseUrl.isEmpty()) {
                    baseUrl = "https://api.deepseek.com";
                }
            }
            translator = new LlmTranslator(baseUrl, apiKey, model);
        } else {
            translator = new MlKitTranslator(sourceLang);
        }
        return translator;
    }

    public static String getTargetLang(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(context.getString(R.string.danmaku_translation_target_lang_key), "zh-CN");
    }

    public static boolean showOriginal(final Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.danmaku_show_original_key), true);
    }
}
