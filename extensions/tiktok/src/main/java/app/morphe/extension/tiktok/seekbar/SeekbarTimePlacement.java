/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.tiktok.settings.Settings;

/**
 * Where the progress bar's time goes (#173): {@link #BESIDE} the bar, or {@link #ABOVE} its right
 * end, and the checks that decide whether the spot above is free.
 *
 * <p>Everything here works in window coordinates built from the views' own offsets, so it gives
 * the same answer on a view that isn't attached yet (a test's, or a cell TikTok has just
 * inflated) as on a laid-out one.
 */
final class SeekbarTimePlacement {
    static final String BESIDE = "beside";
    static final String ABOVE = "above";
    /** How often a row asks again whether the spot above is free. */
    static final long RECHECK_MS = 500L;
    /**
     * The most room asked of the caption, in rows of the bar. The S22 needed about 3.3 rows
     * (2026-10-21: 225 to 259 px over a 79 px row); a caption reaching further down than this is
     * something else sitting on the bar, and the time stays beside it.
     */
    static final int MAX_ROOM_ROWS = 4;

    private SeekbarTimePlacement() {
    }

    /** Whether the choice is Above the bar. Anything else, a stale value included, is beside. */
    static boolean wantsAbove() {
        return ABOVE.equals(Settings.SEEKBAR_TIME_PLACEMENT.get());
    }

