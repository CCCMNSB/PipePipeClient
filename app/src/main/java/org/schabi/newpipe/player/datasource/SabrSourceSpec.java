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
    @Nullable private final byte[] audioInitializationData;
    @Nullable private final byte[] videoInitializationData;

    SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat,
                   @NonNull final Localization localization,
                   @Nullable final byte[] audioInitializationData,
                   @Nullable final byte[] videoInitializationData) {
        this.sourceId = NEXT_SOURCE_ID.incrementAndGet();
        this.videoId = videoId;
        this.info = info;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
        this.localization = localization;
        this.audioInitializationData = cloneOrNull(audioInitializationData);
        this.videoInitializationData = cloneOrNull(videoInitializationData);
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
            return cloneOrNull(audioInitializationData);
        }
        if (itag == videoFormat.getItag()) {
            return cloneOrNull(videoInitializationData);
        }
        return null;
    }

    boolean usesFallbackTimeline(@NonNull final YoutubeSabrFormat format) {
        if (format.getItag() == audioFormat.getItag()) {
            return audioInitializationData == null;
        }
        if (format.getItag() == videoFormat.getItag()) {
            return videoInitializationData == null;
        }
        return false;
    }

    long getDurationMs() {
        return Math.max(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs());
    }

    @NonNull
    YoutubeSabrStreamState newStreamState() {
        final YoutubeSabrStreamState state = new YoutubeSabrStreamState(audioFormat, videoFormat);
        if (audioInitializationData != null) {
            state.ingestInitializationData(audioFormat, audioInitializationData);
        }
        if (videoInitializationData != null) {
            state.ingestInitializationData(videoFormat, videoInitializationData);
        }
        return state;
    }

    @Nullable
    private static byte[] cloneOrNull(@Nullable final byte[] data) {
        return data == null ? null : data.clone();
    }
}
