/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The theme LIVE panels inflate with while Dark LIVE panels is on in dark mode (#159). */
@SuppressWarnings("unused")
public final class LivePanelTheme {
    /** The base of every themed LIVE dialog (LiveDialogFragment and the rest). */
    static final String THEMED_DIALOG = "com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment";
    /** The base of LIVE's sheet pages: the fan club sheets and most newer LIVE panels. */
    static final String SHEET = "com.bytedance.android.live.design.view.sheet.LiveSheetFragment";

    private static final Map<Class<?>, Boolean> PANEL_TYPES = new ConcurrentHashMap<>();
    private static volatile boolean overlayFailureLogged;

    private LivePanelTheme() {
    }

    /**
     * Called with each layout inflater a themed LIVE dialog or LIVE fragment hands its views,
     * with the dialog or fragment. TikTok gives a LIVE panel its light colors by inflating it
     * through its light LIVE theme, applied over the room's own with force. The dark one it only
     * adds to the room's theme without force, so every color the room's light theme already set
     * wins and the panel stays white, which is what the S25 showed with only the provider
     * answering dark (#159). While Dark LIVE panels is on and the panel's context is in dark
     * mode, the panel's views inflate through its own theme overlay, which the provider hook
     * makes TikTok's dark LIVE theme, applied with force the way TikTok applies the light one. A
     * room's own fragments aren't panels and keep TikTok's inflater, and anything that fails
     * hands TikTok's back.
     */
    public static LayoutInflater darkLivePanel(Object panel, LayoutInflater inflater) {
        if (panel == null || inflater == null) {
            return inflater;
        }
        try {
            Context context = inflater.getContext();
            if (!LiveControls.darkLivePanels(context) || !isPanel(panel.getClass())) {
                return inflater;
            }
            int overlay = themeOverlay(panel, context);
            if (overlay == 0) {
                return inflater;
            }
            return inflater.cloneInContext(new ContextThemeWrapper(context, overlay));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE panel", failure);
            return inflater;
        }
    }

    /** True for a themed LIVE dialog or a LIVE sheet page, by TikTok's own class names. */
    static boolean isPanel(Class<?> type) {
        Boolean known = PANEL_TYPES.get(type);
        if (known != null) {
            return known;
        }
        boolean panel = false;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (THEMED_DIALOG.equals(name) || SHEET.equals(name)) {
                panel = true;
                break;
            }
        }
        PANEL_TYPES.put(type, panel);
        return panel;
    }

    /**
     * The panel's own LIVE theme overlay (getThemeOverlay), 0 when it has none. A panel that
     * names its own overlay gets that one again, so only the default one turns dark.
     */
    static int themeOverlay(Object panel, Context context) {
        try {
            Method method = panel.getClass().getMethod("getThemeOverlay", Context.class);
            Object overlay = method.invoke(panel, context);
            return overlay instanceof Integer ? (Integer) overlay : 0;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!overlayFailureLogged) {
                overlayFailureLogged = true;
                Logger.printException(() -> "A LIVE panel has no theme overlay to darken it with", failure);
            }
            return 0;
        }
    }
}
