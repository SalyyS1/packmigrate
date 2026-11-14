package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.legacy.ModelId
import com.salyvn.packmigrate.legacy.Override
import com.salyvn.packmigrate.modern.ItemModelDefinition
import com.salyvn.packmigrate.modern.ItemModelNode
import kotlin.math.floor

enum class IssueLevel { ERROR, WARNING, NOTE }

/** Something the migration could not do exactly, or did differently, with its SARIF rule and artifact path. */
data class Issue(
    val level: IssueLevel,
    val item: String,
    val overrideIndex: Int?,
    val message: String,
    val rule: MigrationRule = defaultRule(level),
    val path: String = defaultPath(item),
) {
    companion object {
        private fun defaultRule(level: IssueLevel): MigrationRule = when (level) {
            IssueLevel.ERROR -> MigrationRule.PM001
            IssueLevel.WARNING -> MigrationRule.PM004
            IssueLevel.NOTE -> MigrationRule.PM007
        }

        fun defaultPath(item: String): String = when {
            item == "pack.mcmeta" -> "pack.mcmeta"
            item.endsWith(".json") -> item
            else -> ModelId.parse(item).relativeFile
        }
    }
}

/** The outcome for one legacy file: a definition, or nothing, and what was said about it. */
data class Conversion(
    val item: String,
    val definition: ItemModelDefinition?,
    val issues: List<Issue>,
    /** Model references the definition points at, for the existence check. */
    val referencedModels: List<ModelId>,
    /** Legacy custom_model_data values this item claims, for the collision check. */
    val customModelDataValues: List<Int>,
    /** Non-null when the legacy model came from an overlay directory rather than the pack root. */
    val sourceOverlay: String? = null,
    /** Pack-relative path of the source legacy model JSON. */
    val sourcePath: String = ModelId.parse(item).relativeFile,
)

/**
 * Turns one legacy item model's `overrides` into an item model definition.
 */
object Converter {

    fun convert(legacy: LegacyItemModel, sourceOverlay: String? = null): Conversion {
        val issues = mutableListOf<Issue>()
        val item = legacy.selfModel
        val baseRel = ModelId.parse(item).relativeFile
        val sourcePath = if (sourceOverlay != null) "$sourceOverlay/$baseRel" else baseRel

        if (!legacy.hasOverrides) {
            return Conversion(item, null, issues, emptyList(), emptyList(), sourceOverlay, sourcePath)
        }

        val unknown = legacy.overrides.flatMap { it.predicate.keys }.filter { PredicateMapping.of(it) == null }.distinct()
        unknown.forEach { name ->
            issues += Issue(
                level = IssueLevel.ERROR,
                item = item,
                overrideIndex = null,
                message = "Predicate '$name' is not one the legacy format defined; the override using it cannot be migrated.",
                rule = MigrationRule.PM002,
                path = sourcePath,
            )
        }

        val usable = legacy.overrides.filter { override -> override.predicate.keys.all { PredicateMapping.of(it) != null } }
        reportNonIntegerOrNegativeCmd(usable, item, sourcePath, issues)
        reportShadowedValues(usable, item, sourcePath, issues)

        val builder = PredicateTreeBuilder(legacy.item.substringAfterLast('/'), item, sourcePath, issues)
        val candidates = usable.map { Candidate(it.index, ModelId.parse(it.model).qualified, it.predicate) }
        val root = builder.build(candidates, ItemModelNode.Model(legacy.selfModel))

        val referenced = usable.map { ModelId.parse(it.model) } + ModelId.parse(legacy.selfModel)
        val integerCmdValues = usable.mapNotNull { ov ->
            val raw = ov.predicate["custom_model_data"] ?: return@mapNotNull null
            if (raw >= 0.0 && raw == floor(raw)) raw.toInt() else null
        }.distinct()

        return Conversion(
            item = item,
            definition = ItemModelDefinition(root),
            issues = issues,
            referencedModels = referenced.distinct(),
            customModelDataValues = integerCmdValues,
            sourceOverlay = sourceOverlay,
            sourcePath = sourcePath,
        )
    }

    private fun reportNonIntegerOrNegativeCmd(
        usable: List<Override>,
        item: String,
        sourcePath: String,
        issues: MutableList<Issue>,
    ) {
        usable.forEach { ov ->
            val raw = ov.predicate["custom_model_data"] ?: return@forEach
            if (raw < 0.0 || raw != floor(raw)) {
                issues += Issue(
                    level = IssueLevel.WARNING,
                    item = item,
                    overrideIndex = ov.index,
                    message = "custom_model_data $raw is negative or fractional; Minecraft's item data fixer writes integer CustomModelData tags to floats[0].",
                    rule = MigrationRule.PM011,
                    path = sourcePath,
                )
            }
        }
    }

    private fun reportShadowedValues(
        usable: List<Override>,
        item: String,
        sourcePath: String,
        issues: MutableList<Issue>,
    ) {
        usable.filter { it.customModelData != null && it.otherPredicates.isEmpty() }
            .groupBy { it.predicate["custom_model_data"]!! }
            .filterValues { it.size > 1 }
            .forEach { (value, declarations) ->
                val formatted = if (value == floor(value)) value.toInt().toString() else value.toString()
                issues += Issue(
                    level = IssueLevel.WARNING,
                    item = item,
                    overrideIndex = declarations.last().index,
                    message = "custom_model_data $formatted is declared ${declarations.size} times with no other predicate; the last one (${declarations.last().model}) wins, as it did before.",
                    rule = MigrationRule.PM004,
                    path = sourcePath,
                )
            }
    }
}
