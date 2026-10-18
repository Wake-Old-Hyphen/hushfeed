package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #159: the provider answering dark only reached the panels' navigation bar, because TikTok adds
 * the dark LIVE theme to the room's theme without force. The panels' inflaters now go through
 * the extension, which wraps them in the panel's own overlay with force. These hold the anchors
 * on every declared build: both themed bases keep their inflater and their getThemeOverlay
 * (which the extension calls by name), every return takes the four-bit hook, LIVE's sheet page
 * still extends the fragment base (the extension finds sheet pages by that name), and the older
 * quality list keeps its onViewCreated for the readable-rows hook. TikTok's generic sheets over a
 * room (Share, Contact) inflate through the room's own theme, so the room's setTheme takes a hook
 * after its last room theme (each one re-applies TikTok's light app theme), and TikTok's dark app
 * theme is read from its own dark mode code.
 */
class LiveDarkPanelsAnchorsTest {
    private fun classes(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .associateBy { it.type }
    }

    @Test
    fun `each declared build keeps the themed panel inflaters the hook wraps`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for ((type, fingerprint) in listOf(
                LIVE_THEMED_DIALOG to LiveThemedDialogInflaterFingerprint,
                LIVE_THEMED_FRAGMENT to LiveThemedFragmentInflaterFingerprint,
            )) {
                val base = classes[type] ?: throw AssertionError("${apk.name}: $type is gone")
                val found = base.methods.filter { fingerprint.takes(it, base) }
                assertEquals("${apk.name}: $type inflater", 1, found.size)
                val returns = inflaterReturns(found.single())
                assertTrue("${apk.name}: $type returns no inflater", returns.isNotEmpty())

                val overlay = base.methods.singleOrNull {
                    it.name == "getThemeOverlay" && it.returnType == "I" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;")
                }
                assertTrue("${apk.name}: $type has no public getThemeOverlay(Context)",
                    overlay != null && AccessFlags.PUBLIC.isSet(overlay.accessFlags))

                // The hook as the patch writes it: each return hands its inflater over first, and
                // the result keeps the verifier's typing.
                val hooked = MutableMethod(found.single())
                hookPanelInflater(hooked)
                val code = hooked.implementation!!.instructions.toList()
                val hookedReturns = code.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
                assertEquals("${apk.name}: $type returns", returns.size, hookedReturns.size)
                for ((index, instruction) in hookedReturns) {
                    val call = (code[index - 2] as? ReferenceInstruction)?.reference as? MethodReference
                    assertEquals("${apk.name}: $type return at $index", "darkLivePanel", call?.name)
                    assertEquals((instruction as OneRegisterInstruction).registerA, (code[index - 1] as OneRegisterInstruction).registerA)
                }
                assertEquals("${apk.name}: $type", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
            assertEquals("${apk.name}: LIVE's sheet page base", LIVE_THEMED_FRAGMENT, classes[LIVE_SHEET_FRAGMENT]?.superclass)
        }
    }

    @Test
    fun `each declared build keeps the room theme and TikTok's dark app theme the room hook uses`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val room = classes[LIVE_PLAY_ACTIVITY] ?: throw AssertionError("${apk.name}: $LIVE_PLAY_ACTIVITY is gone")
            val found = room.methods.filter { LivePlayActivityThemeFingerprint.takes(it, room) }
            assertEquals("${apk.name}: the room's setTheme", 1, found.size)

            // The hook as the patch writes it: right after the last room theme, in the range form,
            // handing over the room itself, on the join's label so a skipped cold start style
            // still reaches it, and before the room's color scheme styles.
            val hooked = MutableMethod(found.single())
            hookRoomTheme(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "darkLiveRoom"
            }
            assertTrue("${apk.name}: no hook in the room's setTheme", hook > 0)
            val call = code[hook]
            assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("${apk.name}: the hook hands over the room", found.single().implementation!!.registerCount - 2,
                (call as RegisterRangeInstruction).startRegister)
            val before = code[hook - 1]
            assertEquals("${apk.name}: the hook follows a room theme", "setTheme",
                ((before as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(Opcode.INVOKE_SUPER, before.opcode)
            assertTrue("${apk.name}: branches past the last room theme miss the hook", code[hook + 1].location.labels.isEmpty())
            if ("47.4.3" in apk.name) {
                // style/y2, the cold start style, is the last room theme in the code's flow; the
                // one placed at the method's end (aeq) jumps back above it.
                val y2 = (hook - 1 downTo maxOf(0, hook - 8)).any { (code[it] as? NarrowLiteralInstruction)?.narrowLiteral == 0x7f12036b }
                assertTrue("${apk.name}: the hook follows the cold start style", y2)
                assertTrue("${apk.name}: the cold start join", call.location.labels.isNotEmpty())
            }
            val accents = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "applyStyle"
            }
            assertTrue("${apk.name}: a color scheme style lands before the hook", accents < 0 || accents > hook)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // TikTok's dark mode for its screens, found once, with the style it sets.
            val dark = classes.values.flatMap { owner -> owner.methods.filter { AppDarkThemeFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: TikTok's dark mode", 1, dark.size)
            val theme = appDarkTheme(dark.single())!!
            // On 47.4.3 that's style/yb, which aapt2 shows X.060v.LIZ setting (2026-10-18).
            if ("47.4.3" in apk.name) assertEquals("${apk.name}: the dark app theme", 0x7f120375, theme)
        }
    }

    @Test
    fun `each declared build keeps the quality list's view setup`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val dialog = classes[LIVE_DEFINITION_DIALOG] ?: throw AssertionError("${apk.name}: $LIVE_DEFINITION_DIALOG is gone")
            val found = dialog.methods.filter { LiveDefinitionListFingerprint.takes(it, dialog) }
            assertEquals("${apk.name}: onViewCreated", 1, found.size)
            // The view sits above v15 on 47.1.4, so the hook takes the range form; the inline
            // compiler drops a four-bit call to a register that doesn't fit, silently.
            val hooked = MutableMethod(found.single())
            hookQualityList(hooked)
            val first = hooked.implementation!!.instructions.first()
            assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC_RANGE, first.opcode)
            val view = (first as com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction).startRegister
            assertEquals("${apk.name}: the hook reads the view", found.single().implementation!!.registerCount - 2, view)
            assertEquals("readableQualityList", ((first as ReferenceInstruction).reference as MethodReference).name)
        }
    }
}
