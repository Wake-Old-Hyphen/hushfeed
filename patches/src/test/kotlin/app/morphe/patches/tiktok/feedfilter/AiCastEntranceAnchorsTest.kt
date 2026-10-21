package app.morphe.patches.tiktok.feedfilter

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
 * Hide the AI Cast button, held to every declared build: the camera's AI Theater gate is one
 * static method, its frame has the local the guard writes, and the recorder branches on its
 * answer, so a no leaves the AI Cast toolbar component unregistered rather than half built.
 */
class AiCastEntranceAnchorsTest {
    @Test
    fun `the camera's AI Cast gate is one static method whose answer the recorder branches on`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> AiTheaterRecordEntranceFingerprint.takes(method, classDef) }.toList()
            assertEquals("${apk.name}: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val gate = taken.single().second
            assertTrue("${apk.name}: the gate is not static", AccessFlags.STATIC.isSet(gate.accessFlags))
            val body = gate.implementation!!
            assertTrue("${apk.name}: the gate has no local for the guard", body.registerCount - 1 >= 1)

            val reference = "${gate.definingClass}->${gate.name}(${gate.parameterTypes.joinToString("")})${gate.returnType}"
            var branched = 0
            build.methods.forEach { (_, caller) ->
                val instructions = caller.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) return@forEachIndexed
                    if (((instruction as ReferenceInstruction).reference as? MethodReference)?.toString() != reference) return@forEachIndexed
                    val result = instructions.getOrNull(index + 1) ?: return@forEachIndexed
                    val test = instructions.getOrNull(index + 2) ?: return@forEachIndexed
                    if (result.opcode == Opcode.MOVE_RESULT &&
                        (test.opcode == Opcode.IF_EQZ || test.opcode == Opcode.IF_NEZ) &&
                        (test as OneRegisterInstruction).registerA == (result as OneRegisterInstruction).registerA
                    ) {
                        branched++
                    }
                }
            }
            assertTrue("${apk.name}: no caller branches on $reference", branched >= 1)
        }
    }

    @Test
    fun `Feed filter answers the gate through the AI Cast filter`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/feedfilter/FeedFilterPatch.kt").readText()
        assertTrue("the gate is not guarded", source.contains("AiTheaterRecordEntranceFingerprint.method"))
        assertTrue("the answer is not the AI Cast filter's", source.contains("shouldHideRecordEntrance()Z"))
        assertTrue("the row's flag is not set", source.contains("SettingsStatus;->enableAiCastEntrance()V"))
    }

    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }
    }
}
