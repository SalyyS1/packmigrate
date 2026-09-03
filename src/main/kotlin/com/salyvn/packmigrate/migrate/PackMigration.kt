package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackMeta
import com.salyvn.packmigrate.core.VanillaModels
import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.legacy.LegacyParseException
import com.salyvn.packmigrate.legacy.ModelId
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension

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

/**
 * The whole pack at once.
 *
 * Per-item conversion is only half of it. The checks that need the whole pack are the ones that catch what
 * a server owner actually ships broken: a definition pointing at a model file that is not in the pack, two
 * items claiming the same custom_model_data value (fine for the game, confusing for the plugin config that
 * has to hand them out), and a pack.mcmeta that still says the new files are not there.
 */
class PackMigration(private val root: Path, private val minFormat: PackFormat, private val maxFormat: PackFormat) {

    fun plan(stripOverrides: Boolean): MigrationPlan {
        val issues = mutableListOf<Issue>()
        val legacyFiles = findLegacyItemModels()
        val conversions = legacyFiles.mapNotNull { (namespace, item, file) ->
            val text = Files.readString(file)
            val legacy = try {
                LegacyItemModel.parse(namespace, item, text)
            } catch (failure: LegacyParseException) {
                issues += Issue(IssueLevel.ERROR, "$namespace:item/$item", null, failure.message ?: "unreadable")
                return@mapNotNull null
            }
            if (!legacy.hasOverrides) return@mapNotNull null
            Converter.convert(legacy)
        }
        conversions.forEach { issues += it.issues }

        checkReferencedModels(conversions, issues)
        checkCollisions(conversions, issues)

        val files = mutableListOf<PlannedFile>()
        conversions.forEach { conversion ->
            val definition = conversion.definition ?: return@forEach
            val id = ModelId.parse(conversion.item)
            val relative = "assets/${id.namespace}/items/${id.path.removePrefix("item/")}.json"
            val target = root.resolve(relative)
            if (target.exists()) {
                issues += Issue(IssueLevel.WARNING, conversion.item, null, "$relative already exists and would be overwritten. If it was written by hand, keep it and drop the legacy overrides instead.")
            }
            files += PlannedFile(relative, definition.render(), overwrites = target.exists())
        }

        val meta = root.resolve("pack.mcmeta")
        val description = if (meta.exists()) existingDescription(Files.readString(meta)) else "migrated pack"
        files += PlannedFile("pack.mcmeta", PackMeta.render(description, minFormat, maxFormat), overwrites = meta.exists())

        val stripped = if (stripOverrides) {
            legacyFiles.mapNotNull { (namespace, item, file) ->
                LegacyItemModel.withoutOverrides(Files.readString(file))?.let { text ->
                    PlannedFile("assets/$namespace/models/item/$item.json", text, overwrites = true)
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

    /** `(namespace, item, path)` for every `assets/<ns>/models/item/<item>.json`. */
    private fun findLegacyItemModels(): List<Triple<String, String, Path>> {
        val assets = root.resolve("assets")
        if (!assets.exists()) return emptyList()
        return Files.list(assets).use { namespaces ->
            namespaces.filter { Files.isDirectory(it) }.toList()
        }.flatMap { namespaceDir ->
            val items = namespaceDir.resolve("models/item")
            if (!items.exists()) return@flatMap emptyList()
            Files.walk(items).use { stream ->
                stream.filter { it.isRegularFile() && it.extension == "json" }
                    .map { file ->
                        val relative = items.relativize(file).joinToString("/") { it.toString() }.removeSuffix(".json")
                        Triple(namespaceDir.fileName.toString(), relative, file)
                    }
                    .sorted(compareBy { it.second })
                    .toList()
            }
        }
    }

    /** A model the definition names has to be in this pack or shipped by the game. */
    private fun checkReferencedModels(conversions: List<Conversion>, issues: MutableList<Issue>) {
        conversions.forEach { conversion ->
            conversion.referencedModels.forEach { model ->
                val inPack = root.resolve(model.relativeFile).exists()
                val inVanilla = model.namespace == "minecraft" && VanillaModels.has(model.path)
                if (!inPack && !inVanilla) {
                    issues += Issue(
                        IssueLevel.ERROR, conversion.item, null,
                        "References ${model.qualified} but ${model.relativeFile} is not in the pack. The client shows the missing-model box for that entry.",
                    )
                }
            }
        }
    }

    /** The same value on two items is legal, but it is the thing plugin configs get wrong, so it is said. */
    private fun checkCollisions(conversions: List<Conversion>, issues: MutableList<Issue>) {
        val byValue = mutableMapOf<Int, MutableList<String>>()
        conversions.forEach { conversion ->
            conversion.customModelDataValues.forEach { value -> byValue.getOrPut(value) { mutableListOf() } += conversion.item }
        }
        byValue.filterValues { it.size > 1 }.forEach { (value, items) ->
            issues += Issue(IssueLevel.NOTE, items.first(), null, "custom_model_data $value is used by ${items.size} items: ${items.joinToString(", ")}. Legal, but a config that hands out $value has to say which item.")
        }
    }

    private fun existingDescription(text: String): String {
        val match = Regex("\"description\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return "migrated pack"
        return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
