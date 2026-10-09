/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch

private const val LIVE_ICON_GENERATOR_DESCRIPTOR =
    "Lcom/bytedance/tiktok/homepage/mainfragment/toolbar/LiveIconGenerator;"

private object LiveIconEnabledFingerprint : Fingerprint(
    definingClass = LIVE_ICON_GENERATOR_DESCRIPTOR,
    name = "enabled",
    returnType = "Z",
    parameters = emptyList(),
)

@Suppress("unused")
val hideFeedLiveButtonPatch = bytecodePatch(
    name = "Hide feed LIVE button",
    description = "Removes the LIVE button from the top left of the feed, for a cleaner " +
        "screen. Starts off. Turn it on in Hushfeed settings > Feed screen.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideFeedLiveButton()V",
        )
        LiveIconEnabledFingerprint.method.overrideToolbarButtonEnabled(
            "hideFeedLiveButtonEnabled",
        )
    }
}
