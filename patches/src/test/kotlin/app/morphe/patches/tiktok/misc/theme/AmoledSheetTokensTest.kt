package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.ArrayPayload
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.value.IntEncodedValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where TikTok's two sheets take their dark fill, read off the declared build's fixture (first
 * traced on 47.0.3), so the name the AMOLED patch rewrites for them is held to that build.
 *
 * <p>The comment panel's shapes and the Tux sheet behind the share sheet fill with one theme
 * attribute: the comment page sets drawable af4 or af9 on its container, whose solid is
 * ?attr/a24, and the Tux sheet reads TuxSheet's tux_sheetBackgroundColor, which its style points
 * at ?attr/a24 as well. The dark themes send a24 to the palette token aia, a dark literal
 * (#1E1E1E on the S22, 2026-09-23), and the light ones to axi, white. The names are the build's
 * own: 46.x reached the same sheets through agk and c3, which 47.x keeps as unrelated tokens.
 * a24 is what TikTok's own token table calls UISheetFlat1, the fill of its sheets, panels, modals
 * and text cells as well, so all of those go black with it.
 */
class AmoledSheetTokensTest {
    @Test
    fun `each declared build fills both sheets through a24, which the dark themes send to aia`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)

            val sheetAttr = tuxSheetBackgroundAttr(apk)
            assertEquals("the Tux sheet background attribute", "b79", table.attrNames[sheetAttr])
            val sheetValues = table.styleValues("b79").toSet()
            assertTrue("b79 reaches a24 in a Tux sheet style: $sheetValues", "?attr/a24" in sheetValues)

            // The comment page's two container shapes were traced on 47.0.3 (af4 and af9).
            // Drawable names move with every build and nothing here reads which ones the
            // declared build's page sets.

            val a24 = table.styleValues("a24")
            assertEquals("what the themes point a24 at: $a24", setOf("?attr/aia", "?attr/axi"), a24.toSet())
            val dark = table.styleValues("aia")
            val light = table.styleValues("axi")
            assertTrue("aia is a dark opaque literal wherever it is set: $dark", dark.isNotEmpty() && dark.all(::isDarkOpaqueLiteral))
            assertTrue("axi is light wherever it is set: $light", light.isNotEmpty() && light.none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites aia", "aia" in SURFACE_STYLE_ITEMS)
        }
    }

    /**
     * The feed's long-press panel and Inbox > Activity stayed gray under AMOLED (Facebook report,
     * 2026-10-12; #1E1E1E and #121212 on the S22). LongPressAwemeFragment fills with
     * UISheetGrouped1 (a2_), which the dark themes send to aig and the light ones to axo, and its
     * cards are UISheetGrouped2 (a2b, aii), which keep their gray. The notification page's
     * container and its rows fill with BGPrimary (bx), a literal of its own in every theme.
     */
    @Test
    fun `the long-press panel and the Activity page fill with tokens the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val idOf = { name: String -> table.attrNames.entries.single { it.value == name }.key }

            assertTrue(
                "LongPressAwemeFragment fills with UISheetGrouped1",
                loadsConstant(apk, "Lcom/ss/android/ugc/profile/business/ur/longpress/LongPressAwemeFragment;", "onCreateView", idOf("a2_")),
            )
            assertEquals("what the themes point a2_ at", setOf("?attr/aig", "?attr/axo"), table.styleValues("a2_").toSet())
            val grouped = table.styleValues("aig")
            assertTrue("aig is a dark opaque literal wherever it is set: $grouped", grouped.isNotEmpty() && grouped.all(::isDarkOpaqueLiteral))
            assertTrue("axo stays light", table.styleValues("axo").none(::isDarkOpaqueLiteral))
            assertEquals("the panel's cards", setOf("?attr/aii", "?attr/axq"), table.styleValues("a2b").toSet())
            assertTrue("the cards keep their gray", "aii" !in SURFACE_STYLE_ITEMS)

            assertTrue(
                "the notification page's container fills with BGPrimary",
                loadsConstant(apk, "Lcom/by/andInflater/awemenotice_fragment_navigation_container;", "LIZ", idOf("bx")),
            )
            val primary = table.styleValues("bx")
            assertTrue("bx is a literal in every theme: $primary", primary.none { it.startsWith("?") })
            assertTrue("bx is dark in a dark theme: $primary", primary.any(::isDarkOpaqueLiteral))
            assertTrue("bx is light in a light theme: $primary", !primary.all(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites aig and bx", SURFACE_STYLE_ITEMS.containsAll(setOf("aig", "bx")))
        }
    }

    /**
     * TikTok's own settings pages stayed stock under AMOLED (#152): Settings and privacy is
     * UIPageGrouped1, already black in TikTok's dark mode, and its cards were #1E1E1E on the S22.
     * A TuxCell (the settings row) fills with UIPageGrouped2 (a0g) unless it sits on a sheet,
     * where it takes UISheetGrouped2 (a2b) like the long-press panel's cards. The dark themes send
     * a0g to agn and the light ones to avv.
     */
    @Test
    fun `settings rows off a sheet fill with UIPageGrouped2, which the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val table = FixtureResourceTable(apk)
            val idOf = { name: String -> table.attrNames.entries.single { it.value == name }.key }

            val cell = "Lcom/ss/android/ugc/aweme/cell/TuxCell;"
            assertTrue("a TuxCell fills with UIPageGrouped2", loadsConstant(apk, cell, "e6", idOf("a0g")))
            assertTrue("a TuxCell on a sheet fills with UISheetGrouped2", loadsConstant(apk, cell, "e6", idOf("a2b")))
            assertEquals("what the themes point a0g at", setOf("?attr/agn", "?attr/avv"), table.styleValues("a0g").toSet())
            val dark = table.styleValues("agn")
            assertTrue("agn is a dark opaque literal wherever it is set: $dark", dark.isNotEmpty() && dark.all(::isDarkOpaqueLiteral))
            assertTrue("avv stays light", table.styleValues("avv").none(::isDarkOpaqueLiteral))
            assertTrue("the patch rewrites agn", "agn" in SURFACE_STYLE_ITEMS)
            assertTrue("a sheet's cards keep their gray", "aii" !in SURFACE_STYLE_ITEMS)
        }
    }

    private fun loadsConstant(apk: File, type: String, method: String, value: Int): Boolean {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classDef = container.dexEntryNames.asSequence().mapNotNull { entry ->
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == type }
        }.first()
        return classDef.methods.filter { it.name == method }.any { candidate ->
            candidate.implementation?.instructions?.any { (it as? NarrowLiteralInstruction)?.narrowLiteral == value } == true
        }
    }

    /** R$styleable.TuxSheet[TuxSheet__tux_sheetBackgroundColor]: the int field and the array <clinit> fills. */
    private fun tuxSheetBackgroundAttr(apk: File): Int {
        val styleable = "Lcom/ss/android/ugc/aweme/app/R\$styleable;"
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classDef = container.dexEntryNames.asSequence().mapNotNull { entry ->
            container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == styleable }
        }.first()
        val index = (classDef.staticFields.single { it.name == "TuxSheet__tux_sheetBackgroundColor" }
            .initialValue as IntEncodedValue).value
        val instructions = classDef.methods.single { it.name == "<clinit>" }.implementation!!.instructions.toList()
        val address = IntArray(instructions.size + 1)
        for (i in instructions.indices) address[i + 1] = address[i] + instructions[i].codeUnits
        val filled = HashMap<Int, List<Number>>()
        for (i in instructions.indices) {
            val instruction = instructions[i]
            if (instruction.opcode == Opcode.FILL_ARRAY_DATA) {
                val payload = instructions[address.indexOf(address[i] + (instruction as OffsetInstruction).codeOffset)] as ArrayPayload
                filled[(instruction as OneRegisterInstruction).registerA] = payload.arrayElements
            } else if (instruction.opcode == Opcode.SPUT_OBJECT) {
                val field = (instruction as ReferenceInstruction).reference as FieldReference
                if (field.definingClass == styleable && field.name == "TuxSheet") {
                    return filled.getValue((instruction as OneRegisterInstruction).registerA)[index].toInt()
                }
            }
        }
        error("no TuxSheet array in $styleable")
    }
}
