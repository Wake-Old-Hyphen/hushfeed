/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.branding.readXml
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import java.io.File
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.roundToInt
import org.w3c.dom.Document
import org.w3c.dom.Element

/** TikTok's own accent red, the UIShapePrimary of its standard palette. */
internal const val TIKTOK_ACCENT = "#FE2C55"

/**
 * The option's presets, in the order Manager lists them. Each is dark enough that the white label
 * TikTok puts on a primary button keeps at least 3:1 contrast, as its own red does (3.7:1).
 */
internal val ACCENT_PRESETS: Map<String, String> = linkedMapOf(
    "TikTok original" to TIKTOK_ACCENT,
    "Blue" to "#2D7FF9",
    "Green" to "#1DA153",
    "Purple" to "#8B5CF6",
    "Orange" to "#F26411",
    "Teal" to "#0F9E8E",
)

@Suppress("unused")
val accentColorPatch = resourcePatch(
    name = "Accent color",
    description = "Swaps the red of TikTok's main buttons and red text for blue, green, purple, " +
        "orange, teal or a color code you type in this patch's options, or for the color of the " +
        "phone's wallpaper theme on Android 12 and up. Warnings stay red, and so can icons with " +
        "the red drawn in. It's built in while patching, so only patching again without it " +
        "undoes it.",
    default = false,
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildDetailsPatch)
    val accent by colorOption(
        key = "accentColor",
        default = TIKTOK_ACCENT,
        title = "Accent color",
        description = "Pick a preset, or type a solid color code like #2D7FF9. TikTok original " +
            "leaves the red as it is. TikTok's lighter and darker reds follow the color you pick.",
        values = ACCENT_PRESETS,
    )
    val followWallpaper by booleanOption(
        key = "followWallpaper",
        default = false,
        title = "Follow the wallpaper",
        description = "On Android 12 and up, buttons and red text take the color of the phone's " +
            "wallpaper theme and change when the wallpaper does, and the color above is not used. " +
            "Android 11 and older keep TikTok's red. The follow plus under a creator's avatar " +
            "and the soft see-through reds stay as they are.",
        required = false,
    )
    execute {
        // Checked before anything is read or written, so a refused color changes nothing.
        val target = checkedAccent(accent)
        val wallpaper = followWallpaper == true
        val packageRoot = get("res").parentFile
        val decodedRoots = decodedPackageRoots(packageRoot)
        val collisions = renamedPathCollisions(
            entries = listApkEntries("res/"),
            decodedDirectories = decodedResourceDirectories(decodedRoots),
            aliasOf = { name -> get(name).relativeTo(packageRoot).invariantSeparatorsPath },
            isDecoded = { name -> decodedRoots.any { it.resolve(name).isFile } },
        )
        if (collisions.isNotEmpty()) throw PatchException(renamedPathsRefusal(collisions.size, "Accent color"))
        if (wallpaper || target != TIKTOK_ACCENT) {
            // This build's accent tokens, or a refusal: a name carried over from another build is a guess.
            val tokens = accentTokens(packageMetadata.versionName)
            val files = get("res").listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .map { it.resolve("styles.xml") }.filter { it.exists() }
            // A dry run on a throwaway copy of each file first: a refusal leaves every file as it was.
            val found = files.flatMapTo(mutableSetOf()) {
                recolorAccentItems(readXml(it), tokens, accentRecolor(wallpaper, target, mutableSetOf()))
            }
            if (found != tokens) throw PatchException(incompleteAccentRefusal(tokens - found))
            // Icons with the red drawn in, each recolored in memory first for the same reason. A picture
            // can't take a color the phone picks later, so the wallpaper choice leaves them as they are.
            val drawables = get("res").listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("drawable") }
            val icons = if (wallpaper) emptyList() else accentIcons(packageMetadata.versionName).flatMap { name ->
                val pictures = drawables.map { it.resolve("$name.png") }.filter { it.isFile }
                if (pictures.isEmpty()) throw PatchException(missingAccentIconRefusal(name))
                pictures.map { it to (recolorRedPalette(it.readBytes(), target) ?: throw PatchException(missingAccentIconRefusal(name))) }
            }
            val wallpaperLiterals = mutableSetOf<String>()
            val colorFiles = if (wallpaper) wallpaperColorPaths(get("res")) else null
            for (file in files) {
                document(file.relativeTo(get(".")).invariantSeparatorsPath).use {
                    recolorAccentItems(it, tokens, accentRecolor(wallpaper, target, wallpaperLiterals))
                }
            }
            for ((picture, bytes) in icons) picture.writeBytes(bytes)
            if (colorFiles != null) writeWallpaperColors(colorFiles, wallpaperLiterals)
        }
        BuildDetails.applied(get(BUILD_DETAILS_ASSET), "accentColor")
    }
}

