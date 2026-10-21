/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * What the Open the comments at full height patch asks while the comment sheet is sized
 * (issue #191).
 *
 * <p>TikTok sizes the comment sheet from a server mode: 68 or 73 percent of the screen, a ratio
 * it picks, or a fixed height from its resources when the mode is 0. The method that turns the
 * mode into pixels asks here first, and while the switch is on it hands back its sibling's
 * answer instead, the full height the screen has below the status bar. The sheet's own sizing
 * asks for the mode too, and skips the pixels method when it is 0, so that answer is lifted to
 * a mode that goes through it. Dragging the sheet down to close is TikTok's own and still works.
 *
 * <p>Off, paused, or a failure in here, and the sheet opens at the height TikTok gave it.
 */
public final class CommentSheetHeight {
    private CommentSheetHeight() {
    }

    /** Injection point, first thing in the method that turns the sheet's mode into pixels. Never throws. */
    public static boolean openAtFullHeight() {
        try {
            if (!Settings.COMMENTS_FULL_HEIGHT.get()) return false;
            Logger.printDebug(() -> "Open the comments at full height: sheet sized to the screen");
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * Injection point, on the sheet's mode as its sizing reads it. A mode of 0 or below sends the
     * sheet to a fixed height that never asks the pixels method, so with the switch on it becomes
     * 1, one of the modes that does. Any other mode is left as it is. Never throws.
     */
    public static int heightMode(int mode) {
        if (mode > 0) return mode;
        return openAtFullHeight() ? 1 : mode;
    }
}
