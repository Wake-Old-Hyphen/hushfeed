package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentBoxButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FrameLayout box;

    @Before public void setUp() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.resetToDefault();
        HookStatus.clear();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        box = new FrameLayout(activity);
        activity.setContentView(box);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_COMMENT_BOX_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    private View button() {
        View button = new View(box.getContext());
        box.addView(button);
        return button;
    }

    private void layOut() {
        box.requestLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void tikTokKeepsItsButtonsByDefault() {
        View photo = button();
        CommentBoxButtons.photo(photo);
        assertEquals(View.VISIBLE, photo.getVisibility());
    }

    @Test public void theSwitchHidesEachOfTheThree() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View photo = button();
        View mention = button();
        View gift = button();
        CommentBoxButtons.photo(photo);
        CommentBoxButtons.mention(mention);
        CommentBoxButtons.gift(gift);
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.GONE, mention.getVisibility());
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test public void aButtonTikTokShowsAgainIsGoneByTheNextLayout() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View mention = button();
        CommentBoxButtons.mention(mention);
        mention.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, mention.getVisibility());
    }

    @Test public void aButtonHandedOverBeforeItIsAttachedIsHeldOnceItIs() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View gift = new View(box.getContext());
        CommentBoxButtons.gift(gift);
        box.addView(gift);
        gift.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test public void thePhotoSlotGoesWithThePhotoButton() {
        // Over the keyboard the photo icon sits in a tappable FrameLayout beside a badge that's gone.
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        FrameLayout slot = new FrameLayout(box.getContext());
        slot.setClickable(true);
        View photo = new View(box.getContext());
        View badge = new View(box.getContext());
        badge.setVisibility(View.GONE);
        slot.addView(photo);
        slot.addView(badge);
        box.addView(slot);
        CommentBoxButtons.photo(photo);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.GONE, slot.getVisibility());
        slot.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, slot.getVisibility());
    }

    @Test public void turningTheSwitchOffShowsWhatItHidAtTheNextLayout() {
        // TikTok keeps the comment box and never sets the buttons again on its own (Login2, 2026-10-20).
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View photo = button();
        View mention = button();
        View gift = button();
        gift.setVisibility(View.INVISIBLE);
        CommentBoxButtons.photo(photo);
        CommentBoxButtons.mention(mention);
        CommentBoxButtons.gift(gift);
        layOut();
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(false);
        layOut();
        assertEquals(View.VISIBLE, photo.getVisibility());
        assertEquals(View.VISIBLE, mention.getVisibility());
        assertEquals(View.INVISIBLE, gift.getVisibility());
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.GONE, mention.getVisibility());
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test public void aButtonTikTokLeftGoneStaysGoneWhenTheSwitchGoesOff() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View mention = button();
        mention.setVisibility(View.GONE);
        CommentBoxButtons.mention(mention);
        layOut();
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(false);
        layOut();
        assertEquals(View.GONE, mention.getVisibility());
    }

    @Test public void thePhotoSlotComesBackWithThePhotoButton() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        FrameLayout slot = new FrameLayout(box.getContext());
        slot.setClickable(true);
        View photo = new View(box.getContext());
        slot.addView(photo);
        box.addView(slot);
        CommentBoxButtons.photo(photo);
        layOut();
        assertEquals(View.GONE, slot.getVisibility());
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(false);
        layOut();
        assertEquals(View.VISIBLE, photo.getVisibility());
        assertEquals(View.VISIBLE, slot.getVisibility());
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.GONE, slot.getVisibility());
    }

    @Test public void onlyTheSlotHiddenHereComesBack() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        FrameLayout slot = new FrameLayout(box.getContext());
        slot.setClickable(true);
        View photo = new View(box.getContext());
        slot.addView(photo);
        box.addView(slot);
        CommentBoxButtons.photo(photo);
        layOut();
        FrameLayout other = new FrameLayout(box.getContext());
        other.setVisibility(View.GONE);
        box.addView(other);
        slot.removeView(photo);
        other.addView(photo);
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(false);
        layOut();
        assertEquals(View.VISIBLE, slot.getVisibility());
        assertEquals(View.GONE, other.getVisibility());
    }

    @Test public void aRowWithOtherButtonsShowingStays() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        FrameLayout row = new FrameLayout(box.getContext());
        row.setClickable(true);
        View photo = new View(box.getContext());
        View emoji = new View(box.getContext());
        row.addView(photo);
        row.addView(emoji);
        box.addView(row);
        CommentBoxButtons.photo(photo);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.VISIBLE, row.getVisibility());
        FrameLayout tapless = new FrameLayout(box.getContext());
        View icon = new View(box.getContext());
        tapless.addView(icon);
        box.addView(tapless);
        CommentBoxButtons.photo(icon);
        layOut();
        assertEquals(View.VISIBLE, tapless.getVisibility());
        FrameLayout held = new FrameLayout(box.getContext());
        held.setClickable(true);
        View heldPhoto = new View(box.getContext());
        View spinner = new View(box.getContext());
        spinner.setVisibility(View.INVISIBLE);
        held.addView(heldPhoto);
        held.addView(spinner);
        box.addView(held);
        CommentBoxButtons.photo(heldPhoto);
        layOut();
        assertEquals(View.VISIBLE, held.getVisibility());
    }

    /** Stands in for TikTok's plus panel button enum, with every constant 47.4.3's has. */
    private enum Tile { PHOTO, STICKER, MENTION, AUDIO, EMOJI, PRODUCT, GIFT, GROUP_CHAT, POLL }

    @Test public void thePlusPanelLosesTheHiddenButtonsAndKeepsTheRest() {
        assertEquals(Tile.GIFT, CommentBoxButtons.plusTile(Tile.GIFT));
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        for (Tile tile : Tile.values()) {
            boolean hidden = tile == Tile.PHOTO || tile == Tile.MENTION || tile == Tile.GIFT;
            assertEquals(tile.name(), hidden ? null : tile, CommentBoxButtons.plusTile(tile));
        }
        assertEquals("gift", CommentBoxButtons.plusTile("gift"));
        assertNull(CommentBoxButtons.plusTile(null));
        PausedProcess.set(true);
        assertEquals(Tile.GIFT, CommentBoxButtons.plusTile(Tile.GIFT));
    }

    @Test public void handingTheSameButtonOverTwiceIsHarmless() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View photo = button();
        CommentBoxButtons.photo(photo);
        CommentBoxButtons.photo(photo);
        photo.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
    }

    @Test public void pausedTheButtonsAreTikToks() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        PausedProcess.set(true);
        View photo = button();
        CommentBoxButtons.photo(photo);
        assertEquals(View.VISIBLE, photo.getVisibility());
    }

    @Test public void aNullButtonIsIgnored() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        CommentBoxButtons.photo(null);
    }

    @Test public void theExportCountsEachButtonHandedOver() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (CommentBoxButtons.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            CommentBoxButtons.photo(button());
            CommentBoxButtons.mention(button());
            CommentBoxButtons.gift(button());
            HookStatus.report();
            assertEquals(3, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
