/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.checkBooleanWhenOn
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide feed LIVE button"
private const val HOME_SIDEBAR_SERVICE = "Lcom/ss/android/ugc/aweme/spi/IHomeSidebarService;"
private const val FEATURE_CONTROLS = "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

/**
 * The feed's edge-swipe helper (#213), whose class holds the hot-area test `(FF)Z` and a plain
 * `()Z` that asks the home sidebar service whether the side menu is on offer. The pager asks that
 * before it lets a right swipe from For You go to the side menu page, so a no sends the swipe to
 * the next tab. The class is renamed on every build, so it is found by what it holds.
 */
internal object SidebarSwipeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = emptyList(),
    custom = { method, classDef -> isSidebarSwipeQuestion(method, classDef) },
)

internal fun isSidebarSwipeQuestion(method: Method, classDef: ClassDef): Boolean {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val asks = method.implementation?.instructions?.toList()
        ?.filter { it.opcode == Opcode.INVOKE_INTERFACE }
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.filter { it.definingClass == HOME_SIDEBAR_SERVICE }
        .orEmpty()
    // The service comes out of a lazy holder, so a Lazy call sits in front of the one that counts.
    if (asks.size != 1) return false
    val ask = asks.single()
    if (ask.returnType != "Z" || ask.parameterTypes.isNotEmpty()) return false
    // The hot-area test sits beside it: two floats in, a yes or no out.
    return classDef.methods.any { sibling ->
        sibling.returnType == "Z" && sibling.parameterTypes.map { it.toString() } == listOf("F", "F")
    }
}

/**
 * Turns the right swipe to the side menu off while its switch is on (#213), and says whether the
 * hook went in. Required on a declared build, where the anchors test holds it, and left out with
 * a note on any other, so a reworked pager there doesn't take the LIVE switches down with it. The
 * settings row shows only when the hook went in. Takes the version so a test can stand in for a
 * build that isn't declared.
 */
internal fun BytecodePatchContext.hookSidebarSwipeOn(versionName: String): Boolean {
    if (!hookSidebarSwipe(versionName)) return false
    SettingsStatusLoadFingerprint.method.addInstruction(
        0,
        "invoke-static {}, " +
            "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedSidebarSwipe()V",
    )
    return true
}

/** The guard alone, without the settings flag that shows the row, so a probe patch can run it. */
internal fun BytecodePatchContext.hookSidebarSwipe(versionName: String): Boolean {
    val question = if (versionName in declaredVersions()) {
        SidebarSwipeFingerprint.method
    } else {
        SidebarSwipeFingerprint.methodOrNull
    }
    if (question == null) {
        println("[$PATCH] Left out the side menu swipe on $versionName: no swipe check found.")
        return false
    }
    question.checkBooleanWhenOn(PATCH)
    question.returnBooleanWhenOn(PATCH, "$FEATURE_CONTROLS->sidebarSwipeOff()Z", false)
    return true
}
