package com.salyvn.packmigrate.bundle

import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.PackMeta
import com.salyvn.packmigrate.core.ZipArtifact
import com.salyvn.packmigrate.core.ZipPacks
import com.salyvn.packmigrate.dedup.DedupOptions
import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.migrate.Issue
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationRule
import com.salyvn.packmigrate.migrate.PackMigration
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

@Serializable
data class BundleSliceArtifact(
    val slice: String,
    val fileName: String,
    val minecraftRange: String,
    val minFormat: String,
    val maxFormat: String,
    val minProtocol: Int,
    val maxProtocol: Int,
    val fileCount: Int,
    val sizeBytes: Long,
    val sha1: String,
    val sha256: String,
    val serverPropertiesLine: String,
)

@Serializable
data class DualEraBundleManifest(
    val schemaVersion: String = "1.0",
    val bundleDirectory: String,
    val legacySlice: BundleSliceArtifact,
    val modernSlice: BundleSliceArtifact,
    val hybridUniversalSlice: BundleSliceArtifact,
)

data class BundleExportResult(
    val manifest: DualEraBundleManifest,
    val manifestPath: Path,
    val legacyZip: ZipArtifact,
    val modernZip: ZipArtifact,
    val hybridZip: ZipArtifact,
    val issues: List<Issue>,
)

/**
 * Exports a deterministic 3-slice Dual-Era Asset Bundle (`--bundle-export <dir>`, rule PM020)
 * for proxy networks (Velocity / BungeeCord / ViaVersion) serving 1.20.1 through 26.3 clients:
 *
 * - `pack-legacy-1_20_1-1_21_3.zip` (formats `15..42`, protocol `763..768`): retains legacy `overrides`,
 *   omits 1.21.4+ `items/STAR.json` and modern-only `>= 46` overlays, writes pre-minor `pack.mcmeta`.
 * - `pack-modern-1_21_4-26_3.zip`   (formats `46..97.1`, protocol `769..775`): emits migrated `items/STAR.json`,
 *   strips legacy `overrides` from `models/item/STAR.json`, writes modern `pack.mcmeta`.
 * - `pack-hybrid-universal.zip`     (formats `15..97.1`, protocol `763..775`): single-URL universal fallback
 *   isolating `items/STAR.json` inside `overlay_1_21_4` while preserving root `overrides`.
 * - `bundle-manifest.json`          : machine-readable proxy dispatch table with SHA-1 and SHA-256 digests.
 */
object DualEraBundleExporter {

    const val LEGACY_ZIP_NAME = "pack-legacy-1_20_1-1_21_3.zip"
    const val MODERN_ZIP_NAME = "pack-modern-1_21_4-26_3.zip"
    const val HYBRID_ZIP_NAME = "pack-hybrid-universal.zip"
    const val MANIFEST_NAME = "bundle-manifest.json"
    const val HYBRID_OVERLAY_DIR = "overlay_1_21_4"

    private const val PROTO_1_20_1 = 763
    private const val PROTO_1_21_3 = 768
    private const val PROTO_1_21_4 = 769
    private const val PROTO_26_3 = 775

