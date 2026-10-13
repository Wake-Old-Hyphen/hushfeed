/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/** The home page ends with Report a problem, then the Ko-fi row, and both open the browser. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsFooterRowsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void reportAProblemSitsAboveTheKoFiRowWhichIsLast() throws Exception {
        try (ActivityController<Activity> owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment home = new TikTokPreferenceFragment();
            activity.getFragmentManager().beginTransaction().replace(android.R.id.content, home).commit();
            activity.getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            PreferenceScreen screen = home.getPreferenceScreen();
            int count = screen.getPreferenceCount();
            Preference support = screen.getPreference(count - 1);
            Preference report = screen.getPreference(count - 2);
            assertEquals(TikTokPreferenceFragment.SUPPORT_KEY, support.getKey());
            assertEquals("Support Hushfeed", String.valueOf(support.getTitle()));
            assertEquals("Buy me a coffee on Ko-fi", String.valueOf(support.getSummary()));
            assertEquals(TikTokPreferenceFragment.REPORT_PROBLEM_KEY, report.getKey());
            assertEquals("Report a problem", String.valueOf(report.getTitle()));
            assertEquals("Opens a GitHub report with your versions and settings filled in. Add what happened.",
                    String.valueOf(report.getSummary()));

            assertTrue(support.getOnPreferenceClickListener().onPreferenceClick(support));
            Intent kofi = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("the Ko-fi row opened nothing", kofi);
            assertEquals(Intent.ACTION_VIEW, kofi.getAction());
            assertEquals("https://ko-fi.com/X8K126YVER", String.valueOf(kofi.getData()));

            assertTrue(report.getOnPreferenceClickListener().onPreferenceClick(report));
            Intent issue = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull("Report a problem opened nothing", issue);
            assertEquals(Intent.ACTION_VIEW, issue.getAction());
            assertTrue(String.valueOf(issue.getData()), String.valueOf(issue.getData()).startsWith(
                    "https://github.com/SysAdminDoc/HushFeed/issues/new?template=bug_report.yml&"));
        }
    }
}
