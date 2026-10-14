/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Color;
import android.view.Window;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The window half of See-through status bar (#145). VideoOverlayHider collapses the spacer TikTok
 * keeps above each feed video for the bar, so the picture reaches the top edge; this clears the
 * bar's own color while the feed is in front, so the picture shows through it. Android 15 and up
 * draw every app's bar see through already and ignore the color, so there it's only the spacer.
 *
 * <p>Nothing else about the window changes: no flags, no insets, no cutout mode. The color TikTok
 * last gave the bar is kept, and given back when the feed leaves the front, the switch goes off or
 * Hushfeed is paused, unless TikTok has set another color since.
 */
public final class SeeThroughStatusBar {
    /** Windows whose bar this cleared, with the color TikTok last gave it. */
    private static final Map<Window, Integer> CLEARED = new WeakHashMap<>();

    private SeeThroughStatusBar() {
    }

    /** One layout pass: clears the bar while {@code wanted}, and gives its color back otherwise. */
    static void apply(Activity activity, boolean wanted) {
        Window window = activity.getWindow();
        if (window == null) return;
        int color = window.getStatusBarColor();
        if (wanted) {
            if (color == Color.TRANSPARENT) return;
            // TikTok's own color, kept even when it set a new one while the bar was clear.
            CLEARED.put(window, color);
            window.setStatusBarColor(Color.TRANSPARENT);
            return;
        }
        Integer own = CLEARED.remove(window);
        if (own != null && color == Color.TRANSPARENT) window.setStatusBarColor(own);
    }

    /** Whether this has cleared the bar of {@code activity}'s window. */
    static boolean isCleared(Activity activity) {
        Window window = activity.getWindow();
        return window != null && CLEARED.containsKey(window);
    }
}
