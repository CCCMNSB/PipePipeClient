package org.schabi.newpipe.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import androidx.core.content.res.ResourcesCompat;
import androidx.preference.PreferenceManager;
import org.schabi.newpipe.R;
import org.schabi.newpipe.databinding.BulletCommentsPlayerBinding;
import org.schabi.newpipe.extractor.bulletComments.BulletCommentsInfoItem;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

public final class BulletCommentsView extends ConstraintLayout {
    private final String TAG = "BulletCommentsView";
    private SharedPreferences prefs;

    /**
     * Tuple of TextView and ObjectAnimator.
     */
    private static class AnimatedTextView {
        AnimatedTextView(final TextView textView, final ObjectAnimator animator) {
            this.textView = textView;
            this.animator = animator;
        }

        public final TextView textView;
        public final ObjectAnimator animator;
    }

    public BulletCommentsView(final Context context) {
        super(context);
        init(context);
    }

    public BulletCommentsView(final Context context,
                              final AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public BulletCommentsView(final Context context,
                              final AttributeSet attrs,
                              final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    //How to create custom view: https://maku77.github.io/android/ui/create-custom-view.html
    private void init(final Context context) {
        final View layout = LayoutInflater.from(context)
                .inflate(R.layout.bullet_comments_player, this);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        commentsDuration = prefs.getInt("top_bottom_bullet_comments_key", 8);
        durationFactor = (float) prefs.getInt("regular_bullet_comments_duration_key", 8) / (float) commentsDuration;
        outlineRadius = prefs.getInt("bullet_comments_outline_radius_key", 2);


        boolean limitMaxRows = prefs.getBoolean(context.getString(R.string.enable_max_rows_customization_key), false);
        if (limitMaxRows) {
            maxRowsTop = prefs.getInt(context.getString(R.string.max_bullet_comments_rows_top_key), 15);
            maxRowsBottom = prefs.getInt(context.getString(R.string.max_bullet_comments_rows_bottom_key), 15);
            maxRowsRegular = prefs.getInt(context.getString(R.string.max_bullet_comments_rows_bottom_key), 15);
        }

        font = prefs.getString("bullet_comments_font_key", "LXGW WenKai Screen");
        opacity = prefs.getInt("bullet_comments_opacity_key", 0xFF);
        //Not this: BulletCommentsPlayerBinding.inflate(LayoutInflater.from(context));
        binding = BulletCommentsPlayerBinding.bind(this);
        //This does not work. post(this::setLayout);
    }

    /**
     * Whether setLayout() is called.
     */
    private boolean layoutSet = false;

    /**
     * Needs additional space to draw comments longer than the view size.
     */
    private void setLayout() {
        final int additionalWidth = additionalSpaceRelative * getWidth();
        binding.bottomRight.getLayoutParams().width = additionalWidth;
        requestLayout();
        Log.i(TAG, "Additional width: " + additionalWidth
                + ", container width: " + binding.bulletCommentsContainer.getWidth());
    }

    /**
     * Auto-generated binding class.
     * https://developer.android.com/topic/libraries/data-binding/generated-binding
     */
    private BulletCommentsPlayerBinding binding;
    /**
     * Additional width of this ViewGroup relative to the parent ViewGroup
     * to show comments longer than the view size.
     */
    private final int additionalSpaceRelative = 4;

    /**
     * Number of comment rows.
     */
    private final int commentsRowsCount = 11;
    private int lastCalculatedCommentsRowsCount = 11;
    List<Long> rows = Collections.synchronizedList(new ArrayList<Long>());
    List<Map.Entry<Long, Integer>> rowsRegular = Collections.synchronizedList(new ArrayList<>());
    private final double commentRelativeTextSize = 1 / 13.5;
    PriorityQueue<BulletCommentsInfoItem> bulletCommentsInfoItemRegularPool = new PriorityQueue<>();
    PriorityQueue<BulletCommentsInfoItem> bulletCommentsInfoItemFixedPool = new PriorityQueue<>();

    /**
     * Duration of comments. get from preferences. key: "bullet_comments_key"
     */
    private int commentsDuration;
    private float durationFactor;
    private int outlineRadius;
    private String font;
    private int opacity; // 0~255, 0: hide
    private final List<AnimatedTextView> animatedTextViews = new ArrayList<>();
    // Key by reference identity (not InfoItem.equals, which may collapse all danmaku of a
    // video to one key); each on-screen danmaku is a distinct entry.
    private final Map<BulletCommentsInfoItem, TextView> activeComments =
            Collections.synchronizedMap(new IdentityHashMap<BulletCommentsInfoItem, TextView>());

    private int maxRowsTop = 1000000;
    private int maxRowsBottom = 1000000;
    private int maxRowsRegular = 1000000;

    /**
     * Clear all child views.
     */
    public void clearComments() {
        animatedTextViews.clear();
        binding.bulletCommentsContainer.removeAllViews();
    }

    /**
     * An alias for pauseComments() or resumeComments().
     *
     * @param pause whether to pause.
     */
    public void setPauseComments(final boolean pause) {
        if (pause) {
            pauseComments();
        } else {
            resumeComments();
        }
    }

    /**
     * Pause animation of comments.
     */
    public void pauseComments() {
        animatedTextViews.stream().forEach(s -> s.animator.pause());
    }

    /**
     * Resume animation of comments.
     */
    public void resumeComments() {
        animatedTextViews.stream().forEach(s -> s.animator.resume());
    }

    /**
     * Draw comments by creating textViews.
     *
     * @param items             comments.
     * @param drawUntilPosition
     */
    public void drawComments(@NonNull final BulletCommentsInfoItem[] items, Duration drawUntilPosition) {
        if (!layoutSet) {
            setLayout();
            layoutSet = true;
        }
//        if(bulletCommentsInfoItemPool.size() > 0
//                && !(drawUntilPosition.compareTo(Duration.ofSeconds(Long.MAX_VALUE)) == 0)
//                && bulletCommentsInfoItemPool.peek().getDuration().toMillis() - drawUntilPosition.toMillis() > 30000){
//            // should only apply when the stream is a YouTube live replay
//            bulletCommentsInfoItemPool.clear();
//        }
        bulletCommentsInfoItemRegularPool.addAll(Arrays.asList(items).stream().filter(x->x.getPosition() == BulletCommentsInfoItem.Position.REGULAR).collect(Collectors.toList()));
        bulletCommentsInfoItemFixedPool.addAll(Arrays.asList(items).stream().filter(x->x.getPosition() != BulletCommentsInfoItem.Position.REGULAR).collect(Collectors.toList()));
        //Log.v(TAG, "New comments count: " + items.length);
        final int height = getHeight();
        final int width = getWidth();
        final int calculatedCommentRowsCount = height / Math.min(height, width) * commentsRowsCount;
        if(calculatedCommentRowsCount != lastCalculatedCommentsRowsCount){
            lastCalculatedCommentsRowsCount = calculatedCommentRowsCount;
            rows.clear();
            rowsRegular.clear();
        }
        while(rowsRegular.size() < calculatedCommentRowsCount){
            rowsRegular.add(new AbstractMap.SimpleEntry<>(0L, 0));
        }
        while(rows.size() < calculatedCommentRowsCount){
            rows.add(0L);
        }
        drawCommentsByPool(bulletCommentsInfoItemRegularPool, drawUntilPosition, height, width, calculatedCommentRowsCount);
        drawCommentsByPool(bulletCommentsInfoItemFixedPool, drawUntilPosition, height, width, calculatedCommentRowsCount);
        //Log.v(TAG, "Child count: " + binding.bulletCommentsContainer.getChildCount());
        //Log.v(TAG, "AnimatedTextView count: " + (long) animatedTextViews.size());
    }

    public int tryToDrawComment(BulletCommentsInfoItem item, int calculatedCommentRowsCount, int width, boolean reallyDo) {
        long current = new Date().getTime();
        int row = -1;
        int comparedDuration = (int) (commentsDuration * 1000);
        if(item.getPosition().equals(BulletCommentsInfoItem.Position.TOP)
                || item.getPosition().equals(BulletCommentsInfoItem.Position.SUPERCHAT)){
            for(int i = 0; i < Math.min(maxRowsTop, calculatedCommentRowsCount) ;i++){
                long last = rows.get(i);
                if(current - last >= comparedDuration){
                    if (reallyDo) {
                        rows.set(i, current);
                    }
                    row = i;
                    break;
                }
            }
        } else if (item.getPosition().equals(BulletCommentsInfoItem.Position.REGULAR)) {
            for(int i = 0; i < Math.min(maxRowsRegular, calculatedCommentRowsCount) ;i++){
                long last_time = rowsRegular.get(i).getKey();
                long last_length = rowsRegular.get(i).getValue();
                long t = current - last_time;
                double t_all = comparedDuration * durationFactor;
                double lx = (last_length / 25.0 + 1) * width;
                double ly = (item.getCommentText().length() / 25.0 + 1) * width;
                double vx = lx / t_all;
                double vy = ly / t_all;
                if((vy - vx) * (t_all - t) < t * vx - (last_length / 25.0) * width && t * vx - (last_length / 25.0) * width > 0) {
                    if (reallyDo) {
                        rowsRegular.set(i, new AbstractMap.SimpleEntry<>(current, item.getCommentText().length()));
                    }
                    row = i;
                    break;
                }
            }
        } else {
            for(int i = calculatedCommentRowsCount - 1; i >= Math.max(0, calculatedCommentRowsCount - maxRowsBottom) ;i--){
                long last = rows.get(i);
                if(current - last >= comparedDuration){
                    if (reallyDo) {
                        rows.set(i, current);
                    }
                    row = i;
                    break;
                }
            }
        }
        return row;
    }

    private void drawCommentsByPool(PriorityQueue<BulletCommentsInfoItem> pool, Duration drawUntilPosition, int height, int width, int calculatedCommentRowsCount) {
        final Context context = binding.bulletCommentsContainer.getContext();
        while(!pool.isEmpty()
                && (drawUntilPosition.compareTo(Duration.ofSeconds(Long.MAX_VALUE)) == 0
                || pool.peek().getDuration().toMillis() < drawUntilPosition.toMillis())) {
            BulletCommentsInfoItem item = pool.peek();
            if (item.isLive() && tryToDrawComment(item, calculatedCommentRowsCount, width, false) == -1) {
                return;
            }
            pool.poll();
            //Create TextView.
            final TextView textView = new TextView(context);
            final Typeface fontToBeUsed;
            switch (font) {
                case "serif":
                    fontToBeUsed = Typeface.SERIF;
                    break;
                case "monospace":
                    fontToBeUsed = Typeface.MONOSPACE;
                    break;
                case "sans-serif":
                    fontToBeUsed = Typeface.SANS_SERIF;
                    break;
                case "LXGW WenKai Screen":
                    fontToBeUsed = ResourcesCompat.getFont(context, R.font.lxgw_wenkai);
                    break;
                default:
                    fontToBeUsed = Typeface.DEFAULT;
                    break;
            }
            textView.setGravity(View.TEXT_ALIGNMENT_CENTER);
            int color = item.getArgbColor();
            if(opacity != 0xFF) {
                color &= 0x00FFFFFF;
                color |= ((opacity & 0xFF) << 24);
            }
            textView.setTextColor(color);
            final String content = item.getCommentText();
            if (content.length() == 0) {
                continue;
            }
            // Two-line danmaku: line 1 = translated, line 2 = original (smaller + dimmer).
            final int newline = content.indexOf('\n');
            // Respect the "show original" toggle at render time, so switching it off hides the
            // original even for text already baked into the download/cache (translated\noriginal).
            final boolean showOrig = prefs.getBoolean(
                    getContext().getString(R.string.danmaku_show_original_key), true);
            final String display = (showOrig || newline < 0)
                    ? content : content.substring(0, newline);
            textView.setText(buildDisplay(display, color));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    (float) (Math.min(height, width) * commentRelativeTextSize * item.getRelativeFontSize()));
            textView.setMaxLines(showOrig && newline >= 0 ? 2 : 1);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                textView.setTypeface(Typeface.create(fontToBeUsed, Typeface.BOLD, item.getPosition().equals(BulletCommentsInfoItem.Position.SUPERCHAT)));
            } else {
                textView.setTypeface(Typeface.create(fontToBeUsed, Typeface.BOLD));
            }
            Paint paint = textView.getPaint();
            int shadowColor = Color.BLACK & 0x00FFFFFF;
            shadowColor |= ((opacity & 0xFF) << 24);
            paint.setShadowLayer(outlineRadius, 0, 0, shadowColor);
            textView.setLayerType(View.LAYER_TYPE_SOFTWARE, paint);

            final double commentSpace = 1 / 4.4 * height;
            if (true) {
                //Setting initial position by addView() won't work properly.
                //setTop(), ... etc. won't work.
                int row = tryToDrawComment(item, calculatedCommentRowsCount, width, true);
                if(row == -1){
                    continue;
                }
                textView.setX(width);
                //To get width with getWidth(), it should be called inside post().
                //or it returns 0.
                textView.post(() -> {
                    //Create ObjectAnimator.
                    final int textWidth = textView.getWidth();
                    final int textHeight = textView.getHeight();
                    ObjectAnimator animator;
                    if(!item.getPosition().equals(BulletCommentsInfoItem.Position.REGULAR)){
                        animator = ObjectAnimator.ofFloat(
                                textView,
                                View.TRANSLATION_X,
                                (float) ((width - textWidth)/2.0),
                                (float) ((width - textWidth)/2.0)
                        );
                    } else {
                        animator = ObjectAnimator.ofFloat(
                                textView,
                                View.TRANSLATION_X,
                                width,
                                -textWidth
                        );
                    }
                    // Clamp Y inside the view so a taller two-line (translated+original) comment
                    // is not pushed off the top/bottom edge and clipped.
                    float y = (float) (height * (0.5 + row) / calculatedCommentRowsCount
                            - textHeight / 2);
                    y = Math.max(0f, Math.min(y, height - textHeight));
                    textView.setY(y);

                    final AnimatedTextView animatedTextView = new AnimatedTextView(
                            textView, animator);
                    animatedTextViews.add(animatedTextView);
                    animator.setFrameDelay(1);
                    animator.setInterpolator(new LinearInterpolator());
                    animator.setDuration(item.getLastingTime() != -1?
                            item.getLastingTime():
                            (long) (commentsDuration * 1000 *
                                    (item.getPosition().equals(BulletCommentsInfoItem.Position.REGULAR)
                                            ? durationFactor:1)));
                    animator.addListener(new AnimatorListenerAdapter() {
                        public void onAnimationEnd(final Animator animation) {
                            binding.bulletCommentsContainer.removeView(textView);
                            animatedTextViews.remove(animatedTextView);
                            activeComments.remove(item);
                        }
                    });
                    animator.start();
                });
            } else {
                // TODO: Non-regular comments not implemented.
                //textView.setY(random.nextInt(maxTextViewPosY));
            }
            binding.bulletCommentsContainer.addView(textView);
            // Register synchronously so the fast ML Kit translation callback can find it.
            activeComments.put(item, textView);
        }
    }

