/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Hide Balance in the profile menu (#194). The patch asks {@link ProfileMenuCells#hide} about each
 * cell of the menu your profile's top right button opens, by id. The switch starts off, hides the
 * Balance cell and nothing else, and leaves TikTok's menu alone while Hushfeed is paused.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ProfileMenuCellsTest {
    private static final String TITLE = "Hide Balance in the profile menu";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_PROFILE_MENU_BALANCE.resetToDefault();
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.resetToDefault();
        SettingsStatus.profileShortcutsEnabled = false;
        SettingsStatus.profileMenuCellsEnabled = false;
        HookStatus.clear();
    }

    @Test public void theSwitchStartsOffAndHidesOnlyBalance() {
        assertEquals("hide_profile_menu_balance", Settings.HIDE_PROFILE_MENU_BALANCE.key);
        assertEquals(Boolean.FALSE, Settings.HIDE_PROFILE_MENU_BALANCE.defaultValue);
        assertFalse("the menu is built each time it opens", Settings.HIDE_PROFILE_MENU_BALANCE.rebootApp);

        assertFalse("off, Balance stays", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        assertTrue(ProfileMenuCells.hide("content_section_cell_balance"));
        assertFalse("another cell stays", ProfileMenuCells.hide("content_section_cell_wallet"));
        assertFalse("a cell with no id stays", ProfileMenuCells.hide(null));
        Settings.HIDE_PROFILE_MENU_BALANCE.save(false);
        assertFalse("turned back off, Balance is back", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        String report = String.join("\n", HookStatus.report());
        assertTrue(report, report.contains("profile menu balance"));
    }

    /**
     * #230: the icons under TikTok Minis are a cell of their own, so the Minis entry stays while
     * its card goes. Each switch moves only its own cell.
     */
    @Test public void theMinisIconsSwitchHidesOnlyTheIconCard() {
        assertEquals("hide_profile_menu_minis_icons", Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key);
        assertEquals(Boolean.FALSE, Settings.HIDE_PROFILE_MENU_MINIS_ICONS.defaultValue);
        assertFalse("the menu is built each time it opens", Settings.HIDE_PROFILE_MENU_MINIS_ICONS.rebootApp);
        assertEquals("content_section_cell_tiktok_minis_rich_card", ProfileMenuCells.MINIS_ICONS);

        assertFalse("off, the icons stay", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(true);
        assertTrue(ProfileMenuCells.hide("content_section_cell_tiktok_minis_rich_card"));
        assertFalse("the Minis entry itself stays", ProfileMenuCells.hide("content_section_cell_tiktok_minis"));
        assertFalse("Balance is its own switch", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        assertTrue(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(false);
        assertFalse("turned back off, the icons are back", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        assertTrue("Balance is unaffected", ProfileMenuCells.hide(ProfileMenuCells.BALANCE));

        Settings.HIDE_PROFILE_MENU_MINIS_ICONS.save(true);
        PausedProcess.set(true);
        assertFalse("paused, TikTok's own menu", ProfileMenuCells.hide(ProfileMenuCells.MINIS_ICONS));
        String report = String.join(" ", HookStatus.report());
        assertTrue(report, report.contains("profile menu minis icons"));
    }

    @Test public void theMinisIconsRowSitsBesideBalanceAndNamesTheEntryItKeeps() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.profileShortcutsEnabled = true;
            SettingsStatus.profileMenuCellsEnabled = true;
            Preference row = build(activity).findPreference(Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key);
            assertNotNull(row);
            assertEquals("Hide the TikTok Minis icons in the profile menu", String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("TikTok Minis entry") && summary.contains("icons"));
            assertFalse("no restart is asked for", summary.contains("Restart TikTok"));

            SettingsStatus.profileMenuCellsEnabled = false;
            assertNull("the row showed on a build where the menu wasn't hooked",
                    build(activity).findPreference(Settings.HIDE_PROFILE_MENU_MINIS_ICONS.key));
        }
    }

    @Test public void aPausedHushfeedLeavesTheMenuAlone() {
        Settings.HIDE_PROFILE_MENU_BALANCE.save(true);
        PausedProcess.set(true);
        assertFalse(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
        PausedProcess.set(false);
        assertTrue(ProfileMenuCells.hide(ProfileMenuCells.BALANCE));
    }

    @Test public void theRowIsOnTheAppPageOnlyWhereTheMenuWasHooked() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);

            SettingsStatus.profileShortcutsEnabled = true;
            assertNull("the row showed on a build where the menu wasn't hooked",
                    build(activity).findPreference(Settings.HIDE_PROFILE_MENU_BALANCE.key));

            SettingsStatus.profileMenuCellsEnabled = true;
            assertTrue(ExtensionPreferenceCategory.isAvailable());
            Preference row = build(activity).findPreference(Settings.HIDE_PROFILE_MENU_BALANCE.key);
            assertNotNull(row);
            assertEquals(TITLE, String.valueOf(row.getTitle()));
            String summary = String.valueOf(row.getSummary());
            assertTrue(summary, summary.contains("Balance") && summary.contains("coins"));
            assertFalse("no restart is asked for", summary.contains("Restart TikTok"));
        }
    }

    private static PreferenceScreen build(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new ExtensionPreferenceCategory(activity, screen);
        return screen;
    }
}
