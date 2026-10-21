/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
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
import org.robolectric.annotation.Config;

/** What TikTok's brightness boost starts ask before they touch the window (issue #200). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CameraBrightnessTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.KEEP_CAMERA_BRIGHTNESS.resetToDefault();
    }

    @Test public void theSwitchStartsOffAndTheBoostRuns() {
        assertEquals("the patch is in the default selection, so its switch starts off",
                Boolean.FALSE, Settings.KEEP_CAMERA_BRIGHTNESS.defaultValue);
        assertFalse(CameraBrightness.keepScreenBrightness());
    }

    @Test public void switchedOnTheBoostIsSkipped() {
        Settings.KEEP_CAMERA_BRIGHTNESS.save(true);
        assertTrue(CameraBrightness.keepScreenBrightness());
        Settings.KEEP_CAMERA_BRIGHTNESS.save(false);
        assertFalse(CameraBrightness.keepScreenBrightness());
    }

    @Test public void pausedTheBoostRunsWithTheSwitchOn() {
        Settings.KEEP_CAMERA_BRIGHTNESS.save(true);
        PausedProcess.set(true);
        assertFalse(CameraBrightness.keepScreenBrightness());
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.cameraBrightnessEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new ExtensionPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("keep_camera_brightness"));
        } finally {
            SettingsStatus.cameraBrightnessEnabled = false;
        }
    }
}
