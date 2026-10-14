/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Show translation options in Language settings (#143). TikTok hides the translation rows on its
 * Language page when either of two gates answers true. The switch starts off, answers each gate
 * false while it is on, never turns a false into true, and leaves TikTok's answer alone while
 * Hushfeed is paused or before the settings context exists.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LanguagePageTranslationsTest {
    private static final String TITLE = "Show translation options in Language settings";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SHOW_TRANSLATION_SETTINGS.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndOnlyItShowsTheRows() {
        assertEquals("show_translation_settings", Settings.SHOW_TRANSLATION_SETTINGS.key);
        assertEquals(Boolean.FALSE, Settings.SHOW_TRANSLATION_SETTINGS.defaultValue);
        assertFalse("the page asks each time it opens", Settings.SHOW_TRANSLATION_SETTINGS.rebootApp);

        assertTrue("off, TikTok hides them", LanguagePageTranslations.hidden(true));
        assertFalse("off, TikTok shows them", LanguagePageTranslations.hidden(false));

        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        assertFalse(LanguagePageTranslations.hidden(true));
        assertFalse("a gate TikTok already opened stays open", LanguagePageTranslations.hidden(false));
        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("language page translations"));
    }

    @Test
    public void aPausedHushfeedLeavesTikTokAlone() {
        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        PausedProcess.set(true);
        assertTrue(LanguagePageTranslations.hidden(true));
        PausedProcess.set(false);
        assertFalse(LanguagePageTranslations.hidden(true));
    }

    @Test
    public void withoutAContextTheGateIsTikToks() {
        Settings.SHOW_TRANSLATION_SETTINGS.save(true);
        Utils.setContext(null);
        try {
            assertTrue(LanguagePageTranslations.hidden(true));
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertFalse(LanguagePageTranslations.hidden(true));
    }

    /** The row shows on the Comments page only where the patch found both gates. */
    @Test
    public void theRowShowsOnlyWhereTheGatesWereHooked() {
        boolean status = SettingsStatus.languagePageTranslationsEnabled;
        try (var controller = Robolectric.buildActivity(DoNotAutoTranslateTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.languagePageTranslationsEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, without);
            assertNull("the row showed on a build where the gates weren't hooked",
                    without.findPreference(Settings.SHOW_TRANSLATION_SETTINGS.key));

            SettingsStatus.languagePageTranslationsEnabled = true;
            assertTrue("the Comments page opens for this row alone", CommentsPreferenceCategory.isAvailable());
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, with);
            Preference row = with.findPreference(Settings.SHOW_TRANSLATION_SETTINGS.key);
            assertNotNull(row);
            assertEquals(TITLE, String.valueOf(row.getTitle()));
        } finally {
            SettingsStatus.languagePageTranslationsEnabled = status;
        }
    }
}
