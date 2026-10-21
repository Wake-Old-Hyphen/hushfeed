/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.Fixtures
import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SERVICE = "Lcom/ss/android/ugc/aweme/spi/IHomeSidebarService;"

/**
 * What the side menu swipe switch (#213) hooks, held to each declared build: exactly one swipe
 * check, a plain `()Z` that asks the home sidebar service, with the hook assembling in front of
 * it and TikTok's own code left behind it for a no.
 */
class SidebarSwipeAnchorsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `each declared build has one side menu swipe check`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val taken = mutableListOf<Method>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (SidebarSwipeFingerprint.takes(method, classDef)) taken += method
                    }
                }
            }
            assertEquals("$version: swipe checks ${taken.map { it.definingClass + "->" + it.name }}", 1, taken.size)
        }
    }

    @Test
    fun `the hook assembles on each declared build and answers false ahead of TikToks check`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var checked = false
            val probe = bytecodePatch(name = "side menu swipe probe") {
                execute {
                    val before = SidebarSwipeFingerprint.method.implementation!!.instructions
                        .filter { it.opcode != Opcode.NOP }.map { it.opcode }
                    SidebarSwipeFingerprint.clearMatch()
                    val hooked = hookSidebarSwipe(version)
                    assertTrue("$version: the hook went in", hooked)
                    SidebarSwipeFingerprint.clearMatch()
                    val after = SidebarSwipeFingerprint.method.implementation!!.instructions
                        .filter { it.opcode != Opcode.NOP }.toList()
                    val ask = after.first().getReference<MethodReference>()!!
                    assertEquals(
                        "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;->sidebarSwipeOff()Z",
                        "${ask.definingClass}->${ask.name}()${ask.returnType}",
                    )
                    assertEquals(
                        "$version: the guard, then TikTok's own code",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4, Opcode.RETURN) + before,
                        after.map { it.opcode },
                    )
                    checked = true
                }
            }
            SidebarSwipeFingerprint.clearMatch()
            Patcher(PatcherConfig(apk, temporary.newFolder())).use { patcher ->
                patcher += setOf(probe)
                runBlocking { patcher().collect { result -> result.exception?.let { throw it } } }
            }
            SidebarSwipeFingerprint.clearMatch()
            assertTrue("$version: the probe never ran", checked)
        }
    }

    @Test
    fun `only a plain check of the sidebar service beside a hot area test is taken`() {
        assertTrue(isSidebarSwipeQuestion(check(asks = SERVICE), pager()))
        assertFalse("another service", isSidebarSwipeQuestion(check(asks = "LX/0Other;"), pager()))
        assertFalse("returns a number", isSidebarSwipeQuestion(check(asks = SERVICE, returns = "I"), pager()))
        assertFalse("static", isSidebarSwipeQuestion(check(asks = SERVICE, static = true), pager()))
        assertFalse("no hot area test in its class", isSidebarSwipeQuestion(check(asks = SERVICE), pager(hotArea = false)))
    }

    /** The check cut down to its service call: `service.LIZJ()`, its answer returned. */
    private fun check(asks: String, returns: String = "Z", static: Boolean = false): Method =
        ImmutableMethod(
            "LX/0Pager;", "LIZJ", listOf(), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
            ImmutableMethodImplementation(
                2,
                listOf(
                    ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 1, 0, 0, 0, 0,
                        ImmutableMethodReference(asks, "LIZJ", listOf(), returns)),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                null,
                null,
            ),
        )

    /** The helper's class, with the `(FF)Z` hot area test beside the check or without it. */
    private fun pager(hotArea: Boolean = true): ClassDef {
        val methods = if (hotArea) {
            listOf(
                ImmutableMethod(
                    "LX/0Pager;", "LIZIZ", listOf("F", "F").map { ImmutableMethodParameter(it, null, null) }, "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null,
                ),
            )
        } else {
            listOf()
        }
        return ImmutableClassDef(
            "LX/0Pager;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )
    }
}
