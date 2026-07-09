package org.schabi.newpipe.player.datasource;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrMediaSegment;
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrSegmentRequest;
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class SabrSegmentDataSource implements DataSource {
    private static final String TAG = "SabrSegmentDataSource";

    private static final long WAIT_MS = 250;
    private static final long RECOVERY_FAILURE_MS = 10_000;
    private static final long REFETCH_AFTER_MS = 2_000;
    private static final long FORWARD_SEEK_AHEAD_MS = 30_000;

    private final SabrSessionStore.Holder holder;
    private final Object readerOwner;
    @Nullable
    private final YoutubeSabrFormat fixedFormat;
    private final Localization localization;
    private final boolean prependInit;

    @Nullable
    private Uri uri;
    @Nullable
    private byte[] data;
    private long bytesRemaining;
    private int pos;
    private boolean opened;
    private volatile boolean canceled;

    public SabrSegmentDataSource(final SabrSessionStore.Holder holder,
                                 final Object readerOwner,
                                 final YoutubeSabrFormat format,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.readerOwner = readerOwner;
        this.fixedFormat = format;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    public SabrSegmentDataSource(final SabrSessionStore.Holder holder,
                                 final Object readerOwner,
                                 final Localization localization,
                                 final boolean prependInit) {
        this.holder = holder;
        this.readerOwner = readerOwner;
        this.fixedFormat = null;
        this.localization = localization;
        this.prependInit = prependInit;
    }

    @Override
    public void addTransferListener(final TransferListener transferListener) {
    }

    @Override
    public long open(final DataSpec dataSpec) throws IOException {
        this.uri = dataSpec.uri;
        this.canceled = false;
        this.pos = (int) Math.max(0, dataSpec.position);
        final SabrSegmentRequest request = requestFromUri(dataSpec.uri);
        final YoutubeSabrFormat format = request.getFormat();
        Log.d(TAG, "open video=" + holder.videoId
                + " itag=" + format.getItag()
                + " uri=" + dataSpec.uri
                + " prependInit=" + prependInit);
        if (request.isInitializationSegment()) {
            this.data = getInitializationData(format);
        } else if (prependInit) {
            final byte[] init = getInitializationData(format);
            final byte[] media = awaitSegment(request);
            final byte[] both = new byte[init.length + media.length];
            System.arraycopy(init, 0, both, 0, init.length);
            System.arraycopy(media, 0, both, init.length, media.length);
            this.data = both;
        } else {
            this.data = awaitSegment(request);
        }
        this.opened = true;
        final int remaining = Math.max(0, data.length - pos);
        this.bytesRemaining = dataSpec.length == C.LENGTH_UNSET
                ? remaining : Math.min(dataSpec.length, remaining);
        Log.d(TAG, "opened video=" + holder.videoId
                + " itag=" + format.getItag()
                + " bytes=" + data.length
                + " remaining=" + remaining);
        return bytesRemaining;
    }

    private byte[] getInitializationData(final YoutubeSabrFormat format) throws IOException {
        final int itag = format.getItag();
        final byte[] cached = holder.getInitializationData(itag);
        if (cached != null) {
            return cached;
        }
        final SabrMediaSegment segment =
                holder.session.getCachedSegment(SabrSegmentRequest.initialization(format));
        if (segment != null) {
            final byte[] data = segment.getData();
            holder.setInitializationData(itag, data);
            return data;
        }
        return awaitSegment(SabrSegmentRequest.initialization(format));
    }

    @Override
    public int read(final byte[] target, final int offset, final int length) {
        if (length == 0) {
            return 0;
        }
        if (data == null || pos >= data.length || bytesRemaining <= 0) {
            return C.RESULT_END_OF_INPUT;
        }
        final int toCopy = (int) Math.min(Math.min(length, data.length - pos), bytesRemaining);
        System.arraycopy(data, pos, target, offset, toCopy);
        pos += toCopy;
        bytesRemaining -= toCopy;
        return toCopy;
    }

    private SabrSegmentRequest requestFromUri(final Uri u) throws IOException {
        final YoutubeSabrFormat format = formatFromUri(u);
        final String seg = u.getLastPathSegment();
        if (seg == null) {
            throw new SabrLogicException("Bad SABR segment uri: " + u);
        }
        if ("init".equals(seg)) {
            return SabrSegmentRequest.initialization(format);
        }
        try {
            return SabrSegmentRequest.media(format, Integer.parseInt(seg));
        } catch (final NumberFormatException e) {
            throw new SabrLogicException("Bad SABR segment uri: " + u, e);
        }
    }

    private YoutubeSabrFormat formatFromUri(final Uri u) throws IOException {
        if (fixedFormat != null) {
            return fixedFormat;
        }
        final String host = u.getHost();
        if (host == null) {
            throw new SabrLogicException("Bad SABR segment uri without itag: " + u);
        }
        final int itag;
        try {
            itag = Integer.parseInt(host);
        } catch (final NumberFormatException e) {
            throw new SabrLogicException("Bad SABR segment itag in uri: " + u, e);
        }
        if (holder.videoFormat.getItag() == itag) {
            return holder.videoFormat;
        }
        if (holder.audioFormat.getItag() == itag) {
            return holder.audioFormat;
        }
        throw new SabrLogicException("Unknown SABR segment itag=" + itag + " uri=" + u);
    }

    private byte[] awaitSegment(final SabrSegmentRequest request) throws IOException {
        final YoutubeSabrFormat format = request.getFormat();
        holder.throwIfTerminal();
        if (holder.isInvalidated()) {
            throw invalidatedException(request.getFormat());
        }
        final SabrStreamPump pump = holder.getPump(localization);
        final long readerGeneration = holder.getReaderGeneration(readerOwner);
        final long waitStart = System.currentTimeMillis();
        long recoveryAtMs = -1;
        long lastRecoveryAtMs = -1;
        boolean loggedWait = false;
        try {
            while (true) {
            if (canceled) {
                throw new IOException("SABR segment read canceled");
            }
            holder.throwIfTerminal();
            if (holder.isInvalidated()) {
                throw invalidatedException(request.getFormat());
            }
            if (holder.session.isBeyondEnd(request)) {
                Log.d(TAG, "beyond end video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                holder.session.addDiagnosticEvent("beyond_end itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                return new byte[0];
            }
            final IOException networkFailure = pump.takeNetworkFailure();
            if (networkFailure != null) {
                throw networkFailure;
            }
            if (request.isInitializationSegment()) {
                pump.requestInitialization(format);
            } else {
                pump.ensureStarted();
            }
            final SabrMediaSegment segment;
            if (request.isInitializationSegment()) {
                segment = pump.getCached(request);
            } else {
                try {
                    segment = holder.session.awaitCachedSegment(request, WAIT_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for SABR segment", e);
                }
            }
            if (segment != null) {
                Log.d(TAG, "cache hit video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " bytes=" + segment.getData().length);
                if (!segment.getHeader().isInitSegment()) {
                    holder.setReaderPositionMs(readerOwner,
                            holder.getReaderGeneration(readerOwner), format.getItag(),
                            segment.getHeader().getStartMs() + segment.getHeader().getDurationMs());
                }
                return segment.getData();
            }
            if (holder.session.isBeyondEnd(request)) {
                Log.d(TAG, "beyond end video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                holder.session.addDiagnosticEvent("beyond_end itag=" + format.getItag()
                        + " seq=" + request.getSequenceNumber());
                return new byte[0];
            }
            if (!request.isInitializationSegment()) {
                pump.requestSegmentDemand(request, readerOwner, readerGeneration);
            }
            if (!loggedWait && System.currentTimeMillis() - waitStart > 1000) {
                loggedWait = true;
                holder.session.addDiagnosticEvent("wait itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " pump=" + pump.getStateName()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs()
                        + " readerHeadMs=" + holder.getReaderHeadMs()
                        + " readerTailMs=" + holder.getReaderTailMs()
                        + " cachedBytes=" + holder.session.getCachedBytes());
                Log.d(TAG, "waiting video=" + holder.videoId
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs()
                        + " readerHeadMs=" + holder.getReaderHeadMs());
            }
            final long now = System.currentTimeMillis();
            if (now - waitStart > REFETCH_AFTER_MS
                    && (lastRecoveryAtMs < 0 || now - lastRecoveryAtMs > REFETCH_AFTER_MS)
                    && pump.canRecover()) {
                String recovery;
                if (request.isInitializationSegment()) {
                    recovery = "init";
                    pump.requestInitialization(format);
                } else {
                    final long edgeMs = holder.session.getStreamState().getMinBufferedEndMs();
                    final long segStartMs = holder.session.getStreamState()
                            .getSegmentStartMs(format, request.getSequenceNumber());
                    if (segStartMs < edgeMs) {
                        recovery = "rewind";
                        holder.setReaderPositionMs(readerOwner,
                                holder.getReaderGeneration(readerOwner), format.getItag(),
                                segStartMs);
                        pump.requestRefetchFrom(request);
                    } else if (segStartMs > edgeMs + FORWARD_SEEK_AHEAD_MS) {
                        recovery = "forward";
                        holder.setReaderPositionMs(readerOwner,
                                holder.getReaderGeneration(readerOwner), format.getItag(),
                                segStartMs);
                        pump.requestForwardSeekTo(request);
                    } else {
                        recovery = "near_edge_wait";
                    }
                }
                holder.session.addDiagnosticEvent("recovery type=" + recovery
                        + " itag=" + format.getItag()
                        + " init=" + request.isInitializationSegment()
                        + " seq=" + request.getSequenceNumber()
                        + " pump=" + pump.getStateName()
                        + " edgeMs=" + holder.session.getStreamState().getMinBufferedEndMs());
                if (recoveryAtMs < 0) {
                    recoveryAtMs = now;
                }
                lastRecoveryAtMs = now;
            }
            if (recoveryAtMs >= 0 && now - recoveryAtMs > RECOVERY_FAILURE_MS
                    && pump.canRecover()) {
                final SabrLogicException failure = new SabrLogicException(
                        "SABR made no progress after recovery for itag=" + format.getItag()
                                + ", init=" + request.isInitializationSegment()
                                + ", seq=" + request.getSequenceNumber()
                                + ", waitMs=" + (now - waitStart)
                                + ", pump=" + pump.getStateName()
                                + ", edgeMs="
                                + holder.session.getStreamState().getMinBufferedEndMs()
                                + ", readerHeadMs=" + holder.getReaderHeadMs()
                                + ", readerTailMs=" + holder.getReaderTailMs()
                                + ", cachedBytes=" + holder.session.getCachedBytes()
                                + ", trace=" + holder.session.getDiagnosticTrace());
                holder.failTerminal(failure);
                throw failure;
            }
            if (request.isInitializationSegment()) {
                try {
                    Thread.sleep(WAIT_MS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted awaiting SABR initialization", e);
                }
            }
        }
        } finally {
            if (!request.isInitializationSegment()) {
                pump.clearSegmentDemand(request, readerOwner, readerGeneration);
            }
        }
    }

    private SabrLogicException invalidatedException(final YoutubeSabrFormat format) {
        return new SabrLogicException("SABR session invalidated for video=" + holder.videoId
                + ", itag=" + format.getItag() + ", " + holder.getInvalidationDetails());
    }

    @Nullable
    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        canceled = true;
        data = null;
        opened = false;
    }
}
