/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Cells in the menu your profile's top right button opens (#194). Balance is the one that shows
 * your coins and gift earnings, and the TikTok Minis entry has a second cell of app icons (#230).
 *
 * <p>TikTok asks one method about each cell by its id while it builds that menu, and leaves a cell
 * out when the answer is no. The patch puts {@link #hide} in front of that question, so a hidden
 * cell is left out the way TikTok leaves out one it doesn't offer you. Nothing is sent, and your
 * coins and the Balance page itself are untouched.
 */
@SuppressWarnings("unused")
public final class ProfileMenuCells {
    /** The Balance cell's id, the same on every build that has the cell. */
    static final String BALANCE = "content_section_cell_balance";
    /**
     * The card of app icons under the TikTok Minis entry (#230). It is a cell of its own with its
     * own id, so leaving it out keeps the TikTok Minis entry and drops the icons.
     */
    static final String MINIS_ICONS = "content_section_cell_tiktok_minis_rich_card";

    private ProfileMenuCells() {
    }

    /** Asked for each cell before TikTok adds it to the menu. True leaves the cell out. */
    public static boolean hide(String cell) {
        if (BALANCE.equals(cell)) {
            boolean hide = Settings.HIDE_PROFILE_MENU_BALANCE.get();
            HookStatus.bound("profile menu balance", hide ? "cell hidden" : "cell left");
            return hide;
        }
        if (MINIS_ICONS.equals(cell)) {
            boolean hide = Settings.HIDE_PROFILE_MENU_MINIS_ICONS.get();
            HookStatus.bound("profile menu minis icons", hide ? "cell hidden" : "cell left");
            return hide;
        }
        return false;
    }
}
