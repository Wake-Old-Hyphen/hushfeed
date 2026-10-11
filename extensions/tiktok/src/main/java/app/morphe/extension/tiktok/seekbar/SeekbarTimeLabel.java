/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

/**
 * The time at the end of TikTok's progress bar row.
 *
 * <p>The row is TikTok's bar: a horizontal LinearLayout whose only child is the frame holding
 * the seek bar. Attaching gives that frame the row's leftover width and puts this label after it,
 * so the bar ends where the time starts. The frame's own layout params are kept and handed back
 * when the label comes out, which leaves the row exactly as TikTok built it.
 *
 * <p>The label stays in the row while it is attached, so the bar's length doesn't change between
 * videos or while a drag runs. What changes is whether it draws: it takes the seek bar's alpha
 * while the seek bar is visible and has a time to show, and none during a drag, when TikTok shows
 * its own big readout. The seek bar is asked before every frame is drawn, because TikTok hides it
 * in several places without going through the bar's progress method.
 *
 * <p>Screen readers skip it. The seek bar already reports the progress, and a label that changes
 * every second would only interrupt.
 */
@SuppressLint({"ViewConstructor", "AppCompatCustomView"})
final class SeekbarTimeLabel extends TextView implements ViewTreeObserver.OnPreDrawListener {
    static final float TEXT_SP = 11f;
    /** The row is 18dp tall on the feed; a larger font scale stops here so the digits fit. */
    static final float TEXT_CAP_DP = 14f;
    static final float GAP_DP = 4f;
    /** The same distance TikTok leaves between the bar and the screen edge. */
    static final float EDGE_DP = 12f;
    static final int TEXT_COLOR = 0xE6FFFFFF;
    static final int SHADOW_COLOR = 0x99000000;

    private final View frame;
    private final ViewGroup.LayoutParams frameParams;
    @Nullable private ProgressBar seekBar;
    @Nullable private ViewTreeObserver observer;

    @Nullable private String aid;
    private long durationMs;
    private boolean hidden;
    private boolean dragging;
    /** Whether the last answer drew the label; null before the first. */
    @Nullable private Boolean drawn;
    private String sizedFor = "";

    private static boolean reportedShape;

