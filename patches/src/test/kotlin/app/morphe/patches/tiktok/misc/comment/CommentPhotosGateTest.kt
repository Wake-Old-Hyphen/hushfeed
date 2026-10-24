package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Allow photo comments (#156): PineDrama 47.4.3's DramaCommentAppDiffProtocol.LIZIZ(Z)Z answers
 * false whatever bit it is handed. The fixtures are TikTok builds, which don't carry the class,
 * so the gate is built here in its 47.4.3 shape: three registers, one local, p1 the bit.
 */
class CommentPhotosGateTest {
    private val protocol = "Lcom/ss/android/ugc/aweme/appdiff/DramaCommentAppDiffProtocol;"

    @Test
    fun `with the switch on the gate answers with the bit it was handed`() {
        val method = MutableMethod(gate(registers = 3))
        method.installCommentPhotoSwitch()

        val instructions = method.implementation!!.instructions.toList()
        val call = (instructions[0] as ReferenceInstruction).reference as MethodReference
        assertEquals("Lapp/morphe/extension/tiktok/comment/CommentPhotos;", call.definingClass)
        assertEquals("isOn", call.name)
        assertEquals(Opcode.MOVE_RESULT, instructions[1].opcode)
        assertEquals(0, (instructions[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, instructions[2].opcode)
        assertEquals(Opcode.RETURN, instructions[3].opcode)
        assertEquals("p1 is v2 in a three register frame", 2, (instructions[3] as OneRegisterInstruction).registerA)
        assertTrue(
            "PineDrama's own answer still follows",
            instructions.drop(4).any { it.opcode == Opcode.CONST_4 },
        )
    }

    @Test
    fun `a gate of another shape stops the patch`() {
        val static = MutableMethod(gate(registers = 3, flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value))
        assertThrows(PatchException::class.java) { static.installCommentPhotoSwitch() }
        val object_ = MutableMethod(gate(registers = 3, returns = "Ljava/lang/Object;"))
        assertThrows(PatchException::class.java) { object_.installCommentPhotoSwitch() }
        val noLocal = MutableMethod(gate(registers = 2))
        assertThrows(PatchException::class.java) { noLocal.installCommentPhotoSwitch() }
    }

    private fun gate(
        registers: Int,
        flags: Int = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        returns: String = "Z",
    ): Method = ImmutableMethod(
        protocol,
        "LIZIZ",
        listOf(ImmutableMethodParameter("Z", null, null)),
        returns,
        flags,
        null,
        null,
        ImmutableMethodImplementation(
            registers,
            listOf(
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            null,
            null,
        ),
    )
}
