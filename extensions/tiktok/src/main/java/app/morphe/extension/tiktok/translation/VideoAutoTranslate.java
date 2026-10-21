/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Videos kept in the language they were made in while TikTok's Always translate is on (#194).
 *
 * <p>TikTok translates descriptions, captions, photo titles and text stickers by itself when the
 * Settings and privacy > Language page's Always translate switch is on. Every one of those decisions
 * asks one static read of the switch, which answers from a state TikTok publishes when it starts and
 * whenever the switch moves, and that state also streams to the screens that translate on their own.
 * The patch builds every published state through {@link #publishedState} and puts {@link
 * #keepOriginal} in front of the read for the moment before a state exists, so both answer off while
 * the setting is on, the same as TikTok's own switch turned off. TikTok's comment translation used
 * the same read for its default, and the patch points it at the last state TikTok published, as it
 * was before the filter, or at the switch as stored before one exists (see {@link #commentDefault}),
 * so comments follow their own toggle and Always translate exactly as before. Nothing is written to
 * TikTok's switch and See translation still translates the one video it's tapped on. With the
 * setting off, and before the application context exists, TikTok's answer stands. The context is set
 * in the application's attachBaseContext, ahead of TikTok's own start up code. The state is built
 * once per launch, so the setting asks for a restart.
 */
@SuppressWarnings("unused")
public final class VideoAutoTranslate {
    private static final String HOOK_FAMILY = "video auto translate";

    /** The last Always translate value TikTok published, before the filter. Null until it publishes one. */
    private static volatile Boolean lastPublished;

    private VideoAutoTranslate() {
    }

    /** Whether TikTok's Always translate should read as off. Called on every read of it. */
    public static boolean keepOriginal() {
        try {
            // TikTok reads the switch while it starts, before Settings can load without throwing.
            if (Utils.getContext() == null) return false;
            boolean keep = Settings.NO_VIDEO_AUTO_TRANSLATE.get();
            HookStatus.bound(HOOK_FAMILY, keep ? "kept videos original" : "TikTok's Always translate");
            return keep;
        } catch (Exception ex) {
            // The read sits on the feed's path; TikTok's own answer is the safe one.
            Logger.printDebug(() -> "[VideoAutoTranslate] left TikTok's Always translate as it was", ex);
            return false;
        }
    }

    /**
     * The Always translate value TikTok publishes: off while videos stay original, else {@code on}.
     * Called each time TikTok builds the state, at launch and when the switch is flipped.
     */
    public static boolean publishedState(boolean on) {
        lastPublished = on;
        return on && !keepOriginal();
    }

    /**
     * The Always translate value TikTok's comment switch defaults to: the last state TikTok published,
     * as it was before {@link #publishedState} filtered it, else {@code stored}, the switch as kept on
     * disk. That is the order TikTok's own read answers in. When the switch is flipped TikTok publishes
     * the new state, rebuilds the comment default and only then stores the switch, so the stored value
     * alone would still be the old one there.
     */
    public static boolean commentDefault(boolean stored) {
        Boolean last = lastPublished;
        return last != null ? last : stored;
    }

    /** Forgets the last published state, as a fresh launch would. */
    static void forgetPublished() {
        lastPublished = null;
    }
}
