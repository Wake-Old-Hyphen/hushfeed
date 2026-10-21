package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Open the comments at full height (#191), held to every declared build: the sheet's height
 * method is one static method with a free local for the guard and one full height sibling, and
 * the panel sizing reads the same sheet mode into a register it writes again before reading.
 */
class CommentSheetHeightAnchorsTest {
    @Test
    fun `the height method, its full height sibling and the panel's mode read are each one`() {
        Fixtures.forEachDeclared { apk ->
            val methods = methodsOf(apk).toList()

            val heights = methods.filter { (classDef, method) -> CommentSheetHeightFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: height took ${heights.map { "${it.first.type}->${it.second.name}" }}", 1, heights.size)
            val (heightClass, height) = heights.single()
            assertTrue("${apk.name}: ${height.name} is not static", AccessFlags.STATIC.isSet(height.accessFlags))
            val body = height.implementation!!
            assertTrue("${apk.name}: ${height.name} has no local for the guard", body.registerCount - 1 >= 1)

            val sibling = fullHeightSibling(heightClass, height)
            assertTrue("${apk.name}: the sibling is the height method", sibling.name != height.name)
            assertEquals("I", sibling.returnType)

            val mode = sheetModeCall(height)
            assertTrue("${apk.name}: the mode call takes arguments", mode.parameterTypes.isEmpty())

            val panels = methods.filter { (classDef, method) -> CommentPanelSizeFingerprint.takes(method, classDef) }
            assertEquals("${apk.name}: panel took ${panels.map { it.second.name }}", 1, panels.size)
            val instructions = panels.single().second.implementation!!.instructions.toList()
            val modeIndex = instructions.indexOfFirst { instruction ->
                instruction.opcode == Opcode.INVOKE_STATIC &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)?.let {
                        it.definingClass == mode.definingClass && it.name == mode.name &&
                            it.parameterTypes.isEmpty() && it.returnType == "I"
                    } == true
            }
            assertTrue("${apk.name}: the panel reads no sheet mode", modeIndex >= 0)
            val result = instructions[modeIndex + 1]
            assertEquals("${apk.name}: the mode's answer is not moved next", Opcode.MOVE_RESULT, result.opcode)
            assertEquals("${apk.name}: the mode is not tested next", Opcode.IF_LEZ, instructions[modeIndex + 2].opcode)
            val register = (result as OneRegisterInstruction).registerA
            assertEquals(
                "${apk.name}: the test reads another register",
                register,
                (instructions[modeIndex + 2] as OneRegisterInstruction).registerA,
            )

            // The panel asks the height method by name once the mode passes.
            assertTrue(
                "${apk.name}: the panel never asks the height method",
                instructions.any { instruction ->
                    instruction.opcode == Opcode.INVOKE_STATIC &&
                        ((instruction as ReferenceInstruction).reference as? MethodReference)?.let {
                            it.definingClass == height.definingClass && it.name == height.name
                        } == true
                },
            )
        }
    }

    @Test
    fun `the height is guarded, the mode is lifted and the row's flag is set`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/comment/CommentSheetHeightPatch.kt").readText()
        assertTrue("the height is not guarded", source.contains("height.guardAtEntry("))
        assertTrue("the answer is not the switch's", source.contains("openAtFullHeight()Z"))
        assertTrue("the mode is not lifted", source.contains("heightMode(I)I"))
        assertTrue("the row's flag is not set", source.contains("SettingsStatus;->enableCommentsFullHeight()V"))
    }

    private fun methodsOf(apk: File): Sequence<Pair<ClassDef, Method>> {
        val byType = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
        }
        return byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
