/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

internal const val COMMENT_PHOTOS_SWITCH = "Lapp/morphe/extension/tiktok/comment/CommentPhotos;->isOn()Z"

/**
 * PineDrama's comment protocol, asked whether the comment box may build its gallery tab. The
 * caller passes bit 1 of the `comment_photo_publish` value; TikTok's protocol answers with it,
 * and PineDrama's answers with an unrelated creation check that is false on every account
 * (47.4.3, #156). It is the only `(Z)Z` the class declares.
 */
internal object DramaCommentPhotoGateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/appdiff/DramaCommentAppDiffProtocol;",
    returnType = "Z",
    parameters = listOf("Z"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

@Suppress("unused")
val commentPhotosPatch = bytecodePatch(
    name = "Allow photo comments",
    description = "Lets you bring back the gallery tab in PineDrama's comment box, so you can " +
        "add a photo to a comment like on TikTok. TikTok's server still decides whether your " +
        "account can post one. Starts off. Turn it on in Hushfeed settings > Comments.",
) {
    category("Comments")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.pineDrama())

    execute {
        DramaCommentPhotoGateFingerprint.method.installCommentPhotoSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentPhotos()V",
        )
    }
}

/**
 * With the switch on, the gate answers with the value it was handed, the way TikTok's does. The
 * answer goes in v0, so the frame needs a local, and the value is read from p1, so the method has
 * to be an instance `(Z)Z`: anything else is refused before a line is added.
 */
internal fun MutableMethod.installCommentPhotoSwitch() {
    if (returnType != "Z" || parameterTypes.map { it.toString() } != listOf("Z") || AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Allow photo comments: $definingClass->$name is not an instance (Z)Z.")
    }
    guardAtEntry("Allow photo comments", "invoke-static {}, $COMMENT_PHOTOS_SWITCH", "return p1")
}
