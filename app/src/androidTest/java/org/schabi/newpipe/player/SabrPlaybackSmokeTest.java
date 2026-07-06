package org.schabi.newpipe.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
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
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.player.helper.LegacySubtitleRenderersFactory;
import org.schabi.newpipe.player.helper.LoadController;
import org.schabi.newpipe.player.helper.PlayerDataSource;
import org.schabi.newpipe.player.resolver.QualityResolver;
import org.schabi.newpipe.player.resolver.VideoPlaybackResolver;
import org.schabi.newpipe.player.datasource.SabrSessionStore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
 * https://www.youtube.com/watch?v=dQw4w9WgXcQ
 * </pre>
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public final class SabrPlaybackSmokeTest {
    private static final String DEFAULT_URL =
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final long PREPARE_TIMEOUT_SECONDS = 150;
    private static final long PLAYBACK_TIMEOUT_SECONDS = 30;
    private static final AtomicBoolean SUPPRESS_SABR_INITIALIZATION = new AtomicBoolean();
    private static final AtomicReference<Thread> INITIALIZATION_SUPPRESSOR =
            new AtomicReference<>();
    private static final AtomicReference<Throwable> INITIALIZATION_SUPPRESSION_FAILURE =
            new AtomicReference<>();

    @Test
    public void extractorToMedia3PlaysAndSeeks() throws Exception {
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

        final int maxVideoHeight = Integer.parseInt(arguments.getString("maxVideoHeight", "360"));
        final PlayerDataSource dataSource = new PlayerDataSource(context,
                DownloaderImpl.USER_AGENT, new DefaultBandwidthMeter.Builder(context).build());
        final VideoPlaybackResolver resolver = new VideoPlaybackResolver(context, dataSource,
                new BoundedQualityResolver(maxVideoHeight));
        final MediaSource mediaSource = resolver.resolve(info);
        assertNotNull("VideoPlaybackResolver returned no MediaSource", mediaSource);
        final boolean useAdaptiveInitFallback = Boolean.parseBoolean(
                arguments.getString("useAdaptiveInitFallback", "false"));
        final String recoveryScenario = arguments.getString("recoveryScenario", "playback");
        if ("stalledReader".equals(recoveryScenario)) {
            verifyStalledReaderReadAhead(getHolder(info.getId()));
            return;
        }
        if ("rewindState".equals(recoveryScenario)) {
            verifyRewindResetsSabrState(getHolder(info.getId()));
            return;
        }
        final boolean simulateEvictedRewind = "evictedRewind".equals(recoveryScenario);
        final SabrSessionStore.Holder injectedHolder = Boolean.parseBoolean(
                arguments.getString("simulateMissingInit", "false"))
                ? discardSabrInitialization(info.getId(), useAdaptiveInitFallback) : null;

        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch firstVideoFrame = new CountDownLatch(1);
        final CountDownLatch audioStarted = new CountDownLatch(1);
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
            if (injectedHolder != null) {
                final String trace = injectedHolder.session.getDiagnosticTrace();
                assertTrue("Missing initialization recovery was not used: " + trace,
                        trace.contains(useAdaptiveInitFallback
                                ? "initialization_fallback" : "pump_initialization"));
                final String audioInitializationMarker = useAdaptiveInitFallback
                        ? "initialization_fallback itag="
                                + injectedHolder.audioFormat.getItag()
                        : injectedHolder.audioFormat.getItag() + ":init";
                final String videoInitializationMarker = useAdaptiveInitFallback
                        ? "initialization_fallback itag="
                                + injectedHolder.videoFormat.getItag()
                        : injectedHolder.videoFormat.getItag() + ":init";
                assertTrue("Audio initialization was not returned: " + trace,
                        trace.contains(audioInitializationMarker));
                assertTrue("Video initialization was not returned: " + trace,
                        trace.contains(videoInitializationMarker));
                assertNull("Initialization fault injection failed",
                        INITIALIZATION_SUPPRESSION_FAILURE.get());
            }
            assertTrue("MediaCodec did not render a video frame",
                    firstVideoFrame.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting video", playerError.get());
            assertTrue("Audio output did not start",
                    audioStarted.await(PLAYBACK_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertNull("Player failed while starting audio", playerError.get());

            final long initialPositionMs = positionOf(playerRef.get());
            waitForPosition(playerRef.get(), initialPositionMs + 3_000,
                    PLAYBACK_TIMEOUT_SECONDS);
            assertNull("Player failed during linear playback", playerError.get());

            final long durationMs = durationOf(playerRef.get());
            final long seekPositionMs;
            if (simulateEvictedRewind) {
                seekPositionMs = durationMs == C.TIME_UNSET
                        ? 120_000 : Math.min(durationMs - 20_000,
                                Math.max(60_000, durationMs * 2 / 3));
                assertTrue("Video is too short for an eviction/rewind test: " + durationMs,
                        seekPositionMs >= 60_000);
            } else {
                seekPositionMs = durationMs == C.TIME_UNSET
                        ? 10_000 : Math.max(1_000, Math.min(30_000, durationMs / 2));
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
            waitForPosition(playerRef.get(), seekPositionMs + 2_000,
                    simulateEvictedRewind ? PLAYBACK_TIMEOUT_SECONDS : 15);
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
                waitForPosition(playerRef.get(), rewindPositionMs + 2_000,
                        PLAYBACK_TIMEOUT_SECONDS);
                assertNull("Player failed after evicted-segment rewind", playerError.get());
                final String trace = holder.session.getDiagnosticTrace();
                assertTrue("Evicted media segment did not enter rewind recovery: " + trace,
                        trace.contains("recovery type=rewind"));
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
            stopInitializationSuppression();
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
            final String throttleMarker = "pump_throttled cushionMs=10000"
                    + " unstartedReader=true";
            while (!trace.contains("pump_throttled ") && System.nanoTime() < deadlineNs) {
                Thread.sleep(250);
                trace = holder.session.getDiagnosticTrace();
            }
            assertTrue("Pump did not apply the startup read-ahead bound: " + trace,
                    trace.contains(throttleMarker));

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

    private static SabrSessionStore.Holder discardSabrInitialization(
            final String videoId, final boolean useAdaptiveInitFallback) throws Exception {
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
        holder.session.prepareForInitialization(holder.audioFormat);
        holder.session.prepareForInitialization(holder.videoFormat);
        assertNull(holder.session.getCachedSegment(audioInit));
        assertNull(holder.session.getCachedSegment(videoInit));
        if (useAdaptiveInitFallback) {
            startInitializationSuppression(holder, audioInit, videoInit);
        }
        return holder;
    }

    private static void startInitializationSuppression(
            final SabrSessionStore.Holder holder,
            final SabrSegmentRequest audioInit,
            final SabrSegmentRequest videoInit) {
        INITIALIZATION_SUPPRESSION_FAILURE.set(null);
        SUPPRESS_SABR_INITIALIZATION.set(true);
        final String audioFallback = "initialization_fallback itag="
                + holder.audioFormat.getItag();
        final String videoFallback = "initialization_fallback itag="
                + holder.videoFormat.getItag();
        final Thread suppressor = new Thread(() -> {
            try {
                while (SUPPRESS_SABR_INITIALIZATION.get()) {
                    holder.session.discardCachedSegment(audioInit);
                    holder.session.discardCachedSegment(videoInit);
                    final String trace = holder.session.getDiagnosticTrace();
                    if (trace.contains(audioFallback) && trace.contains(videoFallback)) {
                        break;
                    }
                    Thread.sleep(2);
                }
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (final Throwable failure) {
                INITIALIZATION_SUPPRESSION_FAILURE.compareAndSet(null, failure);
            } finally {
                SUPPRESS_SABR_INITIALIZATION.set(false);
            }
        }, "SabrInitSuppressor");
        suppressor.setDaemon(true);
        INITIALIZATION_SUPPRESSOR.set(suppressor);
        suppressor.start();
    }

    private static void stopInitializationSuppression() {
        SUPPRESS_SABR_INITIALIZATION.set(false);
        final Thread suppressor = INITIALIZATION_SUPPRESSOR.getAndSet(null);
        if (suppressor != null) {
            suppressor.interrupt();
            try {
                suppressor.join(1_000);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
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

    private static final class BoundedQualityResolver implements QualityResolver {
        private final int maxHeight;

        private BoundedQualityResolver(final int maxHeight) {
            this.maxHeight = maxHeight;
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
