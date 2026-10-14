/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.download.LiveRecorder;
import app.morphe.extension.tiktok.download.LiveRecordingSaver;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * The Record button in a LIVE room (#37). The room's page tells this class it is resuming; while
 * the switch is on a small chip shows on that page. Tapping it starts a recording from that
 * moment, tapping it again stops, and leaving the room (another room, the app in the background,
 * the page gone) stops it too. A once-a-second check on the main thread is what notices leaving,
 * so a recording never runs on for more than a second without its room on screen.
 */
@SuppressWarnings("unused")
public final class LiveRecordChip {
    private LiveRecordChip() {
    }

    private static final long TICK_MS = 1000;

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    // Everything below is touched on the main thread only.
    private static WeakReference<Object> page = new WeakReference<>(null);
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static TextView chip;
    private static LiveRecorder recorder;
    private static boolean saving;
    private static boolean ticking;
    private static boolean callbacksRegistered;

    /** True while the switch is on and Hushfeed isn't paused. */
    static boolean enabled() {
        return SettingsStatus.liveControlsEnabled && Settings.RECORD_LIVE.get() && !Setting.isPaused();
    }

    /** Called as a LIVE room's page begins to resume, with that page. */
    public static void noteRoomFragment(Object fragment) {
        try {
            if (fragment == null) return;
            Utils.runOnMainThread(() -> onRoomResumed(fragment));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not note the LIVE room page", failure);
        }
    }

    private static void onRoomResumed(Object fragment) {
        try {
            Object previous = page.get();
            if (previous != null && previous != fragment) leave(LiveRecorder.End.LEFT_ROOM);
            Object activityObject = Reflect.invoke(fragment, "getActivity");
            if (!(activityObject instanceof Activity)) return;
            Activity activity = (Activity) activityObject;
            page = new WeakReference<>(fragment);
            host = new WeakReference<>(activity);
            registerCallbacks(activity);
            if (!enabled()) {
                removeChip();
                return;
            }
            showChip(activity);
            refresh();
            scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set up the LIVE record button", failure);
        }
    }

    private static void registerCallbacks(Activity activity) {
        if (callbacksRegistered) return;
        Application application = activity.getApplication();
        if (application == null) return;
        callbacksRegistered = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity paused) {
                if (paused == host.get()) {
                    leave(LiveRecorder.End.LEFT_ROOM);
                    removeChip();
                }
            }

            @Override public void onActivityDestroyed(Activity destroyed) {
                if (destroyed == host.get()) {
                    leave(LiveRecorder.End.APP_EXIT);
                    removeChip();
                    page = new WeakReference<>(null);
                    host = new WeakReference<>(null);
                }
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityResumed(Activity resumed) { }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity saved, Bundle state) { }
        });
    }

    // ---- the chip -------------------------------------------------------------------------

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static void showChip(Activity activity) {
        if (chip != null && chip.getContext() == activity && chip.getParent() != null) return;
        removeChip();
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        TextView view = new TextView(activity);
        view.setTextColor(Color.WHITE);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(activity, 48));
        view.setMinWidth(dp(activity, 48));
        view.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 24));
        background.setColor(0xB3000000);
        view.setBackground(background);
        view.setOnClickListener(clicked -> onChipTap());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        params.topMargin = dp(activity, 132);
        params.rightMargin = dp(activity, 12);
        content.addView(view, params);
        chip = view;
    }

    private static void removeChip() {
        TextView view = chip;
        chip = null;
        if (view != null && view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }

    private static void refresh() {
        TextView view = chip;
        if (view == null) return;
        if (saving) {
            view.setText(L10n.t("Saving the recording"));
            view.setContentDescription(L10n.t("Saving the recording"));
            view.setEnabled(false);
            return;
        }
        view.setEnabled(true);
        if (recorder != null && recorder.recording()) {
            long seconds = recorder.elapsedMs() / 1000;
            String elapsed = seconds >= 3600
                    ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                    : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60);
            view.setText("● " + elapsed + "  " + L10n.t("Stop"));
            view.setContentDescription(L10n.t("Stop recording this LIVE"));
        } else {
            view.setText(L10n.t("Record"));
            view.setContentDescription(L10n.t("Record this LIVE from now"));
        }
    }

    // ---- start and stop -------------------------------------------------------------------

    private static void onChipTap() {
        try {
            if (saving) return;
            if (recorder != null && recorder.recording()) {
                recorder.stop(LiveRecorder.End.STOPPED);
                saving = true;
                refresh();
                return;
            }
            start();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not use the LIVE record button", failure);
            Utils.showToastShort(L10n.t("The recording couldn't be saved. Try again."));
        }
    }

    private static void start() throws Exception {
        Activity activity = host.get();
        Object fragment = page.get();
        if (activity == null || fragment == null || !enabled()) return;
        if (Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to record LIVEs"));
            return;
        }
        Object room = roomOf(fragment);
        LiveRoomStream.Pick pick = LiveRoomStream.fromRoom(room, LiveControls.lastQualityPick());
        if (pick.target == null) {
            Logger.printInfo(() -> "LIVE record: no stream to record (" + pick.problem + ")");
            Utils.showToastShort(L10n.t("Couldn't find this LIVE's stream to record. Leave the room and open it again."));
            return;
        }
        LiveRecorder started = LiveRecordingSaver.create(activity, pick.target, result ->
                Utils.runOnMainThread(() -> onFinished(result)));
        recorder = started;
        saving = false;
        started.start();
        Utils.showToastShort(L10n.t("Recording this LIVE from now"));
        refresh();
        scheduleTick();
    }

    private static void onFinished(LiveRecorder.Result result) {
        recorder = null;
        saving = false;
        LiveRecordingSaver.announce(result);
        refresh();
    }

    /** Stops a running recording because the room is no longer in front. */
    private static void leave(LiveRecorder.End why) {
        LiveRecorder running = recorder;
        if (running != null && running.active()) {
            running.stop(why);
            saving = true;
        }
    }

    // ---- watching the room ----------------------------------------------------------------

    private static void scheduleTick() {
        if (ticking) return;
        ticking = true;
        HANDLER.postDelayed(LiveRecordChip::tick, TICK_MS);
    }

    private static void tick() {
        ticking = false;
        try {
            Object fragment = page.get();
            Activity activity = host.get();
            boolean showing = fragment != null && activity != null && enabled() && roomInFront(fragment, activity);
            if (!showing) {
                leave(LiveRecorder.End.LEFT_ROOM);
                removeChip();
            } else {
                LiveRecorder running = recorder;
                if (running != null && running.active() && !sameRoom(running, fragment)) {
                    leave(LiveRecorder.End.LEFT_ROOM);
                }
                showChip(activity);
                refresh();
            }
            // Keep watching while a recording runs or the chip is up; a new resume restarts it.
            if (recorder != null || (showing && chip != null)) scheduleTick();
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not check the LIVE room", failure);
        }
    }

    private static boolean roomInFront(Object fragment, Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        if (Boolean.FALSE.equals(Reflect.invoke(fragment, "isResumed"))) return false;
        if (Boolean.FALSE.equals(Reflect.invoke(fragment, "getUserVisibleHint"))) return false;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isRemoving"))) return false;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isDetached"))) return false;
        if (Boolean.TRUE.equals(Reflect.invoke(fragment, "isHidden"))) return false;
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        return decor != null && decor.isShown();
    }

    private static boolean sameRoom(LiveRecorder running, Object fragment) {
        String recorded = running.target().room;
        if (recorded == null) return true;
        Object room = roomOf(fragment);
        Object id = room == null ? null : Reflect.readField(room, "idStr");
        return !(id instanceof String) || recorded.equals(id);
    }

    /** The page's one Room-typed field, found by type so it doesn't depend on its obfuscated name. */
    private static Object roomOf(Object fragment) {
        try {
            for (Class<?> type = fragment.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().equals("com.bytedance.android.livesdkapi.depend.model.live.Room")) {
                        field.setAccessible(true);
                        return field.get(fragment);
                    }
                }
            }
        } catch (Throwable ignored) {
            // no room readable
        }
        return null;
    }
}
