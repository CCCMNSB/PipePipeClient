package org.schabi.newpipe.player.datasource;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
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
    @Nullable private final Future<byte[]> audioInitialization;
    @Nullable private final Future<byte[]> videoInitialization;
    @Nullable private volatile byte[] audioInitializationData;
    @Nullable private volatile byte[] videoInitializationData;
    private volatile boolean audioUsesFallbackTimeline;
    private volatile boolean videoUsesFallbackTimeline;

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
        this.audioInitialization = null;
        this.videoInitialization = null;
        this.audioInitializationData = cloneOrNull(audioInitializationData);
        this.videoInitializationData = cloneOrNull(videoInitializationData);
    }

    SabrSourceSpec(@NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat,
                   @NonNull final Localization localization,
                   @NonNull final Future<byte[]> audioInitialization,
                   @NonNull final Future<byte[]> videoInitialization) {
        this.sourceId = NEXT_SOURCE_ID.incrementAndGet();
        this.videoId = videoId;
        this.info = info;
        this.audioFormat = audioFormat;
        this.videoFormat = videoFormat;
        this.localization = localization;
        this.audioInitialization = audioInitialization;
        this.videoInitialization = videoInitialization;
        this.audioInitializationData = resolveIfDone(audioInitialization);
        this.videoInitializationData = resolveIfDone(videoInitialization);
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
            if (audioInitializationData == null) {
                audioInitializationData = resolveIfDone(audioInitialization);
            }
            return cloneOrNull(audioInitializationData);
        }
        if (itag == videoFormat.getItag()) {
            if (videoInitializationData == null) {
                videoInitializationData = resolveIfDone(videoInitialization);
            }
            return cloneOrNull(videoInitializationData);
        }
        return null;
    }

    @Nullable
    byte[] awaitInitializationData(@NonNull final YoutubeSabrFormat format) throws IOException {
        if (format.getItag() == audioFormat.getItag()) {
            if (audioInitializationData == null) {
                audioInitializationData = await(audioInitialization);
            }
            return cloneOrNull(audioInitializationData);
        }
        if (format.getItag() == videoFormat.getItag()) {
            if (videoInitializationData == null) {
                videoInitializationData = await(videoInitialization);
            }
            return cloneOrNull(videoInitializationData);
        }
        return null;
    }

    boolean usesFallbackTimeline(@NonNull final YoutubeSabrFormat format) {
        if (format.getItag() == audioFormat.getItag()) {
            return audioUsesFallbackTimeline;
        }
        if (format.getItag() == videoFormat.getItag()) {
            return videoUsesFallbackTimeline;
        }
        return false;
    }

    long getDurationMs() {
        return Math.max(audioFormat.getApproxDurationMs(), videoFormat.getApproxDurationMs());
    }

    @NonNull
    YoutubeSabrStreamState newStreamState() {
        final YoutubeSabrStreamState state = new YoutubeSabrStreamState(audioFormat, videoFormat);
        final byte[] audioData = getInitializationData(audioFormat.getItag());
        final byte[] videoData = getInitializationData(videoFormat.getItag());
        audioUsesFallbackTimeline = audioData == null;
        videoUsesFallbackTimeline = videoData == null;
        if (audioData != null) {
            state.ingestInitializationData(audioFormat, audioData);
        }
        if (videoData != null) {
            state.ingestInitializationData(videoFormat, videoData);
        }
        return state;
    }

    @Nullable
    private static byte[] resolveIfDone(@Nullable final Future<byte[]> future) {
        if (future == null || !future.isDone() || future.isCancelled()) {
            return null;
        }
        try {
            return cloneOrNull(future.get());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException e) {
            return null;
        }
    }

    @Nullable
    private static byte[] await(@Nullable final Future<byte[]> future) throws IOException {
        if (future == null) {
            return null;
        }
        try {
            return cloneOrNull(future.get());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted awaiting SABR initialization", e);
        } catch (final ExecutionException e) {
            // Preserve the existing fallback path: the session can still fetch initialization
            // metadata through SABR if this best-effort prefetch fails.
            return null;
        }
    }

    @Nullable
    private static byte[] cloneOrNull(@Nullable final byte[] data) {
        return data == null ? null : data.clone();
    }
}
