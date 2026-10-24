/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of Allow photo comments (#156).
 *
 * <p>The comment box builds its gallery tab when the {@code comment_photo_publish} value allows
 * it, and that value goes through the app's comment protocol on the way. TikTok's passes it
 * through. PineDrama's answers no whatever the value says. With the switch on, PineDrama's
 * protocol hands the value back the way TikTok's does; with it off, and while Hushfeed is paused,
 * PineDrama answers as it ships.
 */
@SuppressWarnings("unused")
public final class CommentPhotos {
    /** {@link Settings#PINEDRAMA_COMMENT_PHOTOS}'s key, for a read before the settings context. */
    static final String SWITCH_KEY = "pinedrama_comment_photos";

    private CommentPhotos() {
    }

    public static boolean isOn() {
        return Utils.getContext() != null ? Settings.PINEDRAMA_COMMENT_PHOTOS.get() : EarlySwitch.isOn(SWITCH_KEY);
    }
}
