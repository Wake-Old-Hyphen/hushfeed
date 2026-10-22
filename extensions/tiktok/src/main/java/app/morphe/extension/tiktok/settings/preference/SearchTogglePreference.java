/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.SwitchPreference;
import android.view.View;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.SettingsSearchHistory;

/**
 * A switch found by the settings search, flipped where it stands. It saves under the setting's
 * own key, so the page's change handling treats it exactly like the row on its page.
 *
 * <p>Not a {@link TogglePreference}: the title and summary arrive translated from the search
 * index, with the restart sentence and the page name already in them, and that class would look
 * them up again and add the sentence a second time in every language but English.
 */
@SuppressWarnings("deprecation")
final class SearchTogglePreference extends SwitchPreference {
    private final String query;

    SearchTogglePreference(Context context, String title, String summary, BooleanSetting setting, String query) {
        super(context);
        this.query = query;
        setTitle(title);
        setSummary(summary);
        setKey(setting.key);
        setChecked(setting.savedValue());
    }

    /** Flipping a found switch is the search doing its job, so it's kept like opening a result (#232). */
    @Override
    protected void onClick() {
        SettingsSearchHistory.add(query);
        super.onClick();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
    }
}
