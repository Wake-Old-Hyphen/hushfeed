/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.videooverlays

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val COVER_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/music/VideoMusicCoverAssem;"

/** One of the two lazy reads the disc's turn hangs on, and the MusicDiscSpin method that answers it. */
private class DiscRead(val key: String, val fingerprint: Fingerprint, val answer: String)

private val DISC_READS = listOf(
    DiscRead(MUSIC_ANIMATION_CLOSE_KEY, MusicAnimationCloseFingerprint, "closeSetting"),
    DiscRead(MUSIC_COVER_ROTATION_DURATION_KEY, MusicCoverRotationDurationFingerprint, "rotationSeconds"),
)

/**
 * What the music disc switches hook (#68), held to each declared build: the two lazy values
 * VideoMusicCoverAssem's play subscriber asks before it lets the disc turn, each a no-argument
 * lambda body that reads its key through the app AB int getter and boxes the int. 47.1.4 ships
 * the close setting at 3 and the duration at 0, so the subscriber returns before it starts the
 * turn. The hook goes on the real bodies here, so the int MusicDiscSpin answers is the one TikTok
 * just read, and the boxing that follows keeps the answer for the launch.
 */
class MusicDiscSpinAnchorsTest {
    @Test
    fun `each declared build has one boxed read of each key and its fingerprint takes exactly it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byShape = DISC_READS.associateWith { mutableListOf<String>() }
            val byFingerprint = DISC_READS.associateWith { mutableListOf<String>() }
            walk(apk) { classDef, method ->
                for (read in DISC_READS) {
                    if (isBoxedAbIntRead(method, read.key)) byShape.getValue(read) += "${classDef.type}->${method.name}"
                    if (read.fingerprint.takes(method, classDef)) byFingerprint.getValue(read) += "${classDef.type}->${method.name}"
                }
            }
            for (read in DISC_READS) {
                assertEquals("$version: ${read.key} is read by ${byShape[read]}", 1, byShape.getValue(read).size)
                assertEquals("$version: ${read.key}'s fingerprint", byShape[read], byFingerprint[read])
            }
        }
    }

    @Test
    fun `both reads meet in one place and the duration also times the cover's turn`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = readsOf(apk)
            val closeRead = reads.getValue(MUSIC_ANIMATION_CLOSE_KEY).definingClass
            val durationRead = reads.getValue(MUSIC_COVER_ROTATION_DURATION_KEY).definingClass

            // Each lazy value is a static field of the class whose initializer builds its lambda.
            val holders = mutableMapOf<String, MutableSet<String>>()
            walk(apk) { classDef, method ->
                if (method.name != "<clinit>") return@walk
                method.implementation?.instructions?.forEach { instruction ->
                    if (instruction.opcode != Opcode.NEW_INSTANCE) return@forEach
                    val type = instruction.getReference<TypeReference>()?.type ?: return@forEach
                    if (type == closeRead || type == durationRead) holders.getOrPut(type) { mutableSetOf() } += classDef.type
                }
            }
            val closeHolder = holders[closeRead].orEmpty().singleOrNull()
            val durationHolder = holders[durationRead].orEmpty().singleOrNull()
            assertTrue("$version: the close value is held by ${holders[closeRead]}", closeHolder != null)
            assertTrue("$version: the duration is held by ${holders[durationRead]}", durationHolder != null)

