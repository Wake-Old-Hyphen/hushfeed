/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * The window and video halves of See-through status bar (#145). VideoOverlayHider collapses the
 * spacer TikTok keeps above each feed video for the bar, so the picture reaches the top edge; this
 * clears the bar's own color while the feed is in front, so the picture shows through it. Android
 * 15 and up draw every app's bar see through already and ignore the color, so there it's only the
 * spacer.
 *
 * <p>Nothing else about the window changes: no flags, no insets, no cutout mode. The color TikTok
 * last gave the bar is kept, and given back when the feed leaves the front, the switch goes off or
 * Hushfeed is paused, unless TikTok has set another color since.
 *
 * <p>TikTok sizes each video's surface to the picture and centers it in the cell's player, so on
 * a tall screen a 9:16 video still left a black band under the clock with the spacer gone (S22: a
 * 1107 by 1968 surface in a 1080 by 2181 player, 106 px above and below). {@link #fill} grows a
 * portrait surface from its center until it covers the player, trimming a little off each side.
 */
public final class SeeThroughStatusBar {
    /**
     * The most a picture is grown. 1.11 fills the S22's player and trims about 70 px off each side;
     * past 1.2 a picture loses too much of its sides, so it keeps TikTok's bands instead.
     */
    static final float MAX_FILL_SCALE = 1.2f;
    /** Windows whose bar this cleared, with the color TikTok last gave it. */
    private static final Map<Window, Integer> CLEARED = new WeakHashMap<>();
    /** Video surfaces this grew, with the scale it wrote. */
    private static final Map<View, Float> FILLED = new WeakHashMap<>();

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

    /**
     * One layout pass over a feed cell's player: while {@code wanted}, grows its video surface so a
     * portrait picture covers the player top to bottom; otherwise puts back the scale this wrote.
     * Every pass measures again, so a new video in a reused cell gets its own scale. A scale
     * TikTok wrote itself is left alone.
     */
    static void fill(View player, boolean wanted) {
        if (!(player instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) player;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View surface = group.getChildAt(i);
            if (!(surface instanceof SurfaceView) && !(surface instanceof TextureView)) continue;
            Float ours = FILLED.get(surface);
            float current = surface.getScaleY();
            if (current != (ours == null ? 1f : ours) || surface.getScaleX() != current) {
                FILLED.remove(surface);
                continue;
            }
            float scale = wanted ? fillScale(group.getHeight(), surface) : 1f;
            if (scale == current) continue;
            surface.setScaleX(scale);
            surface.setScaleY(scale);
            if (scale == 1f) FILLED.remove(surface);
            else FILLED.put(surface, scale);
        }
    }

    /**
     * How much {@code surface} grows to cover a player {@code playerHeight} tall, or 1 to leave it.
     * Only a portrait picture TikTok centered, since it grows from its center; a landscape video
     * keeps its bands, and so does a picture that would lose more than {@link #MAX_FILL_SCALE}
     * allows. Two pixels over, so no line of the band shows at either edge.
     */
    static float fillScale(int playerHeight, View surface) {
        int width = surface.getWidth();
        int height = surface.getHeight();
        if (width <= 0 || height <= width || playerHeight <= height) return 1f;
        int above = surface.getTop();
        int below = playerHeight - surface.getBottom();
        if (Math.abs(above - below) > 2) return 1f;
        float scale = (playerHeight + 2f) / height;
        return scale > MAX_FILL_SCALE ? 1f : scale;
    }

    /** Whether a surface still carries a scale this wrote, so the pass runs to put it back. */
    static boolean anyFilled() {
        return !FILLED.isEmpty();
    }
}
