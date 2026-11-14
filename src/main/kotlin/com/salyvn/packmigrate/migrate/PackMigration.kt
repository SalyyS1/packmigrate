package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.core.OverlayEntrySpec
import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.PackMeta
import com.salyvn.packmigrate.core.PackMetaFindingKind
import com.salyvn.packmigrate.core.VanillaModels
import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.legacy.LegacyParseException
import com.salyvn.packmigrate.legacy.ModelId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/** One file the migration would write, with the text. */
data class PlannedFile(val relative: String, val text: String, val overwrites: Boolean)

data class MigrationPlan(
    val conversions: List<Conversion>,
    val files: List<PlannedFile>,
    val issues: List<Issue>,
    val strippedOverrides: List<PlannedFile>,
) {
    val errors: Int get() = issues.count { it.level == IssueLevel.ERROR }
    val warnings: Int get() = issues.count { it.level == IssueLevel.WARNING }
    val itemsMigrated: Int get() = conversions.count { it.definition != null }
}

private data class LegacyModelSource(
    val namespace: String,
    val item: String,
    val file: Path,
    val sourceOverlay: String?,
) {
    val relativePath: String
        get() = if (sourceOverlay != null) {
            "$sourceOverlay/assets/$namespace/models/item/$item.json"
        } else {
            "assets/$namespace/models/item/$item.json"
        }
}

/**
 * Pack-wide migration planner and applier across root `assets/` and `pack.mcmeta` `overlays.entries`.
 */