    /** The view's top left corner in the window, summed up through its parents' offsets. */
    static void positionInWindow(View view, int[] out) {
        float x = 0f;
        float y = 0f;
        View current = view;
        while (true) {
            x += current.getLeft() + current.getTranslationX();
            y += current.getTop() + current.getTranslationY();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) break;
            View above = (View) parent;
            x -= above.getScrollX();
            y -= above.getScrollY();
            current = above;
        }
        out[0] = Math.round(x);
        out[1] = Math.round(y);
    }

    /** The view's bounds in the window. */
    static Rect boundsInWindow(View view) {
        int[] at = new int[2];
        positionInWindow(view, at);
        return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
    }

    /**
     * Whether any text or icon on screen reaches into [spot]: a shown, visible TextView with
     * something in it, or an icon-sized ImageView with a picture, whose bounds overlap. The
     * caption, the creator's name and a hashtag line are text, and over the bar's right end sit
     * the Tako button and the music disc, so the time never goes where it would cover one of
     * them. A picture wider or taller than a third of the screen is the video's cover, not an
     * icon. The bar's own row is skipped.
     */
    static boolean textReaches(View root, Rect spot, View row) {
        return textReaches(root, spot, row, 0);
    }

    /** As {@link #textReaches(View, Rect, View)}, skipping every view with id [skipId] too. */
    static boolean textReaches(View root, Rect spot, View row, int skipId) {
        if (root == row || (skipId != 0 && root.getId() == skipId)
                || root.getVisibility() != View.VISIBLE || root.getAlpha() <= 0.01f) {
            return false;
        }
        if (inTheWay(root) && Rect.intersects(boundsInWindow(root), spot)) {
            return true;
        }
        if (!(root instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            if (textReaches(group.getChildAt(i), spot, row, skipId)) return true;
        }
        return false;
    }

    /**
     * How far the widget layer of the video on screen has to go up so nothing in it reaches into
     * [spot] (#173): the caption, the rail and the music disc all live in that layer, and on the
     * S22 the disc, and on some videos the search row, sit right over the bar's right end. Worked
     * out from where TikTok laid the layer out, before any lift, so the answer stays the same
     * while a lift is on. 0 when nothing in it reaches the spot, when there's no layer over the
     * spot, or when it would take more than {@link #MAX_ROOM_ROWS} rows; -1 while the pager is
     * between two videos, when there's nothing settled to measure.
     */
    static int roomNeeded(View root, Rect spot, int layerId) {
        if (layerId == 0 || spot.isEmpty()) return 0;
        List<View> layers = new ArrayList<>();
        collectById(root, layerId, layers);
        boolean moving = false;
        for (View layer : layers) {
            ViewParent parent = layer.getParent();
            if (!(parent instanceof View)) continue;
            View cell = (View) parent;
            Rect cellBounds = boundsInWindow(cell);
            if (cellBounds.left > spot.left || cellBounds.right < spot.right
                    || cellBounds.top >= spot.bottom || cellBounds.bottom <= spot.top) {
                continue;
            }
            if (!settled(cell)) {
                moving = true;
                continue;
            }
            int lowest = lowestOver(layer, spot, Math.round(layer.getTranslationY()), Integer.MIN_VALUE);
            if (lowest == Integer.MIN_VALUE) return 0;
            int need = lowest - spot.top;
            if (need <= 0 || need > MAX_ROOM_ROWS * spot.height()) return 0;
            return need;
        }
        return moving ? -1 : 0;
    }

    /** Every view with [id] under a shown [view], whatever the matches' own visibility. */
    private static void collectById(View view, int id, List<View> out) {
        if (view.getId() == id) {
            out.add(view);
            return;
        }
        if (view.getVisibility() != View.VISIBLE || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectById(group.getChildAt(i), id, out);
        }
    }

    /**
     * Whether the cell sits where its pager has settled, the same test the overlay pass uses
     * (VideoOverlayHider.inSettledCell): its top, moved and scrolled, at the pager's top.
     */
    private static boolean settled(View cell) {
        ViewParent parent = cell.getParent();
        if (!(parent instanceof View)) return true;
        View pager = (View) parent;
        float offset = cell.getTop() + cell.getTranslationY() - pager.getScrollY() - pager.getPaddingTop();
        return Math.abs(offset) < 1f;
    }

    /**
     * The lowest window bottom, where TikTok laid it out before the layer's [lift], of anything in
     * the way under [view] that reaches into the spot, or [lowest] when nothing does. Something
     * wholly over the spot or wholly under it, on the bar, needs no room. The layer itself is
     * skipped: Clear display fades it whole, and the room it needs back afterwards is the same.
     */
    private static int lowestOver(View view, Rect spot, int lift, int lowest) {
        if (!(view instanceof ViewGroup)) return lowest;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0.01f) continue;
            if (inTheWay(child)) {
                Rect bounds = boundsInWindow(child);
                bounds.offset(0, -lift);
                if (Rect.intersects(bounds, spot)) lowest = Math.max(lowest, bounds.bottom);
            }
            lowest = lowestOver(child, spot, lift, lowest);
        }
        return lowest;
    }

    private static boolean inTheWay(View view) {
        if (view instanceof SeekbarTimeLabel) return false;
        if (view instanceof TextView) return ((TextView) view).length() > 0;
        if (!(view instanceof ImageView) || ((ImageView) view).getDrawable() == null) return false;
        int screen = view.getResources().getDisplayMetrics().widthPixels;
        return view.getWidth() * 3 <= screen && view.getHeight() * 3 <= screen;
    }

    /**
     * The groups whose clipping would cut off a time drawn over [spot]: the row, the ancestors
     * above it, and last the first ancestor whose bounds hold the spot. A group clips each child
     * at the child's own bounds, so that last one has to stop clipping too, or the time is cut
     * away at the edge of the child that carries it (S22, 2026-10-21: the view holding the spot
     * clipped the row and no time showed). Null when no ancestor holds it: the spot is off screen
     * or past every container.
     */
    @Nullable
    static List<ViewGroup> clipChain(LinearLayout row, Rect spot) {
        List<ViewGroup> chain = new ArrayList<>();
        ViewGroup current = row;
        while (current != null) {
            chain.add(current);
            if (boundsInWindow(current).contains(spot)) return chain;
            ViewParent parent = current.getParent();
            current = parent instanceof ViewGroup ? (ViewGroup) parent : null;
        }
        return null;
    }
}
