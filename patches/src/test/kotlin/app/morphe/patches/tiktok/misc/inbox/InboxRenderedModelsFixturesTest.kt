package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.*
import org.junit.Test

/** Original-DEX proof for the field-only list selection shared by seed and diagnostics. */
class InboxRenderedModelsFixturesTest {
    @Test fun `current adapter schema declares each exact typed field and loading state hierarchy`() {
        assertEquals(listOf("47.1.4"), Fixtures.declaredVersions())
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owners = mapOf(
                ADAPTER to "classes5.dex", DIFF to "classes5.dex", CONFIG to "classes5.dex",
                STUB to "classes4.dex", LOADING to "classes5.dex", MODEL to "classes5.dex",
                STATE to "classes5.dex", PHASE to "classes5.dex", REFRESH to "classes9.dex", EMPTY to "classes5.dex"
            ).mapValues { (type, entry) -> dex.getEntry(entry)!!.dexFile.classes.single { it.type == type } }
            val fields = listOf(
                Triple(ADAPTER, "LLLII", DIFF), Triple(DIFF, "LJIIJJI", "Ljava/util/List;"),
                Triple(ADAPTER, "LLLIIIIL", CONFIG), Triple(CONFIG, "LJ", "Z"),
                Triple(ADAPTER, "LLJJJJLIIL", STUB), Triple(ADAPTER, "LLJJL", "Ljava/util/List;"),
                Triple(STUB, "LLL", "Ljava/util/List;"), Triple(ADAPTER, "LLLIIL", "Ljava/lang/Class;"),
                Triple(ADAPTER, "LLJLLL", LOADING), Triple(LOADING, "LLJJIJIIJIL", MODEL),
                Triple(MODEL, "LIZIZ", STATE), Triple(STATE, "LIZIZ", PHASE)
            )
            fields.forEach { (owner, name, type) ->
                val field = owners.getValue(owner).fields.single { it.name == name }
                assertEquals(type, field.type)
                assertFalse(AccessFlags.STATIC.isSet(field.accessFlags))
            }
            assertEquals(STATE, owners.getValue(REFRESH).superclass)
            assertEquals(STATE, owners.getValue(EMPTY).superclass)
            assertEquals("Ljava/lang/Enum;", owners.getValue(PHASE).superclass)
            listOf("Refresh", "Next", "Prev").forEach { name ->
                val field = owners.getValue(PHASE).fields.single { it.name == name }
                assertEquals(PHASE, field.type)
                assertTrue(AccessFlags.STATIC.isSet(field.accessFlags))
            }
        }
    }

    @Test fun `native list mode joins primary then extra stubs and applies loading predicates to state`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val owner = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == ADAPTER }
            val count = owner.methods.single { it.name == "getItemCount" }
            assertEquals(listOf("LLLIIIIL", "LJ", "LLLII", "LJIIJJI"), count.fields().map { it.name })
            assertTrue(count.implementation!!.instructions.any { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == ADAPTER && method.name == "LLLLJ" } == true })
            val stubs = owner.methods.single { it.name == "LLLLLLLZIL" }
            assertTrue(stubs.implementation!!.instructions.any { it.getReference<MethodReference>()?.name == "LLLLLJIL" })
            assertEquals(listOf("LLJJL"), stubs.fields().map { it.name })
            assertTrue(stubs.implementation!!.instructions.any { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "LX/035o;" && method.name == "LJLJJI" } == true })
            assertEquals(listOf("LLJJJJLIIL"), owner.methods.single { it.name == "LLLLLJIL" }.fields().map { it.name })

            val aggregate = owner.methods.single { it.name == "LLLLJ" }.implementation!!.instructions.toList()
            assertEquals(listOf("LLL", "LLLIIL", "LLJLLL", "LLJJIJIIJIL", "LIZIZ", "LIZIZ", "Refresh", "LLJLLL"),
                aggregate.mapNotNull { it.getReference<FieldReference>()?.name })
            val stateRead = aggregate.single { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == MODEL && field.name == "LIZIZ" } == true } as TwoRegisterInstruction
            val phaseRead = aggregate.single { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == STATE && field.name == "LIZIZ" } == true } as TwoRegisterInstruction
            assertEquals(stateRead.registerA, phaseRead.registerB)
            val predicates = aggregate.filter { it.opcode == Opcode.INSTANCE_OF }
            assertEquals(listOf(REFRESH, EMPTY), predicates.map { it.getReference<TypeReference>()!!.type })
            predicates.forEach { assertEquals(stateRead.registerA, (it as TwoRegisterInstruction).registerB) }
            assertTrue(aggregate.any { it.opcode == Opcode.IF_NE })
            assertTrue(aggregate.any { it.opcode == Opcode.XOR_INT_LIT8 && (it as NarrowLiteralInstruction).narrowLiteral == 1 })
        }
    }

    private fun Method.fields(): List<FieldReference> = implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }

    private companion object {
        const val ADAPTER = "Lcom/bytedance/ies/powerlist/PowerAdapter;"
        const val DIFF = "LX/09Np;"
        const val CONFIG = "LX/09Nn;"
        const val STUB = "Lcom/bytedance/ies/powerlist/PowerStub;"
        const val LOADING = "LX/09NO;"
        const val MODEL = "LX/09NQ;"
        const val STATE = "LX/09NY;"
        const val PHASE = "LX/09Ne;"
        const val REFRESH = "LX/0H2C;"
        const val EMPTY = "LX/09Nd;"
    }
}
