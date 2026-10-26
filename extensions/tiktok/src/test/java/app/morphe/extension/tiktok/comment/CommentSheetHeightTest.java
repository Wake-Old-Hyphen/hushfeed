/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What TikTok's comment sheet sizing asks before it picks a height (issue #191). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentSheetHeightTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.COMMENTS_FULL_HEIGHT.resetToDefault();
    }

    @Test public void theSwitchStartsOffAndTikTokPicksTheHeight() {
        assertEquals("the patch is in the default selection, so its switch starts off",
                Boolean.FALSE, Settings.COMMENTS_FULL_HEIGHT.defaultValue);
        assertFalse(CommentSheetHeight.openAtFullHeight());
        assertEquals(0, CommentSheetHeight.heightMode(0));
        assertEquals(-1, CommentSheetHeight.heightMode(-1));
        assertEquals(2, CommentSheetHeight.heightMode(2));
    }

    @Test public void switchedOnTheSheetTakesTheFullHeight() {
        Settings.COMMENTS_FULL_HEIGHT.save(true);
        assertTrue(CommentSheetHeight.openAtFullHeight());
        Settings.COMMENTS_FULL_HEIGHT.save(false);
        assertFalse(CommentSheetHeight.openAtFullHeight());
    }

    @Test public void switchedOnAModeWithNoShareIsLiftedAndTheOthersStay() {
        Settings.COMMENTS_FULL_HEIGHT.save(true);
        assertEquals("a mode of 0 never asks for the height, so it becomes one that does",
                1, CommentSheetHeight.heightMode(0));
        assertEquals(1, CommentSheetHeight.heightMode(-3));
        for (int mode = 1; mode <= 5; mode++) {
            assertEquals(mode, CommentSheetHeight.heightMode(mode));
        }
    }

    @Test public void pausedTikTokPicksTheHeightWithTheSwitchOn() {
        Settings.COMMENTS_FULL_HEIGHT.save(true);
        PausedProcess.set(true);
        assertFalse(CommentSheetHeight.openAtFullHeight());
        assertEquals(0, CommentSheetHeight.heightMode(0));
    }

    @Test public void theFullHeightStopsAtTheSystemBars() {
        // #261: TikTok's full height can be the display's whole height, status bar and all.
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup().visible()) {
            var activity = controller.get();
            var decor = activity.getWindow().getDecorView();
            decor.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 720, 1600);
            android.graphics.Rect bars = app.morphe.extension.tiktok.settings.SystemBarInsets.current(decor);
            int room = 1600 - bars.top - bars.bottom;

            assertEquals("a height past the bars is trimmed to the room between them",
                    room, CommentSheetHeight.belowStatusBar(activity, 1600 + 80));
            assertEquals("a height that fits is left alone",
                    room - 1, CommentSheetHeight.belowStatusBar(activity, room - 1));
            // A wrapped context is the activity's all the same.
            assertEquals(room, CommentSheetHeight.belowStatusBar(
                    new android.content.ContextWrapper(activity), 5000));
        }
    }

    @Test public void withNoWindowTheSiblingsHeightStands() {
        assertEquals(2000, CommentSheetHeight.belowStatusBar(null, 2000));
        assertEquals(2000, CommentSheetHeight.belowStatusBar(
                org.robolectric.RuntimeEnvironment.getApplication(), 2000));
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.commentsFullHeightEnabled = true;
            assertTrue(CommentsPreferenceCategory.isAvailable());
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            CommentsPreferenceCategory category = new CommentsPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("comments_full_height"));
            // With only this patch in the APK, the row still opens under its own heading.
            String heading = null;
            boolean found = false;
            for (int index = 0; index < category.getPreferenceCount() && !found; index++) {
                Preference row = category.getPreference(index);
                if (row instanceof SectionHeadingPreference) heading = String.valueOf(row.getTitle());
                found = "comments_full_height".equals(row.getKey());
            }
            assertTrue("the row is not a direct child of the Comments page", found);
            assertEquals("the row sits under the wrong heading", "Reading", heading);
        } finally {
            SettingsStatus.commentsFullHeightEnabled = false;
        }
    }
}