/**
 * The accent tokens per build, by the name TikTok's TUX token table gives each (`X.0CKW.LIZ` maps
 * "UIShapePrimary" to attr a0w on 47.1.4): its dark palette literal, then its light one. The
 * semantic attrs point at `?attr/<dark>` in the dark themes and `?attr/<light>` in the light
 * ones, and a few themes send UIShapePrimary and UITextPrimaryDisplay to `?attr/e7`, a green of
 * their own that stays. UIShapeDanger (agr and avz) is red too and isn't listed: warnings keep it.
 * AccentColorPaletteTest holds each declared build's fixture to its entry here.
 */
internal val ACCENT_TOKENS: Map<String, Map<String, Pair<String, String>>> = linkedMapOf(
    "47.1.4" to linkedMapOf(
        "UIShapePrimary" to ("ah3" to "awa"),
        "UIShapePrimary2" to ("ah4" to "awb"),
        "UIShapePrimary3" to ("ah5" to "awc"),
        "UIShapePrimary4" to ("ah6" to "awd"),
        "UIShapePrimary5" to ("ah7" to "awe"),
        "UITextPrimary" to ("aiv" to "ay3"),
        "UITextPrimaryDisplay" to ("aiw" to "ay4"),
    ),
)

/** Every palette item name the patch recolors on this build, or a refusal. */
internal fun accentTokens(versionName: String?): Set<String> {
    val tokens = versionName?.let { ACCENT_TOKENS[it] } ?: throw PatchException(unreadAccentRefusal(versionName))
    return tokens.values.flatMapTo(linkedSetOf()) { listOf(it.first, it.second) }
}

internal fun unreadAccentRefusal(versionName: String?): String {
    val build = if (versionName.isNullOrBlank()) "this TikTok build" else "TikTok $versionName"
    val known = ACCENT_TOKENS.keys.toList()
    val builds = if (known.size == 1) known.single() else known.dropLast(1).joinToString(", ") + " and " + known.last()
    return "Accent color hasn't read the accent palette of $build, so nothing was changed. TikTok " +
        "renames its colors from one build to the next, and a name taken from another build can " +
        "recolor a background or a warning. The palette is known for TikTok $builds."
}

/**
 * Drawables with TikTok's red drawn into their pixels, per build, which the palette rewrite can't
 * reach. On 47.1.4 `bv8` is the follow plus under each feed avatar (FeedAvatarDefaultAssem sets it
 * as an icon res, or as a TuxIcon behind a flag) and on a DM's profile card (#84). Each is a
 * palette PNG of TikTok's red, white and the blends between them, so only its palette changes.
 * AccentColorPaletteTest holds each declared build's fixture to its entry here.
 */
internal val ACCENT_ICONS: Map<String, List<String>> = linkedMapOf(
    "47.1.4" to listOf("bv8"),
)

/** The drawables to recolor on this build, or a refusal. */
internal fun accentIcons(versionName: String?): List<String> =
    versionName?.let { ACCENT_ICONS[it] } ?: throw PatchException(unreadAccentRefusal(versionName))

internal fun missingAccentIconRefusal(name: String): String =
    "Accent color: this TikTok has no red follow plus picture where the patch looked ($name), so its " +
        "resources aren't the ones this patch read. Nothing was changed."

/**
 * [png] with every palette entry that is TikTok's red, white or a blend of the two moved the same
 * distance from white toward [target]; transparency stays in the PNG's tRNS chunk, so the edges
 * keep their smoothing. Null when [png] isn't a palette PNG or has no red in its palette. Only the
 * PLTE chunk and its checksum change, so no image decoding is needed (Morphe Manager patches on
 * the phone, where there's no image library).
 */
