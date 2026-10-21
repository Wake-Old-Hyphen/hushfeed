/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import android.os.Handler;
import android.os.Looper;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Sorts the videos a profile or Liked grid has loaded so far (#180). The patch hands this the list
 * TikTok is about to give the grid's adapter, and the adapter keeps the list it gets back. TikTok
 * hands over its whole list again with each page, so a new page is sorted in with the rest and
 * nothing repeats. The list TikTok owns is never touched, which is how Original order, switching
 * the setting off and pausing Hushfeed bring TikTok's order back the next time a grid loads.
 *
 * <p>Pinned videos keep their place at the front in the order TikTok has them. A video with no
 * value for the chosen order (no length, no counts, no date) goes after the ones that have one,
 * in the order TikTok had them, and ties between equal values keep TikTok's order too, so a
 * sort gives the same grid every time.
 */
@SuppressWarnings("unused")
public final class GridSort {
    static final String OFF = "off";
    static final String NEWEST = "newest";
    static final String OLDEST = "oldest";
    static final String LONGEST = "longest";
    static final String SHORTEST = "shortest";
    static final String MOST_VIEWED = "most_viewed";
    static final String MOST_LIKED = "most_liked";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private GridSort() {
    }

    /** The adapter's setData: the list it should keep. Anything this can't sort comes back as it came. */
    public static List<?> data(List<?> list) {
        HookStatus.bound("profile grid sort", OFF.equals(Settings.PROFILE_GRID_SORT.get()) ? "order left" : "order sorted");
        return sorted(list);
    }

    /**
     * The adapter's setDataAfterLoadMore: the list it should keep. The adapter announces the page
     * as new cells at the end of the grid, which is wrong for a list that was reordered, so a list
     * that moved anything also gets a full refresh once TikTok's own call is through.
     */
    public static List<?> page(Object adapter, List<?> list) {
        HookStatus.bound("profile grid sort", OFF.equals(Settings.PROFILE_GRID_SORT.get()) ? "order left" : "order sorted");
        List<?> out = sorted(list);
        if (adapter != null && moved(list, out)) {
            MAIN.post(() -> refresh(adapter));
        }
        return out;
    }

    private static boolean moved(List<?> before, List<?> after) {
        if (before == null || after == null || before.size() != after.size()) return before != after;
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != after.get(i)) return true;
        }
        return false;
    }

    private static void refresh(Object adapter) {
        try {
            adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not refresh a sorted grid", ex);
        }
    }

    static List<?> sorted(List<?> list) {
        try {
            String order = Settings.PROFILE_GRID_SORT.get();
            if (list == null || list.size() < 2 || OFF.equals(order)) return list;
            Comparator<Object> by = comparator(order);
            if (by == null) return list;
            return sort(list, by);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not sort a grid", ex);
            return list;
        }
    }

    /** A new list: the pinned videos first as TikTok has them, then the rest by [by]. */
    static List<Object> sort(List<?> list, Comparator<Object> by) {
        List<Object> pinned = new ArrayList<>();
        List<Object> rest = new ArrayList<>();
        for (Object item : list) {
            (isPinned(item) ? pinned : rest).add(item);
        }
        // List.sort is stable, so equal values and missing ones stay in TikTok's order.
        Collections.sort(rest, by);
        pinned.addAll(rest);
        return pinned;
    }

    static Comparator<Object> comparator(String order) {
        if (NEWEST.equals(order)) return byLong(GridSort::created, true);
        if (OLDEST.equals(order)) return byLong(GridSort::created, false);
        if (LONGEST.equals(order)) return byLong(GridSort::length, true);
        if (SHORTEST.equals(order)) return byLong(GridSort::length, false);
        if (MOST_VIEWED.equals(order)) return byLong(GridSort::views, true);
        if (MOST_LIKED.equals(order)) return byLong(GridSort::likes, true);
        return null;
    }

    private interface Key {
        long of(Object item);
    }

    /** Orders by [key] and puts every item whose key is 0 or less after the ones that have one. */
    private static Comparator<Object> byLong(Key key, boolean descending) {
        return (a, b) -> {
            long x = key.of(a);
            long y = key.of(b);
            boolean hasX = x > 0;
            boolean hasY = y > 0;
            if (hasX != hasY) return hasX ? -1 : 1;
            if (!hasX) return 0;
            return descending ? Long.compare(y, x) : Long.compare(x, y);
        };
    }

    static boolean isPinned(Object item) {
        Object top = Reflect.property(item, "getIsTop", "isTop");
        return top instanceof Number && ((Number) top).intValue() == 1;
    }

    static long created(Object item) {
        return item instanceof Aweme ? ((Aweme) item).getCreateTime() : 0;
    }

    static long length(Object item) {
        return GridVideoLength.lengthMs(item);
    }

    static long views(Object item) {
        AwemeStatistics statistics = item instanceof Aweme ? ((Aweme) item).getStatistics() : null;
        return statistics == null ? 0 : statistics.getPlayCount();
    }

    static long likes(Object item) {
        AwemeStatistics statistics = item instanceof Aweme ? ((Aweme) item).getStatistics() : null;
        return statistics == null ? 0 : statistics.getDiggCount();
    }
}
