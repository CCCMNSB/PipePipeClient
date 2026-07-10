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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class SabrSessionStore {

    private static final String TAG = "SabrSessionStore";

    private static final Map<SessionKey, Holder> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> PREFERRED_AUDIO = new ConcurrentHashMap<>();
    // Active MediaPeriods own leases. MediaSources outside the playback window are lightweight and
    // therefore do not prevent old sessions from being trimmed.
    // Mutated only under the class lock.
    private static final int MAX_SESSIONS = 3;
    private static final java.util.Deque<SessionKey> ORDER = new java.util.ArrayDeque<>();
    private static volatile LocalDomPoTokenProvider sharedProvider;

    private SabrSessionStore() {
    }

    private static final class SessionKey {
        @NonNull private final String videoId;
        private final long sourceId;
        private final int videoItag;
        private final int audioItag;
        @NonNull private final String audioTrackId;
        @NonNull private final YoutubeSabrClientProfile profile;

        SessionKey(final long sourceId,
                   @NonNull final String videoId,
                   @NonNull final YoutubeSabrInfo info,
                   @NonNull final YoutubeSabrFormat audioFormat,
                   @NonNull final YoutubeSabrFormat videoFormat) {
            this.videoId = videoId;
            this.sourceId = sourceId;
            this.videoItag = videoFormat.getItag();
            this.audioItag = audioFormat.getItag();
            this.audioTrackId = Objects.toString(audioFormat.getAudioTrackId(), "");
            this.profile = info.getProfile();
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SessionKey)) {
                return false;
            }
            final SessionKey that = (SessionKey) other;
            return sourceId == that.sourceId
                    && videoItag == that.videoItag
                    && audioItag == that.audioItag
                    && videoId.equals(that.videoId)
                    && audioTrackId.equals(that.audioTrackId)
                    && profile == that.profile;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceId, videoId, videoItag, audioItag, audioTrackId, profile);
        }
    }

    public static final class Lease implements AutoCloseable {
        @NonNull private final SessionKey key;
        @NonNull private final Holder holder;
        private final AtomicBoolean closed = new AtomicBoolean();

        Lease(@NonNull final SessionKey key, @NonNull final Holder holder) {
            this.key = key;
            this.holder = holder;
        }

        @NonNull
        Holder getHolder() {
            return holder;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                releaseLease(key, holder);
            }
        }
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
        @NonNull private final SessionKey key;
        @NonNull private final Context appContext;
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
        private final AtomicInteger leaseReferences = new AtomicInteger();
        private Object readerOwner;
        private long readerGeneration;
        private volatile SabrStreamPump pump;
        private volatile boolean invalidated;
        private volatile String stopReason;
        private volatile SabrLogicException terminalFailure;
        private long lastDiagnosticsAtMs;
        private long lastDiagnosticsPeakCachedBytes;

        Holder(@NonNull final Context appContext,
               @NonNull final String videoId,
               @NonNull final YoutubeSabrInfo info,
               @NonNull final YoutubeSabrSession session,
               @NonNull final YoutubeSabrFormat audioFormat,
               @NonNull final YoutubeSabrFormat videoFormat) {
            this.key = new SessionKey(0, videoId, info, audioFormat, videoFormat);
            this.appContext = appContext.getApplicationContext();
            this.videoId = videoId;
            this.info = info;
            this.session = session;
            this.audioFormat = audioFormat;
            this.videoFormat = videoFormat;
        }

        Holder(@NonNull final Context appContext,
               @NonNull final SabrSourceSpec spec,
               @NonNull final YoutubeSabrSession session) {
            this.key = new SessionKey(spec.getSourceId(), spec.getVideoId(), spec.getInfo(),
                    spec.getAudioFormat(), spec.getVideoFormat());
            this.appContext = appContext.getApplicationContext();
            this.videoId = spec.getVideoId();
            this.info = spec.getInfo();
            this.session = session;
            this.audioFormat = spec.getAudioFormat();
            this.videoFormat = spec.getVideoFormat();
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
            recordDiagnostics("seek positionMs=" + positionMs + " backward=" + backward);
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

        private void retainLease() {
            leaseReferences.incrementAndGet();
        }

        private boolean hasLeaseReferences() {
            return leaseReferences.get() > 0;
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
                    + ", leases=" + leaseReferences.get()
                    + ", trace=" + session.getDiagnosticTrace();
        }

        void failTerminal(@NonNull final SabrLogicException failure) {
            terminalFailure = failure;
            recordDiagnostics("terminal_failure message=" + failure.getMessage());
            evict(key, this, "terminal_failure message=" + failure.getMessage(), false);
        }

        void throwIfTerminal() throws SabrLogicException {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
        }

        void stop(@NonNull final String reason) {
            Log.w(TAG, "stop video=" + videoId + " reason=" + reason
                    + " leases=" + leaseReferences.get() + " activeTracks=" + hasActiveTracks()
                    + " pump=" + (pump == null ? "none" : pump.getStateName()));
            recordDiagnostics("stop reason=" + reason);
            stopReason = reason;
            session.addDiagnosticEvent("session_stop reason=" + reason
                    + " leases=" + leaseReferences.get() + " activeTracks=" + hasActiveTracks());
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

        void recordDiagnostics(@NonNull final String event) {
            SabrPlaybackDiagnostics.record(appContext, this, event);
            lastDiagnosticsAtMs = System.currentTimeMillis();
            lastDiagnosticsPeakCachedBytes = session.getPeakCachedBytes();
        }

        void recordDiagnosticsThrottled(@NonNull final String event) {
            final long now = System.currentTimeMillis();
            final long peakCachedBytes = session.getPeakCachedBytes();
            if (now - lastDiagnosticsAtMs >= 5_000
                    || peakCachedBytes != lastDiagnosticsPeakCachedBytes) {
                recordDiagnostics(event);
            }
        }
    }

    public static void updatePlayerTime(@NonNull final String videoId, final long playerTimeMs) {
        if (playerTimeMs < 0) {
            return;
        }
        for (final Map.Entry<SessionKey, Holder> entry : SESSIONS.entrySet()) {
            if (entry.getKey().videoId.equals(videoId) && entry.getValue().hasLeaseReferences()) {
                entry.getValue().setPlayerTimeMs(playerTimeMs);
                entry.getValue().recordDiagnosticsThrottled("progress");
            }
        }
    }

    public static void updatePlaybackRate(@NonNull final String videoId, final float playbackRate) {
        for (final Map.Entry<SessionKey, Holder> entry : SESSIONS.entrySet()) {
            if (entry.getKey().videoId.equals(videoId) && entry.getValue().hasLeaseReferences()) {
                entry.getValue().session.getStreamState().setPlaybackRate(playbackRate);
            }
        }
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

    @NonNull
    public static SabrSourceSpec createSourceSpec(@NonNull final String videoId,
                                                  final int preferredVideoItag,
                                                  @Nullable final YoutubeSabrInfo extractorInfo)
            throws IOException, ExtractionException {
        final String preferredAudioTrackId = PREFERRED_AUDIO.get(videoId);
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
        final YoutubeSabrSession initializationSession = new YoutubeSabrSession(
                info, audioFormat, videoFormat, null, null);
        final byte[] videoInitializationData = fetchInitializationData(
                initializationSession, videoFormat, localization, videoId);
        final byte[] audioInitializationData = fetchInitializationData(
                initializationSession, audioFormat, localization, videoId);
        return new SabrSourceSpec(videoId, info, audioFormat, videoFormat, localization,
                audioInitializationData, videoInitializationData);
    }

    @Nullable
    private static byte[] fetchInitializationData(@NonNull final YoutubeSabrSession session,
                                                  @NonNull final YoutubeSabrFormat format,
                                                  @NonNull final Localization localization,
                                                  @NonNull final String videoId) {
        try {
            return session.fetchInitializationDataFallback(format, localization);
        } catch (final IOException e) {
            Log.d(TAG, "Initialization metadata unavailable video=" + videoId
                    + " itag=" + format.getItag() + " message=" + e.getMessage());
            return null;
        }
    }

    @NonNull
    static Lease acquire(@NonNull final Context context, @NonNull final SabrSourceSpec spec)
            throws IOException, ExtractionException {
        final SessionKey key = new SessionKey(spec.getSourceId(), spec.getVideoId(), spec.getInfo(),
                spec.getAudioFormat(), spec.getVideoFormat());
        synchronized (SabrSessionStore.class) {
            final Holder current = SESSIONS.get(key);
            if (current != null) {
                current.retainLease();
                current.recordDiagnosticsThrottled("session_reuse");
                return new Lease(key, current);
            }
            final LocalDomPoTokenProvider provider = provider(context);
            final File spoolDirectory = new File(context.getApplicationContext().getCacheDir(),
                    "sabr-segments/" + spec.getVideoId() + '-' + System.nanoTime());
            final YoutubeSabrSession session = new YoutubeSabrSession(spec.getInfo(),
                    spec.getAudioFormat(), spec.getVideoFormat(), provider, spoolDirectory);
            attachPoToken(spec.getVideoId(), spec.getInfo(), provider, session);
            final Holder holder = new Holder(context, spec, session);
            seedInitializationData(holder, spec, spec.getAudioFormat());
            seedInitializationData(holder, spec, spec.getVideoFormat());
            SESSIONS.put(key, holder);
            ORDER.remove(key);
            ORDER.addLast(key);
            holder.retainLease();
            trimSessions(key);
            holder.recordDiagnostics("session_create");
            return new Lease(key, holder);
        }
    }

    private static void seedInitializationData(@NonNull final Holder holder,
                                               @NonNull final SabrSourceSpec spec,
                                               @NonNull final YoutubeSabrFormat format) {
        final byte[] data = spec.getInitializationData(format.getItag());
        if (data != null) {
            holder.setInitializationData(format.getItag(), data);
            holder.session.getStreamState().ingestInitializationData(format, data);
        }
    }

    private static void releaseLease(@NonNull final SessionKey key,
                                     @NonNull final Holder holder) {
        final int references = holder.leaseReferences.decrementAndGet();
        if (references <= 0) {
            evict(key, holder, "leases_released count=" + references, true);
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
        if (preferredTrackId == null) {
            return info.findBestAudioFormat();
        }
        YoutubeSabrFormat best = null;
        for (final YoutubeSabrFormat f : info.getFormats()) {
            if (!f.isAudio()) {
                continue;
            }
            if (!preferredTrackId.equals(f.getAudioTrackId())) {
                continue;
            }
            if (best == null || f.getBitrate() > best.getBitrate()) {
                best = f;
            }
        }
        return best != null ? best : info.findBestAudioFormat();
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
        return info.findLowestVideoFormat();
    }

    public static void evict(@NonNull final String videoId) {
        final List<Holder> holders = new ArrayList<>();
        synchronized (SabrSessionStore.class) {
            final java.util.Iterator<Map.Entry<SessionKey, Holder>> iterator =
                    SESSIONS.entrySet().iterator();
            while (iterator.hasNext()) {
                final Map.Entry<SessionKey, Holder> entry = iterator.next();
                if (entry.getKey().videoId.equals(videoId)) {
                    holders.add(entry.getValue());
                    ORDER.remove(entry.getKey());
                    iterator.remove();
                }
            }
        }
        for (final Holder holder : holders) {
            holder.stop("explicit");
        }
    }

    private static void trimSessions(@Nullable final SessionKey protectedKey) {
        while (true) {
            final Holder holder;
            synchronized (SabrSessionStore.class) {
                if (ORDER.size() <= MAX_SESSIONS) {
                    return;
                }
                SessionKey candidate = null;
                for (final SessionKey key : ORDER) {
                    final Holder current = SESSIONS.get(key);
                    if (!key.equals(protectedKey)
                            && current != null
                            && !current.hasActiveTracks()
                            && !current.hasLeaseReferences()) {
                        candidate = key;
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
                holder.stop("session_trim protectedVideo="
                        + (protectedKey == null ? null : protectedKey.videoId));
            }
        }
    }

    private static void evict(@NonNull final SessionKey key,
                              @Nullable final Holder expectedHolder,
                              @NonNull final String reason,
                              final boolean requireNoLeaseReferences) {
        final Holder holder;
        synchronized (SabrSessionStore.class) {
            holder = SESSIONS.get(key);
            if (holder == null
                    || (expectedHolder != null && holder != expectedHolder)
                    || (requireNoLeaseReferences && holder.hasLeaseReferences())) {
                return;
            }
            SESSIONS.remove(key);
            ORDER.remove(key);
        }
        if (holder != null) {
            holder.stop(reason);
        }
    }
}
