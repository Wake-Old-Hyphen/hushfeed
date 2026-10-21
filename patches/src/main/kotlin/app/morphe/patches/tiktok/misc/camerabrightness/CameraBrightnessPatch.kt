/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.camerabrightness

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val PATCH = "Keep the screen's brightness in the camera"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/CameraBrightness;"
private const val LIFECYCLE_OWNER = "Landroidx/lifecycle/LifecycleOwner;"
private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"

/**
 * The brightness boost's start on 47.4.3, `BrightnessManager.LIZ(activity, owner, scene)`. The
 * camera, editor, post screen and effect preview call it from onStart. It reads TikTok's server
 * config, then either hands the window to the light sensor or sets it once from the phone's own
 * level plus a step, and watches the brightness slider until the owner stops. The class keeps its
 * name; the method is the only static void one there taking a lifecycle owner second.
 */
internal object BrightnessBoostStartFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/property/BrightnessManager;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    custom = { method, _ ->
        method.parameterTypes.size == 3 && method.parameterTypes[1].toString() == LIFECYCLE_OWNER
    },
)

/**
 * The older start, in an obfuscated class (`LX/1P0U;` on 47.4.3). The same screens call it from
 * onCreate, and it returns straight away when the server flag picks the one above, so only one
 * of the two runs. It names its own server key and writes the window's brightness itself.
 */
internal object OldBrightnessBoostStartFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("L", LIFECYCLE_OWNER, "L"),
    strings = listOf("creative_tools_opti_max_brightness", "screen_brightness"),
    filters = listOf(fieldAccess(definingClass = LAYOUT_PARAMS, name = "screenBrightness", opcode = Opcode.IPUT)),
)

@Suppress("unused")
val cameraBrightnessPatch = bytecodePatch(
    name = "Keep the screen's brightness in the camera",
    description = "Stops TikTok from turning your screen brighter while you film, edit or " +
        "post a video. Starts off. Turn it on in Hushfeed settings > App.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    // PineDrama has no camera, and neither start is in it.
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val ask = "invoke-static {}, $EXTENSION->keepScreenBrightness()Z"
        // A plain skip, so the window keeps whatever brightness it had. Skipped, the start also
        // registers no light sensor and no stop observer, so nothing writes it later either.
        BrightnessBoostStartFingerprint.method.guardAtEntry(PATCH, ask, "return-void")
        OldBrightnessBoostStartFingerprint.method.guardAtEntry(PATCH, ask, "return-void")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCameraBrightness()V",
        )
    }
}