            val meeting = mutableListOf<Pair<String, Boolean>>()
            val durationReaders = mutableListOf<ClassDef>()
            walk(apk) { classDef, method ->
                if (classDef.type == closeHolder || classDef.type == durationHolder) return@walk
                val code = method.implementation?.instructions ?: return@walk
                var asksClose = false
                var readsDuration = false
                var namesCover = false
                for (instruction in code) {
                    when (instruction.opcode) {
                        Opcode.INVOKE_STATIC -> instruction.getReference<MethodReference>()?.let {
                            if (it.definingClass == closeHolder && it.returnType == "I" && it.parameterTypes.isEmpty()) asksClose = true
                        }
                        Opcode.SGET_OBJECT -> instruction.getReference<FieldReference>()?.let {
                            if (it.definingClass == durationHolder) readsDuration = true
                        }
                        Opcode.CHECK_CAST -> if (instruction.getReference<TypeReference>()?.type == COVER_ASSEM) namesCover = true
                        else -> {}
                    }
                }
                if (asksClose && readsDuration) meeting += "${classDef.type}->${method.name}" to namesCover
                if (readsDuration) durationReaders += classDef
            }
            // The play subscriber: it returns before the turn starts unless the close value lets
            // the disc go or the duration is above 0, and it is handed the cover assem it starts.
            assertEquals("$version: both reads are asked by $meeting", 1, meeting.size)
            assertTrue("$version: ${meeting.single().first} is not handed the cover assem", meeting.single().second)
            // The disc's turn itself reads the duration for its length, and is built on the cover.
            assertTrue(
                "$version: no reader of the duration is built on the cover: ${durationReaders.map { it.type }}",
                durationReaders.any { reader ->
                    reader.methods.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(COVER_ASSEM) }
                },
            )
        }
    }

    @Test
    fun `the answer is asked for on the read's own register just before it is boxed`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val reads = readsOf(apk)
            for (read in DISC_READS) {
                val native = reads.getValue(read.key)
                val before = native.implementation!!.instructions.toList()
                val hooked = MutableMethod(native)
                hooked.answerMusicDiscSetting("Hide video overlays", read.answer)
                val after = hooked.implementation!!.instructions.toList()

                assertEquals("$version: ${read.key} grew by", before.size + 2, after.size)
                val boxing = after.indexOfFirst { it.isIntBoxing() }
                assertTrue("$version: ${read.key} boxes nothing after the hook", boxing >= 3)
                val register = after[boxing].firstRegister()
                // TikTok's read lands in the register, MusicDiscSpin answers into the same one,
                // and the boxing takes the answer.
                assertEquals("$version: ${read.key}", Opcode.MOVE_RESULT, after[boxing - 3].opcode)
                assertEquals("$version: ${read.key}", register, (after[boxing - 3] as OneRegisterInstruction).registerA)
                val call = after[boxing - 2].getReference<MethodReference>()!!
                assertEquals(MUSIC_DISC_SPIN_DESCRIPTOR, call.definingClass)
                assertEquals(read.answer, call.name)
                assertEquals(listOf("I"), call.parameterTypes.map(CharSequence::toString))
                assertEquals("I", call.returnType)
                assertEquals(register, after[boxing - 2].firstRegister())
                assertEquals(Opcode.MOVE_RESULT, after[boxing - 1].opcode)
                assertEquals(register, (after[boxing - 1] as OneRegisterInstruction).registerA)
                // Everything else is TikTok's, in its own order.
                assertEquals(
                    "$version: ${read.key}",
                    before.map { it.opcode },
                    (after.take(boxing - 2) + after.drop(boxing)).map { it.opcode },
                )
            }
        }
    }

    @Test
    fun `only a no-argument body that loads the key, reads an AB int and boxes one int is taken`() {
        val key = MUSIC_ANIMATION_CLOSE_KEY
        fun body(loads: String = key, getter: String = "(IILjava/lang/String;Z)I", boxings: Int = 1) = listOf(
            "invoke-static {}, Lfixture/Ab;->get()Lfixture/Ab;",
            "move-result-object v4",
            "const-string v3, \"$loads\"",
            "const/4 v2, 0x1",
            "const/16 v1, 0x7c00",
            "const/4 v0, 0x3",
            "invoke-virtual {v4, v1, v0, v3, v2}, Lfixture/Ab;->read$getter",
            "move-result v0",
        ) + List(boxings) {
            listOf("invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;", "move-result-object v1")
        }.flatten() + "return-object v1"

        assertTrue(isBoxedAbIntRead(lambda(body()), key))
        // A key of the same stem is found by the fingerprint's string, and the exact load keeps it out.
        assertFalse(isBoxedAbIntRead(lambda(body(loads = "${key}_v2")), key))
        assertFalse(isBoxedAbIntRead(lambda(body(loads = "video_music_cover_visual_opt_rotation_duration")), key))
        // The boolean getter is another setting's read.
        assertFalse(isBoxedAbIntRead(lambda(body(getter = "(ILjava/lang/String;ZZ)Z")), key))
        assertFalse(isBoxedAbIntRead(lambda(body(boxings = 0)), key))
        assertFalse(isBoxedAbIntRead(lambda(body(boxings = 2)), key))
        // The ABMock table that lists the key is void, and a read with an argument is not the lambda.
        assertFalse(isBoxedAbIntRead(lambda(body().dropLast(1) + "return-void", returns = "V"), key))
        assertFalse(isBoxedAbIntRead(lambda(body(), parameters = listOf("I")), key))
        // No code, nothing to read.
        assertFalse(isBoxedAbIntRead(
            ImmutableMethod("Lfixture/Read;", "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null),
            key,
        ))
    }

    @Test
    fun `a body that boxes two ints stops the patch and is left as it was`() {
        val twice = lambda(listOf(
            "const/4 v0, 0x3",
            "invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            "move-result-object v1",
            "invoke-static {v0}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
            "move-result-object v1",
            "return-object v1",
        ))
        val before = twice.implementation!!.instructions.toList()
        assertThrows(PatchException::class.java) { twice.answerMusicDiscSetting("Hide video overlays", "closeSetting") }
        assertEquals(before, twice.implementation!!.instructions.toList())
    }

    @Test
    fun `a boxing above v15 is answered through the range form`() {
        val high = lambda(
            listOf(
                "const/4 v0, 0x3",
                "move/from16 v17, v0",
                "invoke-static/range {v17 .. v17}, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;",
                "move-result-object v1",
                "return-object v1",
            ),
            registers = 20,
        )
        high.answerMusicDiscSetting("Hide video overlays", "rotationSeconds")
        val after = high.implementation!!.instructions.toList()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[2].opcode)
        assertEquals(17, (after[2] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (after[2] as RegisterRangeInstruction).registerCount)
        assertEquals("rotationSeconds", after[2].getReference<MethodReference>()!!.name)
        assertEquals(Opcode.MOVE_RESULT, after[3].opcode)
        assertEquals(17, (after[3] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.INVOKE_STATIC_RANGE, after[4].opcode)
        assertEquals("valueOf", after[4].getReference<MethodReference>()!!.name)
    }

    @Test
    fun `the patch hands both reads to the extension methods that answer them`() {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val patch = File(repo, "patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/videooverlays/HideVideoOverlaysPatch.kt").readText()
        assertTrue("the close read is not hooked", patch.contains("answerMusicDiscSetting(PATCH_NAME, \"closeSetting\")"))
        assertTrue("the duration read is not hooked", patch.contains("answerMusicDiscSetting(PATCH_NAME, \"rotationSeconds\")"))
        assertTrue("the rows are never shown", patch.contains("SettingsStatus;->enableMusicDiscSpin()V"))
        val extension = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/feed/MusicDiscSpin.java").readText()
        assertTrue("the extension takes no close value", extension.contains("public static int closeSetting(int value)"))
        assertTrue("the extension takes no duration", extension.contains("public static int rotationSeconds(int seconds)"))
        val status = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsStatus.java").readText()
        assertTrue("the status has no switch for the rows", status.contains("public static void enableMusicDiscSpin()"))
    }

    /** A no-argument lambda body answering Object, made of [lines]. */
    private fun lambda(
        lines: List<String>,
        returns: String = "Ljava/lang/Object;",
        parameters: List<String> = emptyList(),
        registers: Int = 6,
    ) = MutableMethod(
        ImmutableMethod(
            "Lfixture/Read;", "invoke", parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructions(0, lines.joinToString("\n")) }

    private fun Instruction.isIntBoxing() =
        (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
            getReference<MethodReference>()?.let { it.definingClass == "Ljava/lang/Integer;" && it.name == "valueOf" } == true

    /** The one register a one-argument static call is handed, in either form. */
    private fun Instruction.firstRegister(): Int = when (this) {
        is FiveRegisterInstruction -> registerC
        is RegisterRangeInstruction -> startRegister
        else -> throw AssertionError("$opcode hands no register")
    }

    /** Each key's read on one build, by its shape. */
    private fun readsOf(apk: File): Map<String, Method> {
        val found = mutableMapOf<String, Method>()
        walk(apk) { _, method ->
            for (read in DISC_READS) {
                if (isBoxedAbIntRead(method, read.key)) {
                    assertTrue("${read.key} is read twice", found.put(read.key, method) == null)
                }
            }
        }
        return found
    }

    /** Every method of one build, walked once and never held (the dex has millions). */
    private fun walk(apk: File, visit: (ClassDef, Method) -> Unit) {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                for (method in classDef.methods) visit(classDef, method)
            }
        }
    }
}
