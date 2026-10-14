/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.branding.readXml
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import java.util.Locale
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
        "orange, teal or a color code you type in this patch's options. Warnings stay red, and so " +
        "can icons with the red drawn in. It's built in while patching, so only patching again " +
        "without it undoes it.",
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
    execute {
        // Checked before anything is read or written, so a refused color changes nothing.
        val target = checkedAccent(accent)
        val packageRoot = get("res").parentFile
        val decodedRoots = decodedPackageRoots(packageRoot)
        val collisions = renamedPathCollisions(
            entries = listApkEntries("res/"),
            decodedDirectories = decodedResourceDirectories(decodedRoots),
            aliasOf = { name -> get(name).relativeTo(packageRoot).invariantSeparatorsPath },
            isDecoded = { name -> decodedRoots.any { it.resolve(name).isFile } },
        )
        if (collisions.isNotEmpty()) throw PatchException(renamedPathsRefusal(collisions.size, "Accent color"))
        if (target != TIKTOK_ACCENT) {
            // This build's accent tokens, or a refusal: a name carried over from another build is a guess.
            val tokens = accentTokens(packageMetadata.versionName)
            val files = get("res").listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .map { it.resolve("styles.xml") }.filter { it.exists() }
            // A dry run on a throwaway copy of each file first: a refusal leaves every file as it was.
            val found = files.flatMapTo(mutableSetOf()) { recolorAccentItems(readXml(it), tokens, target) }
            if (found != tokens) throw PatchException(incompleteAccentRefusal(tokens - found))
            for (file in files) {
                document(file.relativeTo(get(".")).invariantSeparatorsPath).use { recolorAccentItems(it, tokens, target) }
            }
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
internal fun recolorAccentItems(styles: Document, names: Set<String>, target: String): Set<String> {
    val changed = mutableSetOf<String>()
    val items = styles.getElementsByTagName("item")
    for (index in 0 until items.length) {
        val item = items.item(index) as Element
        val name = item.getAttribute("name")
        if (name !in names || !isRedLiteral(item.textContent)) continue
        item.textContent = accentShade(item.textContent, target)
        changed.add(name)
    }
    return changed
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
