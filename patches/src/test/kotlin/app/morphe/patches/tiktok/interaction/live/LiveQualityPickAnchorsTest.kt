package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #57: LIVE controls keeps TikTok's last LIVE quality pick after TikTok closes. TikTok holds the
 * pick in one static string that only its picker's setter writes, and the patch saves it there.
 * Two starts read it, and the patch hands each the saved pick while TikTok's is still null. Each
 * declared build has exactly those three places, so no pick goes unsaved and no start misses
 * the saved one, and the player start still checks the stream has the pick before using it.
 */
class LiveQualityPickAnchorsTest {
    @Test
    fun `each declared build has one quality pick, one setter and the two starts the hook reads it in`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            fun taken(fingerprint: Fingerprint): Method {
                val found = classes.flatMap { cls -> cls.methods.filter { fingerprint.takes(it, cls) } }
                assertEquals("${apk.name}: ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
                return found.single()
            }
            val startQuality = taken(LiveStartQualityFingerprint)
            val playerStart = taken(LivePlayerStartFingerprint)
            assertEquals(PULL_STREAM_SERVICE, startQuality.definingClass)

            val service: ClassDef = classes.single { it.type == PULL_STREAM_SERVICE }
            val pick = liveQualityPick(service, startQuality)

            // Every read of the pick is one of the two hooked starts, once each, into a register
            // the hook's range call can name.
            val readers = mutableListOf<String>()
            val writers = mutableListOf<String>()
            for (cls in classes) for (method in cls.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                for (instruction in instructions) {
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if ("${field.definingClass}->${field.name}:${field.type}" != pick.field) continue
                    val at = "${cls.type}->${method.name}"
                    when (instruction.opcode) {
                        Opcode.SGET_OBJECT -> readers += at
                        Opcode.SPUT_OBJECT -> writers += at
                        else -> throw AssertionError("${apk.name}: ${instruction.opcode} of the pick in $at")
                    }
                }
            }
            assertEquals(
                "${apk.name}: readers of ${pick.field}",
                listOf(startQuality, playerStart).map { "${it.definingClass}->${it.name}" }.sorted(),
                readers.sorted(),
            )
            assertEquals("${apk.name}: writers of ${pick.field}", listOf("$PULL_STREAM_SERVICE->${pick.setter.name}"), writers)
            for (reader in listOf(startQuality, playerStart)) {
                val read = reader.readsOf(pick.field).single()
                val register = (reader.implementation!!.instructions.toList()[read] as OneRegisterInstruction).registerA
                assertTrue("${apk.name}: ${reader.name} reads into v$register", register < reader.implementation!!.registerCount)
            }

            // The player start keeps the stream's own default unless the stream lists the pick.
            assertTrue(
                "${apk.name}: ${playerStart.name} no longer checks the stream has the pick",
                playerStart.implementation!!.instructions.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.name == "contains" && call.definingClass.startsWith("Ljava/util/")
                },
            )
            assertTrue(
                "${apk.name}: ${playerStart.name} no longer asks needPersistAcrossRooms",
                playerStart.implementation!!.instructions.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == LIVE_RESOLUTION_SELECTION_SETTING && call.name == "needPersistAcrossRooms"
                },
            )
        }
    }
}
