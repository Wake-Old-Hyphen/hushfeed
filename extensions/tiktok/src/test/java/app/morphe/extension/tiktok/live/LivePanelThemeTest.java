package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import android.content.Context;
import android.content.res.Configuration;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment;
import com.bytedance.android.live.design.view.sheet.LiveSheetFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * #159: a LIVE panel's views inflate through its own (dark) LIVE overlay, applied with force,
 * only while Dark LIVE panels is on and its context is in dark mode, and only for themed dialogs
 * and sheet pages. Everything else gets TikTok's inflater back untouched.
 */
@RunWith(RobolectricTestRunner.class)
public class LivePanelThemeTest {
    private static final int OVERLAY = android.R.style.Theme_Light;

    /** A room's own fragment: it has an overlay too, but it isn't a panel. */
    public static final class RoomFragment {
        public int getThemeOverlay(Context context) {
            return OVERLAY;
        }
    }

    /** A dialog that extends the themed base, as LiveShareDialog does through LiveDialogFragment. */
    public static final class ShareDialog extends LiveThemeAwareDialogFragment {
    }

    private Context dark;
    private Context light;

    @Before public void setUp() {
        Context app = RuntimeEnvironment.getApplication();
        Utils.setContext(app);
        SettingsStatus.liveControlsEnabled = true;
        Configuration night = new Configuration(app.getResources().getConfiguration());
        night.uiMode = Configuration.UI_MODE_NIGHT_YES | Configuration.UI_MODE_TYPE_NORMAL;
        dark = new ContextThemeWrapper(app.createConfigurationContext(night), android.R.style.Theme_Black);
        Configuration day = new Configuration(night);
        day.uiMode = Configuration.UI_MODE_NIGHT_NO | Configuration.UI_MODE_TYPE_NORMAL;
        light = new ContextThemeWrapper(app.createConfigurationContext(day), android.R.style.Theme_Black);
    }

    @After public void tearDown() {
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        Settings.DARK_LIVE_PANELS.save(Settings.DARK_LIVE_PANELS.defaultValue);
    }

    private static int textColor(Context context) {
        TypedValue value = new TypedValue();
        assertTrue(context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true));
        return value.resourceId != 0 ? value.resourceId : value.data;
    }

    private static ShareDialog dialog() {
        ShareDialog dialog = new ShareDialog();
        dialog.overlay = OVERLAY;
        return dialog;
    }

    @Test public void aPanelInflatesThroughItsOverlayWithForceInDarkModeWithTheSwitch() {
        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("the switch starts off", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));

        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater wrapped = LivePanelTheme.darkLivePanel(dialog(), tiktok);
        assertNotSame(tiktok, wrapped);
        // The overlay wins over the context's own theme, the way TikTok's light wrap does.
        assertEquals(textColor(new ContextThemeWrapper(dark, OVERLAY)), textColor(wrapped.getContext()));
        assertTrue(textColor(dark) != textColor(wrapped.getContext()));

        LiveSheetFragment sheet = new LiveSheetFragment();
        sheet.overlay = OVERLAY;
        assertNotSame("a sheet page is a panel", tiktok, LivePanelTheme.darkLivePanel(sheet, tiktok));
    }

    @Test public void lightModeRoomFragmentsPauseAndMissingPiecesKeepTikToksInflater() {
        Settings.DARK_LIVE_PANELS.save(true);
        LayoutInflater day = LayoutInflater.from(light);
        assertSame("light mode", day, LivePanelTheme.darkLivePanel(dialog(), day));

        LayoutInflater tiktok = LayoutInflater.from(dark);
        assertSame("a room's own fragment", tiktok, LivePanelTheme.darkLivePanel(new RoomFragment(), tiktok));
        assertSame("no panel", tiktok, LivePanelTheme.darkLivePanel(null, tiktok));
        assertSame(null, LivePanelTheme.darkLivePanel(dialog(), null));
        ShareDialog none = new ShareDialog();
        assertSame("no overlay", tiktok, LivePanelTheme.darkLivePanel(none, tiktok));

        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, true));
        assertSame("paused", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
        ReflectionHelpers.callStaticMethod(Setting.class, "setPausedForProcess", ClassParameter.from(boolean.class, false));
        SettingsStatus.liveControlsEnabled = false;
        assertSame("without the patch", tiktok, LivePanelTheme.darkLivePanel(dialog(), tiktok));
    }

    @Test public void panelsAreFoundByTikToksClassNames() {
        assertEquals(LiveThemeAwareDialogFragment.class.getName(), LivePanelTheme.THEMED_DIALOG);
        assertEquals(LiveSheetFragment.class.getName(), LivePanelTheme.SHEET);
        assertTrue(LivePanelTheme.isPanel(ShareDialog.class));
        assertTrue(!LivePanelTheme.isPanel(RoomFragment.class));
    }
}