    private val PARSER = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }

    fun export(
        packRoot: Path,
        bundleOutDir: Path,
        dedupOptions: DedupOptions = DedupOptions(),
        pruneOverrides: Boolean = false,
        simplifyTrees: Boolean = false,
        codegenComponents: Boolean = false,
        componentOverrides: JsonElement? = null,
    ): BundleExportResult {
        Files.createDirectories(bundleOutDir)
        val issues = mutableListOf<Issue>()

        // Audit hand-authored modern items/*.json without legacy overrides counterparts (PM020)
        issues += auditUnpairedModernDefinitions(packRoot)

        val modernMax = PackFormat(PackFormats.formatOf("26.3"), 1)
        val legacyMin = PackFormat(PackFormats.formatOf("1.20.1"))
        val legacyMax = PackFormat(PackFormats.formatOf("1.21.3"))
        val modernMin = PackFormat(PackFormats.formatOf("1.21.4"))

        val workDir = Files.createTempDirectory("packmigrate-bundle-")
        try {
            // 1. Legacy slice (1.20.1..1.21.3, formats 15..42)
            val legacyStage = workDir.resolve("legacy")
            stageLegacySlice(packRoot, legacyStage, legacyMin, legacyMax)
            val legacyFileCount = countRegularFiles(legacyStage)
            val legacyZip = ZipPacks.writeDeterministicZip(legacyStage, bundleOutDir.resolve(LEGACY_ZIP_NAME))

            // 2. Modern slice (1.21.4..26.3, formats 46..[97, 1]) with overrides stripped
            val modernStage = workDir.resolve("modern")
            copyTreeFiltered(packRoot, modernStage) { true }
            val modernMigrator = PackMigration(modernStage, modernMin, modernMax, overlayItemsDir = null)
            val modernPlan = modernMigrator.plan(
                stripOverrides = true,
                dedupOptions = dedupOptions,
                pruneOverrides = pruneOverrides,
                componentOverrides = componentOverrides,
                simplifyTrees = simplifyTrees,
                codegenComponents = codegenComponents,
            )
            modernMigrator.apply(modernPlan, modernStage)
            prunePre1214OnlyOverlays(modernStage, modernMin, modernMax)
            val modernFileCount = countRegularFiles(modernStage)
            val modernZip = ZipPacks.writeDeterministicZip(modernStage, bundleOutDir.resolve(MODERN_ZIP_NAME))

            // 3. Hybrid universal slice (1.20.1..26.3, formats 15..[97, 1]) with isolated overlay_1_21_4
            val hybridStage = workDir.resolve("hybrid")
            copyTreeFiltered(packRoot, hybridStage) { true }
            val hybridMigrator = PackMigration(hybridStage, legacyMin, modernMax, overlayItemsDir = HYBRID_OVERLAY_DIR)
            val hybridPlan = hybridMigrator.plan(
                stripOverrides = false,
                dedupOptions = dedupOptions,
                pruneOverrides = pruneOverrides,
                componentOverrides = componentOverrides,
                simplifyTrees = simplifyTrees,
                codegenComponents = codegenComponents,
            )
            hybridMigrator.apply(hybridPlan, hybridStage)
            val hybridFileCount = countRegularFiles(hybridStage)
            val hybridZip = ZipPacks.writeDeterministicZip(hybridStage, bundleOutDir.resolve(HYBRID_ZIP_NAME))

            val manifest = DualEraBundleManifest(
                bundleDirectory = bundleOutDir.toString(),
                legacySlice = BundleSliceArtifact(
                    slice = "legacy",
                    fileName = LEGACY_ZIP_NAME,
                    minecraftRange = "1.20.1..1.21.3",
                    minFormat = legacyMin.toString(),
                    maxFormat = legacyMax.toString(),
                    minProtocol = PROTO_1_20_1,
                    maxProtocol = PROTO_1_21_3,
                    fileCount = legacyFileCount,
                    sizeBytes = legacyZip.sizeBytes,
                    sha1 = legacyZip.sha1,
                    sha256 = legacyZip.sha256,
                    serverPropertiesLine = "resource-pack-sha1=${legacyZip.sha1}",
                ),
                modernSlice = BundleSliceArtifact(
                    slice = "modern",
                    fileName = MODERN_ZIP_NAME,
                    minecraftRange = "1.21.4..26.3",
                    minFormat = modernMin.toString(),
                    maxFormat = modernMax.toString(),
                    minProtocol = PROTO_1_21_4,
                    maxProtocol = PROTO_26_3,
                    fileCount = modernFileCount,
                    sizeBytes = modernZip.sizeBytes,
                    sha1 = modernZip.sha1,
                    sha256 = modernZip.sha256,
                    serverPropertiesLine = "resource-pack-sha1=${modernZip.sha1}",
                ),
                hybridUniversalSlice = BundleSliceArtifact(
                    slice = "hybrid",
                    fileName = HYBRID_ZIP_NAME,
                    minecraftRange = "1.20.1..26.3",
                    minFormat = legacyMin.toString(),
                    maxFormat = modernMax.toString(),
                    minProtocol = PROTO_1_20_1,
                    maxProtocol = PROTO_26_3,
                    fileCount = hybridFileCount,
                    sizeBytes = hybridZip.sizeBytes,
                    sha1 = hybridZip.sha1,
                    sha256 = hybridZip.sha256,
                    serverPropertiesLine = "resource-pack-sha1=${hybridZip.sha1}",
                ),
            )

            val manifestPath = bundleOutDir.resolve(MANIFEST_NAME)
            Files.writeString(manifestPath, PRETTY.encodeToString(manifest) + "\n")

            return BundleExportResult(
                manifest = manifest,
                manifestPath = manifestPath,
                legacyZip = legacyZip,
                modernZip = modernZip,
                hybridZip = hybridZip,
                issues = issues,
            )
        } finally {
            workDir.toFile().deleteRecursively()
        }
    }

    private fun stageLegacySlice(packRoot: Path, dest: Path, legacyMin: PackFormat, legacyMax: PackFormat) {
        val modernOnlyOverlayDirs = findOverlayDirsAbove(packRoot, ceilingExclusive = 46)
        copyTreeFiltered(packRoot, dest) { rel ->
            val segs = rel.split('/')
            val inModernOverlay = segs.firstOrNull() in modernOnlyOverlayDirs
            val isModernItemDef = segs.size >= 4 && segs[0] == "assets" && segs[2] == "items" && rel.endsWith(".json")
            !inModernOverlay && !isModernItemDef
        }

        val metaPath = packRoot.resolve("pack.mcmeta")
        val rawMeta = if (metaPath.exists()) Files.readString(metaPath) else null
        val filteredMeta = stripModernOverlaysFromMeta(rawMeta, modernOnlyOverlayDirs)
        val migratedMeta = PackMeta.migrateExisting(filteredMeta, legacyMin, legacyMax, extraOverlay = null)
        Files.createDirectories(dest)
        Files.writeString(dest.resolve("pack.mcmeta"), migratedMeta.rendered)
    }

    private fun prunePre1214OnlyOverlays(stageDir: Path, modernMin: PackFormat, modernMax: PackFormat) {
        val obsoleteOverlayDirs = findOverlayDirsBelow(stageDir, floorInclusive = 46)
        if (obsoleteOverlayDirs.isEmpty()) return
        for (dir in obsoleteOverlayDirs) {
            val target = stageDir.resolve(dir)
            if (target.exists()) target.toFile().deleteRecursively()
        }
        val metaPath = stageDir.resolve("pack.mcmeta")
        if (metaPath.exists()) {
            val cleaned = stripModernOverlaysFromMeta(Files.readString(metaPath), obsoleteOverlayDirs)
            val rewritten = PackMeta.migrateExisting(cleaned, modernMin, modernMax, extraOverlay = null)
            Files.writeString(metaPath, rewritten.rendered)
        }
    }

    private fun auditUnpairedModernDefinitions(packRoot: Path): List<Issue> {
        val issues = mutableListOf<Issue>()
        val assets = packRoot.resolve("assets")
        if (!assets.exists() || !assets.isDirectory()) return issues

        val namespaces = Files.list(assets).use { s -> s.filter { it.isDirectory() }.toList() }.sortedBy { it.fileName.toString() }
        for (nsDir in namespaces) {
            val ns = nsDir.fileName.toString()
            val itemsDir = nsDir.resolve("items")
            if (!itemsDir.exists() || !itemsDir.isDirectory()) continue

            val itemDefs = Files.walk(itemsDir).use { s ->
                s.filter { it.isRegularFile() && it.extension == "json" }.toList()
            }.sortedBy { it.toString() }

            for (defFile in itemDefs) {
                val relName = itemsDir.relativize(defFile).invariantSeparatorsPathString.removeSuffix(".json")
                val relDefPath = "assets/$ns/items/$relName.json"
                val legacyCounterpart = nsDir.resolve("models/item/$relName.json")
                val hasLegacyOverrides = if (legacyCounterpart.exists() && legacyCounterpart.isRegularFile()) {
                    try {
                        LegacyItemModel.parse(ns, relName, Files.readString(legacyCounterpart)).hasOverrides
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                if (!hasLegacyOverrides) {
                    issues += Issue(
                        level = IssueLevel.WARNING,
                        item = "$ns:item/$relName",
                        overrideIndex = null,
                        message = "Modern item definition '$relDefPath' has no legacy overrides in 'assets/$ns/models/item/$relName.json'; pre-1.21.4 clients receiving '$LEGACY_ZIP_NAME' will render the default item.",
                        rule = MigrationRule.PM020,
                        path = relDefPath,
                    )
                }
            }
        }
        return issues
    }

    private fun findOverlayDirsAbove(packRoot: Path, ceilingExclusive: Int): Set<String> {
        val meta = readMetaObject(packRoot) ?: return emptySet()
        val entries = (meta["overlays"] as? JsonObject)?.get("entries") as? JsonArray ?: return emptySet()
        val out = mutableSetOf<String>()
        for (el in entries) {
            val obj = el as? JsonObject ?: continue
            val dir = (obj["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val minMaj = extractMinMajor(obj) ?: continue
            if (minMaj >= ceilingExclusive) out += dir
        }
        return out
    }

    private fun findOverlayDirsBelow(packRoot: Path, floorInclusive: Int): Set<String> {
        val meta = readMetaObject(packRoot) ?: return emptySet()
        val entries = (meta["overlays"] as? JsonObject)?.get("entries") as? JsonArray ?: return emptySet()
        val out = mutableSetOf<String>()
        for (el in entries) {
            val obj = el as? JsonObject ?: continue
            val dir = (obj["directory"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val maxMaj = extractMaxMajor(obj) ?: continue
            if (maxMaj < floorInclusive) out += dir
        }
        return out
    }

    private fun extractMinMajor(obj: JsonObject): Int? {
        obj["min_format"]?.let { el ->
            when (el) {
                is JsonPrimitive -> el.intOrNull?.let { return it }
                is JsonArray -> (el.firstOrNull() as? JsonPrimitive)?.intOrNull?.let { return it }
                else -> {}
            }
        }
        return when (val fmts = obj["formats"]) {
            is JsonPrimitive -> fmts.intOrNull
            is JsonArray -> (fmts.firstOrNull() as? JsonPrimitive)?.intOrNull
            is JsonObject -> (fmts["min_inclusive"] as? JsonPrimitive)?.intOrNull
            else -> null
        }
    }

    private fun extractMaxMajor(obj: JsonObject): Int? {
        obj["max_format"]?.let { el ->
            when (el) {
                is JsonPrimitive -> el.intOrNull?.let { return it }
                is JsonArray -> (el.firstOrNull() as? JsonPrimitive)?.intOrNull?.let { return it }
                else -> {}
            }
        }
        return when (val fmts = obj["formats"]) {
            is JsonPrimitive -> fmts.intOrNull
            is JsonArray -> (fmts.getOrNull(1) as? JsonPrimitive)?.intOrNull
            is JsonObject -> (fmts["max_inclusive"] as? JsonPrimitive)?.intOrNull
            else -> null
        }
    }

    private fun stripModernOverlaysFromMeta(rawMeta: String?, dirsToDrop: Set<String>): String? {
        if (rawMeta == null || dirsToDrop.isEmpty()) return rawMeta
        val root = try {
            PARSER.parseToJsonElement(rawMeta).jsonObject
        } catch (_: Exception) {
            return rawMeta
        }
        val overlaysObj = root["overlays"] as? JsonObject ?: return rawMeta
        val entries = overlaysObj["entries"] as? JsonArray ?: return rawMeta
        val kept = entries.filter { el ->
            val dir = ((el as? JsonObject)?.get("directory") as? JsonPrimitive)?.content
            dir == null || dir !in dirsToDrop
        }
        val nextMap = LinkedHashMap<String, JsonElement>(root)
        if (kept.isEmpty()) {
            nextMap.remove("overlays")
        } else {
            nextMap["overlays"] = JsonObject(overlaysObj + ("entries" to JsonArray(kept)))
        }
        return JsonObject(nextMap).toString()
    }

    private fun readMetaObject(packRoot: Path): JsonObject? {
        val f = packRoot.resolve("pack.mcmeta")
        if (!f.exists()) return null
        return try {
            PARSER.parseToJsonElement(Files.readString(f)).jsonObject
        } catch (_: Exception) {
            null
        }
    }

    private fun copyTreeFiltered(from: Path, to: Path, keepRelative: (String) -> Boolean) {
        Files.createDirectories(to)
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                if (src == from) return@forEach
                val rel = from.relativize(src).invariantSeparatorsPathString
                if (!keepRelative(rel)) return@forEach
                val dst = to.resolve(rel)
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst)
                } else if (Files.isRegularFile(src)) {
                    Files.createDirectories(dst.parent)
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun countRegularFiles(dir: Path): Int =
        Files.walk(dir).use { s -> s.filter { it.isRegularFile() }.count().toInt() }
}
