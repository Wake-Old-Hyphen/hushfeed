/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

/**
 * A long press on For You, the feed's name in the strip of tabs across the top of TikTok's home
 * screen, opens Hushfeed's settings. The other Hush packages open theirs from the app's logo, and
 * TikTok's feed shows none, so For You stands in for it. {@link HomeTabSettingsShortcut} is the
 * other way in, from the bottom bar.
 *
 * <p>The settings patch hands over every top tab TikTok binds, with the tag of the tab it shows
 * ({@link #onTopTab}). TikTok's top tab takes its tap in its own performClick (switch to the tab,
 * or reload For You when it's open) and has no long press or touch listener of its own. The press
 * is timed by a touch listener: every touch still reaches the tab as it did, so a tap is TikTok's,
 * and a press held for the phone's long-press time opens the settings with the long-press
 * vibration. The rest of that touch is kept from the tab, so letting go doesn't also reload the
 * feed.
 *
 * <p>No switch reads this, so Pause leaves it working: it's a way back to the switch that resumes
 * Hushfeed. The other top tabs are left alone, and a tab TikTok makes long-clickable keeps its own
 * long press.
 */
public final class ForYouTabSettingsShortcut {
    /** The timer on each top tab that has shown For You, so a tab bound again keeps the one it has. */
    private static final Map<View, Press> PRESSES = new WeakHashMap<>();

    private ForYouTabSettingsShortcut() {
    }

    /**
     * Injected where TikTok's top tab strip finishes binding a tab's view, with the tag of the tab
     * it now shows. TikTok reuses these views, so a view that showed For You can come back as
     * another tab, and the other way around.
     */
    public static void onTopTab(View tab, String tag) {
        try {
            if (tab == null) return;
            boolean forYou = NavigationTabOptions.HOT.equals(NavigationTabOptions.normalizeRuntimeTag(tag));
            synchronized (PRESSES) {
                Press press = PRESSES.get(tab);
                if (press == null) {
                    if (!forYou) return;
                    press = new Press();
                    tab.setOnTouchListener(press);
                    PRESSES.put(tab, press);
                    Logger.printInfo(() -> "A long press on For You opens Hushfeed settings");
                }
                press.forYou = forYou;
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not watch the For You tab", error);
        }
    }

    /**
     * Opens the settings page the way its row in Settings and privacy does.
     *
     * @return whether the page was asked for. False leaves the press to TikTok.
     */
    static boolean open() {
        if (Utils.getContext() == null) {
            Logger.printInfo(() -> "No app context yet, so the long press on For You stays TikTok's");
            return false;
        }
        Logger.printInfo(() -> "Settings requested by a long press on For You");
        TikTokActivityHook.openSettings();
        return true;
    }

    /** Times a press on one top tab, and opens the settings when the tab is For You. */
    static final class Press implements View.OnTouchListener {
        private final Runnable fire = this::fire;
        /** Whether the tab shows For You right now. */
        volatile boolean forYou;
        /** The tab while a press on it is being timed. */
        private View pressed;
        private float downX;
        private float downY;
        /** This touch opened the settings, so the rest of it isn't the tab's. */
        private boolean opened;

        @Override
        public boolean onTouch(View tab, MotionEvent event) {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        stop();
                        opened = false;
                        // Another tab in this view now, or a long press TikTok gave the tab itself.
                        if (!forYou || tab.isLongClickable()) return false;
                        pressed = tab;
                        downX = event.getX();
                        downY = event.getY();
                        tab.postDelayed(fire, ViewConfiguration.getLongPressTimeout());
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        // The same leeway the tab gives its own press before it lets go.
                        if (pressed != null && !within(tab, event)) stop();
                        return opened;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stop();
                        boolean ours = opened;
                        opened = false;
                        return ours;
                    default:
                        return opened;
                }
            } catch (Throwable error) {
                Logger.printException(() -> "Could not follow a press on the For You tab", error);
                return false;
            }
        }

        private static boolean within(View tab, MotionEvent event) {
            float slop = ViewConfiguration.get(tab.getContext()).getScaledTouchSlop();
            float x = event.getX();
            float y = event.getY();
            return x >= -slop && y >= -slop && x < tab.getWidth() + slop && y < tab.getHeight() + slop;
        }

        private void stop() {
            View tab = pressed;
            pressed = null;
            if (tab != null) tab.removeCallbacks(fire);
        }

        private void fire() {
            View tab = pressed;
            pressed = null;
            if (tab == null || !tab.isAttachedToWindow() || !forYou) return;
            try {
                // Not asked for, and letting go is TikTok's tap, as it is without Hushfeed.
                if (!open()) return;
                opened = true;
                tab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                // The tab lets go of its press here, so letting go of the screen isn't a tap on it.
                long now = SystemClock.uptimeMillis();
                MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0);
                tab.onTouchEvent(cancel);
                cancel.recycle();
            } catch (Throwable error) {
                Logger.printException(() -> "The long press on For You failed", error);
            }
        }
    }

    static void resetForTests() {
        synchronized (PRESSES) {
            PRESSES.clear();
        }
    }
}
