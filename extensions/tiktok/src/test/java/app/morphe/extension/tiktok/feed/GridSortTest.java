/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeStatistics;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * Sorting the videos a profile or Liked grid has loaded (#180): every order, pinned videos kept
 * in front, videos with no value last, ties and missing values in TikTok's order, new pages
 * sorted in without repeats, and TikTok's own list left alone.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class GridSortTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class Counts extends AwemeStatistics {
        final long plays;
        final long likes;
        Counts(long plays, long likes) { this.plays = plays; this.likes = likes; }
        @Override public long getPlayCount() { return plays; }
        @Override public long getDiggCount() { return likes; }
    }

    public static final class Clip {
        final long duration;
        Clip(long duration) { this.duration = duration; }
        public Object getDuration() { return duration; }
    }

    /** A grid cell's post: id, posted time, length in ms, views, likes and whether it is pinned. */
    public static final class Post extends Aweme {
        final String id;
        final long created;
        final Clip clip;
        final Counts counts;
        final int top;

        Post(String id, long created, long length, long views, long likes, boolean pinned) {
            this.id = id;
            this.created = created;
            this.clip = new Clip(length);
            this.counts = new Counts(views, likes);
            this.top = pinned ? 1 : 0;
        }

        public Object getVideo() { return clip; }
        public int getIsTop() { return top; }
        @Override public String getAid() { return id; }
        @Override public long getCreateTime() { return created; }
        @Override public AwemeStatistics getStatistics() { return counts; }
        @Override public List getImageInfos() { return null; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return null; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
        @Override public String toString() { return id; }
    }

    /** The adapter, as far as the extension knows it: something with notifyDataSetChanged. */
    public static final class Adapter {
        int refreshes;
        public void notifyDataSetChanged() { refreshes++; }
    }

    private boolean gridSortEnabled;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        gridSortEnabled = SettingsStatus.gridSortEnabled;
    }

    @After public void tearDown() {
        setPaused(false);
        Settings.PROFILE_GRID_SORT.resetToDefault();
        SettingsStatus.gridSortEnabled = gridSortEnabled;
        SettingsStatus.alwaysShowPublishDateEnabled = false;
        HookStatus.clear();
    }

    private static void setPaused(boolean value) {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess",
                ClassParameter.from(boolean.class, value));
    }

    /** a is pinned; b has no length or counts; c and d tie on every number; e is the biggest. */
    private static List<Aweme> grid() {
        return new ArrayList<>(Arrays.asList(
                new Post("a", 100, 30_000, 10, 1, true),
                new Post("b", 0, 0, 0, 0, false),
                new Post("c", 300, 20_000, 500, 50, false),
                new Post("d", 300, 20_000, 500, 50, false),
                new Post("e", 900, 90_000, 9_000, 800, false),
                new Post("f", 200, 5_000, 70, 90, false),
                new Post("p", 50, 1_000, 5, 5, true)));
    }

    private static String ids(List<?> list) {
        StringBuilder out = new StringBuilder();
        for (Object item : list) out.append(item);
        return out.toString();
    }

    private static String order(String choice, List<Aweme> grid) {
        Settings.PROFILE_GRID_SORT.save(choice);
        return ids(GridSort.data(grid));
    }

    @Test public void theSortStartsOffAndLeavesTheListAlone() {
        assertEquals("profile_grid_sort", Settings.PROFILE_GRID_SORT.key);
        assertEquals("off", Settings.PROFILE_GRID_SORT.defaultValue);
        List<Aweme> grid = grid();
        assertSame("off, TikTok's own list", grid, GridSort.data(grid));
        Settings.PROFILE_GRID_SORT.save("off");
        assertSame(grid, GridSort.data(grid));
    }

    @Test public void newestAndOldest() {
        // Pinned a and p stay first as TikTok has them, then the rest by time, 0 (no time) last.
        assertEquals("apecdfb", order("newest", grid()));
        assertEquals("apfcdeb", order("oldest", grid()));
    }

    @Test public void longestAndShortest() {
        assertEquals("apecdfb", order("longest", grid()));
        assertEquals("apfcdeb", order("shortest", grid()));
    }

    @Test public void mostViewedAndMostLiked() {
        assertEquals("apecdfb", order("most_viewed", grid()));
        assertEquals("apefcdb", order("most_liked", grid()));
    }

    @Test public void tiesAndMissingValuesKeepTikToksOrder() {
        List<Aweme> grid = new ArrayList<>(Arrays.asList(
                new Post("x", 0, 0, 0, 0, false),
                new Post("y", 5, 5_000, 5, 5, false),
                new Post("z", 0, 0, 0, 0, false),
                new Post("w", 5, 5_000, 5, 5, false)));
        assertEquals("ywxz", order("newest", grid));
        assertEquals("ywxz", order("longest", grid));
        assertEquals("ywxz", order("most_viewed", grid));
        // The same call twice gives the same grid.
        assertEquals(ids(GridSort.data(grid)), ids(GridSort.data(grid)));
    }

    @Test public void theListTikTokOwnsIsNeverChangedAndNothingIsLostOrRepeated() {
        List<Aweme> grid = grid();
        String before = ids(grid);
        Settings.PROFILE_GRID_SORT.save("newest");
        List<?> sorted = GridSort.data(grid);
        assertNotSame(grid, sorted);
        assertEquals("TikTok's list keeps its order", before, ids(grid));
        assertEquals(grid.size(), sorted.size());
        for (Aweme post : grid) assertTrue("same post objects", sorted.contains(post));
    }

    @Test public void aNewPageIsSortedInWithTheRestWithoutRepeats() {
        List<Aweme> model = new ArrayList<>(Arrays.asList(
                new Post("a", 10, 1_000, 1, 1, false),
                new Post("b", 30, 1_000, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertEquals("ba", ids(GridSort.data(model)));
        // TikTok appends the next page to its own list and hands the whole list over again.
        model.add(new Post("c", 20, 1_000, 1, 1, false));
        model.add(new Post("d", 40, 1_000, 1, 1, false));
        Adapter adapter = new Adapter();
        List<?> kept = GridSort.page(adapter, model);
        assertEquals("dbca", ids(kept));
        assertEquals("a refresh is queued behind TikTok's own call", 0, adapter.refreshes);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(1, adapter.refreshes);
    }

    @Test public void aPageThatMovesNothingIsNotRefreshedAndOffLeavesTikToksAnnouncement() {
        List<Aweme> model = new ArrayList<>(Arrays.asList(
                new Post("b", 30, 1_000, 1, 1, false),
                new Post("a", 10, 1_000, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        Adapter adapter = new Adapter();
        GridSort.page(adapter, model);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("already in order, nothing moved", 0, adapter.refreshes);

        Settings.PROFILE_GRID_SORT.save("off");
        Adapter quiet = new Adapter();
        assertSame(model, GridSort.page(quiet, model));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("off, TikTok's own announcement stands", 0, quiet.refreshes);
    }

    @Test public void pausedHushfeedAndUnknownChoicesLeaveTikToksOrder() {
        List<Aweme> grid = grid();
        Settings.PROFILE_GRID_SORT.save("newest");
        setPaused(true);
        assertSame("paused", grid, GridSort.data(grid));
        setPaused(false);
        assertNotSame(grid, GridSort.data(grid));

        Settings.PROFILE_GRID_SORT.save("sideways");
        assertSame("a choice this build doesn't know", grid, GridSort.data(grid));
        assertSame(null, GridSort.data(null));
        List<Aweme> one = new ArrayList<>(List.of(new Post("a", 1, 1, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertSame("one video has no order", one, GridSort.data(one));
    }

    @Test public void thingsThatAreNotPostsStayWhereTheyAre() {
        List<Object> mixed = new ArrayList<>(Arrays.asList("note", new Post("a", 5, 1, 1, 1, false), 7,
                new Post("b", 9, 1, 1, 1, false)));
        Settings.PROFILE_GRID_SORT.save("newest");
        assertEquals("banote7", ids(GridSort.data(mixed)));
    }

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @Test public void theRowSaysLoadedVideosAndShowsOnlyWhereTheAdapterWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.alwaysShowPublishDateEnabled = true;

            SettingsStatus.gridSortEnabled = false;
            assertEquals(null, build(activity).findPreference(Settings.PROFILE_GRID_SORT.key));

            SettingsStatus.gridSortEnabled = true;
            Preference row = build(activity).findPreference(Settings.PROFILE_GRID_SORT.key);
            assertTrue(row instanceof ListPreference);
            assertEquals("Sort loaded videos on profile grids", String.valueOf(row.getTitle()));
            ListPreference list = (ListPreference) row;
            assertEquals("Original order", String.valueOf(list.getEntries()[0]));
            assertEquals("off", String.valueOf(list.getEntryValues()[0]));
            assertEquals(7, list.getEntryValues().length);
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("loaded so far") && summary.contains("pinned"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new InterfacePreferenceCategory(activity, screen);
        return screen;
    }
}
