/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.captions;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keep captions available (#217): the Captions switch on TikTok's Settings and privacy >
 * Accessibility page, and the caption controls it belongs to, on accounts TikTok leaves them off for.
 *
 * <p>Two server values decide it. {@code enable_captions_a11y_setting} places the Captions row on
 * the Accessibility page: 0 leaves it out, 1 puts it first and 2 puts it last. {@code
 * fixed_caption_v2_consumer_group} turns on the caption controls the row works with. Without it the
 * row draws nothing even where it's placed, and the player, the share panel's Captions item and the
 * caption toggle read the same answer. While the setting is on, a row left out reads as placed first
 * and the controls read as on, the answers TikTok gives the accounts it shows them to. Any other
 * answer is TikTok's, and so is every answer while the setting is off, while Hushfeed is paused and
 * before the application context exists. TikTok reads both values once per launch, so the setting
 * asks for a restart.
 *
 * <p>Captions still come from the tracks a video carries, auto generated or added by its creator.
 * A video without any shows none either way.
 */
@SuppressWarnings("unused")
public final class CaptionsAvailable {
    private static final String HOOK_FAMILY = "captions available";

    /** TikTok's answer that places the Captions row first on the Accessibility page. */
    static final int ROW_FIRST = 1;

    private CaptionsAvailable() {
    }

    /**
     * Where the Captions row goes, from the server's {@code server}: 0 (left out) becomes {@link
     * #ROW_FIRST} while the setting is on. Called once per launch, when the page first asks.
     */
    public static int captionsRow(int server) {
        if (server != 0) return server;
        try {
            if (!keepAvailable()) return server;
            HookStatus.bound(HOOK_FAMILY, "placed the Captions row");
            return ROW_FIRST;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[CaptionsAvailable] left the Captions row where TikTok put it", ex);
            return server;
        }
    }

    /**
     * Whether the caption controls read as on before TikTok's own answer is asked. False hands the
     * question to TikTok. Called each time a video, the share panel or the Accessibility page asks.
     */
    public static boolean captionControls() {
        try {
            if (!keepAvailable()) return false;
            HookStatus.bound(HOOK_FAMILY, "caption controls on");
            return true;
        } catch (Exception ex) {
            Logger.printDebug(() -> "[CaptionsAvailable] left the caption controls to TikTok", ex);
            return false;
        }
    }

    private static boolean keepAvailable() {
        // TikTok can ask while it starts, before Settings can load without throwing.
        return Utils.getContext() != null && Settings.KEEP_CAPTIONS_AVAILABLE.get();
    }
}
