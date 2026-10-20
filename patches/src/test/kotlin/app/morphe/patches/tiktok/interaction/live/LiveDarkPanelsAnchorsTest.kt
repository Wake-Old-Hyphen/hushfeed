package app.morphe.patches.tiktok.interaction.live

import app.morphe.BridgeTypeFlow
import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.takes
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
 * theme is read from its own dark mode code. The profile card's bio link is drawn in the bio
 * view's own text color, so the link color its call resolves goes to the extension with the view,
 * which has to be a TextView. The card's labels come with the server's light card colors, so the
 * label list and the strip drawing it go to the extension right before the card walks them, and
 * the label model keeps the fields the extension sets by name. The ranking dialogs that take TikTok's light overlay with force
 * instead of the dark one keep their names, and their pages and lists stay fragments: plain ones
 * take the room's inflater, so androidx's fragment inflater takes a hook of its own, and the
 * extension finds them by the public getParentFragment and getContext it calls by name. The
 * ranking's tab strip reads its colors through its page's getContext(), the room, so both tab
 * states hand that context and the page to the extension first, and the team list's bottom bar,
 * the page's reach_top20 chevron and every list's bar tag icon do the same with their own. A
 * list's bottom row tints its info icon through the context the list hands it, so that context
 * and the row's text go to the extension before the row runs. LIVE's list rows skip the list's
 * inflater, so the row binders' base asks the extension for each row before TikTok's code runs.
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
    fun `each declared build keeps the profile card's bio link color the bio hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val header = classes[LIVE_PROFILE_HEADER] ?: throw AssertionError("${apk.name}: $LIVE_PROFILE_HEADER is gone")
            val found = header.methods.filter { LiveProfileBioFingerprint.takes(it, header) }
            assertEquals("${apk.name}: the bio", 1, found.size)
            val link = bioLinkColor(found.single())!!
            if ("47.4.3" in apk.name) {
                // attr/f33, the LIVE link color, is what the call resolves (2026-10-18).
                val attr = found.single().implementation!!.instructions.toList()[link.call - 1]
                assertEquals("${apk.name}: the link's color", 0x7f061ec4, (attr as NarrowLiteralInstruction).narrowLiteral)
            }

            // The hook as the patch writes it: right after the color call's result, the extension
            // gets the link color and the bio view, before the color span takes the color.
            val hooked = MutableMethod(found.single())
            hookBioLink(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val color = (code[link.call] as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the color call stays", listOf("I", "Landroid/content/Context;"),
                color.parameterTypes.map(CharSequence::toString))
            assertEquals(Opcode.MOVE_RESULT, code[link.call + 1].opcode)
            assertEquals(link.color, (code[link.call + 1] as OneRegisterInstruction).registerA)
            val hook = code[link.call + 2]
            val call = (hook as ReferenceInstruction).reference as MethodReference
            assertEquals("${apk.name}: the hook's call", "bioLinkColor", call.name)
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            val args = hook as com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
            assertEquals(listOf(link.color, link.view), listOf(args.registerC, args.registerD))
            val span = (code[link.call + 3] as ReferenceInstruction).reference as? MethodReference
            assertEquals("${apk.name}: the color span takes the color next", "Landroid/text/style/ForegroundColorSpan;", span?.definingClass)
            assertEquals("<init>", span?.name)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The extension only colors a TextView, so the bio view has to be one.
            val suffix = code.drop(link.call).firstNotNullOf {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { ref -> ref.name == "setSuffixText" }
            }
            assertEquals("${apk.name}: the bio view", "Landroid/widget/TextView;", outermost(classes, suffix.definingClass))
        }
    }

    @Test
    fun `each declared build keeps the profile card's labels the labels hook lightens`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val header = classes[LIVE_PROFILE_HEADER] ?: throw AssertionError("${apk.name}: $LIVE_PROFILE_HEADER is gone")
            val found = header.methods.filter { LiveProfileLabelsFingerprint.takes(it, header) }
            assertEquals("${apk.name}: the labels", 1, found.size)
            val labels = cardLabels(found.single())!!
            val original = found.single().implementation!!.instructions.toList()
            val context = (labels.call - 1 downTo 0).first {
                ((original[it] as? ReferenceInstruction)?.reference as? MethodReference)?.name == "getContext"
            }
            assertEquals(labels.strip, (original[context] as FiveRegisterInstruction).registerC)
            if ("47.4.3" in apk.name) {
                // The strip is the badge strip id/mts finds, X/0wKt, which the card reads its label
                // manager off just before (2026-10-19).
                val read = (context - 1 downTo 0).first {
                    original[it].opcode == Opcode.IGET_OBJECT && (original[it] as TwoRegisterInstruction).registerB == labels.strip
                }
                assertEquals("${apk.name}: the strip", "LX/0wKt;", ((original[read] as ReferenceInstruction).reference as FieldReference).definingClass)
            }

            // The hook as the patch writes it: right before the card walks the labels, the
            // extension gets the label list and the strip.
            val hooked = MutableMethod(found.single())
            hookCardLabels(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code[labels.call]
            assertEquals(Opcode.INVOKE_STATIC, hook.opcode)
            assertEquals("${apk.name}: the hook's call", "cardLabels", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(labels.labels, labels.strip), (hook as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD) })
            val iterator = code[labels.call + 1]
            assertEquals("${apk.name}: the iterator() comes next", "iterator", ((iterator as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(labels.labels, (iterator as FiveRegisterInstruction).registerC)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // The extension sets the label's background color by name, and makes the background
            // itself with its no-argument constructor when the server sent none.
            val label = classes[PROFILE_CARD_LABEL] ?: throw AssertionError("${apk.name}: $PROFILE_CARD_LABEL is gone")
            val type = label.fields.single { it.name == "background" }.type
            val background = classes[type] ?: throw AssertionError("${apk.name}: $type is gone")
            assertEquals("${apk.name}: the color", "Ljava/lang/String;", background.fields.single { it.name == "colorCode" }.type)
            assertNotNull("${apk.name}: the image", background.fields.singleOrNull { it.name == "image" })
            assertTrue("${apk.name}: the constructor", background.methods.any { it.name == "<init>" && it.parameterTypes.isEmpty() })
        }
    }

    @Test
    fun `each declared build keeps the ranking dialogs the extension leaves stock`() {
        // LivePanelTheme.STOCK_PANELS names these: they paint fixed light backgrounds themselves,
        // so they take TikTok's light overlay with force instead of the dark one. A rename would
        // put them back under the dark one unnoticed.
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for (type in listOf(
                "Lcom/bytedance/android/live/rank/impl/list/RankListDialog;",
                "Lcom/bytedance/android/live/rank/impl/list/view/PopularityRankingEducationDialog;",
            )) {
                assertTrue("${apk.name}: $type is gone", type in classes)
                val chain = generateSequence(type) { classes[it]?.superclass }.toList()
                assertTrue("${apk.name}: $type isn't a themed LIVE dialog", LIVE_THEMED_DIALOG in chain)
            }
            // Its pages and their lists are found by their parent fragments; if they stopped being
            // fragments, neither inflater hook would see them and the white titles would be back.
            if ("47.4.3" in apk.name) {
                for (type in listOf(
                    "Lcom/bytedance/android/live/rank/impl/list/fragment/page/RankPageFragment;",
                    "Lcom/bytedance/android/live/rank/impl/list/fragment/list/GiftListFragment;",
                )) {
                    assertTrue("${apk.name}: $type is gone", type in classes)
                    val chain = generateSequence(type) { classes[it]?.superclass }.toList()
                    assertTrue("${apk.name}: $type isn't a fragment", ANDROIDX_FRAGMENT in chain)
                }
            }
        }
    }

    @Test
    fun `each declared build keeps the fragment inflater the page hook wraps`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val fragment = classes[ANDROIDX_FRAGMENT] ?: throw AssertionError("${apk.name}: $ANDROIDX_FRAGMENT is gone")
            val found = fragment.methods.filter { FragmentInflaterFingerprint.takes(it, fragment) }
            assertEquals("${apk.name}: the fragment inflater", 1, found.size)

            // The hook as the patch writes it: right after onGetLayoutInflater's answer, before
            // the fragment stores it and returns it.
            val hooked = MutableMethod(found.single())
            hookFragmentInflater(hooked)
            val code = hooked.implementation!!.instructions.toList()
            val hook = code.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "darkLivePage"
            }
            assertTrue("${apk.name}: no hook in the fragment inflater", hook > 1)
            assertEquals("onGetLayoutInflater", ((code[hook - 2] as ReferenceInstruction).reference as MethodReference).name)
            val answer = (code[hook - 1] as OneRegisterInstruction).registerA
            assertEquals(Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
            assertEquals(answer, (code[hook + 1] as OneRegisterInstruction).registerA)
            val stored = code.drop(hook + 2).first { it.opcode == Opcode.IPUT_OBJECT || it.opcode == Opcode.RETURN_OBJECT }
            assertEquals("${apk.name}: the fragment stores the hooked inflater", Opcode.IPUT_OBJECT, stored.opcode)
            assertEquals(answer, (stored as com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction).registerA)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))

            // LivePanelTheme walks up by these, and takes a ranking page's view, by name.
            for (name in listOf("getParentFragment", "getContext", "getView")) {
                val method = fragment.methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() }
                assertTrue("${apk.name}: the fragment has no public $name()",
                    method != null && AccessFlags.PUBLIC.isSet(method.accessFlags))
            }
        }
    }

    @Test
    fun `each declared build keeps the ranking tab strip's context the tab hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            for ((field, fingerprint) in listOf(
                "selectedTextColor" to RankTabPickedFingerprint,
                "defaultTextColor" to RankTabUnpickedFingerprint,
            )) {
                val found = classes.values.flatMap { owner -> owner.methods.filter { fingerprint.takes(it, owner) } }
                assertEquals("${apk.name}: the ranking tab strip's $field", 1, found.size)
                val tab = rankTabContext(found.single(), field)!!
                assertNotEquals("${apk.name}: the context moved over the page", tab.page, tab.context)
                val code = found.single().implementation!!.instructions.toList()
                // The page the extension walks up from has to be a fragment.
                val page = ((code.take(tab.call).last {
                    it.opcode == Opcode.IGET_OBJECT && (it as OneRegisterInstruction).registerA == tab.page
                } as ReferenceInstruction).reference as FieldReference).type
                assertTrue("${apk.name}: the tab strip's page isn't a fragment",
                    ANDROIDX_FRAGMENT in generateSequence(page) { classes[it]?.superclass }.toList())
                if ("47.4.3" in apk.name) {
                    assertEquals("${apk.name}: the tab strip's page",
                        "Lcom/bytedance/android/live/rank/impl/list/fragment/page/RankPageFragment;", page)
                }

                // The hook as the patch writes it: right after the context's move-result, the
                // extension's answer goes back into the same register before anything reads it.
                val hooked = MutableMethod(found.single())
                hookRankTab(hooked, field)
                val written = hooked.implementation!!.instructions.toList()
                assertEquals(Opcode.MOVE_RESULT_OBJECT, written[tab.call + 1].opcode)
                val hook = written[tab.call + 2]
                assertEquals("${apk.name}: the hook's call", Opcode.INVOKE_STATIC, hook.opcode)
                assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
                val args = hook as FiveRegisterInstruction
                assertEquals(listOf(tab.page, tab.context), listOf(args.registerC, args.registerD))
                assertEquals(Opcode.MOVE_RESULT_OBJECT, written[tab.call + 3].opcode)
                assertEquals(tab.context, (written[tab.call + 3] as OneRegisterInstruction).registerA)
                assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
            }
        }
    }

    @Test
    fun `each declared build keeps the team list's bottom bar contexts the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val team = classes[TEAM_RANK_LIST] ?: throw AssertionError("${apk.name}: $TEAM_RANK_LIST is gone")
            // The extension walks up from it by the fragment's getParentFragment.
            assertTrue("${apk.name}: the team list isn't a fragment",
                ANDROIDX_FRAGMENT in generateSequence(TEAM_RANK_LIST) { classes[it]?.superclass }.toList())
            val found = team.methods.filter { TeamRankListViewFingerprint.takes(it, team) }
            assertEquals("${apk.name}: the team list's onViewCreated", 1, found.size)
            assertPageHooked(apk.name, found.single(), if ("47.4.3" in apk.name) 3 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking lists' bottom bar tag icon the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val list = classes[RANK_LIST] ?: throw AssertionError("${apk.name}: $RANK_LIST is gone")
            // The team list builds on it, so the trending tab's bar is drawn through it.
            assertTrue("${apk.name}: the team list left the ranking lists' base",
                RANK_LIST in generateSequence(TEAM_RANK_LIST) { classes[it]?.superclass }.toList())
            val found = list.methods.filter { RankListBottomTagFingerprint.takes(it, list) }
            assertEquals("${apk.name}: the bottom bar's tag", 1, found.size)
            // The icon's drawable and its tint, each through its own getContext().
            assertPageHooked(apk.name, found.single(), if ("47.4.3" in apk.name) 2 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking page's reach_top20 chevron the page hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val page = classes[RANK_PAGE] ?: throw AssertionError("${apk.name}: $RANK_PAGE is gone")
            // The extension walks up from it by the fragment's getParentFragment.
            assertTrue("${apk.name}: the ranking page isn't a fragment",
                ANDROIDX_FRAGMENT in generateSequence(RANK_PAGE) { classes[it]?.superclass }.toList())
            val found = page.methods.filter { RankPageReachTopFingerprint.takes(it, page) }
            assertEquals("${apk.name}: the reach_top20 row", 1, found.size)
            // The chevron's drawable and its tint, asked of the copy of p0 in v7 since p0 is past v15.
            val known = "47.4.3" in apk.name
            assertPageHooked(apk.name, found.single(), if (known) 2 else null, if (known) 7 else null)
        }
    }

    @Test
    fun `each declared build keeps the ranking row's info icon the gap hook redirects`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner -> owner.methods.filter { RankGapInfoIconFingerprint.takes(it, owner) } }
            assertEquals("${apk.name}: the bottom row's info icon", 1, found.size)
            val method = found.single()
            val (context, text) = gapInfoIcon(method)!!
            if ("47.4.3" in apk.name) assertEquals("${apk.name}: p1 and p4", 14 to 17, context to text)
            // The extension finds the context only through a row text that's a View.
            assertTrue("${apk.name}: the row text isn't a TextView", "Landroid/widget/TextView;" in
                generateSequence(method.parameterTypes[3].toString()) { classes[it]?.superclass }.toList())
            assertGapHooked(apk.name, method, context, text)
        }
    }

    /**
     * [method] hooked as the patch writes it: before anything else, the row text is copied into v0
     * and handed to the extension with the context, whose answer goes back into the context's own
     * register.
     */
    private fun assertGapHooked(apk: String, method: Method, context: Int, text: Int) {
        val hooked = MutableMethod(method)
        hookGapInfoIcon(hooked)
        val written = hooked.implementation!!.instructions.toList()
        val copy = written[0] as TwoRegisterInstruction
        assertEquals("$apk: the text's copy", Opcode.MOVE_OBJECT_FROM16, copy.opcode)
        assertEquals(listOf(0, text), listOf(copy.registerA, copy.registerB))
        val hook = written[1] as FiveRegisterInstruction
        assertEquals("stockViewContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(0, context), listOf(hook.registerC, hook.registerD))
        assertEquals(Opcode.MOVE_RESULT_OBJECT, written[2].opcode)
        assertEquals(context, (written[2] as OneRegisterInstruction).registerA)
        assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
    }

    /**
     * A bottom row shaped like 47.4.3's in [registers] registers: it reads the gap's FAQ, asks a
     * copy of the row text (p4) what it shows and tints the icon through the context (p1) with
     * [lookup], with [after] past the tint, or runs [body] in place of all that.
     */
    private fun gapRow(
        registers: Int = 19,
        lookup: String = "const v0, 0x7f061f39\ninvoke-static { v0, p1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I",
        after: String = "",
        body: String = """
            move-object/from16 v11, p5
            iget-object v1, v11, $RANK_GAP->bottomBarFaq:Ljava/lang/Object;
            if-eqz v1, :done
            move-object/from16 v5, p4
            invoke-virtual { v5 }, Landroidx/appcompat/widget/AppCompatTextView;->getText()Ljava/lang/CharSequence;
            $lookup
            $after
        """,
    ) = MutableMethod(ImmutableMethod("Lapp/morphe/Gap;", "LJII",
        listOf("Landroid/content/Context;", "Lcom/bytedance/ies/sdk/datachannel/DataChannel;",
            "Lcom/bytedance/android/live/rank/impl/list/controller/RankListController;", "Landroidx/appcompat/widget/AppCompatTextView;",
            RANK_GAP).map { ImmutableMethodParameter(it, null, null) },
        "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(registers, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            $body
            :done
            return-void
        """)
    }

    @Test
    fun `the gap hook takes only a row text and context it can hand over safely`() {
        assertEquals(14 to 17, gapInfoIcon(gapRow()))
        assertGapHooked("a row", gapRow(), 14, 17)
        assertNotNull("Context methods take the wrapper", gapInfoIcon(gapRow(after =
            "invoke-virtual { p1 }, Landroid/content/Context;->getResources()Landroid/content/res/Resources;")))
        assertNull("no lookup", gapInfoIcon(gapRow(lookup = "")))
        assertNull("a call that takes the context alone", gapInfoIcon(gapRow(lookup =
            "invoke-static { p1 }, Lapp/morphe/Gap;->tint(Landroid/content/Context;)I")))

        // In 21 registers p1 is v16, which the hook's four-bit call can't take.
        val wide = "const v15, 0x7f061f39\ninvoke-static/range { v15 .. v16 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I"
        assertNull("a context past v15", gapInfoIcon(gapRow(registers = 21, lookup = wide)))
        // In 6 registers every one is a parameter: v0 is `this`, which the hook's copy would overwrite.
        val bare = """
            iget-object p3, p5, $RANK_GAP->bottomBarFaq:Ljava/lang/Object;
            if-eqz p3, :done
            invoke-virtual { p4 }, Landroidx/appcompat/widget/AppCompatTextView;->getText()Ljava/lang/CharSequence;
            const p2, 0x7f061f39
            invoke-static { p2, p1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
        """
        assertNull("no local to copy the text through", gapInfoIcon(gapRow(registers = 6, body = bare)))
        assertEquals("the same row with one local", 2 to 5, gapInfoIcon(gapRow(registers = 7, body = bare)))
        for (after in listOf(
            "check-cast p1, Landroid/app/Activity;",
            "move-object v4, p1",
            "invoke-static { p1 }, Lapp/morphe/Gap;->keep(Ljava/lang/Object;)V",
            "const/4 p1, 0x0",
            // The copy the text call is asked of holds something else too.
            "new-instance v5, Ljava/lang/Object;",
            "const/4 v1, 0x0\nmove-object/from16 p4, v1",
        )) {
            assertNull(after, gapInfoIcon(gapRow(after = after)))
        }
    }

    /**
     * [method]'s contexts as [pageContexts] finds them, [expected] of them when given, hooked as the
     * patch writes it: right after each move-result, the extension's answer goes back into the
     * same register, each earlier hook two instructions long, handed the register its getContext()
     * asked, p0 or a copy of it ([page] when given).
     */
    private fun assertPageHooked(apk: String, method: Method, expected: Int?, page: Int? = null) {
        val contexts = pageContexts(method)
        if (expected != null) assertEquals("$apk: ${method.name}'s contexts", expected, contexts.size)
        val code = method.implementation!!.instructions.toList()
        val self = method.implementation!!.registerCount - method.parameterTypes.size - 1
        val hooked = MutableMethod(method)
        hookPageContexts(hooked)
        val written = hooked.implementation!!.instructions.toList()
        contexts.forEachIndexed { done, (result, context) ->
            val asked = (code[result - 1] as FiveRegisterInstruction).registerC
            if (page != null) assertEquals("$apk: context $done's page", page, asked)
            assertTrue("$apk: context $done is asked of v$asked, not this", asked == self || code.any {
                it.opcode == Opcode.MOVE_OBJECT_FROM16 && (it as TwoRegisterInstruction).registerA == asked && it.registerB == self
            })
            val at = result + 2 * done
            assertEquals("$apk: context $done", Opcode.MOVE_RESULT_OBJECT, written[at].opcode)
            val hook = written[at + 1] as FiveRegisterInstruction
            assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(asked, context), listOf(hook.registerC, hook.registerD))
            assertEquals(Opcode.MOVE_RESULT_OBJECT, written[at + 2].opcode)
            assertEquals(context, (written[at + 2] as OneRegisterInstruction).registerA)
        }
        assertEquals(apk, emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
    }

    /** A page in 20 registers, p0 being v19, that copies p0 into v7 and asks v7 for its context. */
    private fun copiedPageMethod(after: String = "") = MutableMethod(ImmutableMethod("Lapp/morphe/Page;", "Nd",
        emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(20, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            move-object/from16 v7, p0
            invoke-virtual { v7 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object v1
            const v0, 0x7f061dd8
            invoke-static { v0, v1 }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
            $after
            return-void
        """)
    }

    @Test
    fun `the page hook hands a copy of this past v15 along with its context`() {
        val page = copiedPageMethod()
        assertEquals(listOf(2 to 1), pageContexts(page))
        hookPageContexts(page)
        val hook = page.implementation!!.instructions.toList()[3] as FiveRegisterInstruction
        assertEquals("stockContext", ((hook as ReferenceInstruction).reference as MethodReference).name)
        assertEquals(listOf(7, 1), listOf(hook.registerC, hook.registerD))
        assertEquals(emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(page))
    }

    @Test
    fun `a register that holds something else too isn't taken for this`() {
        // v7 also gets a plain object, so the context asked of it may not be the page's own.
        assertThrows(PatchException::class.java) {
            pageContexts(copiedPageMethod(after = "new-instance v7, Ljava/lang/Object;"))
        }
    }

    /** A page method asking its own context once and handing it to [lookup] with a resource id. */
    private fun pageMethod(lookup: String) = MutableMethod(ImmutableMethod("Lapp/morphe/Page;", "RX",
        emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(3, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            invoke-virtual { p0 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object v1
            const v0, 0x7f061f40
            invoke-static { v0, v1 }, $lookup
            return-void
        """)
    }

    @Test
    fun `the page hook takes a context handed to a color or drawable lookup`() {
        assertEquals("a color", listOf(1 to 1), pageContexts(pageMethod("LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I")))
        assertEquals("a drawable", listOf(1 to 1), pageContexts(pageMethod("LX/0Jvn;->LIZLLL(ILandroid/content/Context;)LX/0Jvv;")))
        for (lookup in listOf(
            "LX/0Jvn;->keep(ILandroid/content/Context;)V",
            "LX/0Jvn;->pick(ILandroid/content/Context;)Z",
            "LX/0Jvn;->tint(Landroid/content/Context;I)I",
        )) {
            assertThrows(lookup, PatchException::class.java) { pageContexts(pageMethod(lookup)) }
        }
    }

    @Test
    fun `each declared build keeps the LIVE row binders' base the row hook asks first`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classes(apk)
            val found = classes.values.flatMap { owner ->
                owner.methods.filter { LiveRowBinderFingerprint.takes(it, owner) }.map { owner to it }
            }
            assertEquals("${apk.name}: the row binders' base", 1, found.size)
            val (owner, make) = found.single()
            val binder = rowBinder(make, owner)!!
            if ("47.4.3" in apk.name) {
                assertEquals("${apk.name}: the row binders' base", "LX/136J;", owner.type)
                assertEquals(listOf("LIZIZ", "LIZJ", "LJIIIIZZ"),
                    listOf(binder.layout.name, binder.attach.name, binder.holder.name))
                // The trending tab's rows come from the ranking's row binder, which builds on it.
                assertTrue("${apk.name}: the ranking's row binder left the base",
                    owner.type in generateSequence("LX/1GYT;") { classes[it]?.superclass }.toList())
            }

            // The hook as the patch writes it: the extension asked first, its row handed to the
            // binder's own holder call and returned, and a null answer landing on TikTok's code.
            val hooked = MutableMethod(make)
            hookRowBinder(hooked, owner)
            val code = hooked.implementation!!.instructions.toList()
            val self = hooked.implementation!!.registerCount - 3
            assertEquals("${apk.name}: the hook",
                listOf(Opcode.IGET, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_EQZ,
                    Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT),
                code.take(8).map { it.opcode })
            assertEquals(binder.layout, (code[0] as ReferenceInstruction).reference)
            assertEquals(binder.attach, (code[1] as ReferenceInstruction).reference)
            val ask = code[2] as FiveRegisterInstruction
            assertEquals("stockRow", ((ask as ReferenceInstruction).reference as MethodReference).name)
            assertEquals(listOf(self + 2, 0, 1), listOf(ask.registerC, ask.registerD, ask.registerE))
            val holder = code[5] as FiveRegisterInstruction
            assertEquals(binder.holder, (holder as ReferenceInstruction).reference)
            assertEquals(listOf(self, 0, self + 2), listOf(holder.registerC, holder.registerD, holder.registerE))
            assertEquals(8, (code[4] as BuilderOffsetInstruction).target.location.index)
            assertEquals(make.implementation!!.instructions.first().opcode, code[8].opcode)
            assertEquals("${apk.name}", emptyList<BridgeTypeFlow.Problem>(), BridgeTypeFlow.check(hooked))
        }
    }

    /** A tab strip shaped like 47.4.3's, its page's context in [context], with [after] past the color call. */
    private fun tabStrip(context: String = "v2", after: String = "") = MutableMethod(ImmutableMethod("Lapp/morphe/Tab;", "LIZIZ",
        listOf(ImmutableMethodParameter(SUB_RANK_TAB, null, null)), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        null, null, ImmutableMethodImplementation(6, emptyList(), null, null))).apply {
        addInstructionsWithLabels(0, """
            iget-object v1, p1, $SUB_RANK_TAB->selectedTextColor:Ljava/lang/String;
            iget-object v0, p0, Lapp/morphe/Tab;->page:$ANDROIDX_FRAGMENT
            invoke-virtual { v0 }, $ANDROIDX_FRAGMENT->getContext()Landroid/content/Context;
            move-result-object $context
            if-nez $context, :tinted
            return-void
            :tinted
            const/4 v3, 0x1
            invoke-static { v3, $context }, LX/0Jvn;->LIZIZ(ILandroid/content/Context;)I
            $after
            return-void
        """)
    }

    @Test
    fun `the tab hook takes only a context it can swap for the extension's`() {
        val tab = rankTabContext(tabStrip(), "selectedTextColor")!!
        assertEquals(listOf(2, 0, 2), listOf(tab.call, tab.page, tab.context))
        assertNotNull("Context methods take the wrapper", rankTabContext(tabStrip(after =
            "invoke-virtual { v2 }, Landroid/content/Context;->getResources()Landroid/content/res/Resources;"), "selectedTextColor"))
        assertNotNull("a Context after a long", rankTabContext(tabStrip(after =
            "invoke-static { v0, v1, v2 }, Lapp/morphe/Tab;->at(JLandroid/content/Context;)V"), "selectedTextColor"))
        assertNull("another field", rankTabContext(tabStrip(), "defaultTextColor"))

        assertNull("the context over the page", rankTabContext(tabStrip(context = "v0"), "selectedTextColor"))
        for (read in listOf(
            "check-cast v2, Landroid/app/Activity;",
            "invoke-static { v2 }, Lapp/morphe/Tab;->keep(Ljava/lang/Object;)V",
            "invoke-static/range { v2 .. v2 }, Lapp/morphe/Tab;->keep(Ljava/lang/Object;)V",
            // The long takes v0 and v1, so v2 is the Object.
            "invoke-static { v0, v1, v2 }, Lapp/morphe/Tab;->at(JLjava/lang/Object;)V",
            "iput-object v2, p0, Lapp/morphe/Tab;->kept:Landroid/content/Context;",
            "move-object v4, v2",
            // A write to v1 that reads v2 as its third register.
            "aget-object v1, v1, v2",
            "filled-new-array { v2 }, [Landroid/content/Context;",
        )) {
            assertNull(read, rankTabContext(tabStrip(after = read), "selectedTextColor"))
        }
    }

    /** The first type above [type] that isn't in the APK: the framework class it builds on. */
    private fun outermost(classes: Map<String, ClassDef>, type: String) =
        generateSequence(type) { classes[it]?.superclass }.first { it !in classes }

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