    /**
     * Build the display text for a danmaku. If it contains a newline (translated + original),
     * the second line is rendered smaller and dimmer.
     */
    private CharSequence buildDisplay(final String content, final int color) {
        final int newline = content.indexOf('\n');
        if (newline < 0) {
            return content;
        }
        final SpannableString sp = new SpannableString(content);
        sp.setSpan(new RelativeSizeSpan(0.62f),
                newline + 1, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        final int dimColor = (color & 0x00FFFFFF) | ((((color >>> 24) & 0xFF) * 60 / 100) << 24);
        sp.setSpan(new ForegroundColorSpan(dimColor),
                newline + 1, content.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /**
     * Two-phase update: once a danmaku's translation arrives, swap its on-screen text to
     * "translated\noriginal" in place. Runs on the UI thread.
     */
    public void updateTranslation(final BulletCommentsInfoItem item, final String translation) {
        if (item == null || translation == null) {
            return;
        }
        final TextView tv = activeComments.get(item);
        Log.d("BulletCommentsView", "updateTranslation: found=" + (tv != null)
                + " tr=[" + translation + "]");
        if (tv == null) {
            return;
        }
        final String original = item.getCommentText();
        final int color = tv.getCurrentTextColor();
        final boolean showOriginal = prefs.getBoolean(
                getContext().getString(R.string.danmaku_show_original_key), true);
        if (showOriginal) {
            tv.setText(buildDisplay(translation + "\n" + original, color));
            tv.setMaxLines(2);
        } else {
            tv.setText(translation);
            tv.setMaxLines(1);
        }
    }
}
