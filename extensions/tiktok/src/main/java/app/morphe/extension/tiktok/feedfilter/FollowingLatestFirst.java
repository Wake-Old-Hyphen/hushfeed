/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Latest first on the Following feed (#226): each batch of posts TikTok adds to the Following
 * list plays newest first. The list grows under the video playing as pages arrive, and TikTok
 * has laid out whatever it already read, so only posts no earlier read saw are sorted, and only
 * among their own slots. Ads, LIVEs and posts with no date keep their places. A card that isn't
 * a post (the "You're all caught up" divider) also splits the batch, so a suggested post after
 * it never moves above it.
 */
final class FollowingLatestFirst {
    /** Lists remembered at once, in the order they were last read, so the one on screen stays. */
    private static final int MAX_OWNERS = 64;
    /** Past this many posts one list is left in TikTok's order from then on. */
    private static final int MAX_AIDS = 4096;

    private FollowingLatestFirst() {}

    /** Puts an order into TikTok's list. True when the list TikTok reads now holds it. */
    interface OrderWriter {
        boolean write(List order);
    }

    /**
     * What earlier reads of each list saw, by the list's identity: FollowFeedList is a Kotlin
     * class from 46.9.3, so its equals can't be trusted to mean the same list.
     */
    private static final Map<Owner, Seen> SEEN =
        new LinkedHashMap<Owner, Seen>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Owner, Seen> eldest) {
                return size() > MAX_OWNERS;
            }
        };

    /** A list by identity. Two lists never share an entry, and a collected one matches nothing. */
    private static final class Owner {
        final WeakReference<Object> ref;
        final int hash;

        Owner(Object owner) {
            ref = new WeakReference<>(owner);
            hash = System.identityHashCode(owner);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Owner)) return false;
            Object list = ref.get();
            return list != null && list == ((Owner) other).ref.get();
        }
    }

    private static final class Seen {
        final Set<String> aids = new HashSet<>();
        boolean full;
    }

    /**
     * Read once per run of the app. Turned on with a list already open, every post in it would
     * look unread and the ones TikTok already showed would move, so the switch asks for a restart.
     */
    private static volatile Boolean enabled;

    private static boolean enabled() {
        Boolean on = enabled;
        if (on == null) {
            on = Settings.FOLLOWING_LATEST_FIRST.get();
            enabled = on;
        }
        return on;
    }

    static void resetForTests() {
        enabled = null;
        synchronized (SEEN) {
            SEEN.clear();
        }
    }

    /**
     * Sorts the posts no earlier read of {@code list} saw, through {@code writer} when any move.
     * They count as read only once the list TikTok reads holds the order, so a write that missed
     * (the list grew in between, or a read-only list was swapped for a copy this read's caller
     * doesn't hold) leaves them for the next read.
     */
    static void sort(Object owner, List list, FeedItemsFilter.AwemeExtractor extractor,
                     long nowMillis, OrderWriter writer) {
        if (owner == null || list == null || !enabled()) return;
        List snapshot = new ArrayList(list);
        Seen seen = seenFor(owner);
        synchronized (seen) {
            if (seen.full) return;
            long[] postedAt = new long[snapshot.size()];
            List<Integer> run = new ArrayList<>();
            List<String> aids = new ArrayList<>(snapshot.size());
            List order = null;
            for (int slot = 0; slot < snapshot.size(); slot++) {
                Aweme post = extractor.extract(snapshot.get(slot));
                if (post == null) {
                    order = sortRun(snapshot, run, postedAt, order);
                    run.clear();
                    continue;
                }
                String aid = aidOf(post);
                if (aid == null) continue;
                aids.add(aid);
                if (seen.aids.contains(aid) || !sortable(post)) continue;
                postedAt[slot] = AlwaysShowPublishDatePatch.postedAt(post, nowMillis);
                if (postedAt[slot] > 0) run.add(slot);
            }
            order = sortRun(snapshot, run, postedAt, order);
            if (order != null && !writer.write(order)) return;
            seen.aids.addAll(aids);
            if (seen.aids.size() > MAX_AIDS) {
                seen.full = true;
                seen.aids.clear();
            }
        }
    }

    /** Sorts the posts in {@code run}'s slots newest first, ties in TikTok's order. */
    private static List sortRun(List snapshot, List<Integer> run, long[] postedAt, List order) {
        if (run.size() < 2) return order;
        List<Integer> sorted = new ArrayList<>(run);
        Collections.sort(sorted, (a, b) -> Long.compare(postedAt[b], postedAt[a]));
        Logger.printDebug(() -> "Latest first on Following: " + run.size() + " new posts at "
            + slots(run, postedAt) + " play as " + slots(sorted, postedAt));
        if (sorted.equals(run)) return order;
        List result = order != null ? order : new ArrayList(snapshot);
        for (int k = 0; k < run.size(); k++) {
            result.set(run.get(k), snapshot.get(sorted.get(k)));
        }
        return result;
    }

    /** Each slot with its post time (epoch seconds, as postedAt gives it), for the debug log. */
    private static String slots(List<Integer> slots, long[] postedAt) {
        StringBuilder out = new StringBuilder("[");
        for (int slot : slots) {
            if (out.length() > 1) out.append(", ");
            out.append(slot).append('@').append(postedAt[slot]);
        }
        return out.append(']').toString();
    }

    private static boolean sortable(Aweme post) {
        try {
            return !post.isAd()
                && !post.isSoftAd()
                && post.getAwemeRawAd() == null
                && !LiveFilter.isLive(post);
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static String aidOf(Aweme post) {
        try {
            String aid = post.getAid();
            return aid == null || aid.isEmpty() ? null : aid;
        } catch (Throwable unknown) {
            return null;
        }
    }

    private static Seen seenFor(Object owner) {
        Owner key = new Owner(owner);
        synchronized (SEEN) {
            Seen entry = SEEN.get(key);
            if (entry == null) {
                entry = new Seen();
                SEEN.put(key, entry);
            }
            return entry;
        }
    }
}
