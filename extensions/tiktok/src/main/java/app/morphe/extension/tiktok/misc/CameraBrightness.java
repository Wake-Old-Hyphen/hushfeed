/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * What the Keep the screen's brightness patch asks each time one of TikTok's creation screens
 * starts (issue #200).
 *
 * <p>The camera, the editor, the post screen and the effect preview each turn on TikTok's
 * brightness boost when they start: the window's screen brightness is set to the phone's own
 * level plus a step from TikTok's server config, then follows the light sensor and the
 * brightness slider until the screen stops, when TikTok clears it again. Two methods do that
 * start, an older one and the one 47.4 uses, and only one of them runs, by a server flag. The
 * patch asks here at the top of both, and while the switch is on they return before touching
 * the window, so the screen stays where the phone has it. Whatever else set the window's
 * brightness is left alone.
 *
 * <p>Off, paused, or a failure in here, and the boost runs as TikTok has it.
 */
public final class CameraBrightness {
    private CameraBrightness() {
    }

    /** Injection point, first thing in both of TikTok's brightness boost starts. Never throws. */
    public static boolean keepScreenBrightness() {
        try {
            if (!Settings.KEEP_CAMERA_BRIGHTNESS.get()) return false;
            Logger.printDebug(() -> "Keep the screen's brightness: skipped TikTok's camera boost");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }
}
