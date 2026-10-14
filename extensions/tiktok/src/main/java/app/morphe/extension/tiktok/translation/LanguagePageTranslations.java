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
 * The switch in front of the gates that hide the translation rows on TikTok's Settings and
 * privacy > Language page (#143).
 *
 * <p>The page draws its translation group (the Translation heading, Always translate, Translate
 * into and Don't translate) only when two App AB values allow it: {@code feed_translation_reverse}
 * must not be 1 and {@code pure_feed_reverse} must be 0. The server sets those per account, so a
 * logged-out phone can get the rows and the same phone loses them once an account that is in
 * either experiment signs in. Nothing Hushfeed hooks decides it. The patch sends both answers
 * through {@link #hidden} as the page reads them, only there, so the feed and every other screen
 * that reads the same two values keep TikTok's answer. With the switch off, and while Hushfeed is
 * paused, the page gets TikTok's answer too.
 */
@SuppressWarnings("unused")
public final class LanguagePageTranslations {
    private static final String HOOK_FAMILY = "language page translations";

    private LanguagePageTranslations() {
    }

    /** False while the switch is on; TikTok's own answer otherwise. Called with each gate's answer. */
    public static boolean hidden(boolean tikToks) {
        HookStatus.bound(HOOK_FAMILY, tikToks ? "TikTok hid the rows" : "TikTok showed the rows");
        if (!tikToks) return false;
        // The page opens long after the application context exists, but loading Settings without
        // one throws inside TikTok, so this never assumes it.
        if (Utils.getContext() == null || !Settings.SHOW_TRANSLATION_SETTINGS.get()) return true;
        Logger.printDebug(() -> "[LanguagePageTranslations] showed the translation rows TikTok hid");
        return false;
    }
}
