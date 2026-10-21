/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Accent color on Lynx text (#163), held to each declared build: Lynx's `color` setter keeps a
 * text color in one Integer field, it stores a number there for an int and for a long, the compact
 * prop buffer stores there too, and after the hook each of those numbers passes through the
 * extension right before it's boxed, with TikTok's own instructions in order.
 */
class LynxTextAccentTest {
    @Test
    fun `each declared build stores Lynx text colors where the hook runs them through the accent`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) classes += container.getEntry(entry)!!.dexFile.classes

            val node = classes.singleOrNull { it.type == LYNX_TEXT_SHADOW_NODE }
            assertNotNull("$version: no $LYNX_TEXT_SHADOW_NODE", node)
            val field = node!!.lynxTextColorField()
            assertNotNull("$version: the color setter stores no number into one Integer field", field)
            field!!
            assertEquals("Ljava/lang/Integer;", field.type)

            val setter = node.methods.single { it.name == "setColor" && it.parameterTypes.size == 1 }
            val setterStores = setter.implementation!!.instructions.toList().lynxColorStores(field)
            assertEquals("$version: one store for an int color and one for a long", 2, setterStores.size)

            val others = classes.flatMap { owner ->
                owner.methods.filter { method ->
                    method !== setter && method.implementation?.instructions?.toList()?.lynxColorStores(field)?.isNotEmpty() == true
                }
            }
            assertTrue(
                "$version: the compact prop buffer stores a text color too: ${others.map { "${it.definingClass}->${it.name}" }}",
                others.any { method -> method.parameterTypes.any { it.toString() == LYNX_COMPACT_BUFFER } },
            )

            for (method in listOf(setter) + others) {
                val before = method.implementation!!.instructions.toList()
                val stores = before.lynxColorStores(field)
                val hooked = MutableMethod(method)
                hooked.accentLynxColors(stores)
                val after = hooked.implementation!!.instructions.toList()
                val label = "$version ${method.definingClass}->${method.name}"
                assertEquals("$label: two instructions per store", before.size + 2 * stores.size, after.size)
                val added = mutableSetOf<Int>()
                val boxed = after.lynxColorStores(field)
                assertEquals("$label: every store is still there", stores.size, boxed.size)
                for (index in boxed) {
                    val register = after.boxedRegister(index)
                    val call = after[index - 2]
                    assertEquals("$label: the accent call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                    call as RegisterRangeInstruction
                    assertEquals(register, call.startRegister)
                    assertEquals(1, call.registerCount)
                    val target = call.getReference<MethodReference>()!!
                    assertEquals("$LYNX_ACCENT_EXTENSION->color(I)I", "${target.definingClass}->${target.name}(${target.parameterTypes.joinToString("")})${target.returnType}")
                    assertEquals(Opcode.MOVE_RESULT, after[index - 1].opcode)
                    assertEquals(register, (after[index - 1] as OneRegisterInstruction).registerA)
                    added += index - 2
                    added += index - 1
                }
                assertEquals(
                    "$label: TikTok's own instructions keep their order",
                    before.map { it.opcode },
                    after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
                )
            }
        }
    }

    @Test
    fun `each declared build's Lynx view background setter asks the extension before it reads the color`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val base = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
                .singleOrNull { it.type == LYNX_BASE_UI }
            assertNotNull("$version: no $LYNX_BASE_UI", base)
            val setter = base!!.lynxBackgroundSetter()
            assertNotNull("$version: no setBackgroundColor(I)V with code", setter)
            val before = setter!!.implementation!!.instructions.toList()
            val color = setter.implementation!!.registerCount - 1

            val hooked = MutableMethod(setter)
            hooked.accentLynxBackground()
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: two instructions in front", before.size + 2, after.size)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, after[0].opcode)
            val call = after[0] as RegisterRangeInstruction
            assertEquals("$version: the color parameter goes in", color, call.startRegister)
            assertEquals(1, call.registerCount)
            val target = call.getReference<MethodReference>()!!
            assertEquals("$LYNX_ACCENT_EXTENSION->color(I)I", "${target.definingClass}->${target.name}(${target.parameterTypes.joinToString("")})${target.returnType}")
            assertEquals(Opcode.MOVE_RESULT, after[1].opcode)
            assertEquals("$version: the answer replaces the parameter", color, (after[1] as OneRegisterInstruction).registerA)
            assertEquals("$version: TikTok's own instructions follow unchanged", before.map { it.opcode }, after.drop(2).map { it.opcode })
        }
    }

    @Test
    fun `the Lynx reds are the palette's text reds on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val reds = accentTokens(Fixtures.versionOf(apk)).flatMap { table.styleValues(it) }
                .filter { isRedLiteral(it) && isOpaqueLiteral(it) }
                .map { it.trim().takeLast(6).uppercase() }.toSet()
            for (red in LYNX_TEXT_REDS) assertTrue("${apk.name}: $red isn't one of the palette's reds $reds", red.removePrefix("#") in reds)
        }
    }

    @Test
    fun `a preset gives each Lynx red its shade, and no v31 lines`() {
        val (plain, system) = lynxColorEntries(false, "#2D7FF9")
        assertNull(system)
        val colors = colors(plain)
        assertEquals(listOf("hushfeed_lynx_e10543", "hushfeed_lynx_ff576f"), colors.keys.toList())
        assertEquals(accentShade("#FF576F", "#2D7FF9").lowercase(), colors.getValue("hushfeed_lynx_ff576f"))
        assertEquals(accentShade("#E10543", "#2D7FF9").lowercase(), colors.getValue("hushfeed_lynx_e10543"))
    }

    @Test
    fun `the wallpaper choice gives each Lynx red back below Android 12 and its system step on it`() {
        val (plain, system) = lynxColorEntries(true, TIKTOK_ACCENT)
        assertEquals(mapOf("hushfeed_lynx_e10543" to "#e10543", "hushfeed_lynx_ff576f" to "#ff576f"), colors(plain))
        assertEquals(
            mapOf(
                "hushfeed_lynx_e10543" to "@android:color/system_accent1_${wallpaperStep("#E10543")}",
                "hushfeed_lynx_ff576f" to "@android:color/system_accent1_${wallpaperStep("#FF576F")}",
            ),
            colors(system!!),
        )
        assertEquals("the same step the palette's own red text takes",
            wallpaperStep("#ffff576f"), wallpaperStep("#FF576F"))
    }

    @Test
    fun `existing Lynx names are refused before anything is written, and the entries join the APK's file`() {
        val res = File.createTempFile("res", "").apply { delete(); mkdirs() }
        try {
            val (plain, system) = wallpaperColorPaths(res, LYNX_COLOR_PREFIX)
            plain.parentFile.mkdirs()
            plain.writeText("<resources>\n    <color name=\"white\">#ffffffff</color>\n</resources>\n")
            appendColorEntries(plain, lynxColorEntries(false, "#2D7FF9").first)
            val joined = plain.readText()
            assertTrue(joined, joined.contains("<color name=\"white\">#ffffffff</color>\n    <color name=\"hushfeed_lynx_e10543\">"))
            assertEquals("one closing tag", 1, Regex("</resources>").findAll(joined).count())
            assertTrue("a preset writes nothing for v31", !system.exists())
            try {
                wallpaperColorPaths(res, LYNX_COLOR_PREFIX)
                fail("accepted a second set of Lynx names")
            } catch (refusal: app.morphe.patcher.patch.PatchException) {
                assertTrue(refusal.message, refusal.message!!.contains(LYNX_COLOR_PREFIX))
                assertTrue(refusal.message, refusal.message!!.endsWith("Nothing was changed."))
            }
            // The wallpaper table's own names are a different prefix, so the Lynx lines don't block them.
            wallpaperColorPaths(res)
        } finally {
            res.deleteRecursively()
        }
    }

    @Test
    fun `a color file with no closing tag is refused before anything is written`() {
        val res = File.createTempFile("res", "").apply { delete(); mkdirs() }
        try {
            val (_, system) = wallpaperColorPaths(res, LYNX_COLOR_PREFIX)
            system.parentFile.mkdirs()
            system.writeText("<resources>\n    <color name=\"white\">#ffffffff</color>\n")
            for (prefix in listOf(LYNX_COLOR_PREFIX, WALLPAPER_COLOR_PREFIX)) {
                try {
                    wallpaperColorPaths(res, prefix)
                    fail("$prefix: accepted values-v31 without a closing tag")
                } catch (refusal: app.morphe.patcher.patch.PatchException) {
                    assertTrue(refusal.message, refusal.message!!.contains("values-v31/colors.xml"))
                    assertTrue(refusal.message, refusal.message!!.endsWith("Nothing was changed."))
                }
            }
            assertEquals("<resources>\n    <color name=\"white\">#ffffffff</color>\n", system.readText())
        } finally {
            res.deleteRecursively()
        }
    }

    private fun colors(entries: String): Map<String, String> {
        val table = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream("<resources>\n$entries</resources>".toByteArray()))
        val colors = table.getElementsByTagName("color")
        return (0 until colors.length).associate {
            val color = colors.item(it) as Element
            color.getAttribute("name") to color.textContent
        }
    }
}
