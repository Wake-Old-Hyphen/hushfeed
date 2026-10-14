package app.morphe.extension.tiktok.feed;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The state behind See-through bottom bar (#145). TikTok's bottom tab bar paints its own
 * background, and the room under the picture is kept for it by a spacer in each cell or, on the
 * S22, by a strip the bar's height under the main feed's pager, so the picture stops above the
 * bar. VideoOverlayHider collapses those through its ordinary hide path, lifts each cell's widget
 * layer by what the pager gained, and hands this class the bar: it swaps the bar's background for
 * a clear one and puts the original back, and it judges whether the collapse still pushed the
 * caption or the buttons under the bar.
 */
final class SeeThroughBottomBar {
    /** How far an overlay may reach into the bar, in pixels, before it counts as under it. */
    private static final float SLACK_PX = 2f;

    /** The clear stand-in, told apart from anything TikTok sets by its class. */
    private static final class ClearBackground extends ColorDrawable {
        ClearBackground() {
            super(Color.TRANSPARENT);
        }
    }

    /** Bars whose background this class replaced, with the drawable each had. */
    private static final Map<View, Drawable> BACKGROUNDS = new WeakHashMap<>();

    /**
     * Overlays (the caption frame, the buttons' column) measured with the spacer in place: true
     * when one sat clear of the bar then. Only an overlay that was fine before the collapse can
     * be blamed for being under the bar after it.
     */
    private static final Map<View, Boolean> CLEAR_BEFORE = new WeakHashMap<>();

    /** Set once a collapse put an overlay under the bar; the spacer then stays as TikTok has it. */
    private static boolean collapseRefused;

    private SeeThroughBottomBar() {
    }

    /** Whether the spacer under the video may be collapsed. */
    static boolean collapseAllowed() {
        return !collapseRefused;
    }

    /** True while a bar's background is held, so the layout pass keeps running to put it back. */
    static boolean holdsBackground() {
        return !BACKGROUNDS.isEmpty();
    }

    /** Forgets the verdict and the measurements once the switch is off. */
    static void reset() {
        collapseRefused = false;
        CLEAR_BEFORE.clear();
    }

    /**
     * Swaps the bar's background for a clear one, or puts back the one this class took. A
     * background TikTok set since is never overwritten on the way back.
     */
    static void setBackgroundClear(View bar, boolean clear) {
        if (bar == null) return;
        Drawable current = bar.getBackground();
        if (clear) {
            if (current == null || current instanceof ClearBackground) return;
            BACKGROUNDS.put(bar, current);
            bar.setBackground(new ClearBackground());
            return;
        }
        if (!BACKGROUNDS.containsKey(bar)) return;
        Drawable original = BACKGROUNDS.remove(bar);
        if (current instanceof ClearBackground) bar.setBackground(original);
    }

    /**
     * Compares the overlays with the bar. With the spacer in place ({@code collapsed} false) it
     * notes which overlays sit clear of the bar; once the spacer is collapsed it reports true, and
     * remembers, when one that was clear has moved under it. Views not laid out, hidden, or
     * entirely above or below the bar (a neighbouring cell) are not judged.
     */
    static boolean judge(View bar, List<View> overlays, boolean collapsed) {
        if (bar == null || !bar.isShown() || bar.getHeight() == 0) return false;
        float barTop = topInWindow(bar);
        for (View overlay : overlays) {
            if (overlay == null || !overlay.isShown() || overlay.getHeight() == 0) continue;
            float top = topInWindow(overlay);
            boolean under = reachesUnder(top, top + overlay.getHeight(), barTop);
            if (!collapsed) {
                CLEAR_BEFORE.put(overlay, !under);
            } else if (under && Boolean.TRUE.equals(CLEAR_BEFORE.get(overlay))) {
                collapseRefused = true;
                return true;
            }
        }
        return false;
    }

    /** An overlay that starts above the bar's top edge and ends below it. */
    static boolean reachesUnder(float top, float bottom, float barTop) {
        return top < barTop && bottom > barTop + SLACK_PX;
    }

    private static float topInWindow(View view) {
        float top = view.getTop() + view.getTranslationY();
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View group = (View) parent;
            top += group.getTop() - group.getScrollY() + group.getTranslationY();
            parent = group.getParent();
        }
        return top;
    }
}
