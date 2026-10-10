/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feed;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Whether the music disc at the bottom of the right column turns (#68).
 *
 * <p>On 47.1.4 the turn is a ValueAnimator VideoMusicCoverAssem starts once its view model's
 * startMusicAnimation goes true, and the only thing that sets it is the assem's play subscriber.
 * That subscriber returns before setting it when the app AB int {@code music_animation_close_exp}
 * is 1 or 3, unless {@code video_music_cover_visual_opt_rotation_duration} is above 0. TikTok's
 * own default for the first is 3 and for the second 0, so the disc stays still unless the server
 * sends something else, which is what the S22 showed with every Hushfeed switch off. Nothing on a
 * video or its sound decides it.
 *
 * <p>The close setting is two flags: 1 holds the disc still, 2 stops the track name scrolling,
 * 3 is both. Only the disc's flag is answered here, so the track name keeps TikTok's choice. A
 * rotation duration above 0 lets the disc turn even with its flag set (and sets the turn's length
 * in seconds), so holding it still answers that one too. Both are read once per launch through a
 * lazy value, which is why the two switches take a restart.
 *
 * <p>Past the subscriber the animator still starts through TikTok's low-end device check
 * ("musicCoverDegrade"), which is left alone: on a phone TikTok counts as low end, a still disc
 * is TikTok's own decision.
 */
public final class MusicDiscSpin {
    /** The Hook status family this reports under. */
    static final String FAMILY = "music disc spin";
    static final String INSTALLED = "installed";
    /** The close setting's flag that holds the disc still. */
    static final int HOLD_DISC = 1;
    /** The close setting's flag that stops the track name scrolling. */
    static final int HOLD_TRACK_NAME = 2;

    private MusicDiscSpin() {}

    /** Called at settings load, so the family is in every export whether or not a read came by. */
    public static void installed() {
        HookStatus.bound(FAMILY, INSTALLED);
    }

    /** {@code music_animation_close_exp} as TikTok is about to keep it for this launch. */
    public static int closeSetting(int value) {
        boolean still = Settings.STOP_MUSIC_DISC_SPIN.get();
        if (!still && !Settings.SPIN_MUSIC_DISC.get()) return value;
        // The two flags as the app tests them: the disc for 1 or 3, the track name for 2 or 3.
        boolean discHeld = value == HOLD_DISC || value == (HOLD_DISC | HOLD_TRACK_NAME);
        if (discHeld == still) return value;
        boolean trackNameHeld = value == HOLD_TRACK_NAME || value == (HOLD_DISC | HOLD_TRACK_NAME);
        int answer = (still ? HOLD_DISC : 0) | (trackNameHeld ? HOLD_TRACK_NAME : 0);
        HookStatus.bound(FAMILY, still ? "held the disc" : "spun the disc");
        Logger.printInfo(() -> "Music disc: music_animation_close_exp came back " + value
                + ", answered " + answer);
        return answer;
    }

    /** {@code video_music_cover_visual_opt_rotation_duration}, in seconds, as TikTok is about to keep it. */
    public static int rotationSeconds(int seconds) {
        if (seconds <= 0 || !Settings.STOP_MUSIC_DISC_SPIN.get()) return seconds;
        HookStatus.bound(FAMILY, "held the disc's turn");
        Logger.printInfo(() -> "Music disc: video_music_cover_visual_opt_rotation_duration came back "
                + seconds + ", answered 0");
        return 0;
    }
}
