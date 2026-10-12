/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.downloads.addShareActionToSnapshot
import app.morphe.patches.tiktok.interaction.downloads.snapshotActionFields
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstructions
import app.morphe.util.classesCalling
import app.morphe.util.cloneMutable
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val SHARE_BLOCK_AUTHOR = "Lapp/morphe/extension/tiktok/share/ShareBlockAuthor;"
private const val BLOCK = "Share sheet tools (block author)"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val BASE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
private const val CONTEXT = "Landroid/content/Context;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val IMAGE = "Landroid/widget/ImageView;"
private const val TEXT = "Landroid/widget/TextView;"
private val CLICK_PARAMETERS = listOf(CONTEXT, BASE_PACKAGE)

internal fun Method.isReportActionConstructor(): Boolean {
    val parameters = parameterTypes.map(CharSequence::toString)
    return name == "<init>" && returnType == "V" && parameters.size == 6 &&
        parameters[0] == AWEME && parameters[1].startsWith("L") &&
        parameters.drop(2) == listOf(STRING, STRING, ACTIVITY, "Landroidx/fragment/app/Fragment;")
}

private fun Method.constantString(value: String): Boolean {
    if (parameterTypes.isNotEmpty() || returnType != STRING || AccessFlags.STATIC.isSet(accessFlags)) return false
    val code = implementation?.instructions?.toList() ?: return false
    return code.size == 2 && code[0].opcode in setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO) &&
        code[0].getReference<StringReference>()?.string == value && code[1].opcode == Opcode.RETURN_OBJECT
}

internal fun ClassDef.reportKey(): Method = methods.single { it.name == "key" && it.constantString("report") }
internal fun ClassDef.reportLabelHelper(): MethodReference {
    val binder = methods.single { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(TEXT) }
    return binder.implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }.single {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString).let { parameters ->
            parameters.size == 2 && parameters[0] in interfaces && parameters[1] == TEXT
        }
    }
}

/** Follow the interface String result handed directly to TextView.setText, not every String read. */
internal fun ClassDef.reportLabel(helper: Method): Method {
    val code = helper.implementation!!.instructions.toList()
    val getter = code.indices.mapNotNull { index ->
        if (index < 2 || code[index].opcode != Opcode.INVOKE_VIRTUAL) return@mapNotNull null
        val setter = code[index].getReference<MethodReference>() ?: return@mapNotNull null
        val target = code[index] as? FiveRegisterInstruction ?: return@mapNotNull null
        if (setter.definingClass != TEXT || setter.name != "setText" || setter.returnType != "V" ||
            setter.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/CharSequence;")) return@mapNotNull null
        val result = code[index - 1] as? OneRegisterInstruction ?: return@mapNotNull null
        if (code[index - 1].opcode != Opcode.MOVE_RESULT_OBJECT || result.registerA != target.registerD ||
            code[index - 2].opcode != Opcode.INVOKE_INTERFACE) return@mapNotNull null
        code[index - 2].getReference<MethodReference>()?.takeIf {
            it.definingClass in interfaces && it.returnType == STRING && it.parameterTypes.isEmpty()
        }
    }.distinctBy { it.toString() }.single()
    return methods.single { it.name == getter.name && it.constantString("") }
}

/** The real Report click invokes its own report implementation; shared dispatch methods don't. */
internal fun ClassDef.reportClick(): Method = methods.single { method ->
    AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == CLICK_PARAMETERS &&
        method.implementation?.instructions?.any { instruction ->
            if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) false
            else instruction.getReference<MethodReference>()?.let { called ->
                called.definingClass == type && called.returnType == "V" &&
                    called.parameterTypes.map(CharSequence::toString) == CLICK_PARAMETERS &&
                    called.name != method.name
            } == true
        } == true
}

internal fun ClassDef.isNativeReportAction(): Boolean {
    if (!AccessFlags.PUBLIC.isSet(accessFlags) || AccessFlags.INTERFACE.isSet(accessFlags) ||
        AccessFlags.ABSTRACT.isSet(accessFlags)) return false
    if (methods.count { it.isReportActionConstructor() && AccessFlags.PUBLIC.isSet(it.accessFlags) } != 1 ||
        methods.none { it.name == "key" && it.constantString("report") } ||
        fields.none { it.type == AWEME && !AccessFlags.STATIC.isSet(it.accessFlags) }) return false
    return methods.any { it.constantString("") } && runCatching { reportLabelHelper(); reportClick() }.isSuccess
}

internal object NativeReportActionFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, classDef -> method.isReportActionConstructor() && classDef.isNativeReportAction() },
)

/** Preserve the original body for every native Report instance the extension doesn't own. */
internal fun MutableMethod.answerShareBlockObject(member: String, resultType: String) {
    guardAtEntry(
        BLOCK,
        "invoke-static/range { p0 .. p0 }, $SHARE_BLOCK_AUTHOR->owns($OBJECT)Z",
        """
            invoke-static/range { p0 .. p0 }, $SHARE_BLOCK_AUTHOR->$member($OBJECT)$resultType
            move-result-object v0
            return-object v0
        """,
    )
}

