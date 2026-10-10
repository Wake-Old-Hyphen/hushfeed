/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.misc;

import android.content.Context;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of Keep the app language (#61).
 *
 * <p>Early in each start TikTok compares the phone's language with the one it saw last time.
 * When a language was picked in TikTok's own settings and it decides the phone's language
 * changed, it clears that pick from {@code key_language_sp_key} and follows the phone again. The
 * #61 reporter's TikTok came up in English after every reboot while its Language page still said
 * Italian, and their own build that skipped this reset stayed Italian. With the switch on the
 * reset returns before it clears anything. With it off, and while Hushfeed is paused, TikTok's
 * reset runs as it ships.
 *
 * <p>The reset runs inside the application's attachBaseContext, before the settings context
 * exists and before ActivityThread has an application, so the switch is read from the saved file
 * through the context TikTok hands the reset. TikTok passes null there sometimes, and then the
 * process's application is tried.
 */
@SuppressWarnings("unused")
public final class AppLanguage {
    /** {@link Settings#KEEP_APP_LANGUAGE}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "keep_app_language";

    private AppLanguage() {
    }

    /** True while TikTok's language reset should leave the language picked in TikTok alone. */
    public static boolean keepChosenLanguage(Context context) {
        return Utils.getContext() != null
                ? Settings.KEEP_APP_LANGUAGE.get() : EarlySwitch.isOn(context, SWITCH_KEY);
    }
}
