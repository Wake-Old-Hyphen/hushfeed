package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Fade the video controls in Clear display (#84): the controls it covers stay in sight at the chosen
 * level, and a finger goes through them to the video under them, so a tap opens nothing and a pinch
 * or a press and hold still reaches TikTok's own Clear display handling. Outside Clear display a
 * faded control takes taps as before, leaving Clear display hands each control its taps back with
 * its own flags untouched, and Pause gives TikTok its look and its taps back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TapThroughControlsTest {
    private static final int CELL_ID = 0x7f0a0f01;
    private static final int COLUMN_ID = 0x7f0a0f02;
    private static final int FRAME_ID = 0x7f0a0f03;
    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;

    private boolean overlays;
    private Activity activity;
    private View video;
    private View column;
    private View frame;
    private int videoClicks;
    private int columnClicks;
    private int frameClicks;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        VideoOverlayHider.resolveForTests("view_rootview", CELL_ID);
        VideoOverlayHider.resolveForTests("47.1.4:llj", COLUMN_ID);
        VideoOverlayHider.resolveForTests("47.1.4:bqv", FRAME_ID);
        overlays = SettingsStatus.videoOverlaysEnabled;
        SettingsStatus.videoOverlaysEnabled = true;
    }

    @After
    public void tearDown() {
        PausedProcess.set(false);
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(false, 1));
        Settings.CLEAR_DISPLAY.save(false);
        Settings.FADE_CONTROLS_OPACITY.save(100);
        SettingsStatus.videoOverlaysEnabled = overlays;
        VideoOverlayHider.resolveForTests("view_rootview", 0);
        VideoOverlayHider.resolveForTests("47.1.4:llj", 0);
        VideoOverlayHider.resolveForTests("47.1.4:bqv", 0);
    }

    /**
     * A feed cell as 47.1.4 lays it out: the video's own layer across the cell, the rail's column
     * at the right and the caption frame at the bottom left, side by side over it.
     */
    private void feed(Activity on) {
        activity = on;
        Utils.setContext(on);
        FrameLayout root = new FrameLayout(on);
        FrameLayout cell = new FrameLayout(on);
        cell.setId(CELL_ID);
        video = new View(on);
        video.setOnClickListener(v -> videoClicks++);
        cell.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column = new View(on);
        column.setId(COLUMN_ID);
        column.setOnClickListener(v -> columnClicks++);
        column.setLongClickable(false);
        cell.addView(column, new FrameLayout.LayoutParams(160, 480, Gravity.END | Gravity.TOP));
        frame = new View(on);
        frame.setId(FRAME_ID);
        frame.setOnClickListener(v -> frameClicks++);
        cell.addView(frame, new FrameLayout.LayoutParams(640, 240, Gravity.START | Gravity.BOTTOM));
        root.addView(cell, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        on.setContentView(root);
    }

    private static void clear(boolean on) {
        RememberClearDisplayPatch.rememberClearDisplayEvent(new VideoOverlayHiderTest.ClearEvent(on, 1));
    }

    private void frameDrawn() {
        activity.findViewById(android.R.id.content).getViewTreeObserver().dispatchOnPreDraw();
    }

    /** The window at a known size, so every point below is the same on each run. */
    private void layOutWindow() {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, WIDTH, HEIGHT);
    }

    /** A point inside {@code view} in window coordinates, {@code fx} and {@code fy} across it. */
    private float[] at(View view, float fx, float fy) {
        layOutWindow();
        int[] corner = new int[2];
        view.getLocationInWindow(corner);
        assertTrue("laid out", view.getWidth() > 0 && view.getHeight() > 0);
        return new float[]{corner[0] + view.getWidth() * fx, corner[1] + view.getHeight() * fy};
    }

    /** A tap the way the system delivers it: to the window's decor view, which hands it to the callback. */
    private void tap(View target) {
        float[] point = at(target, 0.5f, 0.5f);
        long now = SystemClock.uptimeMillis();
        send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, point[0], point[1], 0));
        send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, point[0], point[1], 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void send(MotionEvent event) {
        try {
            activity.getWindow().getDecorView().dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent twoFingers(long down, int action, float[] first, float[] second) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        float[][] points = {first, second};
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0];
            coords[i].y = points[i][1];
            coords[i].pressure = 1f;
            coords[i].size = 1f;
        }
        return MotionEvent.obtain(down, down + 20, action, 2, properties, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    @Test
    public void inClearDisplayAFadedControlStaysInSightAndTheTapGoesToTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);

            VideoOverlayHider.applyTo(activity);
            tap(column);
            assertEquals("outside Clear display a faded control takes the tap", 1, columnClicks);
            assertEquals(0, videoClicks);
            assertFalse(TapThroughControls.isMarked(column));

            clear(true);
            VideoOverlayHider.applyTo(activity);
            // TikTok's Clear display animates the controls to 0. The next frame holds the level.
            column.setAlpha(0f);
            frame.setAlpha(0f);
            frameDrawn();
            assertEquals("the rail stays in sight at the chosen level", 0.5f, column.getAlpha(), 0f);
            assertEquals(0.5f, frame.getAlpha(), 0f);
            assertEquals("the caption frame stays rather than going invisible",
                    View.VISIBLE, frame.getVisibility());
            assertEquals(View.VISIBLE, column.getVisibility());

            tap(column);
            tap(frame);
            assertEquals("a faded control takes no tap in Clear display", 1, columnClicks);
            assertEquals(0, frameClicks);
            assertEquals("the taps go to the video under them", 2, videoClicks);
            assertEquals("nothing is left out of place", 0f, column.getTranslationX(), 0f);
            assertEquals(0f, frame.getTranslationX(), 0f);

            clear(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
            // Leaving, TikTok animates its own opacity back up from 0. The level holds the whole
            // way rather than dropping to a share of 0 and climbing back.
            column.setAlpha(0.2f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);
            column.setAlpha(1f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);
            // Back at full, a value TikTok writes is faded by the share again.
            column.setAlpha(0.6f);
            frameDrawn();
            assertEquals(0.3f, column.getAlpha(), 0.0001f);

            tap(column);
            assertEquals("out of Clear display the control takes taps again", 2, columnClicks);
            assertEquals(2, videoClicks);
            assertTrue("TikTok's own flags were never changed", column.isClickable());
            assertFalse(column.isLongClickable());
            assertTrue(column.isEnabled());
        }
    }

    /**
     * A pinch that starts on the video and puts its second finger on a faded control stays the
     * video's, which is how TikTok's pinch out of Clear display sees both fingers.
     */
    @Test
    public void aSecondFingerOnAFadedControlStaysWithThePinch() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            List<String> videoEvents = new ArrayList<>();
            List<String> columnEvents = new ArrayList<>();
            video.setOnTouchListener((v, event) -> {
                videoEvents.add(event.getActionMasked() + "/" + event.getPointerCount());
                return true;
            });
            column.setOnTouchListener((v, event) -> {
                columnEvents.add(event.getActionMasked() + "/" + event.getPointerCount());
                return true;
            });
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);

            float[] first = at(video, 0.3f, 0.3f);
            float[] second = at(column, 0.5f, 0.5f);
            long now = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, first[0], first[1], 0));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_DOWN
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(twoFingers(now, MotionEvent.ACTION_POINTER_UP
                    | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, second));
            send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, first[0], first[1], 0));

            assertTrue("the faded control saw neither finger: " + columnEvents, columnEvents.isEmpty());
            assertEquals(List.of(MotionEvent.ACTION_DOWN + "/1", MotionEvent.ACTION_POINTER_DOWN + "/2",
                    MotionEvent.ACTION_POINTER_UP + "/2", MotionEvent.ACTION_UP + "/1"), videoEvents);
        }
    }

    /** At 0 Clear display takes the controls away as before, and nothing invisible takes a tap. */
    @Test
    public void atZeroTheControlsAreHiddenAndTheTapsStillGoToTheVideo() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            float[] onColumn = at(column, 0.5f, 0.5f);
            Settings.FADE_CONTROLS_OPACITY.save(0);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            assertEquals(View.GONE, column.getVisibility());
            assertEquals(View.INVISIBLE, frame.getVisibility());
            assertFalse(TapThroughControls.isMarked(column));

            long now = SystemClock.uptimeMillis();
            send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, onColumn[0], onColumn[1], 0));
            send(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, onColumn[0], onColumn[1], 0));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(0, columnClicks);
            assertEquals(1, videoClicks);
        }
    }

    /** Pause answers TikTok's own Clear display: its opacity and its handling of a tap. */
    @Test
    public void pauseGivesTikTokItsClearDisplayAndItsTapsBack() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            feed(controller.get());
            Settings.FADE_CONTROLS_OPACITY.save(50);
            clear(true);
            VideoOverlayHider.applyTo(activity);
            column.setAlpha(0f);
            frameDrawn();
            assertEquals(0.5f, column.getAlpha(), 0f);

            PausedProcess.set(true);
            // Live, before any layout: the tap is TikTok's again straight away.
            tap(column);
            assertEquals(1, columnClicks);
            VideoOverlayHider.applyTo(activity);
            assertEquals("Pause gives back the opacity TikTok's Clear display gave it",
                    0f, column.getAlpha(), 0f);
            assertFalse(TapThroughControls.isMarked(column));

            PausedProcess.set(false);
            VideoOverlayHider.applyTo(activity);
            assertEquals(0.5f, column.getAlpha(), 0f);
            tap(column);
            assertEquals(1, columnClicks);
            assertEquals(1, videoClicks);
        }
    }
}
