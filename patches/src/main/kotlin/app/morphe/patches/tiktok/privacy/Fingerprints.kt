/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * TikTok's lookup of the Pitaya plugin, cached after the first call. Everything the plugin does
 * starts from what this returns: its start-up (the boot executor that loads the native Pitaya
 * libraries, and the real core), and the copy of every app log and CEP event TikTok hands it.
 * Each caller checks for null, which is what it gets when the plugin isn't installed.
 */
internal object PitayaPluginLookupFingerprint : Fingerprint(
    returnType = "Lcom/ss/android/ugc/aweme/pitaya/IPitayaBundle;",
    parameters = listOf(),
    strings = listOf("com.ss.android.ugc.aweme.pitaya.PitayaPluginImpl"),
)

/**
 * The one door Pitaya's real engine comes in by. The Pitaya plugin hands its core provider over
 * here; until it does, every core TikTok asks for is a stand-in that answers "host not ready".
 * PitayaCoreFactory's provider is fixed at class load, and nothing else attaches a real core.
 */
internal object PitayaRealProviderFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/pitaya/api/mutilinstance/DelegateCoreProvider;",
    name = "setRealProvider",
    returnType = "V",
    parameters = listOf("Lcom/bytedance/pitaya/api/CoreProvider;"),
)

/**
 * TikTok's start of Pitaya Lite, which hands PitayaLite its settings and the device id. Stopped
 * here rather than inside PitayaLite, whose class initializer loads libAndroidPitayaProxy.
 */
internal object PitayaLiteStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("pitaya_lite_settings"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass == PITAYA_LITE
        } == true
    },
)

internal const val PITAYA_LITE = "Lcom/bytedance/pitaya/api/PitayaLite;"

internal object WebViewTrackingFingerprint : Fingerprint(
    strings = listOf("addJavascriptInterface"),
    definingClass = "Lcom/bytedance/hybrid/",
)
