/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

/** Shares the live `hide_inbox_stories` row filter with `Hide inbox items`. */
@Suppress("unused")
val hideInboxStoriesPatch = bytecodePatch(
    name = "Hide inbox stories",
    description = "Hides the stories tray at the top of the Inbox and brings it back as soon as " +
        "the switch is turned off. It shares its switch with Hide inbox items, and that switch " +
        "is off until you turn it on. Switch: Hushfeed settings > Inbox.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideInboxStories()V",
        )

        MainActivityOnCreateFingerprint.method.installInboxLayoutFilter()
    }
}
