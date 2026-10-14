package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * See-through status bar (#145): on the main feed the spacer TikTok keeps above each video for
 * the status bar goes and the bar's color is cleared, so the video reaches the top edge under the
 * clock. Off and Pause give both back exactly, Hide the status bar wins, and a video opened from
 * a profile or search is left as TikTok draws it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SeeThroughStatusBarTest {
    private static final int CELL_ID = 0x7f0a0211;
    private static final int SPACER_ID = 0x7f0a0d31;
    private static final int BAND_ID = 0x7f0a0d32;
    private static final int OWN_BAR_COLOR = 0xFF121212;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.1.4:dux", SPACER_ID);
        VideoOverlayHider.resolveForTests("47.1.4:wll", BAND_ID);
    }

    @After
    public void tearDown() {
        Settings.SEE_THROUGH_STATUS_BAR.resetToDefault();
        Settings.HIDE_STATUS_BAR.resetToDefault();
        PausedProcess.set(false);
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.1.4:dux", 0);
        VideoOverlayHider.resolveForTests("47.1.4:wll", 0);
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    /** A feed cell as 47.1.4 lays it out on a tall screen: the bar's spacer, then the video. */
    private static View feedCell(Activity activity, int spacerVisibility) {
        FrameLayout root = new FrameLayout(activity);
        FrameLayout cell = new FrameLayout(activity);
        cell.setId(CELL_ID);
        LinearLayout area = new LinearLayout(activity);
        area.setOrientation(LinearLayout.VERTICAL);
        View spacer = new View(activity);
        spacer.setId(SPACER_ID);
        spacer.setVisibility(spacerVisibility);
        area.addView(spacer, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 75));
        area.addView(new View(activity), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        cell.addView(area);
        root.addView(cell);
        activity.setContentView(root);
        return spacer;
    }

    @Test
    public void theVideoGoesUpUnderTheBarAndOffGivesEverythingBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            int flags = activity.getWindow().getAttributes().flags;
            int cutout = activity.getWindow().getAttributes().layoutInDisplayCutoutMode;
            int systemUi = activity.getWindow().getDecorView().getSystemUiVisibility();

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.INVISIBLE, spacer.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the strip above the video stayed", View.GONE, spacer.getVisibility());
            assertEquals("the bar wasn't made see through", Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
            assertEquals("the window's flags changed", flags, activity.getWindow().getAttributes().flags);
            assertEquals(cutout, activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
            assertEquals("the bar was hidden", systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());

            // Every layout runs a pass; another changes nothing.
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            // TikTok showing the spacer or coloring the bar again on a page change.
            spacer.setVisibility(View.VISIBLE);
            activity.getWindow().setStatusBarColor(Color.BLACK);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the spacer back", View.VISIBLE, spacer.getVisibility());
            assertEquals("off didn't give TikTok's latest color back", Color.BLACK, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            assertEquals(flags, activity.getWindow().getAttributes().flags);
            assertEquals(systemUi, activity.getWindow().getDecorView().getSystemUiVisibility());
        }
    }

    /**
     * The S22 kept a black strip under the clock with the switch on: the spacer was gone and the
     * video was up there, but TikTok's own band, as tall as the bar, lies over the top of each
     * cell. It goes with the spacer, invisible so it keeps its place, and comes back with it.
     */
    @Test
    public void theBlackBandOverTheTopOfTheCellGoesWithTheSpacer() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            FrameLayout cell = activity.findViewById(CELL_ID);
            View band = new View(activity);
            band.setId(BAND_ID);
            band.setBackgroundColor(Color.BLACK);
            cell.addView(band, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 75));

            VideoOverlayHider.applyTo(activity);
            assertEquals("the switch is off by default", View.VISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals("the band still covered the top of the video", View.INVISIBLE, band.getVisibility());

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the band away", View.VISIBLE, band.getVisibility());
            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());

            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals("off didn't give the band back", View.VISIBLE, band.getVisibility());

            // Hide the status bar takes the band too: there's no bar left for it to sit under.
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.INVISIBLE, band.getVisibility());
        }
    }

    @Test
    public void pauseShowsTheFeedAsTikTokDrawsIt() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.INVISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertTrue(SeeThroughStatusBar.isCleared(activity));

            PausedProcess.set(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause kept the strip away", View.INVISIBLE, spacer.getVisibility());
            assertEquals("Pause kept the bar clear", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, spacer.getVisibility());
            assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
        }
    }

    @Test
    public void hidingTheStatusBarWinsAndLeavesTheColorAlone() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.VISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            Settings.HIDE_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("the hidden bar's spacer stayed", View.GONE, spacer.getVisibility());
            assertEquals("the hidden bar's color was changed", OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
            assertFalse(SeeThroughStatusBar.isCleared(activity));
            Settings.HIDE_STATUS_BAR.save(false);
            Settings.SEE_THROUGH_STATUS_BAR.save(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.VISIBLE, spacer.getVisibility());
            WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
            assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT, attributes.layoutInDisplayCutoutMode);
        }
    }

    @Test
    public void aVideoOpenedFromAProfileOrSearchIsLeftAlone() {
        try (var controller = Robolectric.buildActivity(DetailActivity.class).setup().visible()) {
            Activity activity = controller.get();
            Utils.setContext(activity);
            View spacer = feedCell(activity, View.VISIBLE);
            activity.getWindow().setStatusBarColor(OWN_BAR_COLOR);
            Settings.SEE_THROUGH_STATUS_BAR.save(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals("an opened video lost its strip", View.VISIBLE, spacer.getVisibility());
            assertEquals(OWN_BAR_COLOR, activity.getWindow().getStatusBarColor());
        }
    }
}
