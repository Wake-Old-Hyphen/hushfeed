package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
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
 * page its result opened and the reader comes Back, the result SHALL show the new value.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchSummaryTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

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
        TikTokPreferenceFragment search = attachSearch();
        String query = "caption background";
        Field input = TikTokPreferenceFragment.class.getDeclaredField("searchInput");
        input.setAccessible(true);
        ((SettingsSearchInputPreference) input.get(search)).setQuery(query);
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(search, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        String before = String.valueOf(row(search, "Caption background").getSummary());
        assertEquals(before, "Dark", before.substring(before.lastIndexOf(" · ") + 3));

        // Its page opens over the results and the reader picks another value there, then Back
        // brings the results to the front again.
        search.onPause();
        Settings.CAPTION_BACKGROUND.save("default");
        search.onResume();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        String after = String.valueOf(row(search, "Caption background").getSummary());
        assertEquals("the result kept the value from before its page changed it",
                "TikTok default", after.substring(after.lastIndexOf(" · ") + 3));
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

    private static TikTokPreferenceFragment attachSearch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
