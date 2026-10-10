/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.ExtensionPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Keep the app language's switch (#61). It starts off and a paused build answers off. TikTok's
 * reset runs in the application's attachBaseContext, before the settings context and before
 * ActivityThread has an application, so the answer is read from the saved file through the
 * context the reset is handed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppLanguageTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        EarlyApplication.reset();
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_APP_LANGUAGE.save(false);
        saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, false);
        SettingsStatus.appLanguageEnabled = false;
    }

    /** The early read opens the application's own preferences file, as in PasskeySignInTest. */
    private static void saveInTheFile(String key, boolean value) {
        RuntimeEnvironment.getApplication()
                .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    @Test public void theSwitchStartsOffAndOnlyItKeepsTheLanguage() {
        Context application = RuntimeEnvironment.getApplication();
        assertEquals(Boolean.FALSE, Settings.KEEP_APP_LANGUAGE.defaultValue);
        assertEquals(AppLanguage.SWITCH_KEY, Settings.KEEP_APP_LANGUAGE.key);
        assertFalse("the reset runs once a start, so nothing to restart for", Settings.KEEP_APP_LANGUAGE.rebootApp);

        assertFalse("off, TikTok's reset runs as it ships", AppLanguage.keepChosenLanguage(application));

        Settings.KEEP_APP_LANGUAGE.save(true);
        assertTrue(AppLanguage.keepChosenLanguage(application));

        PausedProcess.set(true);
        assertFalse("paused, TikTok's reset runs as it ships", AppLanguage.keepChosenLanguage(application));
    }

    @Test public void beforeTheSettingsContextTheResetsOwnContextReadsTheFile() {
        Context application = RuntimeEnvironment.getApplication();
        Settings.KEEP_APP_LANGUAGE.save(true);
        saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, true);
        // Inside attachBaseContext ActivityThread has no application yet.
        EarlyApplication.set(null);
        Utils.setContext(null);
        try {
            assertTrue("read through the reset's context", AppLanguage.keepChosenLanguage(application));
            assertFalse("no context and no application answers off", AppLanguage.keepChosenLanguage(null));

            EarlyApplication.set(application);
            assertTrue("a null context falls back to the application", AppLanguage.keepChosenLanguage(null));

            saveInTheFile(Settings.KEEP_APP_LANGUAGE.key, false);
            assertFalse(AppLanguage.keepChosenLanguage(application));
        } finally {
            Utils.setContext(application);
        }
    }

    @Test public void theRowIsOnTheAppPageOnlyWhenPatched() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            assertNull(build(activity).findPreference(Settings.KEEP_APP_LANGUAGE.key));

            SettingsStatus.appLanguageEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.KEEP_APP_LANGUAGE.key);
            assertNotNull(row);
            assertEquals("Keep the language picked in TikTok", String.valueOf(row.getTitle()));
            assertFalse("no restart note", String.valueOf(row.getSummary()).contains("Restart"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
