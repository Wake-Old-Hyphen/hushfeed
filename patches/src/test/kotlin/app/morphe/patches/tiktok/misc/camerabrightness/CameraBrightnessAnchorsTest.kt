package app.morphe.patches.tiktok.misc.camerabrightness

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep the screen's brightness in the camera (#200), held to every declared build: each of
 * TikTok's two brightness boost starts is one static void method with a free local for the
 * guard, and the two are different methods.
 */
class CameraBrightnessAnchorsTest {
    @Test
    fun `both brightness boost starts are one static void method each with a local for the guard`() {
        Fixtures.forEachDeclared { apk ->
            val methods = methodsOf(apk).toList()
            val starts = listOf(BrightnessBoostStartFingerprint, OldBrightnessBoostStartFingerprint).map { fingerprint ->
                val taken = methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }
                assertEquals(
                    "${apk.name}: ${fingerprint.javaClass.simpleName} took ${taken.map { "${it.first.type}->${it.second.name}" }}",
                    1,
                    taken.size,
                )
                val start = taken.single().second
                assertTrue("${apk.name}: ${start.name} is not static", AccessFlags.STATIC.isSet(start.accessFlags))
                assertEquals("${apk.name}: ${start.name} returns a value", "V", start.returnType)
                val body = start.implementation!!
                val parameterRegisters = start.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
                assertTrue("${apk.name}: ${start.name} has no local for the guard", body.registerCount - parameterRegisters >= 1)
                "${start.definingClass}->${start.name}"
            }
            assertTrue("${apk.name}: both fingerprints took $starts", starts[0] != starts[1])
        }
    }

    @Test
    fun `both starts are guarded and the row's flag is set`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/camerabrightness/CameraBrightnessPatch.kt").readText()
        assertTrue("the new start is not guarded", source.contains("BrightnessBoostStartFingerprint.method.guardAtEntry("))
        assertTrue("the old start is not guarded", source.contains("OldBrightnessBoostStartFingerprint.method.guardAtEntry("))
        assertTrue("the answer is not the switch's", source.contains("keepScreenBrightness()Z"))
        assertTrue("the row's flag is not set", source.contains("SettingsStatus;->enableCameraBrightness()V"))
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
