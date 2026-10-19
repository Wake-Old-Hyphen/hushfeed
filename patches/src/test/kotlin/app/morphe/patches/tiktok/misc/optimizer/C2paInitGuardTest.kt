/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #211, held to every declared build: the content credential service's init, which the
 * asset strip's guard returns out of right after it stores the wrapper, and the service's
 * reads and sign, which have to go on answering an un-started SDK themselves.
 */
class C2paInitGuardTest {
    @Test
    fun `the content credential service's init is one method, storing its wrapper before any SDK start`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk)
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            val taken = mutableListOf<Pair<ClassDef, Method>>()
            var service: ClassDef? = null
            for (classDef in classes) {
                if (classDef.type == C2PA_SERVICE_CLASS_DESCRIPTOR) service = classDef
                for (method in classDef.methods) {
                    if (C2paServiceInitFingerprint.takes(method, classDef)) taken += classDef to method
                }
            }
            assertEquals("${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            val method = taken.single().second
            val instructions = method.implementation!!.instructions.toList()

            // The guard keeps the store and returns from index 1, so the store has to be first
            // and every SDK start has to sit after it.
            assertEquals("the wrapper is stored first", Opcode.IPUT_OBJECT, instructions.first().opcode)
            val stored = (instructions.first() as ReferenceInstruction).reference as FieldReference
            assertEquals("stored on the service itself", C2PA_SERVICE_CLASS_DESCRIPTOR, stored.definingClass)
            val sdkStarts = instructions.indices.filter { index ->
                val reference = (instructions[index] as? ReferenceInstruction)?.reference
                reference is MethodReference && reference.definingClass == C2PA_SDK_CLASS_DESCRIPTOR
            }
            assertTrue("init no longer starts the SDK, so the guard guards nothing", sdkStarts.isNotEmpty())
            assertTrue("an SDK start before the stored wrapper: $sdkStarts", sdkStarts.all { it >= 1 })

            // What the guard leaves behind: each read and the sign answer an un-started SDK with
            // TikTok's own result instead of reaching the SDK.
            val serviceClass = checkNotNull(service) { "no content credential service" }
            val answering = listOf("readAndVerifyFromPath", "readIngredientsFromPath", "writeAndSignManifest")
            for (name in answering) {
                val call = serviceClass.methods.filter { it.name == name }
                assertEquals("$name on the service", 1, call.size)
                val answers = call.single().implementation!!.instructions.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference
                    reference is StringReference && reference.string == "Must call init before use"
                }
                assertTrue("$name no longer answers an un-started SDK itself", answers)
            }
        }
    }

    @Test
    fun `the asset strip brings the guard, and only the asset strip`() {
        assertTrue("the asset strip doesn't bring the guard", c2paInitGuardPatch in coreAssetDebloatPatch.dependencies)
        assertNull("the guard shows up as a patch of its own", c2paInitGuardPatch.name)
        for (patch in listOf(studioCreationDebloatPatch, liveStreamSuiteOptimizerPatch, p2pRelayBlockerPatch, languagePackPurgerPatch)) {
            assertTrue("${patch.name} brings the C2PA guard", c2paInitGuardPatch !in patch.dependencies)
        }
    }
}
