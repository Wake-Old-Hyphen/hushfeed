/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.feed.AuthorRegion;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.profile.model.User;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * What the comment cell's name, reply label and time end up saying. Each hook gets the text
 * TikTok just picked or wrote, the way the cell hands it over on 47.1.4.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommenterDetailsTest {
    /** Stands in for TikTok's comment model: the fields the hooks read, by their real names. */
    public static final class Remark {
        public User user;
        public String replyToUserId;
        public Object commerceInfo;

        Remark(User user) {
            this.user = user;
        }
    }

    /** A time view that counts how often its text is written, not only what it says. */
    private static final class TimeView extends TextView {
        int writes;

        TimeView(Context context) {
            super(context);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            writes++;
        }

        /** TikTok's own write of the time, which the hook runs right after. */
        void tikTokWrites(String time) {
            setText(time);
            writes = 0;
        }
    }

    private Context context;
    private boolean patched;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        patched = SettingsStatus.commenterDetailsEnabled;
        SettingsStatus.commenterDetailsEnabled = true;
        CommenterDetails.forget();
    }

    @After
    public void tearDown() {
        Settings.SHOW_COMMENTER_HANDLE.save(false);
        Settings.SHOW_COMMENTER_REGION.save(false);
        SettingsStatus.commenterDetailsEnabled = patched;
        CommenterDetails.forget();
    }

    private static User account(String uid, String handle, String nickname, String region) {
        User user = new User();
        user.uid = uid;
        user.uniqueId = handle;
        user.nickname = nickname;
        user.region = region;
        return user;
    }

    private static Remark by(String uid, String handle, String region) {
        return new Remark(account(uid, handle, "Display " + uid, region));
    }

    private TimeView timeSaying(String time) {
        TimeView view = new TimeView(context);
        view.tikTokWrites(time);
        return view;
    }

    @Test
    public void switchesOffLeaveTikTokTextAlone() {
        Remark remark = by("1", "river.otter", "US");
        remark.replyToUserId = "1";
        CharSequence name = "River";
        TimeView time = timeSaying("2d ago");

        assertSame(name, CommenterDetails.commenterName(remark, name));
        assertSame(name, CommenterDetails.replyTargetName(remark, name));
        CommenterDetails.onCommentTime(remark, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    @Test
    public void aBuildWithoutThePatchIgnoresSavedSwitches() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        Settings.SHOW_COMMENTER_REGION.save(true);
        SettingsStatus.commenterDetailsEnabled = false;
        Remark remark = by("1", "river.otter", "US");
        CharSequence name = "River";
        TimeView time = timeSaying("2d ago");

        assertSame(name, CommenterDetails.commenterName(remark, name));
        CommenterDetails.onCommentTime(remark, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    @Test
    public void theHandleReplacesTheDisplayName() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);

        assertEquals("@river.otter", CommenterDetails.commenterName(by("1", "river.otter", null), "River").toString());
        // A payload that keeps the at sign gets one, not two.
        assertEquals("@sam", CommenterDetails.commenterName(by("2", " @sam ", null), "Sam").toString());
    }

    @Test
    public void noHandleOrAnAdKeepsTheName() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        CharSequence name = "Brand";

        assertSame(name, CommenterDetails.commenterName(by("1", "  ", null), name));
        assertSame(name, CommenterDetails.commenterName(new Remark(null), name));
        assertSame(name, CommenterDetails.commenterName(null, name));
        Remark ad = by("2", "brand.account", null);
        ad.commerceInfo = new Object();
        assertSame(name, CommenterDetails.commenterName(ad, name));
    }

    @Test
    public void aReplyLabelGetsTheHandleOnceThatPersonsCommentWasDrawn() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        Remark reply = by("9", "replier", null);
        reply.replyToUserId = "42";

        // Nothing learned about uid 42 yet, so TikTok's name stays rather than a guess.
        assertSame("Bobby", CommenterDetails.replyTargetName(reply, "Bobby"));

        CommenterDetails.commenterName(by("42", "bob", null), "Bobby");
        assertEquals("@bob", CommenterDetails.replyTargetName(reply, "Bobby").toString());

        reply.replyToUserId = "7";
        assertSame("Someone", CommenterDetails.replyTargetName(reply, "Someone"));
    }

    @Test
    public void aReplyLabelStaysWhenTheHandleSwitchIsOff() {
        Settings.SHOW_COMMENTER_HANDLE.save(true);
        CommenterDetails.commenterName(by("42", "bob", null), "Bobby");
        Settings.SHOW_COMMENTER_HANDLE.save(false);
        Remark reply = by("9", "replier", null);
        reply.replyToUserId = "42";

        assertSame("Bobby", CommenterDetails.replyTargetName(reply, "Bobby"));
    }

    @Test
    public void theRegionFollowsTheTime() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "a", " us "), time);

        assertEquals("2d ago · US", time.getText().toString());
    }

    @Test
    public void theAccountRegionStandsInForAMissingRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        Remark remark = by("1", "a", null);
        remark.user.accountRegion = "de";
        TimeView time = timeSaying("5h ago");

        CommenterDetails.onCommentTime(remark, time);

        assertEquals("5h ago · DE", time.getText().toString());
    }

    @Test
    public void noRegionLeavesTheTimeAlone() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "a", null), time);
        CommenterDetails.onCommentTime(new Remark(null), time);
        CommenterDetails.onCommentTime(null, time);

        assertEquals("2d ago", time.getText().toString());
        assertEquals(0, time.writes);
    }

    @Test
    public void aRecycledCellNeverKeepsTheLastCommentsRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");
        CommenterDetails.onCommentTime(by("1", "a", "US"), time);
        assertEquals("2d ago · US", time.getText().toString());

        // The cell is rebound to a comment from an account with no country.
        time.tikTokWrites("5h ago");
        CommenterDetails.onCommentTime(by("2", "b", null), time);
        assertEquals("5h ago", time.getText().toString());

        // And again, to one from another country.
        time.tikTokWrites("1w ago");
        CommenterDetails.onCommentTime(by("3", "c", "GB"), time);
        assertEquals("1w ago · GB", time.getText().toString());
    }

    @Test
    public void aSecondCallOverTheSameWriteDoesNotStackTheRegion() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("2d ago");
        Remark remark = by("1", "a", "US");

        CommenterDetails.onCommentTime(remark, time);
        CommenterDetails.onCommentTime(remark, time);
        assertEquals("2d ago · US", time.getText().toString());
        assertEquals(1, time.writes);

        // Turned off with the stamp still showing: the time goes back to TikTok's own.
        Settings.SHOW_COMMENTER_REGION.save(false);
        CommenterDetails.onCommentTime(remark, time);
        assertEquals("2d ago", time.getText().toString());
    }

    @Test
    public void anEmptyTimeGetsTheRegionOnItsOwn() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        TimeView time = timeSaying("");

        CommenterDetails.onCommentTime(by("1", "a", "FR"), time);

        assertEquals("FR", time.getText().toString());
    }

    @Test
    public void aCommenterWithNoRegionGetsTheCountryTheFeedSawForTheirHandle() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        AuthorRegion.rememberSeen("River.Otter", "us");
        TimeView time = timeSaying("2d ago");

        CommenterDetails.onCommentTime(by("1", "@river.otter", null), time);

        assertEquals("2d ago · US", time.getText().toString());
    }

    @Test
    public void theAccountsOwnRegionBeatsTheFeedsAndARecycledCellNeverShowsAnothersCountry() {
        Settings.SHOW_COMMENTER_REGION.save(true);
        AuthorRegion.rememberSeen("river.otter", "US");
        TimeView time = timeSaying("2d ago");
        CommenterDetails.onCommentTime(by("1", "river.otter", "GB"), time);
        assertEquals("2d ago · GB", time.getText().toString());

        // The cell is rebound to a commenter the feed never showed, then to one with no handle.
        time.tikTokWrites("5h ago");
        CommenterDetails.onCommentTime(by("2", "someone.else", null), time);
        assertEquals("5h ago", time.getText().toString());
        time.tikTokWrites("1w ago");
        CommenterDetails.onCommentTime(by("3", null, null), time);
        assertEquals("1w ago", time.getText().toString());

        // And back to the one the feed did show.
        time.tikTokWrites("1d ago");
        CommenterDetails.onCommentTime(by("4", "river.otter", null), time);
        assertEquals("1d ago · US", time.getText().toString());
    }

    @Test
    public void theFeedCacheKeepsOnlyTheLast256CreatorsAndTheMostRecentlyUsedSurvive() {
        AuthorRegion.rememberSeen("keeper", "FR");
        for (int i = 0; i < 300; i++) {
            AuthorRegion.rememberSeen("creator" + i, "US");
            if (i % 50 == 0) assertEquals("FR", AuthorRegion.seenRegion("keeper"));
        }
        assertEquals("FR", AuthorRegion.seenRegion("keeper"));
        assertEquals(null, AuthorRegion.seenRegion("creator0"));
        assertEquals("US", AuthorRegion.seenRegion("creator299"));
    }
}
