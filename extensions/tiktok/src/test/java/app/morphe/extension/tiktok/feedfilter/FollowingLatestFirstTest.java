package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptionsTest.Item;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.follow.presenter.FollowFeed;
import com.ss.android.ugc.aweme.follow.presenter.FollowFeedList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Latest first on Following (#226), through the same getItems hook TikTok's Following list runs:
 * each batch plays newest first, what an earlier read saw never moves, and ads, LIVEs and cards
 * that aren't posts hold their slots.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowingLatestFirstTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final long NOW_SECONDS = System.currentTimeMillis() / 1000;
    private static final long HOUR = 3600;

    @Before
    public void setUp() {
        FollowingLatestFirst.resetForTests();
    }

    @After
    public void tearDown() {
        Settings.FOLLOWING_LATEST_FIRST.resetToDefault();
        Settings.REMOVE_ADS.resetToDefault();
        FollowingLatestFirst.resetForTests();
    }

    @Test
    public void eachBatchPlaysNewestFirstAndWhatWasReadStays() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("b", "c", "a"),
                read(list, post("a", 3 * HOUR), post("b", HOUR), post("c", 2 * HOUR)));
        // A read with nothing new leaves it alone.
        assertEquals(List.of("b", "c", "a"), read(list));
        // The next page is sorted on its own: d is the newest of all and still comes after a.
        assertEquals(List.of("b", "c", "a", "d", "f", "e"),
                read(list, post("d", 600), post("e", 5 * HOUR), post("f", 1800)));
    }

    @Test
    public void adsLivesAndCardsThatArentPostsKeepTheirSlots() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        Settings.REMOVE_ADS.save(false);
        Item ad = post("ad", 60);
        ad.ad = true;
        Item live = post("live", 60);
        live.liveId = 7;

        assertEquals(List.of("y", "ad", "x", "live", "z"),
                read(emptyList(), post("x", HOUR), ad, post("y", 1800), live, post("z", 2 * HOUR)));

        // A divider splits the batch: s is the newest but stays below it.
        FollowFeedList list = emptyList();
        add(list, post("p", 3 * HOUR), post("q", HOUR));
        list.mItems.add(new FollowFeed());
        add(list, post("r", 4 * HOUR), post("s", 600));
        assertEquals(List.of("q", "p", "-", "s", "r"), read(list));
    }

    @Test
    public void postsWithNoDateOrNoIdAndSoftAdsKeepTheirSlots() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        Settings.REMOVE_ADS.save(false);
        // No createTime and an id that carries no date either.
        Item undated = post("n", 0);
        undated.createTime = 0;
        Item noId = post("", 60);
        Item softAd = new Item("soft") {
            @Override public boolean isSoftAd() { return true; }
        };
        softAd.createTime = NOW_SECONDS - 60;

        assertEquals(List.of("y", "n", "", "soft", "x"),
                read(emptyList(), post("x", 2 * HOUR), undated, noId, softAd, post("y", 1800)));
    }

    @Test
    public void postsFromTheSameSecondKeepTikToksOrder() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);

        assertEquals(List.of("c", "a", "b"),
                read(emptyList(), post("a", HOUR), post("b", HOUR), post("c", 600)));
    }

    @Test
    public void bothHooksReadingTheSameListSortItOnce() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();

        assertEquals(List.of("b", "a"), read(list, post("a", 2 * HOUR), post("b", HOUR)));
        // The presenter's post-process reads the list the getItems hook already sorted.
        FeedItemsFilter.filterLateFinal(list);
        assertEquals(List.of("b", "a"), aids(list));
        assertEquals(List.of("b", "a", "d", "c"), read(list, post("c", 3 * HOUR), post("d", 600)));
        FeedItemsFilter.filterLateFinal(list);
        assertEquals(List.of("b", "a", "d", "c"), aids(list));
    }

    @Test
    public void aListPastTheCapKeepsTikToksOrderFromThenOn() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();
        Item[] page = new Item[4097];
        for (int i = 0; i < page.length; i++) page[i] = post("p" + i, i);
        read(list, page);

        List<String> after = read(list, post("old", 5 * HOUR), post("new", 60));
        assertEquals(List.of("old", "new"), after.subList(after.size() - 2, after.size()));
    }

    @Test
    public void aReadOnlyListIsSwappedForASortedCopy() {
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        FollowFeedList list = emptyList();
        add(list, post("a", 3 * HOUR), post("b", HOUR));
        list.mItems = Collections.unmodifiableList(list.mItems);

        assertEquals(List.of("b", "a"), read(list));
    }

    @Test
    public void offKeepsTikToksOrderUntilTheAppRestarts() {
        FollowFeedList list = emptyList();
        assertEquals(List.of("a", "b"), read(list, post("a", 3 * HOUR), post("b", HOUR)));

        // Turned on with the list open: it waits for the restart the row asks for.
        Settings.FOLLOWING_LATEST_FIRST.save(true);
        assertEquals(List.of("a", "b", "c", "d"), read(list, post("c", 2 * HOUR), post("d", HOUR)));
    }

    private static Item post(String aid, long ageSeconds) {
        Item item = new Item(aid);
        item.createTime = NOW_SECONDS - ageSeconds;
        return item;
    }

    private static FollowFeedList emptyList() {
        FollowFeedList list = new FollowFeedList();
        list.mItems = new ArrayList<>();
        return list;
    }

    private static void add(FollowFeedList list, Item... items) {
        for (Item item : items) {
            FollowFeed entry = new FollowFeed();
            entry.aweme = item;
            list.mItems.add(entry);
        }
    }

    /** {@code items} appended the way TikTok grows the list, then the list read through getItems. */
    private static List<String> read(FollowFeedList list, Item... items) {
        add(list, items);
        FeedItemsFilter.filterLate(list);
        return aids(list);
    }

    private static List<String> aids(FollowFeedList list) {
        List<String> aids = new ArrayList<>();
        for (Object entry : list.mItems) {
            Item item = (Item) ((FollowFeed) entry).aweme;
            aids.add(item == null ? "-" : item.getAid());
        }
        return aids;
    }
}