internal fun MutableMethod.takeShareBlockClick() {
    guardAtEntry(
        BLOCK,
        "invoke-static/range { p0 .. p2 }, $SHARE_BLOCK_AUTHOR->click($OBJECT$CONTEXT$OBJECT)Z",
        "return-void",
    )
}

internal data class ShareBlockIconBind(val index: Int, val actionRegister: Int, val imageRegister: Int)

/** The common icon store follows the same action's package callback, after all drawable branches. */
internal fun Method.shareBlockIconBind(action: ClassDef): ShareBlockIconBind? {
    if (name != "onBindViewHolder" || returnType != "V" ||
        parameterTypes.map(CharSequence::toString) != listOf("Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;", "I")) return null
    val code = implementation?.instructions?.toList() ?: return null
    val stores = code.indices.filter { index ->
        val called = code[index].getReference<MethodReference>()
        code[index].opcode == Opcode.INVOKE_VIRTUAL && called?.definingClass == IMAGE &&
            called.name == "setImageDrawable" && called.parameterTypes.map(CharSequence::toString) ==
            listOf("Landroid/graphics/drawable/Drawable;")
    }
    if (stores.size != 1) return null
    val index = stores.single()
    if (index < 2) return null
    val store = code[index] as? FiveRegisterInstruction ?: return null
    val image = code[index - 1] as? TwoRegisterInstruction ?: return null
    val field = code[index - 1].getReference<FieldReference>() ?: return null
    val callback = code[index - 2].getReference<MethodReference>() ?: return null
    val invocation = code[index - 2] as? FiveRegisterInstruction ?: return null
    if (code[index - 1].opcode != Opcode.IGET_OBJECT || field.type != IMAGE || image.registerA != store.registerC ||
        code[index - 2].opcode != Opcode.INVOKE_INTERFACE || callback.definingClass !in action.interfaces ||
        callback.returnType != "V" || callback.parameterTypes.map(CharSequence::toString) != listOf(BASE_PACKAGE) ||
        invocation.registerC == store.registerC) return null
    return ShareBlockIconBind(index, invocation.registerC, store.registerC)
}

internal fun MutableMethod.bindShareBlockIcon(bind: ShareBlockIconBind) {
    addInstructions(bind.index + 1,
        "invoke-static { v${bind.actionRegister}, v${bind.imageRegister} }, $SHARE_BLOCK_AUTHOR->bindIcon($OBJECT$IMAGE)V")
}

internal fun Method.withShareBlockBody(action: ClassDef): MutableMethod {
    val constructor = action.methods.single { it.isReportActionConstructor() }
    check(AccessFlags.STATIC.isSet(accessFlags) && returnType == OBJECT &&
        parameterTypes.map(CharSequence::toString) == listOf(ACTIVITY, OBJECT)) { "$BLOCK: invalid native factory" }
    return cloneMutable(additionalRegisters = 7).apply {
        addInstructions(
            0,
            """
                check-cast p1, $AWEME
                new-instance v0, ${action.type}
                move-object/from16 v1, p1
                const/4 v2, 0x0
                const-string v3, ""
                move-object v4, v3
                move-object/from16 v5, p0
                const/4 v6, 0x0
                invoke-direct/range { v0 .. v6 }, ${action.type}-><init>(${constructor.parameterTypes.joinToString("")})V
                return-object v0
            """,
        )
    }
}

internal fun BytecodePatchContext.hookShareBlockAuthor() {
    val action = NativeReportActionFingerprint.classDef
    val helperReference = action.reportLabelHelper()
    val helper = mutableClassDefBy(helperReference.definingClass).methods.single {
        it.name == helperReference.name && it.returnType == helperReference.returnType &&
            it.parameterTypes.map(CharSequence::toString) == helperReference.parameterTypes.map(CharSequence::toString)
    }
    val label = action.reportLabel(helper) as MutableMethod
    val callers = classesCalling(action.interfaces)
    val iconBinders = mutableListOf<Pair<Method, ShareBlockIconBind>>()
    classDefForEach { classDef ->
        if (classDef.type in callers) classDef.methods.forEach { method ->
            method.shareBlockIconBind(action)?.let { bind -> iconBinders += method to bind }
        }
    }
    val (iconBinder, iconBind) = iconBinders.singleOrNull()
        ?: throw PatchException("$BLOCK: found ${iconBinders.size} native action icon binders")
    (action.reportKey() as MutableMethod).answerShareBlockObject("key", STRING)
    label.answerShareBlockObject("label", STRING)
    (action.reportClick() as MutableMethod).takeShareBlockClick()
    mutableClassDefBy(iconBinder.definingClass).findMutableMethodOf(iconBinder).bindShareBlockIcon(iconBind)

    val bridges = mutableClassDefBy(SHARE_BLOCK_AUTHOR)
    val original = bridges.methods.singleOrNull { it.name == "newAction" && it.parameterTypes.size == 2 }
        ?: throw PatchException("$BLOCK: no single native action factory")
    val bridge = original.withShareBlockBody(action)
    bridges.methods.remove(original)
    bridges.methods.add(bridge)

    val snapshot = ShareSnapshotFingerprint.method
    snapshot.addShareActionToSnapshot(snapshot.snapshotActionFields(ShareSnapshotFingerprint.classDef), SHARE_BLOCK_AUTHOR)
}
