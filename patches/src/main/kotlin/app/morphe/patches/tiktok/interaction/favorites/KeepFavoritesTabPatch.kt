/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.favorites

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.absettings.hookAppAbBooleanBoundary
import app.morphe.patches.tiktok.misc.absettings.hookAppAbIntBoundary
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/favorites/FavoritesTab;"
private const val QUICK_FAVORITES_DARK = "Lapp/morphe/extension/tiktok/favorites/QuickFavoritesDark;"

/**
 * The class name TikTok's call interceptor records when a quick favorites popup calls its
 * dialog's show, written next to "show" into the method of each popup that opens it. The
 * classes and that method are renamed on every build (`LJJII(Z)V` on 47.1.4); these strings
 * are not.
 */
internal const val QUICK_FAVORITE_PANEL_TRACE =
    "com/ss/android/ugc/aweme/favorites/business/collection/quickfavorite/AbstractQuickFavoritePanelDialog"

/** The method that opens a popup: an instance void method carrying [QUICK_FAVORITE_PANEL_TRACE] and "show". */
internal fun Method.isQuickFavoritePanelShow(): Boolean {
    if (returnType != "V" || AccessFlags.STATIC.isSet(accessFlags)) return false
    val strings = implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string } ?: return false
    return QUICK_FAVORITE_PANEL_TRACE in strings && "show" in strings
}

/**
 * The profile's Favorites tab builds its pages in `FavoritesFragmentV2.onCreate`, and two app
 * AB gates make it build none: `favorite_reverse` read as 1 and
 * `new_user_favorite_full_reversal_exp` read as true. Either one leaves the tab in place and
 * empty, which is what two people reported after patching. Both reads go through the app AB
 * class's typed getters, so the two boundary hooks see every read and answer those two.
 *
 * Holding the Favorites button opens a popup of your collections, drawn white in dark mode too
 * (issue #142). TikTok has a horizontal and a vertical version under one abstract base, each
 * with a method that shows the dialog and fades it in; the extension is handed the popup at
 * every return of both and paints it dark when TikTok is.
 */
@Suppress("unused")
val keepFavoritesTabPatch = bytecodePatch(
    name = "Keep the Favorites tab",
    description = "Keeps the Favorites tab and your saved videos on your profile when TikTok " +
        "tries out a version of the app that hides them, and makes the collections popup you " +
        "get by holding the Favorites button dark when TikTok is. Both are on by default. Turn " +
        "them off in Hushfeed settings > App.",
    default = true,
) {
    category("Settings")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableKeepFavoritesTab()V",
        )
        hookAppAbIntBoundary(EXTENSION_CLASS_DESCRIPTOR, "keepInt")
        hookAppAbBooleanBoundary(EXTENSION_CLASS_DESCRIPTOR, "keepBoolean")

        val shows = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
            classDef.methods.filter { it.isQuickFavoritePanelShow() }.forEach { shows += classDef to it }
        }
        if (shows.size != 2) {
            throw PatchException(
                "Keep the Favorites tab: expected the horizontal and vertical quick favorites " +
                    "popups' show, found ${shows.size}: ${shows.map { "${it.first.type}->${it.second.name}" }}.",
            )
        }
        shows.forEach { (classDef, show) ->
            val method = mutableClassDefBy(classDef.type).findMutableMethodOf(show)
            val returns = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            if (returns.isEmpty()) {
                throw PatchException("Keep the Favorites tab: ${classDef.type}->${show.name} has no return to hook.")
            }
            returns.asReversed().forEach { index ->
                method.addInstructionsAtControlFlowLabel(
                    index,
                    "invoke-static/range {p0 .. p0}, $QUICK_FAVORITES_DARK->paint(Ljava/lang/Object;)V",
                )
            }
        }
    }
}
