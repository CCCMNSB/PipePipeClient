package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.util.concurrent.atomic.AtomicLong;

/** Immutable metadata needed to construct a SABR MediaSource without owning a live session. */
public final class SabrSourceSpec {
    private static final AtomicLong NEXT_SOURCE_ID = new AtomicLong();

    private final long sourceId;
    @NonNull private final String videoId;
    @NonNull private final YoutubeSabrInfo info;
    @NonNull private final YoutubeSabrFormat audioFormat;
    @NonNull private final YoutubeSabrFormat videoFormat;
    @NonNull private final Localization localization;
    @NonNull private final byte[] audioInitializationData;
    @NonNull private final byte[] videoInitializationData;

    public SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final byte[] audioInitializationData,
                   @NonNull final byte[] videoInitializationData) {
        this.sourceId = NEXT_SOURCE_ID.incrementAndGet();
        this.videoId = videoId;
        this.info = info;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
        this.localization = localization;
        this.audioInitializationData = audioInitializationData.clone();
        this.videoInitializationData = videoInitializationData.clone();
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    long getSourceId() {
        return sourceId;
    }

    @NonNull
    public YoutubeSabrInfo getInfo() {
        return info;
    }

    @NonNull
    public YoutubeSabrFormat getAudioFormat() {
        return audioFormat;
    }

    @NonNull
    public YoutubeSabrFormat getVideoFormat() {
        return videoFormat;
    }

    @NonNull
    Localization getLocalization() {
        return localization;
    }

    @Nullable
    byte[] getInitializationData(final int itag) {
        if (itag == audioFormat.getItag()) {
            return audioInitializationData.clone();
        }
        if (itag == videoFormat.getItag()) {
            return videoInitializationData.clone();
        }
        return null;
    }

    long getDurationMs() {
        return Math.max(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs());
    }

    @NonNull
    YoutubeSabrStreamState newStreamState() {
        final YoutubeSabrStreamState state = new YoutubeSabrStreamState(audioFormat, videoFormat);
        state.ingestInitializationData(audioFormat, audioInitializationData);
        state.ingestInitializationData(videoFormat, videoInitializationData);
        return state;
    }
}