class PackMigration(
    private val root: Path,
    private val minFormat: PackFormat,
    private val maxFormat: PackFormat,
    private val overlayItemsDir: String? = null,
) {

    fun plan(stripOverrides: Boolean): MigrationPlan {
        val issues = mutableListOf<Issue>()

        val format1214 = PackFormats.formatOf("1.21.4")
        val extraOverlay = overlayItemsDir?.let { dir ->
            val oMin = if (minFormat.major > format1214) minFormat else PackFormat(format1214)
            OverlayEntrySpec(directory = dir, min = oMin, max = maxFormat)
        }

        val metaFile = root.resolve("pack.mcmeta")
        val existingMetaText = if (metaFile.exists()) Files.readString(metaFile) else null
        val metaMigration = PackMeta.migrateExisting(existingMetaText, minFormat, maxFormat, extraOverlay)

        metaMigration.findings.forEach { finding ->
            when (finding.kind) {
                PackMetaFindingKind.INVALID_PACK_MCMETA -> issues += Issue(
                    level = IssueLevel.ERROR,
                    item = "pack.mcmeta",
                    overrideIndex = null,
                    message = finding.message,
                    rule = MigrationRule.PM001,
                    path = "pack.mcmeta",
                )
                PackMetaFindingKind.INVALID_OVERLAY_DIRECTORY -> issues += Issue(
                    level = IssueLevel.ERROR,
                    item = "pack.mcmeta",
                    overrideIndex = null,
                    message = finding.message,
                    rule = MigrationRule.PM008,
                    path = "pack.mcmeta",
                )
                PackMetaFindingKind.OVERLAY_ERA_MIGRATED -> issues += Issue(
                    level = IssueLevel.NOTE,
                    item = "pack.mcmeta",
                    overrideIndex = null,
                    message = finding.message,
                    rule = MigrationRule.PM010,
                    path = "pack.mcmeta",
                )
            }
        }

        val existingOverlayDirs = metaMigration.validOverlayDirectories.filter { dir ->
            val dirPath = root.resolve(dir)
            val present = dirPath.exists() && dirPath.isDirectory()
            if (!present && dir != overlayItemsDir) {
                issues += Issue(
                    level = IssueLevel.WARNING,
                    item = "pack.mcmeta",
                    overrideIndex = null,
                    message = "Overlay directory '$dir' declared in pack.mcmeta does not exist in the pack.",
                    rule = MigrationRule.PM009,
                    path = "pack.mcmeta",
                )
            }
            present
        }

        val legacySources = buildList {
            addAll(findLegacyItemModels(root, sourceOverlay = null))
            existingOverlayDirs.forEach { overlayDir ->
                addAll(findLegacyItemModels(root.resolve(overlayDir), sourceOverlay = overlayDir))
            }
        }

        val conversions = legacySources.mapNotNull { src ->
            val text = Files.readString(src.file)
            val legacy = try {
                LegacyItemModel.parse(src.namespace, src.item, text)
            } catch (failure: LegacyParseException) {
                issues += Issue(
                    level = IssueLevel.ERROR,
                    item = "${src.namespace}:item/${src.item}",
                    overrideIndex = null,
                    message = failure.message ?: "unreadable",
                    rule = MigrationRule.PM001,
                    path = src.relativePath,
                )
                return@mapNotNull null
            }
            if (!legacy.hasOverrides) return@mapNotNull null
            Converter.convert(legacy, sourceOverlay = src.sourceOverlay)
        }
        conversions.forEach { issues += it.issues }

        checkReferencedModels(conversions, issues)
        checkCollisions(conversions, issues)

        val files = mutableListOf<PlannedFile>()
        conversions.forEach { conversion ->
            val definition = conversion.definition ?: return@forEach
            val id = ModelId.parse(conversion.item)
            val targetPrefix = conversion.sourceOverlay ?: overlayItemsDir
            val baseItemDef = "assets/${id.namespace}/items/${id.path.removePrefix("item/")}.json"
            val relative = if (targetPrefix != null) "$targetPrefix/$baseItemDef" else baseItemDef
            val target = root.resolve(relative)
            val rendered = definition.render()
            if (target.exists() && Files.readString(target) != rendered) {
                issues += Issue(
                    level = IssueLevel.WARNING,
                    item = conversion.item,
                    overrideIndex = null,
                    message = "$relative already exists with different content and would be overwritten. If it was written by hand, keep it and drop the legacy overrides instead.",
                    rule = MigrationRule.PM005,
                    path = relative,
                )
            }
            files += PlannedFile(relative, rendered, overwrites = target.exists())
        }

        files += PlannedFile("pack.mcmeta", metaMigration.rendered, overwrites = metaFile.exists())

        val stripped = if (stripOverrides) {
            legacySources.mapNotNull { src ->
                LegacyItemModel.withoutOverrides(Files.readString(src.file))?.let { text ->
                    PlannedFile(src.relativePath, text, overwrites = true)
                }
            }
        } else {
            emptyList()
        }

        return MigrationPlan(conversions, files, issues.sortedBy { it.level.ordinal }, stripped)
    }

    fun apply(plan: MigrationPlan, destination: Path) {
        (plan.files + plan.strippedOverrides).forEach { planned ->
            val target = destination.resolve(planned.relative)
            Files.createDirectories(target.parent)
            Files.writeString(target, planned.text)
        }
    }

    private fun findLegacyItemModels(baseRoot: Path, sourceOverlay: String?): List<LegacyModelSource> {
        val assets = baseRoot.resolve("assets")
        if (!assets.exists()) return emptyList()
        return Files.list(assets).use { namespaces ->
            namespaces.filter { Files.isDirectory(it) }.toList()
        }.sortedBy { it.fileName.toString() }.flatMap { namespaceDir ->
            val items = namespaceDir.resolve("models/item")
            if (!items.exists()) return@flatMap emptyList()
            Files.walk(items).use { stream ->
                stream.filter { it.isRegularFile() && it.extension == "json" }
                    .map { file ->
                        val relative = items.relativize(file).joinToString("/") { it.toString() }.removeSuffix(".json")
                        LegacyModelSource(namespaceDir.fileName.toString(), relative, file, sourceOverlay)
                    }
                    .sorted(compareBy { it.item })
                    .toList()
            }
        }
    }

    /** A model the definition names has to be in the active overlay, the pack root, or vanilla. */
    private fun checkReferencedModels(conversions: List<Conversion>, issues: MutableList<Issue>) {
        conversions.forEach { conversion ->
            conversion.referencedModels.forEach { model ->
                val inSourceOverlay = conversion.sourceOverlay?.let { root.resolve(it).resolve(model.relativeFile).exists() } ?: false
                val inTargetOverlay = overlayItemsDir?.let { root.resolve(it).resolve(model.relativeFile).exists() } ?: false
                val inPack = root.resolve(model.relativeFile).exists()
                val inVanilla = model.namespace == "minecraft" && VanillaModels.has(model.path)
                if (!inSourceOverlay && !inTargetOverlay && !inPack && !inVanilla) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = conversion.item,
                        overrideIndex = null,
                        message = "References ${model.qualified} but ${model.relativeFile} is not in the pack. The client shows the missing-model box for that entry.",
                        rule = MigrationRule.PM003,
                        path = conversion.sourcePath,
                    )
                }
            }
        }
    }

    private fun checkCollisions(conversions: List<Conversion>, issues: MutableList<Issue>) {
        val byScopeAndValue = mutableMapOf<Pair<String?, Int>, MutableList<Conversion>>()
        conversions.forEach { conversion ->
            conversion.customModelDataValues.forEach { value ->
                byScopeAndValue.getOrPut(conversion.sourceOverlay to value) { mutableListOf() } += conversion
            }
        }
        byScopeAndValue.filterValues { it.size > 1 }.forEach { (key, matching) ->
            val value = key.second
            val names = matching.map { it.item }
            issues += Issue(
                level = IssueLevel.NOTE,
                item = matching.first().item,
                overrideIndex = null,
                message = "custom_model_data $value is used by ${matching.size} items: ${names.joinToString(", ")}. Legal, but a config that hands out $value has to say which item.",
                rule = MigrationRule.PM006,
                path = matching.first().sourcePath,
            )
        }
    }
}
