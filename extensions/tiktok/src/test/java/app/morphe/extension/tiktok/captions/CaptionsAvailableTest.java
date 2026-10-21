/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

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
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Keep captions available (#217). The patch hands TikTok's server answer for the Accessibility
 * page's Captions row to {@link CaptionsAvailable#captionsRow} and asks {@link
 * CaptionsAvailable#captionControls} before TikTok's caption controls question. A row the server
 * left out (0) is placed first while the switch is on, a row the server placed (1 first, 2 last)
 * stays where it is, and with the switch off, while paused or before the context exists every
 * answer is TikTok's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionsAvailableTest {
    private static final String TITLE = "Keep captions available";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After
    public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.KEEP_CAPTIONS_AVAILABLE.resetToDefault();
        HookStatus.clear();
    }

    @Test
    public void theSwitchStartsOffAndAsksForARestart() {
        assertEquals("keep_captions_available", Settings.KEEP_CAPTIONS_AVAILABLE.key);
        assertEquals(Boolean.FALSE, Settings.KEEP_CAPTIONS_AVAILABLE.defaultValue);
        assertTrue("TikTok reads both values once per launch", Settings.KEEP_CAPTIONS_AVAILABLE.rebootApp);
    }

    @Test
    public void onTheServersZeroPlacesTheRowFirst() {
        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
        assertEquals(CaptionsAvailable.ROW_FIRST, CaptionsAvailable.captionsRow(0));
        assertEquals("TikTok's answer for the row first", 1, CaptionsAvailable.ROW_FIRST);
        assertTrue(CaptionsAvailable.captionControls());

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("captions available"));
    }

    @Test
    public void aRowTheServerPlacedStaysWhereItIs() {
        for (boolean on : new boolean[] {false, true}) {
            Settings.KEEP_CAPTIONS_AVAILABLE.save(on);
            assertEquals("switch " + on + ": first", 1, CaptionsAvailable.captionsRow(1));
            assertEquals("switch " + on + ": last", 2, CaptionsAvailable.captionsRow(2));
            assertEquals("switch " + on + ": a value TikTok adds later", 7, CaptionsAvailable.captionsRow(7));
        }
    }

    @Test
    public void offEveryAnswerIsTikToks() {
        assertEquals(0, CaptionsAvailable.captionsRow(0));
        assertFalse("TikTok answers the controls question", CaptionsAvailable.captionControls());

        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
        assertEquals(1, CaptionsAvailable.captionsRow(0));
        Settings.KEEP_CAPTIONS_AVAILABLE.save(false);
        assertEquals("turned back off, the next read is TikTok's", 0, CaptionsAvailable.captionsRow(0));
        assertFalse(CaptionsAvailable.captionControls());
    }

    @Test
    public void aPausedHushfeedLeavesTikTokAlone() {
        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
        PausedProcess.set(true);
        assertEquals(0, CaptionsAvailable.captionsRow(0));
        assertFalse(CaptionsAvailable.captionControls());
        PausedProcess.set(false);
        assertEquals(1, CaptionsAvailable.captionsRow(0));
        assertTrue(CaptionsAvailable.captionControls());
    }

    @Test
    public void withoutAContextTheAnswersAreTikToks() {
        Settings.KEEP_CAPTIONS_AVAILABLE.save(true);
        Utils.setContext(null);
        try {
            assertEquals(0, CaptionsAvailable.captionsRow(0));
            assertFalse(CaptionsAvailable.captionControls());
        } finally {
            Utils.setContext(RuntimeEnvironment.getApplication());
        }
        assertEquals(1, CaptionsAvailable.captionsRow(0));
    }

    /** A build where only this hook landed still opens the Feed screen page. */
    @Test
    public void thisRowAloneOpensTheFeedScreenPage() throws IllegalAccessException {
        Map<Field, Boolean> saved = new HashMap<>();
        for (Field flag : SettingsStatus.class.getFields()) {
            int modifiers = flag.getModifiers();
            if (flag.getType() != boolean.class || !Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) continue;
            saved.put(flag, flag.getBoolean(null));
            flag.setBoolean(null, false);
        }
        try {
            assertFalse("every flag off, nothing to show", InterfacePreferenceCategory.isAvailable());
            SettingsStatus.captionsAvailableEnabled = true;
            assertTrue(InterfacePreferenceCategory.isAvailable());
        } finally {
            for (Map.Entry<Field, Boolean> flag : saved.entrySet()) flag.getKey().setBoolean(null, flag.getValue());
        }
    }

    /** The row shows under Captions only where the patch hooked both reads. */
    @Test
    public void theRowShowsOnlyWhereTheReadsWereHooked() {
        boolean hooked = SettingsStatus.captionsAvailableEnabled;
        boolean subtitles = SettingsStatus.subtitleToolsEnabled;
        try (var controller = Robolectric.buildActivity(
                app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.subtitleToolsEnabled = true;

            SettingsStatus.captionsAvailableEnabled = false;
            PreferenceScreen without = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, without);
            assertNull("the row showed on a build where nothing was hooked",
                    without.findPreference(Settings.KEEP_CAPTIONS_AVAILABLE.key));
            assertNotNull("the other caption rows stay", without.findPreference(Settings.KEEP_CAPTIONS_CLEAR_DISPLAY.key));

            SettingsStatus.captionsAvailableEnabled = true;
            PreferenceScreen with = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, with);
            Preference row = with.findPreference(Settings.KEEP_CAPTIONS_AVAILABLE.key);
            assertNotNull(row);
            assertTrue(row instanceof TogglePreference);
            assertEquals(TITLE, String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("accessibility settings") && summary.contains("videos that have them"));
            assertTrue(summary, summary.endsWith(TogglePreference.RESTART_SENTENCE));
            assertNotNull(with.findPreference(Settings.KEEP_CAPTIONS_CLEAR_DISPLAY.key));
        } finally {
            SettingsStatus.captionsAvailableEnabled = hooked;
            SettingsStatus.subtitleToolsEnabled = subtitles;
        }
    }
}