    private SeekbarTimeLabel(Context context, View frame, ViewGroup.LayoutParams frameParams) {
        super(context);
        this.frame = frame;
        this.frameParams = frameParams;
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        float text = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, metrics);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(text, TEXT_CAP_DP * metrics.density));
        setTextColor(TEXT_COLOR);
        setShadowLayer(2f * metrics.density, 0f, 0.5f * metrics.density, SHADOW_COLOR);
        // Every digit the same width, so the time doesn't shuffle sideways as it counts.
        setFontFeatureSettings("tnum");
        // One line, but not setSingleLine: that scrolls sideways, and a sideways-scrolling line
        // set to the end is laid out against a very wide edge, far off the label (#90). The
        // width always fits the time, so one line never has to wrap.
        setMaxLines(1);
        setHorizontallyScrolling(false);
        setIncludeFontPadding(false);
        setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        setTextDirection(View.TEXT_DIRECTION_LTR);
        setPaddingRelative(Math.round(GAP_DP * metrics.density), 0,
                Math.round(EDGE_DP * metrics.density), 0);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        setAlpha(0f);
    }

    /** The label already in this bar, or null. */
    @Nullable
    static SeekbarTimeLabel find(LinearLayout bar) {
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (child instanceof SeekbarTimeLabel) return (SeekbarTimeLabel) child;
        }
        return null;
    }

    /**
     * The bar's label, added the first time. Null when the bar isn't the one-frame row it is on
     * 47.1.4, which is left alone rather than guessed at.
     */
    @Nullable
    static SeekbarTimeLabel attach(LinearLayout bar) {
        SeekbarTimeLabel label = find(bar);
        if (label != null) return label;
        if (bar.getOrientation() != LinearLayout.HORIZONTAL || bar.getChildCount() != 1) {
            refused(bar);
            return null;
        }
        View frame = bar.getChildAt(0);
        ViewGroup.LayoutParams original = frame.getLayoutParams();
        if (original == null) {
            refused(bar);
            return null;
        }

        LinearLayout.LayoutParams squeezed;
        if (original instanceof LinearLayout.LayoutParams) {
            squeezed = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) original);
        } else if (original instanceof ViewGroup.MarginLayoutParams) {
            squeezed = new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams) original);
        } else {
            squeezed = new LinearLayout.LayoutParams(original);
        }
        squeezed.width = 0;
        squeezed.weight = 1f;

        label = new SeekbarTimeLabel(bar.getContext(), frame, original);
        label.sizedFor = SeekbarTime.widest(0L);
        LinearLayout.LayoutParams own = new LinearLayout.LayoutParams(
                label.widthFor(label.sizedFor), ViewGroup.LayoutParams.MATCH_PARENT);
        own.gravity = Gravity.CENTER_VERTICAL;
        frame.setLayoutParams(squeezed);
        bar.addView(label, own);
        return label;
    }

    /** Says once a launch which bar shape was left alone, so a device log shows a new layout. */
    private static void refused(LinearLayout bar) {
        if (reportedShape) return;
        reportedShape = true;
        Logger.printInfo(() -> "Seekbar time: left a bar alone, " + shapeOf(bar));
    }

    /** The row's orientation and its children's classes, as the refusal reports them. */
    static String shapeOf(LinearLayout bar) {
        StringBuilder shape = new StringBuilder(
                bar.getOrientation() == LinearLayout.HORIZONTAL ? "horizontal [" : "vertical [");
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            if (i > 0) shape.append(", ");
            shape.append(child.getClass().getName());
            if (child.getLayoutParams() == null) shape.append(" without layout params");
        }
        return shape.append(']').toString();
    }

    /** Takes the label out and gives the frame back the layout params TikTok gave it. */
    static void removeFrom(LinearLayout bar) {
        SeekbarTimeLabel label = find(bar);
        if (label == null) return;
        bar.removeView(label);
        if (label.frame.getParent() == bar) label.frame.setLayoutParams(label.frameParams);
    }

    /**
     * Shows the time for the percent the bar is drawing. A matching event names the video and its
     * length. A zero percent is TikTok resetting the bar for the next video, unless it came with
     * an event from the video already shown here (a loop back to the start), and blanks the time
     * until the new video reports. Any other percent without an event, such as TikTok redrawing
     * the bar on a pause, keeps the video it had.
     */
    void show(float percent, @Nullable SeekbarTime.Tick tick) {
        boolean sameVideo = tick != null && tick.aid != null && tick.aid.equals(aid);
        if (tick != null && (percent > 0f || sameVideo)) {
            aid = tick.aid;
            hidden = tick.hidden;
            if (tick.durationMs > 0L) durationMs = tick.durationMs;
        } else if (percent <= 0f) {
            aid = null;
            durationMs = 0L;
            hidden = false;
        }
        String text = hidden || durationMs <= 0L ? "" : SeekbarTime.readout(percent, durationMs);
        if (!text.contentEquals(getText())) setText(text);
        if (!hidden && durationMs > 0L) fitTo(SeekbarTime.widest(durationMs));
        follow();
    }

    void setDragging(boolean dragging) {
        if (this.dragging == dragging) return;
        this.dragging = dragging;
        follow();
    }

    /** Draws as the seek bar does, and not at all without a time or during a drag. */
    void follow() {
        float wanted = 0f;
        String why;
        if (dragging) {
            why = "a drag";
        } else if (length() == 0) {
            why = "no time yet";
        } else {
            ProgressBar bar = seekBar();
            if (bar == null) {
                why = "no seek bar in the row";
            } else if (bar.getVisibility() != View.VISIBLE) {
                why = "the seek bar isn't visible";
            } else {
                wanted = bar.getAlpha();
                why = "the seek bar's alpha " + wanted;
            }
        }
        // The first answer is logged too, so a label that never shows still leaves a line.
        Boolean shows = wanted > 0f;
        if (!shows.equals(drawn)) {
            drawn = shows;
            Logger.printDebug(() -> "Seekbar time: " + (shows ? "shown, " : "hidden, ") + why);
        }
        if (getAlpha() != wanted) setAlpha(wanted);
    }

    @Override
    public boolean onPreDraw() {
        follow();
        return true;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        observer = getViewTreeObserver();
        observer.addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        ViewTreeObserver attached = observer;
        if (attached != null && attached.isAlive()) {
            attached.removeOnPreDrawListener(this);
        } else {
            getViewTreeObserver().removeOnPreDrawListener(this);
        }
        observer = null;
        super.onDetachedFromWindow();
    }

    /** TikTok's seek bar, the one progress bar inside the frame. */
    @Nullable
    private ProgressBar seekBar() {
        if (seekBar == null) seekBar = firstProgressBar(frame);
        return seekBar;
    }

    @Nullable
    private static ProgressBar firstProgressBar(View view) {
        if (view instanceof ProgressBar) return (ProgressBar) view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ProgressBar found = firstProgressBar(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    /**
     * A fixed width, measured from the widest time this length can show. Fixed, because a label
     * sized to its text would ask for a new layout every second; this asks only when a video's
     * length needs another digit.
     */
    private void fitTo(String widest) {
        if (widest.equals(sizedFor)) return;
        sizedFor = widest;
        ViewGroup.LayoutParams params = getLayoutParams();
        int width = widthFor(widest);
        if (params != null && params.width != width) {
            params.width = width;
            setLayoutParams(params);
        }
    }

    private int widthFor(String text) {
        return (int) Math.ceil(getPaint().measureText(withWidestDigit(text)))
                + getPaddingLeft() + getPaddingRight();
    }

    /**
     * The text with every digit swapped for the font's widest one. Not every phone font has even
     * digits for "tnum", and a time a hair wider than its label would lose its second half.
     */
    private String withWidestDigit(String text) {
        char widest = '0';
        float widestWidth = -1f;
        for (char digit = '0'; digit <= '9'; digit++) {
            float width = getPaint().measureText(String.valueOf(digit));
            if (width > widestWidth) {
                widestWidth = width;
                widest = digit;
            }
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c >= '0' && c <= '9' ? widest : c);
        }
        return out.toString();
    }

    // Test access.
    View frame() {
        return frame;
    }

    ViewGroup.LayoutParams frameParams() {
        return frameParams;
    }
}
