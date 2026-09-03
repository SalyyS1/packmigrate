package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.legacy.ModelId
import com.salyvn.packmigrate.legacy.Override
import com.salyvn.packmigrate.modern.ItemModelDefinition
import com.salyvn.packmigrate.modern.ItemModelNode

enum class IssueLevel { ERROR, WARNING, NOTE }

/** Something the migration could not do exactly, or did differently, with the item and override it is about. */
data class Issue(val level: IssueLevel, val item: String, val overrideIndex: Int?, val message: String)

/** The outcome for one legacy file: a definition, or nothing, and what was said about it. */
data class Conversion(
    val item: String,
    val definition: ItemModelDefinition?,
    val issues: List<Issue>,
    /** Model references the definition points at, for the existence check. */
    val referencedModels: List<ModelId>,
    /** Legacy custom_model_data values this item claims, for the collision check. */
    val customModelDataValues: List<Int>,
)

/**
 * Turns one legacy item model's `overrides` into an item model definition.
 *
 * The two formats agree on the one thing that matters. A legacy override list is scanned in order and the
 * last entry whose predicates are all satisfied (`>=`) wins, which is why authors list `custom_model_data`
 * ascending. A `range_dispatch` picks the entry with the largest `threshold` that is `<=` the value. For
 * overrides keyed on one numeric predicate the two rules select the same model for every input, so an
 * integer `custom_model_data: N` becomes `threshold: N` and nothing else changes.
 *
 * Everything past that simple case is [PredicateTreeBuilder]'s job: it rebuilds the legacy rule as a tree
 * instead of matching known file shapes. This object is the part around it — refusing predicate names the
 * legacy format never had, reporting what a reader would want to know, and collecting the model references
 * the pack has to contain for the result to render.
 */
object Converter {

    fun convert(legacy: LegacyItemModel): Conversion {
        val issues = mutableListOf<Issue>()
        val item = legacy.selfModel

        if (!legacy.hasOverrides) {
            return Conversion(item, null, issues, emptyList(), emptyList())
        }

        val unknown = legacy.overrides.flatMap { it.predicate.keys }.filter { PredicateMapping.of(it) == null }.distinct()
        unknown.forEach { name ->
            issues += Issue(IssueLevel.ERROR, item, null, "Predicate '$name' is not one the legacy format defined; the override using it cannot be migrated.")
        }

        val usable = legacy.overrides.filter { override -> override.predicate.keys.all { PredicateMapping.of(it) != null } }
        reportShadowedValues(usable, item, issues)

        // The item's own model is what the tree falls back to, the way an item with no matching override
        // used to render the file it was written in.
        val builder = PredicateTreeBuilder(legacy.item.substringAfterLast('/'), item, issues)
        val candidates = usable.map { Candidate(it.index, ModelId.parse(it.model).qualified, it.predicate) }
        val root = builder.build(candidates, ItemModelNode.Model(legacy.selfModel))

        val referenced = usable.map { ModelId.parse(it.model) } + ModelId.parse(legacy.selfModel)
        return Conversion(
            item = item,
            definition = ItemModelDefinition(root),
            issues = issues,
            referencedModels = referenced.distinct(),
            customModelDataValues = usable.mapNotNull { it.customModelData }.distinct(),
        )
    }

    /**
     * A `custom_model_data` value claimed twice with no other predicate only ever showed the later model.
     * The definition keeps that, and says so, because the dead entry is usually a copy-paste the author
     * still believes in.
     */
    private fun reportShadowedValues(usable: List<Override>, item: String, issues: MutableList<Issue>) {
        usable.filter { it.customModelData != null && it.otherPredicates.isEmpty() }
            .groupBy { it.customModelData!! }
            .filterValues { it.size > 1 }
            .forEach { (value, declarations) ->
                issues += Issue(
                    IssueLevel.WARNING, item, declarations.last().index,
                    "custom_model_data $value is declared ${declarations.size} times with no other predicate; the last one (${declarations.last().model}) wins, as it did before.",
                )
            }
    }
}
