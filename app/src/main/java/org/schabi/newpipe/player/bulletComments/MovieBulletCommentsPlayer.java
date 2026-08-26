package org.schabi.newpipe.player.bulletComments;

import android.annotation.SuppressLint;
import android.util.Log;

import org.schabi.newpipe.extractor.bulletComments.BulletCommentsExtractor;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfo;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.util.ExtractorHelper;
import org.schabi.newpipe.views.BulletCommentsView;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Drives the danmaku overlay. Two modes:
 * - streaming: the extractor delivers comments as it goes (live/`isLive`).
 * - downloaded: a full, already-translated list (from {@link DanmakuDownloadHelper}) is drawn
 *   by its timeline position. This is the reliable path.
 */
public class MovieBulletCommentsPlayer {
    public MovieBulletCommentsPlayer(final BulletCommentsView bulletCommentsView) {
        super();
        this.bulletCommentsView = bulletCommentsView;
    }

    private final String TAG = "MovieBCPlayer";
    protected int serviceId;
    protected String url;
    protected final BulletCommentsView bulletCommentsView;
    protected List<BulletCommentsInfoItem> commentsInfoItems;
    private BulletCommentsExtractor extractor;
    public boolean isRoundPlayStream = false;
    private boolean useDownloadedList = false;

    public void setInitialData(final int serviceId, final String url) {
        this.serviceId = serviceId;
        this.url = url;
    }

    public final Duration INTERVAL = Duration.ofMillis(50);
    protected boolean isLoading = false;

    @SuppressLint("CheckResult")
    public void init() {
        this.bulletCommentsView.clearComments();
        isLoading = true;
        useDownloadedList = false;
        try {
            ExtractorHelper.getBulletCommentsInfo(this.serviceId, this.url, false)
                    .filter(Objects::nonNull)
                    .map((BulletCommentsInfo commentsInfo) -> {
                                extractor = commentsInfo.getBulletCommentsExtractor();
                                extractor.reconnect();
                                return commentsInfo.getRelatedItems();
                            }
                    )
                    .filter(Objects::nonNull)
                    .map(s -> s.stream().toArray(BulletCommentsInfoItem[]::new))
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe((BulletCommentsInfoItem[] newCommentsInfoItems) -> {
                                this.commentsInfoItems = Arrays.asList(newCommentsInfoItems);
                                Log.d(TAG, "Got " + newCommentsInfoItems.length + " comments." + this.url);
                                isLoading = false;
                            },
                            throwable -> Log.e(TAG, Log.getStackTraceString(throwable))
                    );
        } catch (final Exception e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }

    /**
     * Called when the download+translate job finishes: draw this pre-translated list aligned to
     * the video timeline.
     */
    public void loadDownloaded(final List<BulletCommentsInfoItem> translated) {
        if (translated == null || translated.isEmpty()) {
            return;
        }
        this.bulletCommentsView.clearComments();
        this.commentsInfoItems = translated;
        this.useDownloadedList = true;
        this.isLoading = false;
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (final BulletCommentsInfoItem it : translated) {
            final long d = it.getDuration() == null ? 0 : it.getDuration().toMillis();
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        Log.d(TAG, "loadDownloaded: " + translated.size() + " items, durationMs ["
                + (min == Long.MAX_VALUE ? "?" : min) + " .. " + (max == Long.MIN_VALUE ? "?" : max) + "]");
    }

    protected Duration lastPosition = Duration.ZERO;

    public void drawComments(final Duration drawUntilPosition) {
        if (isLoading) {
            return;
        }
        BulletCommentsInfoItem[] nextCommentsInfoItems;
        if (useDownloadedList || (extractor == null || !extractor.isLive())) {
            // Full list: filter by timeline window.
            if (drawUntilPosition.toString().equals("PT0.049S")) {
                return;
            }
            if (commentsInfoItems == null) {
                return;
            }
            nextCommentsInfoItems = commentsInfoItems.stream()
                    .filter(item -> {
                        final Duration d = item.getDuration();
                        return d.compareTo(lastPosition) >= 0
                                && d.compareTo(drawUntilPosition) < 0;
                    })
                    .toArray(BulletCommentsInfoItem[]::new);
        } else {
            try {
                nextCommentsInfoItems = extractor.getLiveMessages()
                        .stream().toArray(BulletCommentsInfoItem[]::new);
                if (drawUntilPosition.compareTo(Duration.ofSeconds(Long.MAX_VALUE)) != 0) {
                    extractor.setCurrentPlayPosition(drawUntilPosition.toMillis());
                }
            } catch (ParsingException e) {
                throw new RuntimeException(e);
            }
        }
        bulletCommentsView.drawComments(nextCommentsInfoItems, drawUntilPosition);
        this.lastPosition = drawUntilPosition;
        if (++listDrawLog % 100 == 1) {
            Log.d(TAG, "draw until=" + drawUntilPosition.toMillis()
                    + "ms window matched=" + nextCommentsInfoItems.length
                    + " useDownloaded=" + useDownloadedList);
        }
    }

    private int listDrawLog = 0;

    public void start(final Duration currentPosition) {
        this.lastPosition = currentPosition;
        bulletCommentsView.resumeComments();
    }

    public void pause() {
        bulletCommentsView.pauseComments();
    }

    public void clear() {
        bulletCommentsView.clearComments();
    }

    public void disconnect() {
        if (extractor != null && extractor.isLive()) {
            extractor.disconnect();
        }
    }

    public void complete(final Duration movieDuration) {
        if (lastPosition == null) {
            return;
        }
        final Duration minimumLastPosition = movieDuration.minus(INTERVAL);
        if (minimumLastPosition.compareTo(lastPosition) >= 0) {
            lastPosition = minimumLastPosition;
        }
        drawComments(Duration.ofSeconds(Long.MAX_VALUE));
    }

    public String getUrl() {
        return url;
    }
}
