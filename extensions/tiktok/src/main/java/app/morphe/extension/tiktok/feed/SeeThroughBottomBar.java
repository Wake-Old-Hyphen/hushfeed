/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.view.View;

import app.morphe.extension.tiktok.settings.preference.SettingsUi;

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

    /** The floating pill's fill: dark and see-through, so the video shows behind the tabs. */
    static final int PILL_COLOR = 0x99000000;
    /** The pill's hairline, light enough to find the pill's edge over a dark video. */
    static final int PILL_EDGE_COLOR = 0x40FFFFFF;
    /** How far the pill stays in from the bar's sides and bottom, and from its top, in dp. */
    static final int PILL_SIDE_DP = 12, PILL_BOTTOM_DP = 6, PILL_TOP_DP = 2;

    /**
     * Floating bottom bar (#124): a rounded see-through pill drawn inside the bar, in from its
     * sides and bottom, so the tabs float over the video. Only the background changes: the bar
     * keeps its place and size, so every tab is where TikTok put it and takes its taps.
     */
    static class FloatingBackground extends InsetDrawable {
        FloatingBackground(float density) {
            this(pill(density), density);
        }

        FloatingBackground(Drawable pill, float density) {
            super(pill, Math.round(PILL_SIDE_DP * density), Math.round(PILL_TOP_DP * density),
                    Math.round(PILL_SIDE_DP * density), Math.round(PILL_BOTTOM_DP * density));
        }

        /**
         * No padding: a background's padding becomes the view's, which would squeeze the tabs and
         * outlive the pill when TikTok's background comes back. The insets only place the drawing.
         */
        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }

        private static GradientDrawable pill(float density) {
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setCornerRadius(SettingsUi.RADIUS_ROUND_ENDS * density);
            pill.setColor(PILL_COLOR);
            pill.setStroke(Math.max(1, Math.round(density)), PILL_EDGE_COLOR);
            return pill;
        }
    }

    /** The glass pill's dark tint, light enough that the video shows through, dark enough for white icons. */
    static final int GLASS_TINT = 0x4D000000;
    /** The white frost over the tint, brighter at the top. */
    static final int GLASS_FROST_TOP = 0x33FFFFFF, GLASS_FROST_BOTTOM = 0x0DFFFFFF;
    /** The sheen over the upper half, fading out by the middle. */
    static final int GLASS_SHEEN = 0x40FFFFFF;
    /** The rim: bright along the top edge where the light catches it, faint on the sides, a little back at the bottom. */
    static final int GLASS_RIM_TOP = 0x99FFFFFF, GLASS_RIM_SIDE = 0x26FFFFFF, GLASS_RIM_BOTTOM = 0x4DFFFFFF;

    /**
     * Liquid glass look (#218): the floating pill drawn as glass, in the same place. A view can
     * only blur what it draws itself, never the video under it, so the glass is drawn rather than
     * blurred: a lighter tint than the plain pill, a frost and a sheen that are brightest at the
     * top, and a rim that catches the light along the top edge.
     */
    static final class GlassBackground extends FloatingBackground {
        GlassBackground(float density) {
            super(new GlassPill(density), density);
        }
    }

    static final class GlassPill extends Drawable {
        private final float cornerRadius;
        private final float rim;
        private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint frost = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private int alpha = 255;

        GlassPill(float density) {
            cornerRadius = SettingsUi.RADIUS_ROUND_ENDS * density;
            rim = Math.max(1f, density);
            tint.setColor(GLASS_TINT);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(rim);
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            float top = bounds.top, bottom = bounds.bottom;
            frost.setShader(new LinearGradient(0, top, 0, bottom,
                    GLASS_FROST_TOP, GLASS_FROST_BOTTOM, Shader.TileMode.CLAMP));
            sheen.setShader(new LinearGradient(0, top, 0, (top + bottom) / 2f,
                    GLASS_SHEEN, GLASS_SHEEN & 0x00FFFFFF, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(0, top, 0, bottom,
                    new int[]{GLASS_RIM_TOP, GLASS_RIM_SIDE, GLASS_RIM_BOTTOM}, new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            box.set(bounds);
            float radius = Math.min(cornerRadius, box.height() / 2f);
            canvas.drawRoundRect(box, radius, radius, tint);
            canvas.drawRoundRect(box, radius, radius, frost);
            // The sheen sits inside the rim.
            float in = rim * 2f;
            box.inset(in, in);
            canvas.drawRoundRect(box, Math.max(0f, radius - in), Math.max(0f, radius - in), sheen);
            box.set(bounds);
            box.inset(rim / 2f, rim / 2f);
            canvas.drawRoundRect(box, Math.max(0f, radius - rim / 2f), Math.max(0f, radius - rim / 2f), edge);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            // A shader's colors are scaled by the paint's alpha; the tint's own alpha is scaled here.
            tint.setColor((GLASS_TINT & 0x00FFFFFF) | ((Color.alpha(GLASS_TINT) * alpha / 255) << 24));
            frost.setAlpha(alpha);
            sheen.setAlpha(alpha);
            edge.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            tint.setColorFilter(filter);
            frost.setColorFilter(filter);
            sheen.setColorFilter(filter);
            edge.setColorFilter(filter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** What a bar's background is: TikTok's own, or one of the three this class draws. */
    private static final int THEIRS = 0, CLEAR = 1, FLOATING = 2, GLASS = 3;

    private static int kind(Drawable background) {
        if (background instanceof GlassBackground) return GLASS;
        if (background instanceof FloatingBackground) return FLOATING;
        if (background instanceof ClearBackground) return CLEAR;
        return THEIRS;
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

    /** The overlay the refusal blamed and where it sat against the bar, for the debug log. */
    private static String blamed = "";

    private SeeThroughBottomBar() {
    }

    static String blamed() {
        return blamed;
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
        setBackgroundClear(bar, clear, false);
    }

    /**
     * The same, with {@code floating} drawing the floating pill (#124) in place of the clear
     * background. A bar with no background of its own is a layout this wasn't written for, and
     * keeps TikTok's look.
     */
    static void setBackgroundClear(View bar, boolean clear, boolean floating) {
        setBackgroundClear(bar, clear, floating, false);
    }

    /** The same, with {@code glass} drawing the floating pill as glass (#218). */
    static void setBackgroundClear(View bar, boolean clear, boolean floating, boolean glass) {
        if (bar == null) return;
        Drawable current = bar.getBackground();
        int kind = kind(current);
        if (clear) {
            if (current == null) return;
            int wanted = !floating ? CLEAR : glass ? GLASS : FLOATING;
            if (kind == wanted) return;
            if (kind == THEIRS) BACKGROUNDS.put(bar, current);
            float density = bar.getResources().getDisplayMetrics().density;
            bar.setBackground(wanted == GLASS ? new GlassBackground(density)
                    : wanted == FLOATING ? new FloatingBackground(density)
                    : new ClearBackground());
            return;
        }
        if (!BACKGROUNDS.containsKey(bar)) return;
        Drawable original = BACKGROUNDS.remove(bar);
        if (kind != THEIRS) bar.setBackground(original);
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
                blamed = "0x" + Integer.toHexString(overlay.getId()) + " at " + Math.round(top) + "-"
                        + Math.round(top + overlay.getHeight()) + ", bar top " + Math.round(barTop);
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
