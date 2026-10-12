package app.morphe.extension.tiktok.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.detail.ui.DetailActivity;
import com.ss.android.ugc.aweme.live.LivePlayActivity;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * #38: a LIVE room hides its status bar, reaches into the cutout and loses the strip TikTok keeps
 * for the bar while it is in front, and gives all three back when it leaves; nothing else is
 * touched, and the switch decides.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LiveStatusBarTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(false);
        Settings.LIVE_UNDER_STATUS_BAR.save(false);
    }

    @After public void tearDown() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.resetToDefault();
        Settings.LIVE_UNDER_STATUS_BAR.resetToDefault();
        LiveStatusBar.videoContainerForTests = 0;
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void aLiveRoomHidesItsBarWhileInFrontAndGivesItBack() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        LivePlayActivity live = room.get();
        assertTrue("the room's bar wasn't hidden", LiveStatusBar.isHidden(live));
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.pause();
        assertFalse("leaving kept the bar hidden", LiveStatusBar.isHidden(live));
        assertEquals("the cutout mode wasn't given back",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.resume();
        assertTrue("coming back to the room didn't hide it again", LiveStatusBar.isHidden(live));
        room.pause().stop().destroy();
    }

    /** 47.1.3's LIVE window already reaches into the cutout on every edge; that stays. */
    @Test public void aRoomAlreadyInTheCutoutKeepsItsOwnMode() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        WindowManager.LayoutParams attributes = live.getWindow().getAttributes();
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        live.getWindow().setAttributes(attributes);
        room.start().resume().visible();
        assertTrue("the room's bar wasn't hidden", LiveStatusBar.isHidden(live));
        assertEquals("ALWAYS was narrowed", WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.pause();
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.stop().destroy();
    }

    @Test public void theSwitchOffOrAnotherScreenLeavesTheBarAlone() {
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        assertFalse("hidden with the switch off", LiveStatusBar.isHidden(room.get()));
        room.pause().stop().destroy();

        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        ActivityController<Activity> other = Robolectric.buildActivity(Activity.class).setup();
        assertFalse("a screen that isn't a LIVE room was touched", LiveStatusBar.isHidden(other.get()));
        other.pause().stop().destroy();
    }

    /**
     * On the S22 the bar hid and a black strip stayed: TikTok's room sits a status bar lower than
     * its container and keeps that offset with the bar gone. The shape is 47.1.3's: AppCompat's
     * empty full-screen frame comes first, TikTok's room container second, and the room inside it
     * is held down by a top margin under a full-size layer that starts at the top. A first build
     * followed only the first full-screen child and never reached the room.
     */
    @Test public void theStripKeptForTheBarGoesWhileTheRoomIsInFront() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout root = new FrameLayout(live);
        FrameLayout appCompat = new FrameLayout(live);
        appCompat.addView(new FrameLayout(live), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(appCompat, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout container = new FrameLayout(live);
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        held.bottomMargin = 135;
        container.addView(stream, held);
        container.addView(new FrameLayout(live), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(root);
        room.start().resume().visible();

        layOut(live);
        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the bottom was touched", 135, ((ViewGroup.MarginLayoutParams) stream.getLayoutParams()).bottomMargin);

        // TikTok setting its offset again on a relayout of its own gets the same answer.
        held.topMargin = bar;
        stream.setLayoutParams(held);
        layOut(live);
        assertEquals("the strip came back on a relayout", 0, topMargin(stream));

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, topMargin(stream));
        layOut(live);
        assertEquals("a layout after leaving took it again", bar, topMargin(stream));

        room.resume();
        layOut(live);
        assertEquals("coming back kept the strip", 0, topMargin(stream));
        room.pause().stop().destroy();
    }

    /**
     * The same strip as a container's own padding, under an outer padding of another height that
     * has to stay; the lifted container keeps its other three sides.
     */
    @Test public void aPaddedContainerLosesOnlyTheBarsHeight() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout outer = new FrameLayout(live);
        outer.setPadding(0, 3, 0, 0);
        FrameLayout container = new FrameLayout(live);
        container.setPadding(7, bar, 9, 135);
        outer.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        live.setContentView(outer);
        room.start().resume().visible();

        layOut(live);
        assertEquals("a padding that isn't the bar's was taken", 3, outer.getPaddingTop());
        assertEquals("the strip stayed", 0, container.getPaddingTop());
        assertEquals(7, container.getPaddingLeft());
        assertEquals(9, container.getPaddingRight());
        assertEquals(135, container.getPaddingBottom());

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, container.getPaddingTop());
        room.stop().destroy();
    }

    @Test public void withTheSwitchOffTheStripStays() {
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout container = new FrameLayout(live);
        container.setPadding(0, bar, 0, 0);
        live.setContentView(container);
        View cover = cover(live, (FrameLayout) container.getParent(), bar);
        room.start().resume().visible();
        layOut(live);
        assertEquals(bar, container.getPaddingTop());
        assertEquals("the cover went with the switch off", View.VISIBLE, cover.getVisibility());
        room.pause().stop().destroy();
    }

    /**
     * #137 on Android 16: TikTok also draws a plain view the bar's height over the top of the room,
     * so under the bar the room's top row sat under black once the strip was gone.
     */
    @Test public void tikToksCoverOverTheBarGoesWhileTheRoomIsInFront() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout root = new FrameLayout(live);
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        root.addView(stream, held);
        View shade = new View(live);
        shade.setBackgroundColor(Color.DKGRAY);
        root.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar * 2));
        live.setContentView(root);
        View cover = cover(live, root, bar);
        room.start().resume().visible();

        layOut(live);
        assertEquals("the cover stayed", View.INVISIBLE, cover.getVisibility());
        assertEquals("a view that isn't the bar's height went", View.VISIBLE, shade.getVisibility());
        assertEquals("the strip stayed", 0, topMargin(stream));

        cover.setVisibility(View.VISIBLE);
        layOut(live);
        assertEquals("the cover came back on a relayout", View.INVISIBLE, cover.getVisibility());

        room.pause();
        assertEquals("leaving didn't give the cover back", View.VISIBLE, cover.getVisibility());
        room.resume();
        layOut(live);
        assertEquals("coming back kept the cover", View.INVISIBLE, cover.getVisibility());

        // TikTok swapping its cover for a new one, as a rotation or a new room can.
        root.removeView(cover);
        View swapped = cover(live, root, bar);
        layOut(live);
        assertEquals("the new cover stayed", View.INVISIBLE, swapped.getVisibility());
        room.pause().stop().destroy();
    }

    /**
     * #137: under a bar left showing, the stream reaches the top edge while the room's controls
     * stay a bar lower, where TikTok had them, so the creator row isn't under the clock. Every
     * page of the room's pager gets it, and it all goes back as the room leaves.
     */
    @Test public void underTheBarTheControlsStayBelowTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        FrameLayout nextRoom = page(live, stream);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertEquals("the next room's controls went up", 7 + bar, nextRoom.getPaddingTop());
        layOut(live);
        assertEquals("a relayout lowered the controls twice", 7 + bar, controls.getPaddingTop());

        controls.setPadding(0, 7, 0, 0);
        layOut(live);
        assertEquals("TikTok's own padding came back and stayed", 7 + bar, controls.getPaddingTop());

        room.pause();
        assertEquals("leaving didn't give the controls back", 7, controls.getPaddingTop());
        assertEquals(7, nextRoom.getPaddingTop());
        room.resume();
        layOut(live);
        assertEquals("coming back left the controls up", 7 + bar, controls.getPaddingTop());
        room.pause().stop().destroy();
    }

    /**
     * #137: TikTok's dark shade across the top is part of the controls, so it went a bar lower
     * with them and the strip under the clock showed lighter than the rest of the top. That strip
     * gets the shade's first color now, only on a layer that has a shade, and it goes as the room
     * leaves.
     */
    @Test public void underTheBarTikToksTopShadeCarriesOnUnderTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        View shade = shade(live, controls);
        FrameLayout plain = page(live, stream);
        room.start().resume().visible();
        layOut(live);
        layOut(live);

        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertTrue("the strip under the clock wasn't shaded", controls.getBackground() instanceof LiveStatusBar.BarShade);
        LiveStatusBar.BarShade fill = (LiveStatusBar.BarShade) controls.getBackground();
        assertEquals("the strip should be as tall as the bar", bar, fill.height);
        assertEquals("the strip should start where the shade does, as drawn", 0x33000000, fill.color());
        assertEquals("a layer without a shade got one", null, plain.getBackground());
        assertEquals("the shade was moved", 0f, shade.getTranslationY(), 0f);

        room.pause();
        assertEquals("leaving kept the strip shaded", null, controls.getBackground());
        assertEquals(7, controls.getPaddingTop());
        room.stop().destroy();
    }

    /**
     * #137: scrolling on through LIVE rooms, the strip under the clock went light again after the
     * first few. The shade search had one budget for the whole visit, and TikTok keeps a room's
     * shade hidden for a while, so a busy LIVE's layout passes used it up. A hidden shade is taken
     * now and its fill shows only while the shade does, and each layer has a budget of its own.
     */
    @Test public void roomsFurtherOnStillGetTheShadeUnderTheClock() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout first = page(live, stream);
        View firstShade = shade(live, first);
        firstShade.setVisibility(View.INVISIBLE);
        page(live, stream);
        room.start().resume().visible();
        for (int pass = 0; pass < 60; pass++) layOut(live);

        assertTrue("a hidden shade got no fill to follow it", first.getBackground() instanceof LiveStatusBar.BarShade);
        LiveStatusBar.BarShade fill = (LiveStatusBar.BarShade) first.getBackground();
        assertEquals("the strip was shaded while TikTok's shade was hidden", 0, fill.color());
        firstShade.setVisibility(View.VISIBLE);
        assertEquals("the strip didn't follow the shade in", 0x33000000, fill.color());

        FrameLayout later = page(live, stream);
        shade(live, later);
        layOut(live);
        layOut(live);
        assertTrue("a room further on got no shade under the clock", later.getBackground() instanceof LiveStatusBar.BarShade);
        room.pause().stop().destroy();
    }

    /** TikTok's top shade as 47.1.4 has it: a gradient across the top of a row in the controls. */
    private static View shade(Activity live, FrameLayout controls) {
        FrameLayout row = new FrameLayout(live);
        controls.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 150));
        View shade = new View(live);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x66000000, Color.TRANSPARENT});
        gradient.setAlpha(128);
        shade.setBackground(gradient);
        row.addView(shade, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100));
        return shade;
    }

    /** With the bar hidden there's no clock to keep clear of, so the controls go up with the room. */
    @Test public void withTheBarHiddenTheControlsGoUpWithTheRoom() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout controls = page(live, stream);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the controls were held down", 7, controls.getPaddingTop());
        room.pause().stop().destroy();
    }

    /** A room page as 47.1.4 has it: the stream's view, and beside it the controls, padded 7. */
    private static FrameLayout page(Activity live, FrameLayout pager) {
        FrameLayout page = new FrameLayout(live);
        FrameLayout video = new FrameLayout(live);
        video.setId(LiveStatusBar.videoContainerForTests);
        page.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FrameLayout controls = new FrameLayout(live);
        controls.setPadding(0, 7, 0, 0);
        page.addView(controls, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pager.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return controls;
    }

    private static View cover(Activity live, FrameLayout parent, int bar) {
        View cover = new View(live);
        cover.setBackgroundColor(Color.BLACK);
        parent.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, bar));
        return cover;
    }

    /**
     * #137: every panel TikTok opened over the room put its strip back once, and the old limit
     * counted those for the whole visit, so the seventh panel left the strip there for good.
     */
    @Test public void aStripPutBackNowAndThenIsTakenEveryTime() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        room.start().resume().visible();
        layOut(live);
        assertEquals("the strip stayed", 0, topMargin(stream));

        for (int panel = 1; panel <= 10; panel++) {
            putBack(stream, bar);
            layOut(live);
            assertEquals("panel " + panel + " left the strip", 0, topMargin(stream));
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        }
        room.pause().stop().destroy();
    }

    /** TikTok putting the strip back on every frame is a fight the room is left to win. */
    @Test public void aStripPutBackOnEveryLayoutIsLeftAfterAFewTries() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        room.start().resume().visible();
        layOut(live);

        for (int frame = 0; frame < 12; frame++) {
            putBack(stream, bar);
            layOut(live);
        }
        assertEquals("the fight went on", bar, topMargin(stream));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        putBack(stream, bar);
        layOut(live);
        assertEquals("a lost fight was taken up again", bar, topMargin(stream));

        // A new visit starts over.
        room.pause();
        room.resume();
        layOut(live);
        assertEquals("coming back kept the strip", 0, topMargin(stream));
        room.pause().stop().destroy();
    }

    /** #137: the other way out of the black strip keeps the clock and icons over the stream. */
    @Test public void underTheBarTheStripGoesAndTheBarStays() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).create();
        LivePlayActivity live = room.get();
        FrameLayout stream = heldStream(live, bar);
        live.getWindow().setStatusBarColor(Color.BLACK);
        room.start().resume().visible();
        layOut(live);

        assertEquals("the strip stayed", 0, topMargin(stream));
        assertEquals("the bar wasn't made see through", Color.TRANSPARENT, live.getWindow().getStatusBarColor());
        assertEquals("the cutout mode was changed with the bar showing",
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);

        room.pause();
        assertEquals("leaving didn't give the strip back", bar, topMargin(stream));
        assertEquals("leaving didn't give the bar's color back", Color.BLACK, live.getWindow().getStatusBarColor());
        room.stop().destroy();
    }

    @Test public void hidingTheBarWinsOverShowingTheRoomUnderIt() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        ActivityController<LivePlayActivity> room = Robolectric.buildActivity(LivePlayActivity.class).setup();
        LivePlayActivity live = room.get();
        assertEquals("the bar wasn't hidden", WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                live.getWindow().getAttributes().layoutInDisplayCutoutMode);
        room.pause().stop().destroy();
    }

    /**
     * #137: a LIVE opened from the preview under a creator's card in search is a page of TikTok's
     * detail pager, held a bar lower by the page's own padding (read off the S22 on 47.1.4), and
     * the switches did nothing there. It's handled like a room while it's on screen now, and
     * swiping on to a video gives everything back.
     */
    @Test public void aLiveOpenedFromSearchIsUnderTheBarWhileItsPageIsOnScreen() {
        Settings.LIVE_UNDER_STATUS_BAR.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<DetailActivity> opened = Robolectric.buildActivity(DetailActivity.class).create();
        DetailActivity detail = opened.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(detail);
        FrameLayout livePage = detailPage(detail, pager, bar);
        FrameLayout controls = page(detail, livePage);
        detail.setContentView(pager);
        detail.getWindow().setStatusBarColor(Color.BLACK);
        opened.start().resume().visible();
        layOut(detail);

        assertEquals("the page's strip stayed", 0, livePage.getPaddingTop());
        assertEquals("the controls went up under the clock", 7 + bar, controls.getPaddingTop());
        assertEquals("the bar wasn't made see through", Color.TRANSPARENT, detail.getWindow().getStatusBarColor());

        livePage.setTranslationY(-2316);
        layOut(detail);
        assertEquals("swiping on didn't give the strip back", bar, livePage.getPaddingTop());
        assertEquals(7, controls.getPaddingTop());
        assertEquals("swiping on didn't give the bar's color back", Color.BLACK, detail.getWindow().getStatusBarColor());

        livePage.setTranslationY(0);
        layOut(detail);
        assertEquals("swiping back left the strip", 0, livePage.getPaddingTop());

        opened.pause();
        assertEquals("leaving didn't give the strip back", bar, livePage.getPaddingTop());
        opened.stop().destroy();
    }

    /** The detail pager is mostly videos: the bar is hidden only while a LIVE page is in view. */
    @Test public void aDetailPageHidesTheBarOnlyWhileALiveIsOnScreen() {
        Settings.HIDE_STATUS_BAR_IN_LIVE.save(true);
        followFromTheMainActivity();
        int bar = platformBar();
        ActivityController<DetailActivity> opened = Robolectric.buildActivity(DetailActivity.class).create();
        DetailActivity detail = opened.get();
        LiveStatusBar.videoContainerForTests = View.generateViewId();
        FrameLayout pager = new FrameLayout(detail);
        FrameLayout video = detailPage(detail, pager, bar);
        FrameLayout livePage = detailPage(detail, pager, bar);
        page(detail, livePage);
        livePage.setTranslationY(2316);
        detail.setContentView(pager);
        opened.start().resume().visible();
        layOut(detail);

        assertFalse("a video page hid the bar", LiveStatusBar.isHidden(detail));
        assertEquals("a video page lost its padding", bar, video.getPaddingTop());
        assertEquals("the LIVE below the screen lost its strip", bar, livePage.getPaddingTop());

        video.setTranslationY(-2316);
        livePage.setTranslationY(0);
        layOut(detail);
        assertTrue("the LIVE in view kept its bar", LiveStatusBar.isHidden(detail));
        assertEquals("the LIVE in view kept its strip", 0, livePage.getPaddingTop());
        assertEquals(bar, video.getPaddingTop());

        opened.pause();
        assertFalse("leaving kept the bar hidden", LiveStatusBar.isHidden(detail));
        assertEquals(bar, livePage.getPaddingTop());
        opened.stop().destroy();
    }

    /** A page of the detail pager as 47.1.4 has it for a LIVE: held a bar lower by its own padding. */
    private static FrameLayout detailPage(Activity detail, FrameLayout pager, int bar) {
        FrameLayout page = new FrameLayout(detail);
        page.setPadding(0, bar, 0, 0);
        pager.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    private static FrameLayout heldStream(Activity live, int bar) {
        FrameLayout container = new FrameLayout(live);
        FrameLayout stream = new FrameLayout(live);
        FrameLayout.LayoutParams held = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        held.topMargin = bar;
        container.addView(stream, held);
        live.setContentView(container);
        return stream;
    }

    private static void putBack(View stream, int bar) {
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) stream.getLayoutParams();
        params.topMargin = bar;
        stream.setLayoutParams(params);
    }

    private static int platformBar() {
        Resources system = Resources.getSystem();
        int bar = system.getDimensionPixelSize(system.getIdentifier("status_bar_height", "dimen", "android"));
        assertTrue("no platform status bar height to test with", bar > 0);
        return bar;
    }

    private static int topMargin(View view) {
        return ((ViewGroup.MarginLayoutParams) view.getLayoutParams()).topMargin;
    }

    private static void layOut(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2316, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2316);
        decor.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static void followFromTheMainActivity() {
        ActivityController<Activity> main = Robolectric.buildActivity(Activity.class).setup();
        LiveStatusBar.follow(main.get());
    }
}
