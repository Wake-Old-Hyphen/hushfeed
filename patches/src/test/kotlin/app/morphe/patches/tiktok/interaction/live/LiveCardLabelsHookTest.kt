package app.morphe.patches.tiktok.interaction.live

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.SwitchLabelElement
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderPackedSwitchPayload
import com.android.tools.smali.dexlib2.builder.instruction.BuilderSparseSwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The profile card labels' shape checks on hand-written methods (#159): the 47.4.3 shape is found
 * and hooked right before the labels' iterator(), and each way the strip could be wrong at the
 * hook, or the hook skipped (a write, a jump, switch case or handler landing after the strip's
 * getContext() up to the iterator(), a range call) is refused rather than written, as is an
 * iterator() the cast's loop doesn't walk. The real build is covered by LiveDarkPanelsAnchorsTest.
 */
class LiveCardLabelsHookTest {

    @Test
    fun `the labels' shape is found and hooked`() {
        val header = smali(labels())
        val found = cardLabels(header)!!
        assertEquals(listOf(7, 3, 2), listOf(found.call, found.labels, found.strip))

        hookCardLabels(header)
        val code = header.implementation!!.instructions.toList()
        val hook = code[7] as FiveRegisterInstruction
        assertEquals(Opcode.INVOKE_STATIC, code[7].opcode)
        assertEquals("cardLabels", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(2, 3, 2), listOf(hook.registerCount, hook.registerC, hook.registerD))
        assertEquals("iterator", ((code[8] as ReferenceInstruction).reference as MethodReference).name)
    }

    @Test
    fun `a strip register written between its getContext and the iterator is refused`() {
        assertNull(cardLabels(smali(labels(afterContext = "const/4 v2, 0x0"))))
        assertNull(cardLabels(smali(labels(beforeIterator = "move-object v2, v3"))))
        // A wide write into v1 takes v2 with it.
        assertNull(cardLabels(smali(labels(afterContext = "const-wide/16 v1, 0x0"))))
    }

    @Test
    fun `a branch landing between the getContext and the iterator is refused`() {
        assertNull(cardLabels(smali(labels(beforeContext = "if-eqz v3, :join", afterContext = ":join"))))
    }

    @Test
    fun `a branch landing on the iterator is refused`() {
        // The label stays on the iterator() when the hook goes in ahead of it, so the jump would skip the hook.
        assertNull(cardLabels(smali(labels(beforeContext = "if-eqz v3, :skip", beforeIterator = ":skip"))))
    }

    @Test
    fun `a branch landing on the getContext itself is taken`() {
        // Every way to the hook still passes the getContext, so the strip register holds the strip.
        val context = ":ask\ninvoke-virtual {v2}, Landroid/view/View;->getContext()Landroid/content/Context;"
        assertEquals(8, cardLabels(smali(labels(beforeContext = "if-eqz v3, :ask", context = context)))!!.call)
    }

    @Test
    fun `a switch case landing between the getContext and the iterator is refused`() {
        // The switch goes in after the first read, so its offset to each case isn't zero.
        for (sparse in listOf(false, true)) {
            assertNull(cardLabels(switchTo(smali(labels()), case = 4, sparse)))
            assertNull(cardLabels(switchTo(smali(labels()), case = 7, sparse)))
            assertEquals(8, cardLabels(switchTo(smali(labels()), case = 16, sparse))!!.call)
        }
    }

    @Test
    fun `a handler landing between the getContext and the iterator is refused`() {
        assertNull(cardLabels(catchAt(smali(labels()), handler = 4)))
        assertEquals(7, cardLabels(catchAt(smali(labels()), handler = 16))!!.call)
    }

    @Test
    fun `range calls are refused, not passed over for an earlier one`() {
        // An earlier getContext() and iterator() on p0, which nothing writes, sit at the top with
        // the iterator the loop walks, so a finder that skipped the range call would take them.
        val earlier = "invoke-virtual {v6}, Landroid/view/View;->getContext()Landroid/content/Context;\n" +
            "invoke-interface {v6}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;\n" +
            "move-result-object v5"
        val iterator = "invoke-interface/range {v3 .. v3}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;"
        val context = "invoke-virtual/range {v2 .. v2}, Landroid/view/View;->getContext()Landroid/content/Context;"
        assertNull(cardLabels(smali(labels(top = earlier, iterator = iterator))))
        assertNull(cardLabels(smali(labels(top = earlier, context = context))))
        assertEquals(10, cardLabels(smali(labels(top = earlier)))!!.call)
    }

