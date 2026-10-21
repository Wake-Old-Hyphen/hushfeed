/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.HapticFeedbackConstants;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A long press on the Profile tab opens TikTok's Watch history (#199), which otherwise sits
 * behind Profile, the menu, Settings and privacy and Activity center.
 *
 * <p>The page is TikTok's own hybrid page, opened the way its profile menu does: a
 * {@code webview} route on TikTok's scheme carrying the inapp.tiktokv.com watch_history address.
 * A VIEW intent kept to TikTok's own package reaches its deep link handler, which opens the page
 * in a SparkActivity (47.4.3, S22, 2026-10-21, cold start included).
 *
 * <p>On some accounts holding Profile is TikTok's account switcher; on others the tab has no long
 * press at all. While the switch is on this takes the press and keeps TikTok's own listener,
 * which goes back on the tab when the switch goes off. If TikTok sets its listener again later,
 * the next layout pass takes the press back and keeps the new one instead.
 *
 * <p>Rides on {@link HomeTabSettingsShortcut}'s front window and layout passes, so it follows the
 * same main activity and the same rebuilt tab bars.
 */
public final class ProfileTabWatchHistory {
    /** The address TikTok's profile menu opens for Watch history, as 47.4.3 ships it. */
    static final String WATCH_HISTORY_URI = "snssdk1233://webview?_pia_=1&hide_nav_bar=1&use_spark=1"
            + "&show_loading=1&should_full_screen=1&allow_webview_gesture=1&url=https%3A%2F%2F"
            + "inapp.tiktokv.com%2Ftpp%2Finapp%2Fpns_product_poseidon%2Fwatch_history%3F_pia_%3D1"
            + "%26hide_nav_bar%3D1%26use_spark%3D1%26show_loading%3D1%26should_full_screen%3D1"
            + "%26allow_webview_gesture%3D1%26bdhm_bid%3Dtiktok_privacy_product_hybrid"
            + "%26enable_search_history%3D1%26enter_from%3Dprofile_menu";

    private static WeakReference<View> attached = new WeakReference<>(null);
    /** A tab view that doesn't take the tap itself, so a long press on it would swallow taps. */
    private static WeakReference<View> refused = new WeakReference<>(null);
    /** TikTok's own long press on the attached tab, given back when the switch goes off. */
    private static View.OnLongClickListener original;
    private static boolean originalLongClickable;

    private static Field listenerInfoField;
    private static Field longClickField;
    private static boolean listenerUnreadable;

    static final View.OnLongClickListener OPEN_WATCH_HISTORY = view -> {
        try {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            open(view.getContext());
        } catch (Throwable error) {
            Logger.printException(() -> "Could not open Watch history from the Profile tab", error);
        }
        // Handled, so the release that ends the press isn't also a tap that opens Profile.
        return true;
    };

    private ProfileTabWatchHistory() {
    }

    static void open(Context context) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(WATCH_HISTORY_URI))
                .setPackage(context.getPackageName());
        if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /** Called on each layout pass of the main activity in front. */
    static void apply(Activity activity) {
        try {
            View current = attached.get();
            // Off, the tab isn't even looked up.
            if (!Settings.PROFILE_TAB_OPENS_WATCH_HISTORY.get()) {
                if (current != null) giveBack(current);
                return;
            }
            View profile = FeedVisibility.profileTabView(activity);
            if (current != null && current != profile) giveBack(current);
            if (profile == null || refused.get() == profile) return;

            if (attached.get() == profile) {
                View.OnLongClickListener now = longClickListener(profile);
                if (listenerUnreadable || now == OPEN_WATCH_HISTORY) return;
                // TikTok set its long press again, or cleared it: that is what goes back on the
                // tab when the switch goes off.
                original = now;
                originalLongClickable = now != null;
                profile.setOnLongClickListener(OPEN_WATCH_HISTORY);
                Logger.printInfo(() -> "TikTok changed the Profile tab's long press; took it back");
                return;
            }
            if (!profile.isClickable()) {
                refused = new WeakReference<>(profile);
                Logger.printInfo(() -> "The Profile tab view doesn't take the tap itself; no long press");
                return;
            }
            originalLongClickable = profile.isLongClickable();
            original = originalLongClickable ? longClickListener(profile) : null;
            if (originalLongClickable && original == null) {
                Logger.printInfo(() -> "Couldn't keep TikTok's own long press on Profile; "
                        + "it comes back when TikTok rebuilds the tab bar");
            }
            profile.setOnLongClickListener(OPEN_WATCH_HISTORY);
            attached = new WeakReference<>(profile);
        } catch (Throwable error) {
            Logger.printException(() -> "Could not set the Profile tab's long press", error);
        }
    }

    /**
     * The main activity followed was destroyed: its tab goes with it, and TikTok's listener (which
     * can hold that activity) isn't kept here past it.
     */
    static void release() {
        View current = attached.get();
        if (current != null) giveBack(current);
        original = null;
        originalLongClickable = false;
    }

    private static void giveBack(View tab) {
        tab.setOnLongClickListener(original);
        tab.setLongClickable(originalLongClickable);
        attached = new WeakReference<>(null);
        original = null;
        originalLongClickable = false;
    }

    /** The view's long-click listener, or null when there is none or it can't be read. */
    private static View.OnLongClickListener longClickListener(View view) {
        if (listenerUnreadable) return null;
        try {
            if (listenerInfoField == null) {
                Field info = View.class.getDeclaredField("mListenerInfo");
                info.setAccessible(true);
                listenerInfoField = info;
            }
            Object info = listenerInfoField.get(view);
            if (info == null) return null;
            if (longClickField == null) {
                Field field = info.getClass().getDeclaredField("mOnLongClickListener");
                field.setAccessible(true);
                longClickField = field;
            }
            return (View.OnLongClickListener) longClickField.get(info);
        } catch (Throwable error) {
            listenerUnreadable = true;
            Logger.printInfo(() -> "Can't read a view's long press on this Android: " + error);
            return null;
        }
    }

    static void resetForTests() {
        View current = attached.get();
        if (current != null) giveBack(current);
        attached = new WeakReference<>(null);
        refused = new WeakReference<>(null);
        original = null;
        originalLongClickable = false;
    }
}
