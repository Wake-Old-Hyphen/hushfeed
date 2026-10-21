/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.theme;

import android.content.Context;
import android.content.res.Resources;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Utils;

/**
 * The accent for text on TikTok's Lynx pages, the "You may like" words in search among them (#163).
 *
 * <p>Lynx pages carry their own colors and hand each one to the text as a number, so the style
 * rewrite of Accent color never reaches them. The patch calls {@link #color(int)} where Lynx
 * stores a text color and where a Lynx view takes its background color (the dot before each of
 * those words), and writes a {@code hushfeed_lynx_<rrggbb>} color for each red it changes:
 * the preset's shade, or with Follow the wallpaper the system accent step on Android 12 and up.
 * A color without such a name, or one that isn't solid, stays as the page asked. Like the rest
 * of Accent color this is part of the build, so pausing Hushfeed leaves it.
 */
public final class LynxAccent {
    /** What the patch names each color a Lynx red becomes. */
    static final String PREFIX = "hushfeed_lynx_";

    /** Where the colors come from: the app's resources, or a stand-in in the tests. */
    interface Palette {
        /** The color resource with this name, or 0 when there is none. */
        int id(String name);

        /** The color resource as it is now, so a new wallpaper's accent shows without a restart. */
        int color(int id);
    }

    /** Each red's resource id, 0 for none, looked up once per red. */
    private static final Map<Integer, Integer> ids = new HashMap<>();

    private static Palette palette;

    private LynxAccent() {}

    /** Called with the color Lynx is about to use for a piece of text or a view's background, and answers the one to use. */
    public static int color(int argb) {
        if ((argb >>> 24) != 0xFF) return argb;
        try {
            Palette source = palette();
            if (source == null) return argb;
            int id = idFor(source, argb & 0xFFFFFF);
            return id == 0 ? argb : source.color(id);
        } catch (Throwable ignored) {
            return argb;
        }
    }

    private static synchronized int idFor(Palette source, int rgb) {
        Integer cached = ids.get(rgb);
        if (cached != null) return cached;
        int id = source.id(PREFIX + String.format(Locale.ROOT, "%06x", rgb));
        ids.put(rgb, id);
        return id;
    }

    private static synchronized Palette palette() {
        if (palette != null) return palette;
        Context context = Utils.getContext();
        if (context == null) return null;
        Resources resources = context.getResources();
        String packageName = context.getPackageName();
        palette = new Palette() {
            @Override
            public int id(String name) {
                return resources.getIdentifier(name, "color", packageName);
            }

            @Override
            public int color(int id) {
                return resources.getColor(id, null);
            }
        };
        return palette;
    }

    /** Only for the tests, which say which colors exist. Null puts the app's resources back. */
    static synchronized void setPaletteForTests(Palette source) {
        palette = source;
        ids.clear();
    }
}
