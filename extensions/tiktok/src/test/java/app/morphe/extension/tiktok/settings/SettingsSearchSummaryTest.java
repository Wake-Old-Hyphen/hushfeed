package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.LicensesPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsSearchInputPreference;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

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

/**
 * A result copies its setting's value into its second line. WHEN a list setting is changed on the
 * page its result opened and the reader comes Back, the result SHALL show the new value. WHEN the
 * search only pauses (the screen going off), or a result only opens a dialog, its results SHALL
 * stay as they were, unrebuilt.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchSummaryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String QUERY = "caption background";
    private static final String TITLE = "Caption background";

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        Settings.CAPTION_BACKGROUND.resetToDefault();
    }

    @Test public void aListSettingChangedOnItsPageShowsItsNewValueBackOnTheResults() throws Exception {
        Settings.CAPTION_BACKGROUND.save("dark");
        TikTokPreferenceFragment search = searchFor(attachSearch());
        assertEquals("Dark", value(row(search, TITLE)));

        // Its page opens over the results and the reader picks another value there, then Back
        // brings the results to the front again.
        Preference result = row(search, TITLE);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        search.getFragmentManager().executePendingTransactions();
        idle();
        assertNull("the result's page didn't open over the search", search.getView());
        Settings.CAPTION_BACKGROUND.save("default");
        assertTrue(search.getFragmentManager().popBackStackImmediate());
        idle();

        assertEquals("the result kept the value from before its page changed it",
                "TikTok default", value(row(search, TITLE)));
    }

    @Test public void aPlainPauseLeavesTheResultsAsTheyWere() throws Exception {
        TikTokPreferenceFragment search = searchFor(attachSearch());
        Object index = searchIndex(search);
        Preference before = row(search, TITLE);

        search.onPause();
        search.onResume();
        idle();

        assertTrue("the index was rebuilt after a pause that opened nothing", index == searchIndex(search));
        assertSame("the results were redrawn after a pause that opened nothing", before, row(search, TITLE));

        // Opening a result still owes a rebuild on the way back.
        assertTrue(before.getOnPreferenceClickListener().onPreferenceClick(before));
        search.getFragmentManager().executePendingTransactions();
        idle();
        assertTrue(search.getFragmentManager().popBackStackImmediate());
        idle();
        assertTrue("the index wasn't rebuilt after its result's page", index != searchIndex(search));
    }

    @Test public void aResultThatOnlyOpensADialogLeavesTheResultsAsTheyWere() throws Exception {
        TikTokPreferenceFragment search = searchFor(attachSearch(), "licenses");
        Object index = searchIndex(search);
        Preference licenses = row(search, LicensesPreference.title(search.getActivity()));

        assertTrue(licenses.getOnPreferenceClickListener().onPreferenceClick(licenses));
        idle();
        search.onPause();
        search.onResume();
        idle();

        assertTrue("the index was rebuilt after the licenses notice", index == searchIndex(search));
    }

    private static TikTokPreferenceFragment searchFor(TikTokPreferenceFragment search) throws Exception {
        return searchFor(search, QUERY);
    }

    private static TikTokPreferenceFragment searchFor(TikTokPreferenceFragment search, String query) throws Exception {
        Field input = TikTokPreferenceFragment.class.getDeclaredField("searchInput");
        input.setAccessible(true);
        ((SettingsSearchInputPreference) input.get(search)).setQuery(query);
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(search, query);
        idle();
        return search;
    }

    private static Object searchIndex(TikTokPreferenceFragment search) throws Exception {
        Field index = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        index.setAccessible(true);
        return index.get(search);
    }

    /** The setting's value, the last part of a result's second line. */
    private static String value(Preference row) {
        String summary = String.valueOf(row.getSummary());
        return summary.substring(summary.lastIndexOf(" · ") + 3);
    }

    private static Preference row(TikTokPreferenceFragment fragment, String title) {
        PreferenceScreen screen = fragment.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row.getTitle() != null && title.contentEquals(row.getTitle())) return row;
        }
        assertNotNull("the results lost " + title, null);
        return null;
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static TikTokPreferenceFragment attachSearch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        idle();
        return fragment;
    }
}
