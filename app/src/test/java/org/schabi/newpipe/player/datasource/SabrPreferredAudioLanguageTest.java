package org.schabi.newpipe.player.datasource;

import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;

import java.lang.reflect.Constructor;
import java.util.Arrays;

public class SabrPreferredAudioLanguageTest {

    @Test
    public void preferredLanguageSelectsHighestBitrateRegionalTrack() throws Exception {
        final YoutubeSabrFormat original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrFormat portugueseLow = audioFormat(
                139, "pt-BR.4", "Portuguese (Brazil)", 96_000);
        final YoutubeSabrFormat portugueseHigh = audioFormat(
                251, "pt-BR.4", "Portuguese (Brazil)", 160_000);
        final YoutubeSabrInfo info = info(original, portugueseLow, portugueseHigh);

        assertSame(portugueseHigh, SabrSessionStore.pickAudioFormat(info, null, "pt"));
    }

    @Test
    public void explicitTrackOverridesPreferredLanguage() throws Exception {
        final YoutubeSabrFormat original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrFormat portuguese = audioFormat(
                251, "pt-BR.4", "Portuguese (Brazil)", 160_000);
        final YoutubeSabrFormat spanish = audioFormat(
                250, "es-ES.4", "Spanish (Spain)", 96_000);
        final YoutubeSabrInfo info = info(original, portuguese, spanish);

        assertSame(spanish,
                SabrSessionStore.pickAudioFormat(info, "es-ES.4", "pt"));
    }

    @Test
    public void missingPreferredLanguageFallsBackToOriginal() throws Exception {
        final YoutubeSabrFormat original = audioFormat(
                140, "en.4", "English (original)", 128_000);
        final YoutubeSabrFormat spanish = audioFormat(
                251, "es-ES.4", "Spanish (Spain)", 160_000);
        final YoutubeSabrInfo info = info(original, spanish);

        assertSame(original, SabrSessionStore.pickAudioFormat(info, null, "pt"));
    }

    private static YoutubeSabrFormat audioFormat(final int itag,
                                                  final String trackId,
                                                  final String displayName,
                                                  final int bitrate) throws Exception {
        final Constructor<YoutubeSabrFormat> constructor =
                YoutubeSabrFormat.class.getDeclaredConstructor(int.class, long.class,
                        String.class, String.class, String.class, String.class, boolean.class,
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, long.class, long.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(itag, 123456L, null, "audio/mp4", trackId,
                displayName, displayName.contains("original"), null, "AUDIO_QUALITY_MEDIUM",
                false, -1, -1, bitrate, 100_000L, 300_000L, null, -1L, -1L);
    }

    private static YoutubeSabrInfo info(final YoutubeSabrFormat... formats) throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(YoutubeSabrClientProfile.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, java.util.List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(YoutubeSabrClientProfile.MWEB, "video-id", "cpn",
                "2.test", "visitor", "https://sabr.test", null, Arrays.asList(formats));
    }
}
