package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * #37: the Record button follows the room page that is on screen. On 47.1.4 the pager resumes the
 * next room right after the one being watched, so following the last page to resume put the button
 * on a room nobody could see, and it went away a second after it showed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRecordChipTest {
    @Test public void aPageFullyOnScreenIsInFront() {
        assertTrue(LiveRecordChip.mostlyShown(2340, 2340));
    }

    @Test public void halfwayThroughASwipeThePageStillCounts() {
        assertTrue(LiveRecordChip.mostlyShown(1170, 2340));
        assertFalse(LiveRecordChip.mostlyShown(1169, 2340));
    }

    @Test public void aRoomBesideTheOneInFrontDoesNot() {
        assertFalse(LiveRecordChip.mostlyShown(0, 2340));
        assertFalse(LiveRecordChip.mostlyShown(0, 0));
    }
}
