/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val MUSIC_DISC_SPIN_DESCRIPTOR = "Lapp/morphe/extension/tiktok/feed/MusicDiscSpin;"

/** 1 or 3 holds the disc still, 2 or 3 stops the track name scrolling. 47.1.4's default is 3. */
internal const val MUSIC_ANIMATION_CLOSE_KEY = "music_animation_close_exp"

/** Above 0 lets the disc turn whatever the close setting says, for that many seconds a turn. */
internal const val MUSIC_COVER_ROTATION_DURATION_KEY = "video_music_cover_visual_opt_rotation_duration"

/** The app AB class's int getter as its callers write it: flags, default, key, sticky. */
private val AB_INT_READ = listOf("I", "I", "Ljava/lang/String;", "Z")

/**
 * The lazy value behind `music_animation_close_exp`: a no-argument lambda body that reads the key
 * through the app AB int getter and boxes it. Its class is renamed on every build (0Af6 on
 * 47.1.4), so the key, a name TikTok wrote, is the anchor. The ABMock table that lists the key
 * with its default is a void method with no boxing, which the shape keeps out.
 *
 * <p>On 47.1.4 the disc's turn starts only from VideoMusicCoverAssem's play subscriber, which
 * returns first when this reads 1 or 3 and the rotation duration below is not above 0 (#68).
 */
internal object MusicAnimationCloseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(MUSIC_ANIMATION_CLOSE_KEY),
    custom = { method, _ -> isBoxedAbIntRead(method, MUSIC_ANIMATION_CLOSE_KEY) },
)

/** The same for `video_music_cover_visual_opt_rotation_duration` (0AnS on 47.1.4). */
internal object MusicCoverRotationDurationFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(MUSIC_COVER_ROTATION_DURATION_KEY),
    custom = { method, _ -> isBoxedAbIntRead(method, MUSIC_COVER_ROTATION_DURATION_KEY) },
)

/**
 * A no-argument body that loads exactly [key], reads an int through the app AB getter and boxes
 * one int. The fingerprint's string is matched inside longer strings too, so the exact load is
 * what keeps a longer key of the same stem out.
 */
internal fun isBoxedAbIntRead(method: Method, key: String): Boolean {
    if (method.parameterTypes.isNotEmpty() || method.returnType != "Ljava/lang/Object;") return false
    val code = method.implementation?.instructions?.toList() ?: return false
    val loadsKey = code.any { it.getReference<StringReference>()?.string == key }
    val readsInt = code.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.returnType == "I" && it.parameterTypes.map(CharSequence::toString) == AB_INT_READ
        } == true
    }
    return loadsKey && readsInt && code.count { it.isIntBoxing() } == 1
}

private fun Instruction.isIntBoxing(): Boolean =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        getReference<MethodReference>()?.let {
            it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I")
        } == true

/**
 * Hands the int the lazy value is about to box to [extensionMethod], a static `(I)I` on
 * MusicDiscSpin, and boxes its answer instead. The call goes in front of the boxing, on the
 * register the boxing reads, so TikTok's read and its default stay as they were and the answer
 * is what the lazy value keeps for the launch.
 */
internal fun MutableMethod.answerMusicDiscSetting(patch: String, extensionMethod: String) {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$patch: $definingClass->$name has no implementation.")
    val boxing = instructions.withIndex().filter { it.value.isIntBoxing() }
    if (boxing.size != 1) {
        throw PatchException("$patch: $definingClass->$name boxes ${boxing.size} ints, expected one.")
    }
    val (index, instruction) = boxing.single()
    val register = when (instruction) {
        is FiveRegisterInstruction -> instruction.registerC
        is RegisterRangeInstruction -> instruction.startRegister
        else -> throw PatchException("$patch: $definingClass->$name boxes from no register it names.")
    }
    val target = "$MUSIC_DISC_SPIN_DESCRIPTOR->$extensionMethod(I)I"
    val call = if (register <= 15) {
        "invoke-static {v$register}, $target"
    } else {
        "invoke-static/range {v$register .. v$register}, $target"
    }
    addInstructionsAtControlFlowLabel(index, "$call\nmove-result v$register")
}
