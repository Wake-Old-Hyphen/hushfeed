/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.text.NumberFormat;
import java.util.List;

/** The LIVE controls patch's switches, each read at the call it guards. */
@SuppressWarnings("unused")
public final class LiveControls {
    private LiveControls() {
    }

    /**
     * True while a LIVE preview in the feed should not start the countdown that takes you into
     * its room. TikTok already skips the countdown on the Following feed, so a preview without
     * one is a state it handles; tapping the preview still opens the room. Paused, the switch
     * answers off.
     */
    public static boolean skipAutoEnter() {
        return SettingsStatus.liveControlsEnabled && Settings.STOP_LIVE_AUTO_ENTER.get();
    }

    /**
     * True while LIVE rooms should offer TikTok's newer quality picker and a pick should
     * carry to the next room. TikTok's own setting for both is off unless its server turns it on,
     * which leaves a quality pick only in rooms whose stream asks for the older one. Paused, the
     * switch answers off and TikTok's setting decides again.
     */
    public static boolean offerQualityEverywhere() {
        return SettingsStatus.liveControlsEnabled && Settings.OFFER_LIVE_QUALITY.get();
    }

    /**
     * True while every portrait LIVE room's menu should offer clear screen, which hides the room's
     * chat, gifts and buttons and leaves the stream on its own (#57). TikTok offers the entry only
     * when its server turns it on. Paused, the switch answers off and TikTok's setting decides.
     */
    public static boolean offerClearScreen() {
        return SettingsStatus.liveControlsEnabled && Settings.OFFER_LIVE_CLEAR_SCREEN.get();
    }

    /**
     * True while a LIVE quality pick should outlive TikTok closing. It needs Offer LIVE quality in
     * more rooms as well, which is what makes TikTok carry a pick between rooms at all. Paused,
     * both switches answer off.
     */
    public static boolean rememberQuality() {
        return offerQualityEverywhere() && Settings.REMEMBER_LIVE_QUALITY.get();
    }

    private static volatile boolean restoredLogged;
    private static volatile String lastQualityPick;

    /** The last quality TikTok carried to a room this run, or null. Used to record the quality being watched. */
    static String lastQualityPick() {
        return lastQualityPick;
    }

    /**
     * Called right after a LIVE room's start reads TikTok's last quality pick, with that pick.
     * TikTok keeps it only until it closes, so the first rooms after a cold start read null; while
     * {@link #rememberQuality} is on they get the pick saved by {@link #noteLiveQualityPick}
     * instead. The player start uses it only when the stream has that quality and starts at the
     * stream's own default when it doesn't, as it does with a pick from the same session.
     */
    public static String liveQualityPick(String tiktokPick) {
        if (tiktokPick != null || !rememberQuality()) return tiktokPick;
        String saved = Settings.REMEMBERED_LIVE_QUALITY.get();
        if (saved == null || saved.isEmpty()) return null;
        if (!restoredLogged) {
            restoredLogged = true;
            Logger.printInfo(() -> "LIVE quality: TikTok had no pick since it started, so rooms start at the saved " + saved);
        }
        return saved;
    }

    /** Called with each quality TikTok's picker carries to the next room; saved while the switch is on. */
    public static void noteLiveQualityPick(String pick) {
        if (pick != null && !pick.isEmpty()) lastQualityPick = pick;
        if (pick == null || pick.isEmpty() || !rememberQuality()) return;
        if (pick.equals(Settings.REMEMBERED_LIVE_QUALITY.get())) return;
        Settings.REMEMBERED_LIVE_QUALITY.save(pick);
        Logger.printDebug(() -> "LIVE quality: saved the pick " + pick);
    }

    /**
     * Called as the For You LIVE preview's Service+ row ("Service+ | Ask me anything!") is bound to
     * a LIVE, with the row and that LIVE's Service+ preview. True when the row still shows and the
     * LIVE has no Service+ item, and the patch then runs the row's own hide before its show.
     * TikTok's show returns without hiding when there's nothing to show, and only the hide a
     * preview runs as it leaves the screen took the row away, so a preview bound to the next LIVE
     * without leaving first kept the last LIVE's row under the next LIVE's name (#94). Paused, it
     * answers false and the row is TikTok's again.
     */
    public static boolean hideStaleServicePlus(Object row, Object preview) {
        if (row == null || Setting.isPaused()) return false;
        try {
            if (!Boolean.TRUE.equals(Reflect.invoke(row, "isShowing"))) return false;
            Object items = preview == null ? null : Reflect.readField(preview, "previewItem");
            if (items instanceof List && !((List<?>) items).isEmpty()) return false;
            Logger.printDebug(() -> "LIVE preview: hid a Service+ row left from the last LIVE");
            return true;
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE preview's Service+ row", failure);
            return false;
        }
    }

    private static final long NONE = -1;
    private static long pendingViewers = NONE;

    /**
     * Called just before the room widget turns its viewer count into text, with the count. The
     * formatter overwrites the register the count sits in, so the patch hands it over here and
     * {@link #viewerCountText} picks it up from the same straight line of code on the main thread.
     */
    public static void noteViewerCount(long count) {
        pendingViewers = count;
    }

    /**
     * Called with TikTok's text for the count noted just before ("1.2K+"). Returns the exact
     * count, grouped the way the reader's locale writes numbers, while the switch is on, and
     * TikTok's own text otherwise. Paused, the switch answers off.
     */
    public static String viewerCountText(String tiktokText) {
        long count = pendingViewers;
        pendingViewers = NONE;
        if (tiktokText == null || count < 0 || !SettingsStatus.liveControlsEnabled
                || !Settings.SHOW_EXACT_LIVE_VIEWERS.get()) {
            return tiktokText;
        }
        return NumberFormat.getIntegerInstance().format(count);
    }
}
