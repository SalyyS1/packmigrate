package com.salyvn.packmigrate.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A pack format, which since resource pack format 65 has a minor component as well as a major one. */
data class PackFormat(val major: Int, val minor: Int? = null) : Comparable<PackFormat> {
    init {
        require(major >= 0) { "A pack format cannot be negative" }
        require(minor == null || minor >= 0) { "A pack format's minor version cannot be negative" }
    }

    /** `15` or `[97, 1]` - the compact form the client's codec accepts for both spellings. */
    fun toJson(): Json = if (minor == null) Json.Num(major) else Json.Arr(listOf(Json.Num(major), Json.Num(minor)))

    override fun compareTo(other: PackFormat): Int {
        val majorCmp = major.compareTo(other.major)
        if (majorCmp != 0) return majorCmp
        return (minor ?: 0).compareTo(other.minor ?: 0)
    }

    override fun toString(): String = if (minor == null) "$major" else "$major.$minor"
}

/** Raised when a requested version range cannot be expressed in a `pack.mcmeta` the client will accept. */
class PackMetaException(message: String) : Exception(message)

data class OverlayEntrySpec(
    val directory: String,
    val min: PackFormat,
    val max: PackFormat,
)

enum class PackMetaFindingKind {
    INVALID_PACK_MCMETA,
    INVALID_OVERLAY_DIRECTORY,
    OVERLAY_ERA_MIGRATED,
}

data class PackMetaFinding(
    val kind: PackMetaFindingKind,
    val directory: String?,
    val message: String,
)

data class PackMetaMigration(
    val rendered: String,
    val validOverlayDirectories: List<String>,
    val findings: List<PackMetaFinding>,
)

/**
 * `pack.mcmeta`, built to the rules the client actually enforces across 1.20 through 26.3+.
 *
 * Resource pack format 64 (1.21.8) is the last one without minor versions. From 65 up, `min_format` and
 * `max_format` are mandatory and must both be present; `supported_formats` is rejected when `min_format`
 * starts above 64. Below 65 the opposite holds: a range that reaches down there has to carry
 * `supported_formats` and `pack_format` as well, `supported_formats` has to start on the same major as
 * `min_format` and end either on `max_format`'s major or on 64, and `pack_format` has to sit inside the
 * range and be at least 15. The same dual-era vs modern-only rules apply to `overlays.entries`.
 */
object PackMeta {
    /** `PackFormat.lastPreMinorVersion(CLIENT_RESOURCES)`. */
    const val LAST_PRE_MINOR_FORMAT: Int = 64

    /** `validatePackFormatForRange` refuses a multi-version pack whose main format is below this. */
    const val MULTI_VERSION_FLOOR: Int = 15

    private val OVERLAY_DIR_REGEX = Regex("^[a-z0-9_-]+$")
    private val PARSER = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun isValidOverlayDirectory(directory: String): Boolean = OVERLAY_DIR_REGEX.matches(directory)

    fun build(description: String, min: PackFormat, max: PackFormat): Json.Obj =
        Json.Obj("pack" to buildPackSection(Json.Text(description), min, max))

    fun render(description: String, min: PackFormat, max: PackFormat): String =
        build(description, min, max).render() + "\n"

    fun buildOverlayEntry(directory: String, overlayMin: PackFormat, overlayMax: PackFormat, packMax: PackFormat): Json.Obj {
        if (overlayMin.major > overlayMax.major || (overlayMin.major == overlayMax.major && (overlayMin.minor ?: 0) > (overlayMax.minor ?: Int.MAX_VALUE))) {
            throw PackMetaException("Overlay '$directory' has min_format ($overlayMin) greater than max_format ($overlayMax)")
        }
        val fields = mutableListOf<Pair<String, Json>>()
        if (overlayMin.major > LAST_PRE_MINOR_FORMAT) {
            fields += "min_format" to overlayMin.toJson()
            fields += "max_format" to overlayMax.toJson()
        } else if (packMax.major > LAST_PRE_MINOR_FORMAT || overlayMax.major > LAST_PRE_MINOR_FORMAT) {
            val legacyCeiling = minOf(overlayMax.major, LAST_PRE_MINOR_FORMAT)
            fields += "formats" to Json.Arr(listOf(Json.Num(overlayMin.major), Json.Num(legacyCeiling)))
            fields += "min_format" to overlayMin.toJson()
            fields += "max_format" to overlayMax.toJson()
        } else {
            fields += "formats" to Json.Arr(listOf(Json.Num(overlayMin.major), Json.Num(overlayMax.major)))
        }
        fields += "directory" to Json.Text(directory)
        return Json.Obj(fields)
    }

