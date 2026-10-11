/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hide the status bar in LIVE rooms (#38). A LIVE room is an activity of its own,
 * {@code LivePlayActivity}, so Hide the status bar, which works on the main activity's window,
 * never reached it. Each time a LIVE room comes to the front with the switch on, its status bar
 * is hidden and its window allowed into the display cutout, and the strip TikTok keeps for the
 * bar is taken away so the stream reaches the top edge; all of it goes back as the room leaves,
 * so switching apps or rotating starts again from TikTok's own state.
 *
 * <p>Show a LIVE under the status bar takes only the strip away and leaves the bar showing, see
 * through, over the stream. That's how rooms looked until TikTok started keeping a black strip
 * for the bar in October 2026, with or without Hushfeed (#137). The black is two things: the
 * offset that holds the room a bar lower, and a plain view the bar's height that TikTok draws
 * over the top of the room. Both go while the room is in front, and the room's controls are held
 * a bar lower again on their own, so only the stream reaches up under the clock.
 */
public final class LiveStatusBar {
    /** TikTok's LIVE room, real-named on 47.0.3 and 47.1.3 (read off the S22, 2026-09-27). */
    static final String LIVE_ROOM = "com.ss.android.ugc.aweme.live.LivePlayActivity";

    private static final String HOOK_FAMILY = "LIVE status bar";
    private static final int LEGACY_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
    /** How deep the strip is looked for; on 47.1.3 it sits three levels under the content view. */
    private static final int MAX_DEPTH = 12;
    /** Containers looked into on one pass, so a deep room can't make a layout pass slow. */
    private static final int MAX_VISITS = 200;
    /** Layout passes that look for the strip before giving up on a room that has none. */
    private static final int MAX_SEARCHES = 40;
    /**
     * TikTok putting the strip back this many times inside {@link #FIGHT_WINDOW_MS} is a layout
     * fight, and the room keeps its strip from then on. A count per visit stopped too soon: each
     * panel opened in the room (rankings, gifts, comments) puts the strip back once, so after a
     * few taps the black strip stayed (#137).
     */
    private static final int MAX_RELIFTS = 6;
    private static final long FIGHT_WINDOW_MS = 1000;
    /** The view holding a room's stream on 47.1.4 (read off the emulator, 2026-10-11). */
    private static final String VIDEO_CONTAINER = "video_view_container";
    /** Set by tests, where TikTok's resources aren't there to name the stream's view. */
    static int videoContainerForTests;

    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** The rooms whose bar this hid. */
    private static final WeakHashMap<Activity, Room> HIDDEN = new WeakHashMap<>();

    private LiveStatusBar() {
    }

    /** Registered once per application, from the main activity the overlay hider installs on. */
    public static void follow(Activity activity) {
        Application application = activity == null ? null : activity.getApplication();
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                if (!isLiveRoom(resumed)) return;
                if (Settings.HIDE_STATUS_BAR_IN_LIVE.get()) hide(resumed, true);
                else if (Settings.LIVE_UNDER_STATUS_BAR.get()) hide(resumed, false);
            }

            @Override public void onActivityPaused(Activity paused) {
                if (HIDDEN.containsKey(paused)) show(paused);
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) { HIDDEN.remove(destroyed); }
        });
    }

    static boolean isLiveRoom(Activity activity) {
        return activity != null && LIVE_ROOM.equals(activity.getClass().getName());
    }

    static boolean isHidden(Activity activity) {
        return HIDDEN.containsKey(activity);
    }

    static void hide(Activity activity) {
        hide(activity, true);
    }

    /** With {@code hideBar} false only the strip goes, and the bar stays over the stream. */
    static void hide(Activity activity, boolean hideBar) {
        try {
            Window window = activity.getWindow();
            if (window == null || HIDDEN.containsKey(activity)) return;
            WindowManager.LayoutParams attributes = window.getAttributes();
            int cutout = Build.VERSION.SDK_INT >= 28 ? attributes.layoutInDisplayCutoutMode : 0;
            View decor = window.getDecorView();
            Room room = new Room(activity, decor, cutout, hideBar, window.getStatusBarColor());
            HIDDEN.put(activity, room);
            if (!hideBar) {
                // See through, so the stream shows under the clock. Android 15 draws every app's
                // bar see through already and ignores this; the black there is TikTok's cover.
                window.setStatusBarColor(Color.TRANSPARENT);
            }
            // Only a room that stays out of the cutout is let in. 47.1.3's LIVE window already
            // asks for ALWAYS (read off the S22), which SHORT_EDGES would narrow in landscape.
            if (hideBar && Build.VERSION.SDK_INT >= 28
                    && (cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || cutout == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER)) {
                attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(attributes);
            }
            if (hideBar) {
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController controller = decor.getWindowInsetsController();
                    if (controller != null) {
                        controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                        controller.hide(WindowInsets.Type.statusBars());
                    }
                } else {
                    decor.setSystemUiVisibility(decor.getSystemUiVisibility() | LEGACY_FLAGS);
                }
            }
            // The room's views are laid out after it resumes, and its insets are only known once
            // the window is attached, so the strip is looked for on the layout passes that follow.
            decor.getViewTreeObserver().addOnGlobalLayoutListener(room);
            HookStatus.bound(HOOK_FAMILY, hideBar ? "LIVE room status bar hidden" : "LIVE room under the status bar");
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "hide", failure);
            Logger.printException(() -> "Could not hide the status bar in a LIVE room", failure);
        }
    }

    static void show(Activity activity) {
        Room room = HIDDEN.remove(activity);
        if (room == null) return;
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            decor.getViewTreeObserver().removeOnGlobalLayoutListener(room);
            room.putBack();
            if (!room.barHidden) {
                window.setStatusBarColor(room.statusBarColor);
                return;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                WindowManager.LayoutParams attributes = window.getAttributes();
                if (attributes.layoutInDisplayCutoutMode != room.cutout) {
                    attributes.layoutInDisplayCutoutMode = room.cutout;
                    window.setAttributes(attributes);
                }
            }
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) controller.show(WindowInsets.Type.statusBars());
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~LEGACY_FLAGS);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not bring the status bar back after a LIVE room", failure);
        }
    }

    /**
     * The heights TikTok could have kept a strip for: the status bar as it is when shown, the
     * cutout's safe inset, and the platform's own status bar height. On the S22 the room's content
     * starts 75 px down with the bar hidden, a fixed offset TikTok never takes back.
     */
    static int[] barHeights(View decor) {
        int shown = 0, cutout = 0;
        WindowInsets insets = decor.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            shown = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top;
        }
        if (insets != null && Build.VERSION.SDK_INT >= 28) {
            DisplayCutout displayCutout = insets.getDisplayCutout();
            if (displayCutout != null) cutout = displayCutout.getSafeInsetTop();
        }
        int platform = 0;
        Resources resources = Resources.getSystem();
        int id = resources.getIdentifier("status_bar_height", "dimen", "android");
        if (id != 0) platform = resources.getDimensionPixelSize(id);
        return new int[] {shown, cutout, platform};
    }

    private static boolean isBar(int offset, int[] bars) {
        if (offset <= 0) return false;
        for (int bar : bars) if (bar == offset) return true;
        return false;
    }

    /**
     * The first padding or top margin under the content view that is exactly a status bar tall,
     * level by level. Each container's own padding is checked, then its children's top margins
     * (the tallest child first), and every child that still starts at the top is looked into next.
     * One path isn't enough: on 47.1.3 the content view holds AppCompat's empty frame and TikTok's
     * room side by side, both full screen, and the room is the second.
     */
    static Offset findOffset(View content, int[] bars) {
        ArrayDeque<View> level = new ArrayDeque<>();
        level.add(content);
        int visited = 0;
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            ArrayDeque<View> next = new ArrayDeque<>();
            for (View at : level) {
                if (!(at instanceof ViewGroup) || visited++ >= MAX_VISITS) continue;
                ViewGroup group = (ViewGroup) at;
                if (isBar(group.getPaddingTop(), bars)) return new Offset(group, true, group.getPaddingTop());
                View margined = null;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE) continue;
                    ViewGroup.LayoutParams params = child.getLayoutParams();
                    if (params instanceof ViewGroup.MarginLayoutParams
                            && isBar(((ViewGroup.MarginLayoutParams) params).topMargin, bars)
                            && (margined == null || child.getHeight() > margined.getHeight())) {
                        margined = child;
                    }
                    if (child instanceof ViewGroup && child.getTop() == group.getPaddingTop()) next.add(child);
                }
                if (margined != null) {
                    return new Offset(margined, false, ((ViewGroup.MarginLayoutParams) margined.getLayoutParams()).topMargin);
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * TikTok's cover for the bar: a plain view exactly a status bar tall, across the top of the
     * room and drawn over it. On 47.1.4 under Android 16 it's the last child of the content view's
     * root (read off the emulator, 2026-10-11), so with the strip taken away the room's top row,
     * the creator and the close button, sat under a black bar. Looked for three levels down, last
     * child first, and only along views that start at the top.
     */
    static View findCover(View content, int[] bars) {
        if (!(content instanceof ViewGroup)) return null;
        ArrayDeque<ViewGroup> level = new ArrayDeque<>();
        level.add((ViewGroup) content);
        for (int depth = 0; depth < 3 && !level.isEmpty(); depth++) {
            ArrayDeque<ViewGroup> next = new ArrayDeque<>();
            for (ViewGroup group : level) {
                for (int i = group.getChildCount() - 1; i >= 0; i--) {
                    View child = group.getChildAt(i);
                    if (child.getVisibility() != View.VISIBLE || child.getTop() != 0) continue;
                    if (child.getClass() == View.class && child.getBackground() != null
                            && child.getWidth() == content.getWidth() && isBar(child.getHeight(), bars)) {
                        return child;
                    }
                    if (child instanceof ViewGroup) next.add((ViewGroup) child);
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * The layers beside each room's stream. On 47.1.4 a room's page holds the stream's view and,
     * beside it, a layer as big with the creator row, the chips and the comments. Every page the
     * room's pager has made is taken, so the next room is ready before it's swiped in; the search
     * stops at the level the first stream is found on.
     */
    static List<View> findControls(View root, int videoId) {
        List<View> layers = new ArrayList<>();
        ArrayDeque<View> level = new ArrayDeque<>();
        level.add(root);
        int visited = 0;
        for (int depth = 0; depth < MAX_DEPTH && !level.isEmpty(); depth++) {
            ArrayDeque<View> next = new ArrayDeque<>();
            boolean found = false;
            for (View at : level) {
                if (visited++ >= MAX_VISITS) return layers;
                if (at.getId() == videoId && at.getParent() instanceof ViewGroup) {
                    found = true;
                    ViewGroup page = (ViewGroup) at.getParent();
                    for (int i = 0; i < page.getChildCount(); i++) {
                        View child = page.getChildAt(i);
                        if (child != at && child instanceof ViewGroup && child.getVisibility() == View.VISIBLE
                                && child.getTop() == 0 && child.getHeight() == page.getHeight()) {
                            layers.add(child);
                        }
                    }
                } else if (at instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) at;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        if (child.getVisibility() != View.GONE) next.add(child);
                    }
                }
            }
            if (found) break;
            level = next;
        }
        return layers;
    }

    /** A padding or margin this took away, with the height it had. */
    static final class Offset {
        final WeakReference<View> view;
        final boolean padding;
        final int value;

        Offset(View view, boolean padding, int value) {
            this.view = new WeakReference<>(view);
            this.padding = padding;
            this.value = value;
        }

        int current(View target) {
            if (padding) return target.getPaddingTop();
            ViewGroup.LayoutParams params = target.getLayoutParams();
            return params instanceof ViewGroup.MarginLayoutParams ? ((ViewGroup.MarginLayoutParams) params).topMargin : -1;
        }

        void set(View target, int top) {
            if (padding) {
                target.setPadding(target.getPaddingLeft(), top, target.getPaddingRight(), target.getPaddingBottom());
            } else {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) target.getLayoutParams();
                params.topMargin = top;
                target.setLayoutParams(params);
            }
        }
    }

    /** One visit to a LIVE room with its bar hidden. It holds its views weakly. */
    private static final class Room implements ViewTreeObserver.OnGlobalLayoutListener {
        final int cutout;
        /** Whether the bar was hidden, or only the strip taken away under a bar left showing. */
        final boolean barHidden;
        /** The bar's color as TikTok set it, given back as the room leaves. */
        final int statusBarColor;
        private final WeakReference<Activity> activity;
        private final WeakReference<View> decor;
        private Offset lifted;
        private int searches, relifts, coverSearches;
        private long fightStart;
        private boolean fought;
        private WeakReference<View> cover = new WeakReference<>(null);
        /** The controls layers held a bar lower, with the top padding each had of its own. */
        private final WeakHashMap<View, Integer> lowered = new WeakHashMap<>();
        private int loweredBy;
        /** The stream's view id, -1 until it's been asked for and 0 when TikTok has none. */
        private int videoId = -1;

        Room(Activity activity, View decor, int cutout, boolean barHidden, int statusBarColor) {
            this.activity = new WeakReference<>(activity);
            this.decor = new WeakReference<>(decor);
            this.cutout = cutout;
            this.barHidden = barHidden;
            this.statusBarColor = statusBarColor;
        }

        @Override public void onGlobalLayout() {
            try {
                keepCoverAway();
                keepStripAway();
                if (!barHidden) keepControlsBelowBar();
            } catch (Throwable failure) {
                searches = MAX_SEARCHES;
                coverSearches = MAX_SEARCHES;
                videoId = 0;
                HookStatus.threw(HOOK_FAMILY, "top strip", failure);
                Logger.printException(() -> "Could not take away the LIVE room's top strip", failure);
            }
        }

        private void keepStripAway() {
            if (lifted != null) {
                View target = lifted.view.get();
                // TikTok put the strip back, on a relayout of its own: a panel opening or
                // closing in the room does it.
                if (target == null || fought || lifted.current(target) != lifted.value) return;
                long now = SystemClock.uptimeMillis();
                if (now - fightStart > FIGHT_WINDOW_MS) {
                    fightStart = now;
                    relifts = 0;
                }
                if (++relifts > MAX_RELIFTS) {
                    fought = true;
                    Logger.printDebug(() -> "TikTok keeps putting the LIVE room's top strip back; leaving it");
                    return;
                }
                lifted.set(target, 0);
                return;
            }
            Activity room = activity.get();
            View window = decor.get();
            if (room == null || window == null || searches >= MAX_SEARCHES) return;
            View content = room.findViewById(android.R.id.content);
            if (content == null || content.getHeight() == 0) return;
            searches++;
            Offset offset = findOffset(content, barHeights(window));
            if (offset == null) return;
            View target = offset.view.get();
            if (target == null) return;
            lifted = offset;
            offset.set(target, 0);
            HookStatus.bound(HOOK_FAMILY, "LIVE room top strip taken away");
        }

        /**
         * Hides TikTok's cover once it's found, and again whenever it shows. Invisible rather than
         * see through, so it can't take the taps meant for the creator row under it; a cover TikTok
         * has swapped for a new one is let go and the new one looked for.
         */
        private void keepCoverAway() {
            View known = cover.get();
            if (known != null && !known.isAttachedToWindow()) {
                known = null;
                cover = new WeakReference<>(null);
                coverSearches = 0;
            }
            if (known == null) {
                Activity room = activity.get();
                View window = decor.get();
                if (room == null || window == null || coverSearches >= MAX_SEARCHES) return;
                View content = room.findViewById(android.R.id.content);
                if (content == null || content.getHeight() == 0) return;
                coverSearches++;
                known = findCover(content, barHeights(window));
                if (known == null) return;
                cover = new WeakReference<>(known);
                HookStatus.bound(HOOK_FAMILY, "LIVE room bar cover taken away");
            }
            if (known.getVisibility() == View.VISIBLE) known.setVisibility(View.INVISIBLE);
        }

        /**
         * Under a bar left showing, only the stream goes up to the top edge: the room's controls
         * are held a bar lower again, where TikTok had them, so the creator row and the close
         * button stay clear of the clock (#137). With the strip back, they go back up with it.
         */
        private void keepControlsBelowBar() {
            View target = lifted == null ? null : lifted.view.get();
            if (target == null || lifted.current(target) != 0) {
                raiseControls();
                return;
            }
            if (videoId < 0) {
                Activity room = activity.get();
                if (room == null) return;
                videoId = videoContainerForTests != 0 ? videoContainerForTests
                        : room.getResources().getIdentifier(VIDEO_CONTAINER, "id", room.getPackageName());
                if (videoId == 0) HookStatus.missingViewId(HOOK_FAMILY, VIDEO_CONTAINER);
            }
            if (videoId == 0) return;
            for (View layer : findControls(target, videoId)) {
                Integer own = lowered.get(layer);
                if (own == null) {
                    own = layer.getPaddingTop();
                    lowered.put(layer, own);
                    if (lowered.size() == 1) HookStatus.bound(HOOK_FAMILY, "LIVE room controls kept below the bar");
                } else if (layer.getPaddingTop() != own) {
                    // Already a bar lower, or TikTok set a padding of its own.
                    continue;
                }
                loweredBy = lifted.value;
                layer.setPadding(layer.getPaddingLeft(), own + loweredBy, layer.getPaddingRight(), layer.getPaddingBottom());
            }
        }

        /** Gives the controls their own padding back unless TikTok has set another. */
        private void raiseControls() {
            for (Map.Entry<View, Integer> entry : lowered.entrySet()) {
                View layer = entry.getKey();
                int own = entry.getValue();
                if (layer != null && layer.getPaddingTop() == own + loweredBy) {
                    layer.setPadding(layer.getPaddingLeft(), own, layer.getPaddingRight(), layer.getPaddingBottom());
                }
            }
            lowered.clear();
        }

        /** Gives the strip, the cover and the controls back unless TikTok has already set its own. */
        void putBack() {
            View known = cover.get();
            if (known != null && known.getVisibility() == View.INVISIBLE) known.setVisibility(View.VISIBLE);
            cover = new WeakReference<>(null);
            raiseControls();
            if (lifted == null) return;
            View target = lifted.view.get();
            if (target != null && lifted.current(target) == 0) lifted.set(target, lifted.value);
            lifted = null;
        }
    }
}
