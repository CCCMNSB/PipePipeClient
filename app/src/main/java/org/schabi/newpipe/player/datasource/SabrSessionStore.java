package org.schabi.newpipe.player.datasource;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class SabrSessionStore {

    private static final String TAG = "SabrSessionStore";

    private static final Map<String, Holder> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> PREFERRED_AUDIO = new ConcurrentHashMap<>();
    // Previous, current, and next video, matching MediaSourceManager's playback window.
    // Mutated only under the class lock.
    private static final int MAX_SESSIONS = 3;
    private static final java.util.Deque<String> ORDER = new java.util.ArrayDeque<>();
    private static volatile LocalDomPoTokenProvider sharedProvider;

    private SabrSessionStore() {
    }

    @NonNull
    private static LocalDomPoTokenProvider provider(@NonNull final Context context) {
        LocalDomPoTokenProvider p = sharedProvider;
        if (p == null) {
            synchronized (SabrSessionStore.class) {
                p = sharedProvider;
                if (p == null) {
                    p = new LocalDomPoTokenProvider(context.getApplicationContext());
                    sharedProvider = p;
                }
            }
        }
        return p;
    }

    public static final class Holder {
        @NonNull public final String videoId;
        @NonNull public final YoutubeSabrInfo info;
        @NonNull public final YoutubeSabrSession session;
        @NonNull public final YoutubeSabrFormat audioFormat;
        @NonNull public final YoutubeSabrFormat videoFormat;

        // Playback position is only a hint. Pump and eviction use reader positions.
        private volatile long playerTimeMs;
        private final Map<Integer, Long> readerPositions = new ConcurrentHashMap<>();
        private final Map<Object, Integer> activeTrackModes = new IdentityHashMap<>();
        private final Map<Integer, byte[]> initializationData = new ConcurrentHashMap<>();
        // Tracks currently selected by ExoPlayer. Background/audio-only playback disables the video
        // renderer, so requiring a video reader position there pins the SABR cache at the beginning.
        private final Set<Integer> activeReaderItags =
                Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
        private final AtomicInteger sourceReferences = new AtomicInteger();
        private Object readerOwner;
        private long readerGeneration;
        private volatile SabrStreamPump pump;
        private volatile boolean invalidated;
        private volatile String stopReason;
        private volatile SabrLogicException terminalFailure;

        Holder(@NonNull final String videoId,
               @NonNull final YoutubeSabrInfo info,
               @NonNull final YoutubeSabrSession session,
               @NonNull final YoutubeSabrFormat audioFormat,
               @NonNull final YoutubeSabrFormat videoFormat) {
            this.videoId = videoId;
            this.info = info;
            this.session = session;
            this.audioFormat = audioFormat;
            this.videoFormat = videoFormat;
        }

        public long getPlayerTimeMs() {
            return playerTimeMs;
        }

        void setPlayerTimeMs(final long playerTimeMs) {
            this.playerTimeMs = playerTimeMs;
        }

        /** A data source reports how far it has read (last served segment end, ms). */
        public synchronized void setReaderPositionMs(@NonNull final Object owner,
                                                     final long generation,
                                                     final int itag,
                                                     final long ms) {
            if (readerOwner == owner && readerGeneration == generation) {
                readerPositions.put(itag, ms);
            }
        }

        void setActiveTracks(@NonNull final Object owner,
                             final boolean videoActive,
                             final boolean audioActive) {
            final boolean trim;
            synchronized (this) {
                final int mode = (videoActive ? 1 : 0) | (audioActive ? 2 : 0);
                if (mode == 0) {
                    activeTrackModes.remove(owner);
                    if (readerOwner == owner) {
                        readerOwner = activeTrackModes.isEmpty() ? null
                                : activeTrackModes.keySet().iterator().next();
                        readerGeneration++;
                        readerPositions.clear();
                    }
                } else {
                    activeTrackModes.put(owner, mode);
                    if (readerOwner != owner) {
                        readerOwner = owner;
                        readerGeneration++;
                        readerPositions.clear();
                    }
                }
                applyActiveTracks();
                trim = activeTrackModes.isEmpty();
            }
            if (trim) {
                trimSessions(null);
            }
        }

        void releaseTracks(@NonNull final Object owner) {
            synchronized (this) {
                activeTrackModes.remove(owner);
                if (readerOwner == owner) {
                    readerOwner = activeTrackModes.isEmpty() ? null
                            : activeTrackModes.keySet().iterator().next();
                    readerGeneration++;
                    readerPositions.clear();
                }
                applyActiveTracks();
            }
            trimSessions(null);
        }

        synchronized void advanceReaderGeneration(@NonNull final Object owner) {
            if (readerOwner == owner) {
                readerGeneration++;
                readerPositions.clear();
            }
        }

        synchronized long getReaderGeneration(@NonNull final Object owner) {
            return readerOwner == owner ? readerGeneration : -1;
        }

        synchronized boolean isReaderGenerationActive(@NonNull final Object owner,
                                                      final long generation) {
            return readerOwner == owner && readerGeneration == generation;
        }

        private synchronized void anchorReaderPositionMs(final long positionMs) {
            if (readerOwner == null || activeReaderItags.isEmpty()) {
                return;
            }
            for (final int itag : activeReaderItags) {
                readerPositions.put(itag, positionMs);
            }
        }

        void requestSeek(final long positionMs, @NonNull final Localization localization) {
            final long previousPlayerTimeMs = playerTimeMs;
            final boolean backward = positionMs < previousPlayerTimeMs;
            setPlayerTimeMs(positionMs);
            anchorReaderPositionMs(positionMs);
            session.getStreamState().setSelectVideoFormatBeforeAudio(positionMs > 1_000);
            if (positionMs <= 1_000 && previousPlayerTimeMs <= 1_000) {
                return;
            }
            // Media3 may seek within its sample queue; still reposition the SABR session when the
            // target audio/video segments are not cached.
            final YoutubeSabrFormat targetFormat = videoFormat;
            final int sequence = session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(targetFormat, positionMs);
            final SabrSegmentRequest request = SabrSegmentRequest.media(targetFormat, sequence);
            final int audioSequence = session.getStreamState()
                    .getSegmentNumberAtOrAfterTimeMs(audioFormat, positionMs);
            final SabrSegmentRequest audioRequest = SabrSegmentRequest.media(
                    audioFormat, audioSequence);
            if (session.getCachedSegment(request) == null
                    || session.getCachedSegment(audioRequest) == null) {
                getPump(localization).requestSeekTo(request, backward, positionMs);
            } else {
                getPump(localization).noteSeekWithinCache();
            }
        }

        private synchronized boolean hasActiveTracks() {
            return !activeTrackModes.isEmpty();
        }

        byte[] getInitializationData(final int itag) {
            return initializationData.get(itag);
        }

        void setInitializationData(final int itag, @NonNull final byte[] data) {
            initializationData.put(itag, data);
        }

        void retainSource() {
            sourceReferences.incrementAndGet();
        }

        void releaseSource() {
            final int refs = sourceReferences.decrementAndGet();
            if (refs <= 0) {
                evict(videoId, this, "sources_released refs=" + refs);
            }
        }

        private void applyActiveTracks() {
            boolean videoActive = false;
            boolean audioActive = false;
            for (final int mode : activeTrackModes.values()) {
                videoActive |= (mode & 1) != 0;
                audioActive |= (mode & 2) != 0;
            }
            setTrackActive(videoFormat.getItag(), videoActive);
            setTrackActive(audioFormat.getItag(), audioActive);
            if (videoActive || audioActive) {
                session.getStreamState().setActiveTrackTypes(videoActive, audioActive);
            }
        }

        private void setTrackActive(final int itag, final boolean active) {
            if (active) {
                activeReaderItags.add(itag);
                return;
            }
            activeReaderItags.remove(itag);
            readerPositions.remove(itag);
        }

        public long getReaderHeadMs() {
            long head = 0;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position != null) {
                    head = Math.max(head, position);
                }
            }
            return head;
        }

        /** Zero until every selected track has read something, otherwise eviction can drop unread data. */
        public long getReaderTailMs() {
            if (activeReaderItags.isEmpty()) {
                return 0;
            }
            long tail = Long.MAX_VALUE;
            for (final int itag : activeReaderItags) {
                final Long position = readerPositions.get(itag);
                if (position == null) {
                    return 0;
                }
                tail = Math.min(tail, position);
            }
            return tail == Long.MAX_VALUE ? 0 : tail;
        }

        public boolean hasUnstartedActiveReader() {
            if (activeReaderItags.isEmpty()) {
                return false;
            }
            for (final int itag : activeReaderItags) {
                if (!readerPositions.containsKey(itag)) {
                    return true;
                }
            }
            return false;
        }

        synchronized SabrStreamPump getPump(@NonNull final Localization localization) {
            if (pump == null) {
                pump = new SabrStreamPump(session, this, localization);
            }
            return pump;
        }

        boolean isInvalidated() {
            return invalidated;
        }

        String getInvalidationDetails() {
            return "reason=" + stopReason
                    + ", refs=" + sourceReferences.get()
                    + ", trace=" + session.getDiagnosticTrace();
        }

        void failTerminal(@NonNull final SabrLogicException failure) {
            terminalFailure = failure;
            evict(videoId, this, "terminal_failure message=" + failure.getMessage());
        }

        void throwIfTerminal() throws SabrLogicException {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
        }

        void stop(@NonNull final String reason) {
            Log.w(TAG, "stop video=" + videoId + " reason=" + reason
                    + " refs=" + sourceReferences.get() + " activeTracks=" + hasActiveTracks()
                    + " pump=" + (pump == null ? "none" : pump.getStateName()));
            stopReason = reason;
            session.addDiagnosticEvent("session_stop reason=" + reason
                    + " refs=" + sourceReferences.get() + " activeTracks=" + hasActiveTracks());
            invalidated = true;
            synchronized (this) {
                activeTrackModes.clear();
                readerOwner = null;
                readerGeneration++;
                readerPositions.clear();
                applyActiveTracks();
            }
            final SabrStreamPump streamPump = pump;
            pump = null;
            if (streamPump != null) {
                streamPump.stop();
            } else {
                session.clearCache();
            }
        }

        boolean isBeyondEnd(@NonNull final SabrSegmentRequest request) {
            return session.isBeyondEnd(request);
        }
    }

    public static void updatePlayerTime(@NonNull final String videoId, final long playerTimeMs) {
        final Holder holder = SESSIONS.get(videoId);
        if (holder != null && playerTimeMs >= 0) {
            holder.setPlayerTimeMs(playerTimeMs);
        }
    }

    public static void updatePlaybackRate(@NonNull final String videoId, final float playbackRate) {
        final Holder holder = SESSIONS.get(videoId);
        if (holder != null) {
            holder.session.getStreamState().setPlaybackRate(playbackRate);
        }
    }

    private static boolean sessionMatchesItag(@NonNull final Holder holder,
                                              final int preferredVideoItag) {
        if (preferredVideoItag <= 0) {
            return true;
        }
        final YoutubeSabrFormat wanted = pickVideoFormat(holder.info, preferredVideoItag);
        return wanted != null && wanted.getItag() == holder.videoFormat.getItag();
    }

    private static boolean sessionMatchesAudioTrack(@NonNull final Holder holder,
                                                    @Nullable final String preferredTrackId) {
        return preferredTrackId == null
                || preferredTrackId.equals(holder.audioFormat.getAudioTrackId());
    }

    @NonNull
    public static void setPreferredAudioTrack(@NonNull final String videoId,
                                              @Nullable final String audioTrackId) {
        if (audioTrackId == null) {
            PREFERRED_AUDIO.remove(videoId);
        } else {
            PREFERRED_AUDIO.put(videoId, audioTrackId);
        }
    }

    public static Holder getOrCreate(@NonNull final Context context,
                                     @NonNull final String videoId,
                                     final int preferredVideoItag)
            throws IOException, ExtractionException {
        return getOrCreate(context, videoId, preferredVideoItag, null);
    }

    public static Holder getOrCreate(@NonNull final Context context,
                                     @NonNull final String videoId,
                                     final int preferredVideoItag,
                                     @Nullable final YoutubeSabrInfo extractorInfo)
            throws IOException, ExtractionException {
        final String preferredAudioTrackId = PREFERRED_AUDIO.get(videoId);
        final Holder existing = SESSIONS.get(videoId);
        if (existing != null && sessionMatchesItag(existing, preferredVideoItag)
                && sessionMatchesAudioTrack(existing, preferredAudioTrackId)) {
            return existing;
        }
        synchronized (SabrSessionStore.class) {
            final Holder current = SESSIONS.get(videoId);
            if (current != null) {
                if (sessionMatchesItag(current, preferredVideoItag)
                        && sessionMatchesAudioTrack(current, preferredAudioTrackId)) {
                    return current;
                }
                // A SABR session is locked to its selected formats.
                evict(videoId, null, "format_change oldVideoItag="
                        + current.videoFormat.getItag() + " requestedVideoItag="
                        + preferredVideoItag + " oldAudioTrack="
                        + current.audioFormat.getAudioTrackId() + " requestedAudioTrack="
                        + preferredAudioTrackId);
            }
            final Localization localization = new Localization("en", "US");
            final ContentCountry contentCountry = new ContentCountry("US");
            final YoutubeSabrInfo info = isUsableExtractorInfo(extractorInfo, videoId)
                    ? extractorInfo
                    : YoutubeSabrProbeFetch(videoId, localization, contentCountry);
            final YoutubeSabrFormat audioFormat = pickAudioFormat(info, preferredAudioTrackId);
            final YoutubeSabrFormat videoFormat = pickVideoFormat(info, preferredVideoItag);
            if (audioFormat == null || videoFormat == null) {
                throw new IOException("SABR: could not select audio/video formats for " + videoId);
            }
            final LocalDomPoTokenProvider provider = provider(context);
            final YoutubeSabrSession session =
                    new YoutubeSabrSession(info, audioFormat, videoFormat, provider);
            attachPoToken(videoId, info, provider, session);
            final Holder holder = new Holder(videoId, info, session, audioFormat, videoFormat);
            SESSIONS.put(videoId, holder);
            ORDER.remove(videoId);
            ORDER.addLast(videoId);
            trimSessions(videoId);
            return holder;
        }
    }

    private static void attachPoToken(@NonNull final String videoId,
                                      @NonNull final YoutubeSabrInfo info,
                                      @NonNull final SabrPoTokenProvider provider,
                                      @NonNull final YoutubeSabrSession session)
            throws IOException, ExtractionException {
        try {
            final byte[] token = provider.getPoToken(info, session.getStreamState());
            if (token == null || token.length == 0) {
                throw new SabrLogicException("SABR PO token provider returned no token for video="
                        + videoId);
            }
            session.getStreamState().setPoToken(token);
            session.addDiagnosticEvent("token_attach bytes="
                    + token.length);
        } catch (final IOException | ExtractionException e) {
            Log.w(TAG, "PO token attach failed video=" + videoId, e);
            session.addDiagnosticEvent("token_attach_failed type="
                    + e.getClass().getSimpleName() + " message=" + e.getMessage());
            throw e;
        } catch (final RuntimeException e) {
            Log.w(TAG, "PO token attach failed video=" + videoId, e);
            session.addDiagnosticEvent("token_attach_failed type="
                    + e.getClass().getSimpleName() + " message=" + e.getMessage());
            throw new SabrLogicException("SABR PO token attach failed for video=" + videoId, e);
        }
    }

    private static boolean isUsableExtractorInfo(@Nullable final YoutubeSabrInfo info,
                                                 @NonNull final String videoId) {
        return info != null
                && videoId.equals(info.getVideoId())
                && info.getServerAbrStreamingUrl() != null
                && !info.getServerAbrStreamingUrl().isEmpty()
                && !info.getFormats().isEmpty();
    }

    @NonNull
    private static YoutubeSabrInfo YoutubeSabrProbeFetch(@NonNull final String videoId,
                                                        @NonNull final Localization localization,
                                                        @NonNull final ContentCountry contentCountry)
            throws IOException, ExtractionException {
        return org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe.fetchSabrInfo(
                videoId, YoutubeSabrClientProfile.WEB, localization, contentCountry);
    }

    private static YoutubeSabrFormat pickAudioFormat(@NonNull final YoutubeSabrInfo info,
                                                     @Nullable final String preferredTrackId) {
        YoutubeSabrFormat aac = null;
        for (final YoutubeSabrFormat f : info.getFormats()) {
            if (!f.isAudio()) {
                continue;
            }
            final String mime = f.getMimeType();
            if (mime == null || !mime.contains("mp4")) {
                continue;
            }
            if (preferredTrackId != null && !preferredTrackId.equals(f.getAudioTrackId())) {
                continue;
            }
            if (aac == null) {
                aac = f;
                continue;
            }
            final boolean preferForTrack = f.isOriginalAudio() && !aac.isOriginalAudio();
            final boolean preferForPlain = f.isOriginalAudio() == aac.isOriginalAudio()
                    && isPlainAudioVariant(f) && !isPlainAudioVariant(aac);
            final boolean preferForDrc = f.isOriginalAudio() == aac.isOriginalAudio()
                    && isPlainAudioVariant(f) == isPlainAudioVariant(aac)
                    && !f.isDrc() && aac.isDrc();
            final boolean preferForBitrate = f.isOriginalAudio() == aac.isOriginalAudio()
                    && isPlainAudioVariant(f) == isPlainAudioVariant(aac)
                    && f.isDrc() == aac.isDrc()
                    && f.getBitrate() > aac.getBitrate();
            if (preferForTrack || preferForPlain || preferForDrc || preferForBitrate) {
                aac = f;
            }
        }
        if (aac == null && preferredTrackId != null) {
            return pickAudioFormat(info, null);
        }
        return aac != null ? aac : info.findBestAudioFormat();
    }

    private static boolean isPlainAudioVariant(@NonNull final YoutubeSabrFormat format) {
        final String xtags = format.getXtags();
        return xtags == null || xtags.isEmpty();
    }

    private static YoutubeSabrFormat pickVideoFormat(@NonNull final YoutubeSabrInfo info,
                                                     final int preferredItag) {
        if (preferredItag > 0) {
            for (final YoutubeSabrFormat f : info.getFormats()) {
                if (f.isVideo() && f.getItag() == preferredItag) {
                    return f;
                }
            }
        }
        return info.findBestVideoFormat();
    }

    public static void evict(@NonNull final String videoId) {
        evict(videoId, null, "explicit");
    }

    private static void trimSessions(@Nullable final String protectedVideoId) {
        while (true) {
            final Holder holder;
            synchronized (SabrSessionStore.class) {
                if (ORDER.size() <= MAX_SESSIONS) {
                    return;
                }
                String candidate = null;
                for (final String videoId : ORDER) {
                    final Holder current = SESSIONS.get(videoId);
                    if (!videoId.equals(protectedVideoId)
                            && current != null && !current.hasActiveTracks()) {
                        candidate = videoId;
                        break;
                    }
                }
                if (candidate == null) {
                    return;
                }
                holder = SESSIONS.remove(candidate);
                ORDER.remove(candidate);
            }
            if (holder != null) {
                holder.stop("session_trim protectedVideo=" + protectedVideoId);
            }
        }
    }

    private static void evict(@NonNull final String videoId,
                              @Nullable final Holder expectedHolder,
                              @NonNull final String reason) {
        final Holder holder;
        synchronized (SabrSessionStore.class) {
            holder = SESSIONS.get(videoId);
            if (holder == null || (expectedHolder != null && holder != expectedHolder)) {
                return;
            }
            SESSIONS.remove(videoId);
            ORDER.remove(videoId);
        }
        if (holder != null) {
            holder.stop(reason);
        }
    }
}