    /**
     * Rewrites an existing `pack.mcmeta` (or synthesizes a fresh one when null) while preserving non-`pack`
     * sections (`filter`, `language`, custom metadata) and migrating `overlays.entries` to the target era.
     */
    fun migrateExisting(
        existingText: String?,
        min: PackFormat,
        max: PackFormat,
        extraOverlay: OverlayEntrySpec? = null,
    ): PackMetaMigration {
        val findings = mutableListOf<PackMetaFinding>()
        val validOverlayDirs = mutableListOf<String>()

        if (extraOverlay != null && !isValidOverlayDirectory(extraOverlay.directory)) {
            throw PackMetaException(
                "Overlay directory '${extraOverlay.directory}' is invalid; Minecraft requires lowercase [a-z0-9_-]+ with no path separators or dots.",
            )
        }

        val rootObj: JsonObject? = if (existingText == null) {
            null
        } else {
            try {
                PARSER.parseToJsonElement(existingText).jsonObject
            } catch (failure: Exception) {
                findings += PackMetaFinding(
                    PackMetaFindingKind.INVALID_PACK_MCMETA,
                    null,
                    "pack.mcmeta is not valid JSON (${failure.message?.lineSequence()?.firstOrNull()}); rewriting with default description.",
                )
                null
            }
        }

        val descriptionJson: Json = rootObj
            ?.get("pack")
            ?.let { it as? JsonObject }
            ?.get("description")
            ?.let { toCoreJson(it) }
            ?: Json.Text("migrated pack")

        val packSection = buildPackSection(descriptionJson, min, max)
        val topEntries = mutableListOf<Pair<String, Json>>("pack" to packSection)

        val migratedOverlays = mutableListOf<Json>()
        val existingOverlays = (rootObj?.get("overlays") as? JsonObject)?.get("entries") as? JsonArray
        var extraHandled = false

        existingOverlays?.forEachIndexed { index, element ->
            val obj = element as? JsonObject
            if (obj == null) {
                findings += PackMetaFinding(
                    PackMetaFindingKind.INVALID_OVERLAY_DIRECTORY,
                    null,
                    "overlays.entries[$index] in pack.mcmeta is not a JSON object.",
                )
                return@forEachIndexed
            }
            val dir = (obj["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (dir.isNullOrEmpty() || !isValidOverlayDirectory(dir)) {
                findings += PackMetaFinding(
                    PackMetaFindingKind.INVALID_OVERLAY_DIRECTORY,
                    dir,
                    "Overlay directory '${dir ?: ""}' in pack.mcmeta violates Minecraft's [a-z0-9_-]+ naming rule.",
                )
                return@forEachIndexed
            }

            if (extraOverlay != null && dir == extraOverlay.directory) {
                validOverlayDirs += dir
                migratedOverlays += buildOverlayEntry(extraOverlay.directory, extraOverlay.min, extraOverlay.max, max)
                extraHandled = true
                return@forEachIndexed
            }

            val parsedMin = obj["min_format"]?.let { parseFormatElement(it) }
            val parsedMax = obj["max_format"]?.let { parseFormatElement(it) }
            val legacyBounds = obj["formats"]?.let { parseLegacyFormatsRange(it) }

            val (entryMin, entryMax, wasUpgraded) = when {
                parsedMin != null && parsedMax != null -> {
                    val droppedLegacy = parsedMin.major > LAST_PRE_MINOR_FORMAT && "formats" in obj
                    Triple(parsedMin, parsedMax, droppedLegacy)
                }
                legacyBounds != null -> {
                    val oMin = PackFormat(legacyBounds.first)
                    val oMax = if (legacyBounds.second >= LAST_PRE_MINOR_FORMAT && max.major > LAST_PRE_MINOR_FORMAT) {
                        max
                    } else {
                        PackFormat(legacyBounds.second)
                    }
                    val upgraded = max.major > LAST_PRE_MINOR_FORMAT || oMax.major > LAST_PRE_MINOR_FORMAT
                    Triple(oMin, oMax, upgraded)
                }
                else -> {
                    findings += PackMetaFinding(
                        PackMetaFindingKind.INVALID_OVERLAY_DIRECTORY,
                        dir,
                        "Overlay '$dir' in pack.mcmeta is missing both 'formats' and 'min_format'/'max_format'.",
                    )
                    return@forEachIndexed
                }
            }

            if (wasUpgraded) {
                findings += PackMetaFinding(
                    PackMetaFindingKind.OVERLAY_ERA_MIGRATED,
                    dir,
                    "Upgraded overlays.entries directory '$dir' to format range $entryMin..$entryMax for 1.21.9..26.3 compatibility.",
                )
            }
            validOverlayDirs += dir
            migratedOverlays += buildOverlayEntry(dir, entryMin, entryMax, max)
        }

        if (extraOverlay != null && !extraHandled) {
            validOverlayDirs += extraOverlay.directory
            migratedOverlays += buildOverlayEntry(extraOverlay.directory, extraOverlay.min, extraOverlay.max, max)
        }

        if (migratedOverlays.isNotEmpty()) {
            topEntries += "overlays" to Json.Obj("entries" to Json.Arr(migratedOverlays))
        }

        rootObj?.entries?.forEach { (key, value) ->
            if (key != "pack" && key != "overlays") {
                topEntries += key to toCoreJson(value)
            }
        }

        return PackMetaMigration(
            rendered = Json.Obj(topEntries).render() + "\n",
            validOverlayDirectories = validOverlayDirs.distinct(),
            findings = findings,
        )
    }

    private fun buildPackSection(description: Json, min: PackFormat, max: PackFormat): Json.Obj {
        if (min.major > max.major || (min.major == max.major && (min.minor ?: 0) > (max.minor ?: Int.MAX_VALUE))) {
            throw PackMetaException("min_format ($min) is greater than max_format ($max)")
        }
        val pack = mutableListOf<Pair<String, Json>>("description" to description)
        if (min.major > LAST_PRE_MINOR_FORMAT) {
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        } else {
            if (min.major < MULTI_VERSION_FLOOR) {
                throw PackMetaException(
                    "A pack that also targets format ${max.major} cannot start below $MULTI_VERSION_FLOOR " +
                        "(1.20): the client refuses it because versions in between would be unable to load it.",
                )
            }
            val legacyCeiling = minOf(max.major, LAST_PRE_MINOR_FORMAT)
            pack += "pack_format" to Json.Num(min.major)
            pack += "supported_formats" to Json.Arr(listOf(Json.Num(min.major), Json.Num(legacyCeiling)))
            pack += "min_format" to min.toJson()
            pack += "max_format" to max.toJson()
        }
        return Json.Obj(pack)
    }

    private fun parseFormatElement(element: JsonElement): PackFormat? = when (element) {
        is JsonPrimitive -> element.intOrNull?.takeIf { it >= 0 }?.let { PackFormat(it) }
        is JsonArray -> {
            val nums = element.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            if (nums.size == element.size && nums.size in 1..2 && nums.all { it >= 0 }) {
                PackFormat(nums[0], nums.getOrNull(1))
            } else {
                null
            }
        }
        else -> null
    }

    private fun parseLegacyFormatsRange(element: JsonElement): Pair<Int, Int>? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> element.intOrNull?.takeIf { it >= 0 }?.let { it to it }
        is JsonArray -> {
            val nums = element.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            if (nums.size == 2 && nums[0] >= 0 && nums[1] >= nums[0]) nums[0] to nums[1] else null
        }
        is JsonObject -> {
            val minInc = (element["min_inclusive"] as? JsonPrimitive)?.intOrNull
            val maxInc = (element["max_inclusive"] as? JsonPrimitive)?.intOrNull
            if (minInc != null && maxInc != null && minInc >= 0 && maxInc >= minInc) minInc to maxInc else null
        }
    }

    private fun toCoreJson(element: JsonElement): Json = when (element) {
        is JsonNull -> Json.Text("")
        is JsonPrimitive -> when {
            element.isString -> Json.Text(element.content)
            element.booleanOrNull != null -> Json.Bool(element.booleanOrNull!!)
            element.intOrNull != null -> Json.Num(element.intOrNull!!)
            element.doubleOrNull != null -> Json.Dec(element.doubleOrNull!!)
            else -> Json.Text(element.content)
        }
        is JsonArray -> Json.Arr(element.map { toCoreJson(it) })
        is JsonObject -> Json.Obj(element.entries.map { (k, v) -> k to toCoreJson(v) })
    }
}
