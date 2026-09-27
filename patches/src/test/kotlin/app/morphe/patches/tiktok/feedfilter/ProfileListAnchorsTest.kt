package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Feed filter tells a profile's list by, held to each declared build (#35). TikTok parses
 * a profile's posts from /aweme/v1/aweme/post/ straight into a FeedItemList, the class whose
 * getItems the main feed filter hooks, and its profile model stamps the profile's uid on the list
 * in dataUserId. FeedItemsFilter reads that field directly, so a build that renamed it, retyped
 * it, made it private or stopped writing it would put every profile back under the feed's
 * preference filters.
 */
class ProfileListAnchorsTest {
    private val feedItemList = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"

    @Test
    fun `a FeedItemList carries the profile uid a profile model stamps on it`() {
        Fixtures.forEachDeclared { apk ->
            val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
            var field: Field? = null
            var stamps = 0
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    if (classDef.type == feedItemList && field == null) {
                        field = classDef.fields.firstOrNull { it.name == "dataUserId" }
                    }
                    for (method in classDef.methods) {
                        for (instruction in method.implementation?.instructions ?: continue) {
                            if (instruction.opcode != Opcode.IPUT_OBJECT) continue
                            val reference = (instruction as ReferenceInstruction).reference as FieldReference
                            if (reference.definingClass == feedItemList && reference.name == "dataUserId") stamps++
                        }
                    }
                }
            }
            val declared = field
            assertNotNull("FeedItemList declares no dataUserId", declared)
            assertEquals("dataUserId's type", "Ljava/lang/String;", declared!!.type)
            assertTrue("dataUserId is public, since the extension reads it directly",
                AccessFlags.PUBLIC.isSet(declared.accessFlags))
            assertFalse("dataUserId is an instance field", AccessFlags.STATIC.isSet(declared.accessFlags))
            assertTrue("no method stamps a FeedItemList's dataUserId", stamps > 0)
        }
    }
}