internal fun recolorRedPalette(png: ByteArray, target: String): ByteArray? {
    val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    if (png.size < 33 || !png.copyOfRange(0, 8).contentEquals(signature)) return null
    // IHDR comes first, and its color type 3 is a palette image.
    if (String(png, 12, 4, Charsets.US_ASCII) != "IHDR" || png[25].toInt() != 3) return null
    val to = target.removePrefix("#").takeLast(6).toInt(16)
    val out = png.copyOf()
    var at = 8
    while (at + 12 <= png.size) {
        val length = ((png[at].toInt() and 0xff) shl 24) or ((png[at + 1].toInt() and 0xff) shl 16) or
            ((png[at + 2].toInt() and 0xff) shl 8) or (png[at + 3].toInt() and 0xff)
        if (length < 0 || at + 12 + length > png.size) return null
        if (String(png, at + 4, 4, Charsets.US_ASCII) != "PLTE") {
            at += 12 + length
            continue
        }
        var reds = 0
        for (entry in at + 8 until at + 8 + length - 2 step 3) {
            val rgb = ((png[entry].toInt() and 0xff) shl 16) or ((png[entry + 1].toInt() and 0xff) shl 8) or
                (png[entry + 2].toInt() and 0xff)
            val blend = redBlend(rgb) ?: continue
            if (blend >= 0.5) reds++
            for ((channel, shift) in listOf(16, 8, 0).withIndex()) {
                val wanted = to shr shift and 0xff
                out[entry + channel] = (255 - blend * (255 - wanted)).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        if (reds == 0) return null
        val crc = CRC32().apply { update(out, at + 4, 4 + length) }.value
        for (i in 0 until 4) out[at + 8 + length + i] = (crc shr (24 - 8 * i)).toByte()
        return out
    }
    return null
}

/**
 * How far [rgb] is from white toward TikTok's red, 0 to 1, or null when it isn't white, that red or
 * a blend of the two. The red channel barely moves between them, so green and blue have to agree.
 */
internal fun redBlend(rgb: Int): Double? {
    val red = TIKTOK_ACCENT.substring(1).toInt(16)
    if ((rgb shr 16 and 0xff) < 240) return null
    val green = (255 - (rgb shr 8 and 0xff)).toDouble() / (255 - (red shr 8 and 0xff))
    val blue = (255 - (rgb and 0xff)).toDouble() / (255 - (red and 0xff))
    if (abs(green - blue) > 0.12) return null
    return ((green + blue) / 2).coerceIn(0.0, 1.0)
}

internal fun incompleteAccentRefusal(missing: Set<String>): String =
    "Accent color: this TikTok has no red to change for ${missing.sorted().joinToString(", ")}, so " +
        "its palette isn't the one this patch read. Nothing was changed."

/** The color as `#RRGGBB`, or a refusal that says what to type. */
internal fun checkedAccent(raw: String?): String = solidAccent(raw) ?: throw PatchException(
    "Accent color: '${raw.orEmpty()}' isn't a solid color code. Pick a preset or type six hex " +
        "digits like #2D7FF9. Nothing was changed.",
)

/** `#RRGGBB` or `#FFRRGGBB`, given back as uppercase `#RRGGBB`, or null for anything else. */
internal fun solidAccent(raw: String?): String? {
    val match = Regex("#(?:[fF]{2})?([0-9a-fA-F]{6})").matchEntire(raw?.trim() ?: return null) ?: return null
    return "#" + match.groupValues[1].uppercase(Locale.ROOT)
}

/**
 * Recolors every `<item name="...">` in [names] whose value is a red literal, and returns the
 * names it changed. White (a palette's UIShapePrimary5 in the high-contrast theme), references and
 * anything else that isn't red stay.
 */
internal fun recolorAccentItems(styles: Document, names: Set<String>, target: String): Set<String> =
    recolorAccentItems(styles, names) { literal -> accentShade(literal, target) }

/**
 * The same walk with the new value left to [recolor], which answers null for a literal that
 * stays as it is. Such an item still counts as found, because its red was there to change.
 */
internal fun recolorAccentItems(styles: Document, names: Set<String>, recolor: (String) -> String?): Set<String> {
    val changed = mutableSetOf<String>()
    val items = styles.getElementsByTagName("item")
    for (index in 0 until items.length) {
        val item = items.item(index) as Element
        val name = item.getAttribute("name")
        if (name !in names || !isRedLiteral(item.textContent)) continue
        recolor(item.textContent)?.let { item.textContent = it }
        changed.add(name)
    }
    return changed
}

/** What each accent item becomes: a shade of [target], or with [wallpaper] a reference collected into [literals]. */
internal fun accentRecolor(wallpaper: Boolean, target: String, literals: MutableSet<String>): (String) -> String? =
    if (wallpaper) { literal -> wallpaperReference(literal)?.also { literals.add(literal) } }
    else { literal -> accentShade(literal, target) }

/**
 * The file both wallpaper color tables go into, in `values` and in `values-v31`. The patcher takes
 * a values file's resource type from its name, so a file of our own name fails to encode: the
 * entries join the APK's `colors.xml`, or start one in `values-v31`, which the name makes a color file.
 */
internal const val WALLPAPER_COLORS_FILE = "colors.xml"

/** What every color the wallpaper choice adds is named from. */
internal const val WALLPAPER_COLOR_PREFIX = "hushfeed_accent_"

/**
 * Android's system accent ramp as (tone, step) pairs: `system_accent1_<step>` has a tone (the L*
 * of CIELAB lightness) of about [tone]. Android 12 and up fill the ramp from the wallpaper.
 */
internal val WALLPAPER_STEPS: List<Pair<Int, Int>> = listOf(
    99 to 10, 95 to 50, 90 to 100, 80 to 200, 70 to 300, 60 to 400, 50 to 500, 40 to 600, 30 to 700, 20 to 800, 10 to 900,
)

/**
 * The ramp step that stands in for an accent literal. TikTok's own red sits at tone 50 (step 500),
 * which keeps its white label above 4.5:1, and every other shade moves along the ramp by the
 * lightness it differs from the base red by: lighter text on dark, darker text on light.
 */
internal fun wallpaperStep(literal: String): Int {
    val base = hsl(TIKTOK_ACCENT.substring(1).toInt(16)).l
    val tone = 50 + (hsl(literalRgb(literal) ?: throw IllegalArgumentException("not a color literal: $literal")).l - base) * 100
    return WALLPAPER_STEPS.minByOrNull { abs(it.first - tone) }!!.second
}

/** Whether the literal has no transparency: six digits, or eight starting with ff. */
internal fun isOpaqueLiteral(literal: String): Boolean {
    val hex = literal.trim().removePrefix("#")
    return hex.length == 6 || (hex.length == 8 && hex.startsWith("ff", ignoreCase = true))
}

/** The color resource name for an opaque literal, one per red, however many items hold it. */
internal fun wallpaperColorName(literal: String): String =
    WALLPAPER_COLOR_PREFIX + String.format(Locale.ROOT, "%06x", literalRgb(literal) ?: throw IllegalArgumentException("not a color literal: $literal"))

/**
 * What an item holding [literal] becomes with the wallpaper choice: a reference to a color that
 * is the system accent on Android 12 and up and the literal itself below. Null for a see-through
 * red: a color resource can't take a system color at a fraction of its strength, so those keep
 * their red.
 */
internal fun wallpaperReference(literal: String): String? =
    if (isOpaqueLiteral(literal)) "@color/" + wallpaperColorName(literal) else null

/** The two color files the wallpaper choice adds to, or a refusal when either already holds one of its names. */
internal fun wallpaperColorPaths(res: File): Pair<File, File> {
    val files = res.resolve("values").resolve(WALLPAPER_COLORS_FILE) to res.resolve("values-v31").resolve(WALLPAPER_COLORS_FILE)
    if (listOf(files.first, files.second).any { it.isFile && it.readText(Charsets.UTF_8).contains("name=\"$WALLPAPER_COLOR_PREFIX") }) {
        throw PatchException(
            "Accent color: this TikTok already has colors named $WALLPAPER_COLOR_PREFIX* in its resources, so the " +
                "wallpaper choice would write over them. Nothing was changed.",
        )
    }
    return files
}

/**
 * The two color tables for [literals]: `values` gives each red back as it was, and `values-v31`
 * points the same names at the system accent step for that red's lightness, so Android 12 and
 * up pick them up and older versions never meet a resource they don't have.
 */
internal fun wallpaperColorTables(literals: Collection<String>): Pair<String, String> {
    val (plain, system) = wallpaperColorEntries(literals)
    return newColorsFile(plain) to newColorsFile(system)
}

private fun newColorsFile(entries: String) = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n$entries</resources>\n"

/** The `<color>` lines of each table, one per opaque red, in name order. */
internal fun wallpaperColorEntries(literals: Collection<String>): Pair<String, String> {
    val reds = literals.filter(::isOpaqueLiteral).associateBy(::wallpaperColorName).toSortedMap()
    fun lines(value: (String) -> String) = buildString {
        for ((name, literal) in reds) append("    <color name=\"$name\">${value(literal)}</color>\n")
    }
    return lines { it.trim().lowercase(Locale.ROOT) } to lines { "@android:color/system_accent1_${wallpaperStep(it)}" }
}

/** Adds each table's entries to the end of its file's `resources`, or writes a new file where there is none. */
internal fun writeWallpaperColors(files: Pair<File, File>, literals: Collection<String>) {
    val (plain, system) = wallpaperColorEntries(literals)
    for ((file, entries) in listOf(files.first to plain, files.second to system)) {
        file.parentFile.mkdirs()
        if (!file.isFile) {
            file.writeText(newColorsFile(entries), Charsets.UTF_8)
            continue
        }
        val text = file.readText(Charsets.UTF_8)
        val end = text.lastIndexOf("</resources>")
        if (end < 0) throw PatchException("Accent color: ${file.name} has no closing resources tag. Nothing was changed.")
        val lead = if (text.substring(0, end).endsWith("\n")) "" else "\n"
        file.writeText(text.substring(0, end) + lead + entries + text.substring(end), Charsets.UTF_8)
    }
}

/** A `#RRGGBB` or `#AARRGGBB` literal in the red family: a saturated hue within 40 degrees of red. */
internal fun isRedLiteral(value: String): Boolean {
    val rgb = literalRgb(value) ?: return false
    val color = hsl(rgb)
    return color.s >= 0.35 && color.l in 0.15..0.9 && (color.h >= 320.0 || color.h <= 20.0)
}

/**
 * [literal] moved from TikTok's red to [target], keeping its alpha. TikTok's palettes hold
 * lighter reds for text on dark and darker ones for text on light, so each shade keeps its
 * distance from the base red: the hue turns by the target's turn, saturation scales with the
 * target's, and lightness moves by the target's offset. TikTok's own red comes out as [target].
 */
internal fun accentShade(literal: String, target: String): String {
    val hex = literal.trim().removePrefix("#")
    val alpha = if (hex.length == 8) hex.substring(0, 2).lowercase(Locale.ROOT) else ""
    val from = hsl(literalRgb(literal) ?: throw IllegalArgumentException("not a color literal: $literal"))
    val base = hsl(TIKTOK_ACCENT.substring(1).toInt(16))
    val to = hsl(target.removePrefix("#").takeLast(6).toInt(16))
    val hue = ((to.h + from.h - base.h) % 360.0 + 360.0) % 360.0
    val saturation = if (base.s == 0.0) to.s else (from.s * to.s / base.s).coerceIn(0.0, 1.0)
    val lightness = (from.l + to.l - base.l).coerceIn(0.0, 1.0)
    return "#" + alpha + String.format(Locale.ROOT, "%06x", rgb(Hsl(hue, saturation, lightness)))
}

private fun literalRgb(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (!value.trim().startsWith("#") || (hex.length != 6 && hex.length != 8)) return null
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return hex.takeLast(6).toInt(16)
}

internal class Hsl(val h: Double, val s: Double, val l: Double)

internal fun hsl(rgb: Int): Hsl {
    val r = (rgb shr 16 and 0xff) / 255.0
    val g = (rgb shr 8 and 0xff) / 255.0
    val b = (rgb and 0xff) / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2
    val d = max - min
    if (d == 0.0) return Hsl(0.0, 0.0, l)
    val s = d / (1 - abs(2 * l - 1))
    val h = when (max) {
        r -> 60 * ((g - b) / d)
        g -> 60 * ((b - r) / d + 2)
        else -> 60 * ((r - g) / d + 4)
    }
    return Hsl((h + 360.0) % 360.0, s.coerceIn(0.0, 1.0), l)
}

internal fun rgb(color: Hsl): Int {
    val chroma = (1 - abs(2 * color.l - 1)) * color.s
    val sector = color.h / 60
    val x = chroma * (1 - abs(sector % 2 - 1))
    val (r, g, b) = when {
        sector < 1 -> Triple(chroma, x, 0.0)
        sector < 2 -> Triple(x, chroma, 0.0)
        sector < 3 -> Triple(0.0, chroma, x)
        sector < 4 -> Triple(0.0, x, chroma)
        sector < 5 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    val m = color.l - chroma / 2
    fun channel(value: Double) = ((value + m) * 255).roundToInt().coerceIn(0, 255)
    return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
