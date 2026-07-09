package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.schabi.newpipe.App;
import org.schabi.newpipe.DownloaderImpl;
import org.schabi.newpipe.extractor.downloader.CancellableCall;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.downloader.StreamingResponse;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.ContentCountry;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrResponseDecoder;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.datasource.SabrSegmentDataSource;
import org.schabi.newpipe.player.helper.LegacySubtitleRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.datasource.SabrSessionStore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Online smoke test for the production Extractor -> SABR MediaSource -> Media3 pipeline.
 *
 * <p>Run only this test with:</p>
 * <pre>
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * org.schabi.newpipe.player.SabrPlaybackSmokeTest \
 *   -Pandroid.testInstrumentationRunnerArguments.url=\
 * https://www.youtube.com/watch?v=G-eNlqqkn1w
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrPlaybackSmokeTest {
    private static final int SMOKE_AUDIO_ITAG = 140;
    private static final int SMOKE_VIDEO_ITAG = 248;
    private static final int PROTO_WIRE_VARINT = 0;
    private static final int PROTO_WIRE_LENGTH_DELIMITED = 2;
    private static final String DEFAULT_URL =
            "https://www.youtube.com/watch?v=G-eNlqqkn1w";
    private static final int DEFAULT_MAX_VIDEO_HEIGHT = 720;
    private static final long DEFAULT_SEEK_POSITION_MS = (49 * 60 + 55) * 1000L;
    private static final long DEFAULT_LINEAR_PLAYBACK_MS = 3_000;
    private static final long DEFAULT_POST_SEEK_PLAYBACK_MS = 30_000;
    private static final long DEFAULT_POST_REWIND_PLAYBACK_MS = 30_000;
    private static final long PREPARE_TIMEOUT_SECONDS = 150;
    private static final long PLAYBACK_TIMEOUT_SECONDS = 75;

    @Test
    public void extractorToMedia3PlaysAndSeeks() throws Exception {
        runSmokeCase(SmokeCase.playback());
    }

    @Test
    public void recoversMissingInitializationFromPump() throws Exception {
        runSmokeCase(SmokeCase.missingInitialization());
    }

    @Test
    public void recoversEvictedSegmentRewind() throws Exception {
        runSmokeCase(SmokeCase.evictedRewind());
    }

    @Test
    public void boundsReadAheadForStalledReader() throws Exception {
        runSmokeCase(SmokeCase.stalledReader());
    }

    @Test
    public void rewindClearsBufferedStateAndCookie() throws Exception {
        runSmokeCase(SmokeCase.rewindState());
    }

    @Test
    public void seekToStreamEndReachesEnded() throws Exception {
        runSmokeCase(SmokeCase.endSeek());
    }

    @Test
    public void demandBackoffDoesNotBlockLoaderUntilServerDelay() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .part(SabrResponseDecoder.NEXT_REQUEST_POLICY, nextRequestPolicy(30_000))
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final long elapsedMs = harness.openMediaSegment(
                    SabrSegmentRequest.media(harness.videoFormat, 2), 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Demand path did not request the target segment: " + trace,
                    trace.contains("pump_demand itag=" + SMOKE_VIDEO_ITAG + " seq=2"));
            assertTrue("Demand path honored a long SABR backoff while loader waited: " + trace,
                    trace.contains("skip_backoff waitTarget backoffMs=30000"));
            assertTrue("Loader waited too long for demand retry: elapsedMs=" + elapsedMs
                    + " trace=" + trace, elapsedMs < 5_000);
        }
    }

    @Test
    public void demandIncompleteMediaResponseRetriesThroughPump() throws Exception {
        try (SabrSmokeHarness harness = SabrSmokeHarness.create()) {
            harness.downloader.enqueue(new UmpFixture()
                    .segment(1, SMOKE_VIDEO_ITAG, 1, 0, 30_000)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .mediaHeader(2, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .media(2)
                    .bytes());
            harness.downloader.enqueue(new UmpFixture()
                    .segment(3, SMOKE_VIDEO_ITAG, 2, 30_000, 5_000)
                    .bytes());

            final SabrSegmentRequest request = SabrSegmentRequest.media(harness.videoFormat, 2);
            harness.openMediaSegment(request, 5_000);

            final String trace = harness.holder.session.getDiagnosticTrace();
            assertTrue("Incomplete media response was not exercised: " + trace,
                    trace.contains("missing-media-end:2"));
            assertNotNull("Demand retry did not fetch the target segment: " + trace,
                    harness.holder.session.getCachedSegment(request));
        }
    }

    private static void runSmokeCase(final SmokeCase smokeCase) throws Exception {
        final Context context = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        assertTrue("The target process must use PipePipe's App initialization",
                context instanceof App);

        final Bundle arguments = InstrumentationRegistry.getArguments();
        final String url = arguments.getString("url", DEFAULT_URL);
        final String client = arguments.getString("youtubeClient", "mweb");
        NewPipe.setYoutubePlayerClient(client);

        // This is intentionally live extraction: the test should detect upstream protocol changes.
        final StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
        assertTrue("Extractor returned no SABR video stream for client=" + client,
                info.getVideoStreams().stream().anyMatch(SabrPlaybackSmokeTest::isSabr)
                        || info.getVideoOnlyStreams().stream()
                        .anyMatch(SabrPlaybackSmokeTest::isSabr));

        final int maxVideoHeight = Integer.parseInt(arguments.getString("maxVideoHeight",
                String.valueOf(DEFAULT_MAX_VIDEO_HEIGHT)));
        final String targetCodec = arguments.getString("targetCodec", "");
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, new DefaultBandwidthMeter.Builder(context).build());
        final VideoPlaybackResolver resolver = new VideoPlaybackResolver(context, dataSource,
                new BoundedQualityResolver(maxVideoHeight, targetCodec));
        final MediaSource mediaSource = resolver.resolve(info);
        assertNotNull("VideoPlaybackResolver returned no MediaSource", mediaSource);
        if (smokeCase.kind == SmokeCase.Kind.STALLED_READER) {
            try {
                verifyStalledReaderReadAhead(getHolder(info.getId()));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        if (smokeCase.kind == SmokeCase.Kind.REWIND_STATE) {
            try {
                verifyRewindResetsSabrState(getHolder(info.getId()));
            } finally {
                SabrSessionStore.evict(info.getId());
            }
            return;
        }
        final boolean simulateEvictedRewind = smokeCase.kind == SmokeCase.Kind.EVICTED_REWIND;
        final SabrSessionStore.Holder injectedHolder =
                smokeCase.kind == SmokeCase.Kind.MISSING_INITIALIZATION
                        ? discardSabrInitialization(info.getId())
                        : null;

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch firstVideoFrame = new CountDownLatch(1);
        final CountDownLatch audioStarted = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        final AtomicReference<CountDownLatch> seekProcessed =
                new AtomicReference<>(new CountDownLatch(1));
        final AtomicReference<PlaybackException> playerError = new AtomicReference<>();
        final AtomicReference<Long> seekPositionReported = new AtomicReference<>();
        final AtomicBoolean endedEarly = new AtomicBoolean();
        final AtomicReference<ExoPlayer> playerRef = new AtomicReference<>();
        final AtomicReference<SurfaceTexture> textureRef = new AtomicReference<>();
        final AtomicReference<Surface> surfaceRef = new AtomicReference<>();

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            final SurfaceTexture texture = new SurfaceTexture(0);
            final Surface surface = new Surface(texture);
            final LegacySubtitleRenderersFactory renderersFactory =
                    new LegacySubtitleRenderersFactory(context);
            renderersFactory.setEnableDecoderFallback(true);
            final ExoPlayer player = new ExoPlayer.Builder(context, renderersFactory)
                    .setTrackSelector(new DefaultTrackSelector(context))
                    .setLoadControl(new LoadController())
                    .build();
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(final int playbackState) {
                    if (playbackState == Player.STATE_READY) {
                        ready.countDown();
                    } else if (playbackState == Player.STATE_ENDED) {
                        endedEarly.set(true);
                        ended.countDown();
                    }
                }

                @Override
                public void onPlayerError(final PlaybackException error) {
                    playerError.compareAndSet(null, error);
                    ready.countDown();
                    firstVideoFrame.countDown();
                    audioStarted.countDown();
                }

                @Override
                public void onPositionDiscontinuity(final Player.PositionInfo oldPosition,
                                                    final Player.PositionInfo newPosition,
                                                    final int reason) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        seekPositionReported.set(newPosition.positionMs);
                        seekProcessed.get().countDown();
                    }
                }
            });
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override
                public void onRenderedFirstFrame(final EventTime eventTime,
                                                 final Object output,
                                                 final long renderTimeMs) {
                    firstVideoFrame.countDown();
                }

                @Override
                public void onAudioPositionAdvancing(final EventTime eventTime,
                                                     final long playoutStartSystemTimeMs) {
                    audioStarted.countDown();
                }
            });
            player.setVideoSurface(surface);
            player.setVolume(0f);
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
            textureRef.set(texture);
            surfaceRef.set(surface);
            playerRef.set(player);
        });

        try {
            assertTrue("Player did not reach READY within " + PREPARE_TIMEOUT_SECONDS + "s",
                    ready.await(PREPARE_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while preparing", playerError.get());
            assertTrue("MediaCodec did not render a video frame",
                    firstVideoFrame.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting video", playerError.get());
            assertTrue("Audio output did not start",
                    audioStarted.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting audio", playerError.get());
            if (injectedHolder != null) {
                verifyInitializationRecovery(injectedHolder);
            }
            if (smokeCase.kind == SmokeCase.Kind.END_SEEK) {
                verifyEndSeekReachesEnded(playerRef.get(), seekProcessed, seekPositionReported,
                        playerError, ended);
                return;
            }

            final long linearPlaybackMs = Long.parseLong(arguments.getString(
                    "linearPlaybackMs", String.valueOf(DEFAULT_LINEAR_PLAYBACK_MS)));
            final long initialPositionMs = positionOf(playerRef.get());
            waitForPosition(playerRef.get(), initialPositionMs + linearPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed during linear playback", playerError.get());

            final long postSeekPlaybackMs = Long.parseLong(arguments.getString(
                    "postSeekPlaybackMs", String.valueOf(DEFAULT_POST_SEEK_PLAYBACK_MS)));
            final long durationMs = durationOf(playerRef.get());
            final long seekPositionMs = seekPositionMs(arguments, durationMs,
                    simulateEvictedRewind ? Math.max(20_000, postSeekPlaybackMs)
                            : postSeekPlaybackMs);
            if (simulateEvictedRewind) {
                assertTrue("Video is too short for an eviction/rewind test: " + durationMs,
                        seekPositionMs >= 60_000);
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync(
                    () -> playerRef.get().seekTo(seekPositionMs));
            assertTrue("Player did not report processing the seek",
                    seekProcessed.get().await(10, TimeUnit.SECONDS));
            assertNotNull("Seek discontinuity did not report a new position",
                    seekPositionReported.get());
            assertTrue("Seek landed outside the expected position: requested=" + seekPositionMs
                            + " reported=" + seekPositionReported.get(),
                    Math.abs(seekPositionReported.get() - seekPositionMs) <= 1_000);
            waitForPosition(playerRef.get(), seekPositionMs + postSeekPlaybackMs,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed after seek", playerError.get());
            if (simulateEvictedRewind) {
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long rewindPositionMs = 10_000;
                discardCachedWindow(holder, holder.audioFormat, rewindPositionMs);
                discardCachedWindow(holder, holder.videoFormat, rewindPositionMs);
                final long edgeBeforeRewindMs = holder.session.getStreamState()
                        .getMinBufferedEndMs();
                assertTrue("Rewind target is not behind the SABR edge: target="
                                + rewindPositionMs + " edge=" + edgeBeforeRewindMs,
                        rewindPositionMs < edgeBeforeRewindMs);

                final CountDownLatch rewindProcessed = new CountDownLatch(1);
                seekProcessed.set(rewindProcessed);
                seekPositionReported.set(null);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> playerRef.get().seekTo(rewindPositionMs));
                assertTrue("Player did not process the backward seek",
                        rewindProcessed.await(10, TimeUnit.SECONDS));
                assertNotNull("Backward seek did not report a new position",
                        seekPositionReported.get());
                assertTrue("Backward seek landed outside the expected position: requested="
                                + rewindPositionMs + " reported=" + seekPositionReported.get(),
                        Math.abs(seekPositionReported.get() - rewindPositionMs) <= 1_000);
                final long postRewindPlaybackMs = Long.parseLong(arguments.getString(
                        "postRewindPlaybackMs",
                        String.valueOf(DEFAULT_POST_REWIND_PLAYBACK_MS)));
                waitForPosition(playerRef.get(), rewindPositionMs + postRewindPlaybackMs,
                        PLAYBACK_TIMEOUT_SECONDS);
                assertNull("Player failed after evicted-segment rewind", playerError.get());
                final String trace = holder.session.getDiagnosticTrace();
                // MediaPeriod now asks the pump to rewind as soon as it sees an out-of-buffer seek.
                // The old data-source timeout path ("recovery type=rewind") is only a fallback.
                assertTrue("SABR pump did not execute rewind recovery: " + trace,
                        trace.contains("pump_rewind"));
            }
            assertTrue("Content ended before playback and seek checks completed",
                    !endedEarly.get() || durationMs < 8_000);
            final String maxCachedBytesArgument = arguments.getString("maxCachedBytes");
            if (maxCachedBytesArgument != null) {
                final long maximum = Long.parseLong(maxCachedBytesArgument);
                final SabrSessionStore.Holder holder = getHolder(info.getId());
                final long observed = holder.session.getPeakCachedBytes();
                System.out.println("SABR_MEMORY height=" + holder.videoFormat.getHeight()
                        + " itag=" + holder.videoFormat.getItag()
                        + " peakCachedBytes=" + observed
                        + " maxCachedBytes=" + maximum);
                assertTrue("SABR cache exceeded bound: observed=" + observed
                        + " maximum=" + maximum, observed <= maximum);
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                final ExoPlayer player = playerRef.get();
                if (player != null) {
                    player.release();
                }
                final Surface surface = surfaceRef.get();
                if (surface != null) {
                    surface.release();
                }
                final SurfaceTexture texture = textureRef.get();
                if (texture != null) {
                    texture.release();
                }
            });
            SabrSessionStore.evict(info.getId());
        }
    }

    private static boolean isSabr(final VideoStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.SABR;
    }

    private static SabrSessionStore.Holder getHolder(final String videoId) throws Exception {
        final Field sessionsField = SabrSessionStore.class.getDeclaredField("SESSIONS");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, SabrSessionStore.Holder> sessions =
                (Map<String, SabrSessionStore.Holder>) sessionsField.get(null);
        final SabrSessionStore.Holder holder = sessions.get(videoId);
        assertNotNull("SABR session was not created", holder);
        return holder;
    }

    private static void verifyStalledReaderReadAhead(
            final SabrSessionStore.Holder holder) throws Exception {
        final Object readerOwner = new Object();
        final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "setActiveTracks", Object.class, boolean.class, boolean.class);
        final Method releaseTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                "releaseTracks", Object.class);
        final Method getPump = SabrSessionStore.Holder.class.getDeclaredMethod(
                "getPump", Localization.class);
        setActiveTracks.setAccessible(true);
        releaseTracks.setAccessible(true);
        getPump.setAccessible(true);
        setActiveTracks.invoke(holder, readerOwner, true, true);
        try {
            assertTrue("The test must begin with an unstarted active reader",
                    holder.hasUnstartedActiveReader());
            assertEquals("Reader head must remain at startup", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail must remain at startup", 0, holder.getReaderTailMs());

            final Object pump = getPump.invoke(holder, new Localization("en", "US"));
            final Method ensureStarted = pump.getClass().getDeclaredMethod("ensureStarted");
            ensureStarted.setAccessible(true);
            ensureStarted.invoke(pump);
            final long deadlineNs = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(PREPARE_TIMEOUT_SECONDS);
            String trace = holder.session.getDiagnosticTrace();
            while (!trace.contains("pump_throttled ") && System.nanoTime() < deadlineNs) {
                Thread.sleep(250);
                trace = holder.session.getDiagnosticTrace();
            }
            assertTrue("Pump did not apply the startup read-ahead bound: " + trace,
                    trace.contains("pump_throttled ")
                            && trace.contains("unstartedReader=true"));

            final int requestNumber = holder.session.getRequestNumber();
            final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
            final long cachedBytes = holder.session.getCachedBytes();
            Thread.sleep(1_500);
            assertEquals("Pump continued making SABR requests while the reader was stalled",
                    requestNumber, holder.session.getRequestNumber());
            assertEquals("Buffered edge advanced while the reader was stalled",
                    edgeMs, holder.session.getStreamState().getMinBufferedEndMs());
            assertEquals("Cache grew while the reader was stalled",
                    cachedBytes, holder.session.getCachedBytes());
            assertEquals("Reader head unexpectedly advanced", 0, holder.getReaderHeadMs());
            assertEquals("Reader tail unexpectedly advanced", 0, holder.getReaderTailMs());
        } finally {
            releaseTracks.invoke(holder, readerOwner);
        }
    }

    private static void discardCachedWindow(final SabrSessionStore.Holder holder,
                                            final YoutubeSabrFormat format,
                                            final long positionMs) {
        final int centerSequence = holder.session.getStreamState()
                .getSegmentNumberAtOrAfterTimeMs(format, positionMs);
        for (int sequence = Math.max(1, centerSequence - 1);
             sequence <= centerSequence + 2; sequence++) {
            holder.session.discardCachedSegment(SabrSegmentRequest.media(format, sequence));
        }
        assertNull("Fault injection did not evict target segment for itag=" + format.getItag(),
                holder.session.getCachedSegment(SabrSegmentRequest.media(format, centerSequence)));
    }

    private static void verifyInitializationRecovery(final SabrSessionStore.Holder holder) {
        final String trace = holder.session.getDiagnosticTrace();
        final String audioSabrMarker = holder.audioFormat.getItag() + ":init";
        final String videoSabrMarker = holder.videoFormat.getItag() + ":init";
        assertTrue("Audio initialization was not returned: " + trace,
                trace.contains(audioSabrMarker));
        assertTrue("Video initialization was not returned: " + trace,
                trace.contains(videoSabrMarker));
        assertTrue("Initialization fallback was unexpectedly used: " + trace,
                !trace.contains("initialization_fallback"));
    }

    private static void verifyRewindResetsSabrState(
            final SabrSessionStore.Holder holder) throws Exception {
        final Localization localization = new Localization("en", "US");
        holder.session.pumpOnce(localization);
        final YoutubeSabrFormat format = holder.videoFormat;
        final int maxSegmentBefore = holder.session.getStreamState().getMaxSegment(format);
        assertTrue("Initial SABR response did not advance media state", maxSegmentBefore > 1);

        final Field playbackCookie = holder.session.getStreamState().getClass()
                .getDeclaredField("playbackCookie");
        playbackCookie.setAccessible(true);
        playbackCookie.set(holder.session.getStreamState(), new byte[]{1, 2, 3, 4});
        assertNotNull("Fault injection did not install a stale playback cookie",
                holder.session.getStreamState().getPlaybackCookie());

        holder.session.prepareForRewind(SabrSegmentRequest.media(format, 1));
        assertEquals("Rewind did not move the buffered range before the target", 0,
                holder.session.getStreamState().getMaxSegment(format));
        assertNull("Rewind retained the stale SABR playback cookie",
                holder.session.getStreamState().getPlaybackCookie());
    }

    private static void verifyEndSeekReachesEnded(
            final ExoPlayer player,
            final AtomicReference<CountDownLatch> seekProcessed,
            final AtomicReference<Long> seekPositionReported,
            final AtomicReference<PlaybackException> playerError,
            final CountDownLatch ended) throws Exception {
        final long durationMs = durationOf(player);
        assertTrue("Cannot seek to stream end when duration is unset",
                durationMs != C.TIME_UNSET);
        assertTrue("Video is too short for an end-seek smoke test: " + durationMs,
                durationMs > 10_000);

        seekProcessed.set(new CountDownLatch(1));
        seekPositionReported.set(null);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> player.seekTo(durationMs));
        assertTrue("Player did not report processing the end seek",
                seekProcessed.get().await(10, TimeUnit.SECONDS));
        assertNotNull("End seek did not report a new position", seekPositionReported.get());
        assertTrue("End seek landed outside the expected tail: duration=" + durationMs
                        + " reported=" + seekPositionReported.get(),
                seekPositionReported.get() >= durationMs - 1_000
                        && seekPositionReported.get() <= durationMs);
        assertTrue("Player did not reach ENDED after seeking to stream end",
                ended.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertNull("Player failed after seeking to stream end", playerError.get());
    }

    private static SabrSessionStore.Holder discardSabrInitialization(
            final String videoId) throws Exception {
        final SabrSessionStore.Holder holder = getHolder(videoId);
        holder.session.pumpOnce(new Localization("en", "US"));
        final SabrSegmentRequest audioInit =
                SabrSegmentRequest.initialization(holder.audioFormat);
        final SabrSegmentRequest videoInit =
                SabrSegmentRequest.initialization(holder.videoFormat);
        assertNotNull("Initial SABR response had no audio init",
                holder.session.getCachedSegment(audioInit));
        assertNotNull("Initial SABR response had no video init",
                holder.session.getCachedSegment(videoInit));
        holder.session.discardCachedSegment(audioInit);
        holder.session.discardCachedSegment(videoInit);
        clearStoredInitializationData(holder);
        holder.session.prepareForInitialization(holder.audioFormat);
        holder.session.prepareForInitialization(holder.videoFormat);
        assertNull(holder.session.getCachedSegment(audioInit));
        assertNull(holder.session.getCachedSegment(videoInit));
        return holder;
    }

    private static void clearStoredInitializationData(
            final SabrSessionStore.Holder holder) throws Exception {
        final Field initializationData =
                SabrSessionStore.Holder.class.getDeclaredField("initializationData");
        initializationData.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<Integer, byte[]> values =
                (Map<Integer, byte[]>) initializationData.get(holder);
        values.remove(holder.audioFormat.getItag());
        values.remove(holder.videoFormat.getItag());
    }

    private static long positionOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getCurrentPosition()));
        return result.get();
    }

    private static long durationOf(final ExoPlayer player) {
        final AtomicReference<Long> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> result.set(player.getDuration()));
        return result.get();
    }

    private static long seekPositionMs(final Bundle arguments, final long durationMs,
                                       final long requiredTailMs) {
        assertTrue("Cannot use fixed seek position when duration is unset",
                durationMs != C.TIME_UNSET);
        final long seekPositionMs = Long.parseLong(arguments.getString("seekPositionMs",
                String.valueOf(DEFAULT_SEEK_POSITION_MS)));
        assertTrue("seekPositionMs must be positive: " + seekPositionMs, seekPositionMs > 0);
        assertTrue("Video is too short for seek target: duration=" + durationMs
                        + " target=" + seekPositionMs + " requiredTail=" + requiredTailMs,
                durationMs > seekPositionMs + requiredTailMs);
        return seekPositionMs;
    }

    private static void waitForPosition(final ExoPlayer player, final long targetMs,
                                        final long timeoutSeconds) throws Exception {
        final long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadlineNs) {
            if (positionOf(player) >= targetMs) {
                return;
            }
            Thread.sleep(250);
        }
        assertEquals("Playback position did not reach target", targetMs, positionOf(player));
    }

    private static YoutubeSabrFormat smokeFormat(final int itag, final boolean audio)
            throws Exception {
        final Constructor<YoutubeSabrFormat> constructor =
                YoutubeSabrFormat.class.getDeclaredConstructor(int.class, long.class,
                        String.class, String.class, String.class, String.class, boolean.class,
                        String.class, String.class, boolean.class, int.class, int.class,
                        int.class, long.class, long.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                itag,
                123456L,
                audio ? "audio-xtags" : "video-xtags",
                audio ? "audio/mp4" : "video/webm",
                audio ? "audio-track" : null,
                audio ? "English original" : null,
                audio,
                audio ? null : "1080p",
                audio ? "AUDIO_QUALITY_MEDIUM" : null,
                false,
                audio ? -1 : 1920,
                audio ? -1 : 1080,
                audio ? 128_000 : 2_000_000,
                100_000L,
                300_000L,
                "https://media.test/" + itag,
                -1L,
                -1L);
    }

    private static YoutubeSabrInfo smokeInfo(final YoutubeSabrFormat audioFormat,
                                             final YoutubeSabrFormat videoFormat)
            throws Exception {
        final Constructor<YoutubeSabrInfo> constructor =
                YoutubeSabrInfo.class.getDeclaredConstructor(YoutubeSabrClientProfile.class,
                        String.class, String.class, String.class, String.class, String.class,
                        String.class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(YoutubeSabrClientProfile.MWEB, "smoke-video", "cpn",
                "2.20250122.04.00", "visitor", "https://sabr.test",
                base64(new byte[]{1, 2, 3, 4}), Arrays.asList(audioFormat, videoFormat));
    }

    private static byte[] nextRequestPolicy(final int backoffMs) {
        return proto()
                .u64(1, 3_000)
                .u64(2, 4_000)
                .u64(3, 1_000)
                .u64(4, backoffMs)
                .u64(5, 500)
                .u64(6, 600)
                .bytes();
    }

    private static Proto proto() {
        return new Proto();
    }

    private static String base64(final byte[] bytes) {
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static final class SmokeCase {
        private enum Kind {
            PLAYBACK,
            MISSING_INITIALIZATION,
            EVICTED_REWIND,
            STALLED_READER,
            REWIND_STATE,
            END_SEEK
        }

        private final Kind kind;

        private SmokeCase(final Kind kind) {
            this.kind = kind;
        }

        private static SmokeCase playback() {
            return new SmokeCase(Kind.PLAYBACK);
        }

        private static SmokeCase missingInitialization() {
            return new SmokeCase(Kind.MISSING_INITIALIZATION);
        }

        private static SmokeCase evictedRewind() {
            return new SmokeCase(Kind.EVICTED_REWIND);
        }

        private static SmokeCase stalledReader() {
            return new SmokeCase(Kind.STALLED_READER);
        }

        private static SmokeCase rewindState() {
            return new SmokeCase(Kind.REWIND_STATE);
        }

        private static SmokeCase endSeek() {
            return new SmokeCase(Kind.END_SEEK);
        }
    }

    private static final class SabrSmokeHarness implements AutoCloseable {
        private final Downloader previousDownloader;
        private final Localization previousLocalization;
        private final ContentCountry previousContentCountry;
        private final FakeSabrDownloader downloader;
        private final SabrSessionStore.Holder holder;
        private final YoutubeSabrFormat videoFormat;
        private final Object readerOwner;

        private SabrSmokeHarness(final Downloader previousDownloader,
                                 final Localization previousLocalization,
                                 final ContentCountry previousContentCountry,
                                 final FakeSabrDownloader downloader,
                                 final SabrSessionStore.Holder holder,
                                 final YoutubeSabrFormat videoFormat,
                                 final Object readerOwner) {
            this.previousDownloader = previousDownloader;
            this.previousLocalization = previousLocalization;
            this.previousContentCountry = previousContentCountry;
            this.downloader = downloader;
            this.holder = holder;
            this.videoFormat = videoFormat;
            this.readerOwner = readerOwner;
        }

        private static SabrSmokeHarness create() throws Exception {
            final Downloader previousDownloader = NewPipe.getDownloader();
            final Localization previousLocalization = NewPipe.getPreferredLocalization();
            final ContentCountry previousContentCountry = NewPipe.getPreferredContentCountry();
            final FakeSabrDownloader downloader = new FakeSabrDownloader();
            NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT);
            final YoutubeSabrFormat audioFormat = smokeFormat(SMOKE_AUDIO_ITAG, true);
            final YoutubeSabrFormat videoFormat = smokeFormat(SMOKE_VIDEO_ITAG, false);
            final YoutubeSabrInfo info = smokeInfo(audioFormat, videoFormat);
            final YoutubeSabrSession session =
                    new YoutubeSabrSession(info, audioFormat, videoFormat);
            session.getStreamState().setVideoOnlyRequestMode();
            final Constructor<SabrSessionStore.Holder> constructor =
                    SabrSessionStore.Holder.class.getDeclaredConstructor(String.class,
                            YoutubeSabrInfo.class, YoutubeSabrSession.class,
                            YoutubeSabrFormat.class, YoutubeSabrFormat.class);
            constructor.setAccessible(true);
            final SabrSessionStore.Holder holder = constructor.newInstance(
                    "smoke-video", info, session, audioFormat, videoFormat);
            final Object readerOwner = new Object();
            final Method setActiveTracks = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "setActiveTracks", Object.class, boolean.class, boolean.class);
            setActiveTracks.setAccessible(true);
            setActiveTracks.invoke(holder, readerOwner, true, false);
            return new SabrSmokeHarness(previousDownloader, previousLocalization,
                    previousContentCountry, downloader, holder, videoFormat, readerOwner);
        }

        private long openMediaSegment(final SabrSegmentRequest request,
                                      final long timeoutMs) throws Exception {
            final AtomicReference<Throwable> failure = new AtomicReference<>();
            final CountDownLatch done = new CountDownLatch(1);
            final long startMs = System.currentTimeMillis();
            final Thread thread = new Thread(() -> {
                final SabrSegmentDataSource dataSource = new SabrSegmentDataSource(
                        holder, readerOwner, request.getFormat(), new Localization("en", "US"),
                        false);
                try {
                    dataSource.open(new DataSpec(Uri.parse("sabr://"
                            + request.getFormat().getItag() + '/'
                            + request.getSequenceNumber())));
                } catch (final Throwable e) {
                    failure.set(e);
                } finally {
                    dataSource.close();
                    done.countDown();
                }
            }, "SabrSmokeDemandOpen");
            thread.start();
            assertTrue("SABR smoke demand open timed out, trace="
                            + holder.session.getDiagnosticTrace(),
                    done.await(timeoutMs, TimeUnit.MILLISECONDS));
            if (failure.get() != null) {
                throw new AssertionError("SABR smoke demand open failed, trace="
                        + holder.session.getDiagnosticTrace(), failure.get());
            }
            return System.currentTimeMillis() - startMs;
        }

        @Override
        public void close() throws Exception {
            final Method stop = SabrSessionStore.Holder.class.getDeclaredMethod(
                    "stop", String.class);
            stop.setAccessible(true);
            stop.invoke(holder, "smoke_harness_close");
            NewPipe.init(previousDownloader, previousLocalization, previousContentCountry);
        }
    }

    private static final class FakeSabrDownloader extends Downloader {
        private final LinkedBlockingQueue<byte[]> responses = new LinkedBlockingQueue<>();

        private void enqueue(final byte[] body) {
            responses.add(body);
        }

        @Override
        public Response execute(final Request request) throws IOException {
            throw new IOException("Unexpected buffered request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }

        @Override
        public StreamingResponse postStreaming(final String url,
                                               final Map<String, List<String>> headers,
                                               final byte[] dataToSend,
                                               final Localization localization)
                throws IOException {
            final byte[] body = responses.poll();
            if (body == null) {
                throw new IOException("No queued SABR smoke response for " + url);
            }
            final Map<String, List<String>> responseHeaders = new HashMap<>();
            responseHeaders.put("Content-Type",
                    Collections.singletonList("application/vnd.yt-ump"));
            return new StreamingResponse(200, responseHeaders, new ByteArrayInputStream(body));
        }

        @Override
        public CancellableCall executeAsync(final Request request,
                                            final AsyncCallback callback)
                throws IOException, ReCaptchaException {
            throw new IOException("Unexpected async request in SABR smoke: "
                    + request.httpMethod() + " " + request.url());
        }
    }

    private static final class UmpFixture {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private UmpFixture segment(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence).media(headerId).mediaEnd(headerId);
        }

        private UmpFixture segment(final int headerId,
                                   final int itag,
                                   final int sequence,
                                   final long startMs,
                                   final long durationMs) {
            return mediaHeader(headerId, itag, sequence, startMs, durationMs)
                    .media(headerId)
                    .mediaEnd(headerId);
        }

        private UmpFixture mediaHeader(final int headerId, final int itag, final int sequence) {
            return mediaHeader(headerId, itag, sequence, (sequence - 1) * 5_000L, 5_000L);
        }

        private UmpFixture mediaHeader(final int headerId,
                                       final int itag,
                                       final int sequence,
                                       final long startMs,
                                       final long durationMs) {
            final Proto header = proto()
                    .u64(1, headerId)
                    .u64(3, itag)
                    .u64(4, 123456)
                    .u64(8, 0)
                    .u64(9, sequence)
                    .u64(11, Math.max(0, startMs))
                    .u64(12, Math.max(0, durationMs))
                    .u64(14, 4);
            return part(SabrResponseDecoder.MEDIA_HEADER, header.bytes());
        }

        private UmpFixture media(final int headerId) {
            final byte[] payload = new byte[]{10, 11, 12, 13};
            final byte[] part = new byte[payload.length + 1];
            part[0] = (byte) headerId;
            System.arraycopy(payload, 0, part, 1, payload.length);
            return part(SabrResponseDecoder.MEDIA, part);
        }

        private UmpFixture mediaEnd(final int headerId) {
            return part(SabrResponseDecoder.MEDIA_END, new byte[]{(byte) headerId});
        }

        private UmpFixture part(final int type, final byte[] payload) {
            writeVarint(output, type);
            writeVarint(output, payload.length);
            output.write(payload, 0, payload.length);
            return this;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static final class Proto {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        private Proto u64(final int field, final long value) {
            writeVarint(output, ((long) field << 3) | PROTO_WIRE_VARINT);
            writeVarint(output, value);
            return this;
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }

    private static void writeVarint(final ByteArrayOutputStream output, final long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            output.write((int) ((remaining & 0x7f) | 0x80));
            remaining >>>= 7;
        }
        output.write((int) remaining);
    }

    private static final class BoundedQualityResolver implements QualityResolver {
        private final int maxHeight;
        private final String targetCodec;

        private BoundedQualityResolver(final int maxHeight) {
            this(maxHeight, "");
        }

        private BoundedQualityResolver(final int maxHeight, final String targetCodec) {
            this.maxHeight = maxHeight;
            this.targetCodec = targetCodec == null ? "" : targetCodec.toLowerCase(Locale.ROOT);
        }

        @Override
        public int getDefaultResolutionIndex(final List<VideoStream> sortedVideos) {
            int lowestIndex = -1;
            int lowestHeight = Integer.MAX_VALUE;
            int preferredIndex = -1;
            int preferredHeight = -1;
            for (int i = 0; i < sortedVideos.size(); i++) {
                final VideoStream stream = sortedVideos.get(i);
                if (!isSabr(stream)) {
                    continue;
                }
                final String codec = stream.getCodec() == null
                        ? "" : stream.getCodec().toLowerCase(Locale.ROOT);
                if (!targetCodec.isEmpty() && !codec.isEmpty() && !codec.contains(targetCodec)) {
                    continue;
                }
                final int height = stream.getHeight();
                if (height > 0 && height < lowestHeight) {
                    lowestHeight = height;
                    lowestIndex = i;
                }
                if (height > preferredHeight && height <= maxHeight) {
                    preferredHeight = height;
                    preferredIndex = i;
                }
            }
            if (lowestIndex < 0) {
                throw new AssertionError("Resolver has no selectable SABR video stream");
            }
            return preferredIndex >= 0 ? preferredIndex : lowestIndex;
        }

        @Override
        public int getOverrideResolutionIndex(final List<VideoStream> sortedVideos,
                                              final int selectedIndex) {
            return selectedIndex;
        }

        @Override
        public int getCurrentAudioQualityIndex(final List<AudioStream> audioStreams) {
            return 0;
        }
    }
}
