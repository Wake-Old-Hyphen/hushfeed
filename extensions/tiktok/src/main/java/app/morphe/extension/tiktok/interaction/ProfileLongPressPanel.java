/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Full long-press menu on a creator's videos (#120). TikTok picks the long-press panel's shape by
 * the page it opened on, looked up in its server's long-press panel config: For You gets the full
 * menu, a video opened from someone's profile only the small share sheet. Every lookup in that
 * config passes the page's name through one normalizer first, and this answers For You's name
 * there for a profile's, so the profile gets For You's menu. The share itself still says where it
 * came from.
 */
public final class ProfileLongPressPanel {
    static final String PROFILE = "others_homepage";
    static final String FOR_YOU = "homepage_hot";

    private ProfileLongPressPanel() {
    }

    /** Called first thing in TikTok's long-press scene normalizer, with the page's name. */
    @Nullable
    public static String scene(@Nullable String scene) {
        try {
            // With a Long press action of Hushfeed's the menu never opens, and the profile's Share
            // button sheet, which reads the same config, is left as TikTok draws it.
            if (PROFILE.equals(scene) && SettingsStatus.longPressEnabled
                    && Settings.LONG_PRESS_FULL_MENU_ON_PROFILES.get()
                    && !GestureActions.takesLongPress(Settings.LONG_PRESS_ACTION.get())) {
                return FOR_YOU;
            }
        } catch (Throwable error) {
            Logger.printException(() -> "Could not choose the long-press menu", error);
        }
        return scene;
    }
}
