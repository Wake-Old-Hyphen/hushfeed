/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.language

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep the app language"
internal const val APP_LANGUAGE_SWITCH =
    "Lapp/morphe/extension/tiktok/misc/AppLanguage;->keepChosenLanguage(Landroid/content/Context;)Z"

/** TikTok's language service. Its service registry loads it by this name, so R8 keeps it. */
internal const val I18N_MANAGER_SERVICE = "Lcom/tiktok/ef/i18nimpl/service/I18nManagerServiceImpl;"
internal const val I18N_MANAGER_API = "Lcom/tiktok/ef/i18nmanagerapi/service/i18n/I18nManagerServiceApi;"
internal const val LANGUAGE_PREFERENCES = "key_language_sp_key"
private const val CONTEXT = "Landroid/content/Context;"
private const val EDITOR = "Landroid/content/SharedPreferences\$Editor;"

/**
 * TikTok's language reset (`LJJ` on 47.1.4, read from the fixture's dex): it clears the
 * [LANGUAGE_PREFERENCES] file, where the language picked in TikTok's settings is kept, then
 * applies the language that read gives back with nothing saved, the phone's. Only the start-up
 * language check calls it (`X.033C` on 47.1.4, through [I18N_MANAGER_API], in two places), and
 * only once a language was picked: when the phone's language differs from the one saved at the
 * last start and the reader never changed it in TikTok, or when a language TikTok newly supports
 * is the phone's. It runs inside the application's attachBaseContext.
 */
internal object AppLanguageResetFingerprint : Fingerprint(
    definingClass = I18N_MANAGER_SERVICE,
    returnType = "V",
    parameters = listOf(CONTEXT),
    custom = { method, _ -> method.isAppLanguageReset() },
)

/**
 * Whether [method] takes a context, reads [LANGUAGE_PREFERENCES] and clears a preferences file. It
 * must be an instance method, since the guard reads the context as p1.
 */
internal fun Method.isAppLanguageReset(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags)) return false
    if (returnType != "V" || parameterTypes.map(CharSequence::toString) != listOf(CONTEXT)) return false
    val instructions = implementation?.instructions ?: return false
    return instructions.any { it.getReference<StringReference>()?.string == LANGUAGE_PREFERENCES } &&
        instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let { it.definingClass == EDITOR && it.name == "clear" } == true
        }
}

@Suppress("unused")
val keepAppLanguagePatch = bytecodePatch(
    name = PATCH,
    description = "Lets you keep the language you picked in TikTok's own settings. When TikTok " +
        "starts and decides the phone's language changed, it drops that pick and follows the " +
        "phone, which can leave it in the wrong language after a reboot. Starts off. Turn it on " +
        "in Hushfeed settings > App.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        installAppLanguageSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAppLanguage()V",
        )
    }
}

/**
 * The patch's hook, without its settings row, so a test can put it on a build that carries no
 * extension. The switch gets the reset's own context, since the settings context and
 * ActivityThread's application don't exist yet when it runs. With the switch off the reset runs
 * as TikTok wrote it.
 */
context(patchContext: BytecodePatchContext)
internal fun installAppLanguageSwitch() {
    val reset = AppLanguageResetFingerprint.method
    // A second method of this shape would mean it no longer picks out the reset, and skipping
    // the wrong one is a failure rather than a coin toss.
    val shaped = patchContext.classDefBy(I18N_MANAGER_SERVICE).methods.filter { it.isAppLanguageReset() }
    if (shaped.size != 1 || shaped.single().name != reset.name) {
        throw PatchException(
            "$PATCH: expected one language reset in $I18N_MANAGER_SERVICE, found ${shaped.map { it.name }}",
        )
    }
    reset.guardAtEntry(PATCH, "invoke-static/range { p1 .. p1 }, $APP_LANGUAGE_SWITCH", "return-void")
}