    @Test
    fun `an iterator the cast's loop doesn't walk is refused`() {
        // Its result left untaken, or the loop's next() asked of some other iterator.
        assertNull(cardLabels(smali(labels(walk = ""))))
        assertNull(cardLabels(smali(labels(next = "invoke-interface {v1}, Ljava/util/Iterator;->next()Ljava/lang/Object;"))))
        val range = "invoke-interface/range {v5 .. v5}, Ljava/util/Iterator;->next()Ljava/lang/Object;"
        assertEquals(7, cardLabels(smali(labels(next = range)))!!.call)
    }

    @Test
    fun `a getContext that isn't View's is refused`() {
        val context = "invoke-virtual {v2}, Lcom/example/Strip;->getContext()Landroid/content/Context;"
        assertNull(cardLabels(smali(labels(context = context))))
    }

    @Test
    fun `a loop over something other than the card's labels is refused`() {
        assertNull(cardLabels(smali(labels(cast = "check-cast v4, Ljava/lang/String;"))))
    }

    @Test
    fun `a method without the labels' shape fails the hook`() {
        assertThrows(PatchException::class.java) { hookCardLabels(smali("const/4 v0, 0x0\nreturn v0")) }
    }

    /** The labels' 47.4.3 shape, with one piece swapped in or added at a time. */
    private fun labels(
        top: String = "",
        beforeContext: String = "",
        context: String = "invoke-virtual {v2}, Landroid/view/View;->getContext()Landroid/content/Context;",
        afterContext: String = "",
        beforeIterator: String = "",
        iterator: String = "invoke-interface {v3}, Ljava/lang/Iterable;->iterator()Ljava/util/Iterator;",
        walk: String = "move-result-object v5",
        next: String = "invoke-interface {v5}, Ljava/util/Iterator;->next()Ljava/lang/Object;",
        cast: String = "check-cast v4, $PROFILE_CARD_LABEL",
    ) = """
        $top
        iget-object v2, p0, Lcom/example/Header;->strip:Lcom/example/Strip;
        iget-object v3, p0, Lcom/example/Header;->labels:Ljava/util/List;
        $beforeContext
        $context
        move-result-object v1
        $afterContext
        invoke-interface {v3}, Ljava/util/List;->isEmpty()Z
        move-result v0
        if-nez v0, :done
        $beforeIterator
        $iterator
        $walk
        :loop
        invoke-interface {v5}, Ljava/util/Iterator;->hasNext()Z
        move-result v0
        if-eqz v0, :done
        $next
        move-result-object v4
        $cast
        goto :loop
        :done
        const/4 v0, 0x1
        return v0
    """.lines().filter { it.isNotBlank() }.joinToString("\n")

    private fun smali(body: String, registers: Int = 7) = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Header;", "labels", listOf(ImmutableMethodParameter("Lcom/example/Header;", null, null)), "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, body.trimIndent()) }

    /** Adds a switch after the first read whose one case is the instruction at [case] (counted before the switch). */
    private fun switchTo(header: MutableMethod, case: Int, sparse: Boolean) = header.apply {
        val code = implementation!!
        val payload = code.instructions.size
        val target = code.newLabelForIndex(case)
        code.addInstruction(
            if (sparse) BuilderSparseSwitchPayload(listOf(SwitchLabelElement(7, target)))
            else BuilderPackedSwitchPayload(0, listOf(target)),
        )
        // dexlib2 drops a payload no switch refers to the next time the code is read, so the
        // switch goes in before anything reads it.
        val opcode = if (sparse) Opcode.SPARSE_SWITCH else Opcode.PACKED_SWITCH
        code.addInstruction(1, BuilderInstruction31t(opcode, 0, code.newLabelForIndex(payload)))
    }

    /** Puts the read at index 1 in a catch-all whose handler is the instruction at [handler]. */
    private fun catchAt(header: MutableMethod, handler: Int) = header.apply {
        val code = implementation!!
        code.addCatch(code.newLabelForIndex(1), code.newLabelForIndex(2), code.newLabelForIndex(handler))
    }
}
