package com.salyvn.packmigrate.modern

import com.salyvn.packmigrate.core.Json
import com.salyvn.packmigrate.migrate.Issue
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationRule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

data class ComponentOverrideSpec(
    val conditionComponent: String? = null,
    val customDataKey: String? = null,
    val whenValues: List<String>? = null,
    val model: String,
    val fallbackModel: String? = null,
)

data class TreeOptimizationResult(
    val root: ItemModelNode,
    val prunedBranches: Int,
    val issues: List<Issue>,
)

/**
 * Compiles modern component predicates and optimizes item model dispatch trees (PM014, PM015).
 */
object ComponentOverrideCompiler {

    private val VALID_COMPONENT_IDENTIFIER = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

    private val KNOWN_VANILLA_COMPONENTS = setOf(
        "minecraft:custom_data",
        "minecraft:max_stack_size",
        "minecraft:max_damage",
        "minecraft:damage",
        "minecraft:unbreakable",
        "minecraft:custom_name",
        "minecraft:item_name",
        "minecraft:lore",
        "minecraft:rarity",
        "minecraft:enchantments",
        "minecraft:can_place_on",
        "minecraft:can_break",
        "minecraft:attribute_modifiers",
        "minecraft:custom_model_data",
        "minecraft:hide_additional_tooltip",
        "minecraft:hide_tooltip",
        "minecraft:repair_cost",
        "minecraft:creative_slot_lock",
        "minecraft:enchantment_glint_override",
        "minecraft:intangible_projectile",
        "minecraft:food",
        "minecraft:fire_resistant",
        "minecraft:tool",
        "minecraft:stored_enchantments",
        "minecraft:dyed_color",
        "minecraft:map_color",
        "minecraft:map_id",
        "minecraft:map_decorations",
        "minecraft:charged_projectiles",
        "minecraft:bundle_contents",
        "minecraft:potion_contents",
        "minecraft:suspicious_stew_effects",
        "minecraft:writable_book_content",
        "minecraft:written_book_content",
        "minecraft:trim",
        "minecraft:debug_stick_state",
        "minecraft:entity_data",
        "minecraft:bucket_entity_data",
        "minecraft:block_entity_data",
        "minecraft:instrument",
        "minecraft:ominous_bottle_amplifier",
        "minecraft:jukebox_playable",
        "minecraft:recipes",
        "minecraft:lodestone_tracker",
        "minecraft:firework_explosion",
        "minecraft:fireworks",
        "minecraft:profile",
        "minecraft:note_block_sound",
        "minecraft:banner_patterns",
        "minecraft:base_color",
        "minecraft:pot_decorations",
        "minecraft:container",
        "minecraft:block_state",
        "minecraft:bees",
        "minecraft:lock",
        "minecraft:container_loot",
        "minecraft:item_model",
        "minecraft:equippable",
        "minecraft:glider",
        "minecraft:tooltip_style",
        "minecraft:death_protection",
        "minecraft:consumable",
        "minecraft:use_cooldown",
        "minecraft:use_remainder",
        "minecraft:damage_resistant",
    )

    fun validateComponentId(id: String): Boolean {
        val normalized = if (!id.contains(':')) "minecraft:$id" else id
        return VALID_COMPONENT_IDENTIFIER.matches(normalized)
    }

    fun isKnownComponent(id: String): Boolean {
        val normalized = if (!id.contains(':')) "minecraft:$id" else id
        return KNOWN_VANILLA_COMPONENTS.contains(normalized)
    }

    /**
     * Parses modern component override JSON array specs into ItemModelNode structures.
     */
    fun compileComponentOverrides(
        element: JsonElement,
        baseModel: ItemModelNode,
        itemPath: String,
        issues: MutableList<Issue>,
    ): ItemModelNode {
        if (element !is JsonArray) {
            issues += Issue(
                level = IssueLevel.ERROR,
                item = itemPath,
                overrideIndex = null,
                message = "Component overrides must be a JSON array of override specifications.",
                rule = MigrationRule.PM014,
                path = itemPath,
            )
            return baseModel
        }

        var currentRoot = baseModel

        for ((idx, item) in element.withIndex()) {
            if (item !is JsonObject) {
                issues += Issue(
                    level = IssueLevel.ERROR,
                    item = itemPath,
                    overrideIndex = idx,
                    message = "Component override entry at index $idx is not a JSON object.",
                    rule = MigrationRule.PM014,
                    path = itemPath,
                )
                continue
            }

            val targetModel = (item["model"] as? JsonPrimitive)?.contentOrNull
            if (targetModel == null) {
                issues += Issue(
                    level = IssueLevel.ERROR,
                    item = itemPath,
                    overrideIndex = idx,
                    message = "Component override entry at index $idx missing required 'model' field.",
                    rule = MigrationRule.PM014,
                    path = itemPath,
                )
                continue
            }

            // Case A: has_component condition
            val hasComponent = (item["has_component"] as? JsonPrimitive)?.contentOrNull
            if (hasComponent != null) {
                val comp = if (!hasComponent.contains(':')) "minecraft:$hasComponent" else hasComponent
                if (!validateComponentId(comp)) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "Invalid component identifier '$hasComponent' in has_component condition.",
                        rule = MigrationRule.PM014,
                        path = itemPath,
                    )
                    continue
                } else if (!isKnownComponent(comp)) {
                    issues += Issue(
                        level = IssueLevel.NOTE,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "Custom component identifier '$comp' used in has_component condition.",
                        rule = MigrationRule.PM007,
                        path = itemPath,
                    )
                }

                currentRoot = ItemModelNode.Condition(
                    property = "minecraft:has_component",
                    component = comp,
                    onTrue = ItemModelNode.Model(targetModel),
                    onFalse = currentRoot,
                )
                continue
            }

            // Case B: select on component property
            val selectProperty = (item["property"] as? JsonPrimitive)?.contentOrNull
            val whenVal = (item["when"] as? JsonPrimitive)?.contentOrNull
            if (selectProperty != null && whenVal != null) {
                currentRoot = ItemModelNode.Select(
                    property = selectProperty,
                    cases = listOf(ItemModelNode.Case(listOf(whenVal), ItemModelNode.Model(targetModel))),
                    fallback = currentRoot,
                )
                continue
            }

            // Case C: direct custom_model_data range entry
            val cmd = (item["custom_model_data"] as? JsonPrimitive)?.doubleOrNull
            if (cmd != null) {
                currentRoot = ItemModelNode.RangeDispatch(
                    property = "minecraft:custom_model_data",
                    index = 0,
                    entries = listOf(ItemModelNode.Entry(cmd, ItemModelNode.Model(targetModel))),
                    fallback = currentRoot,
                )
                continue
            }
        }

        return currentRoot
    }

    /**
     * Recursively optimizes an ItemModelNode tree, pruning redundant branches, folding tautologies,
     * and coalescing select cases (rule PM015).
     */
    fun optimizeTree(node: ItemModelNode, itemPath: String): TreeOptimizationResult {
        val issues = mutableListOf<Issue>()
        var pruned = 0

        fun prune(n: ItemModelNode): ItemModelNode {
            return when (n) {
                is ItemModelNode.Model -> n

                is ItemModelNode.Condition -> {
                    val trueBranch = prune(n.onTrue)
                    val falseBranch = prune(n.onFalse)

                    // Tautology check: on_true == on_false
                    if (trueBranch == falseBranch) {
                        pruned++
                        issues += Issue(
                            level = IssueLevel.WARNING,
                            item = itemPath,
                            overrideIndex = null,
                            message = "Tautological condition on '${n.property}': both branches resolve to identical model. Condition folded.",
                            rule = MigrationRule.PM015,
                            path = itemPath,
                        )
                        return trueBranch
                    }

                    ItemModelNode.Condition(
                        property = n.property,
                        onTrue = trueBranch,
                        onFalse = falseBranch,
                        index = n.index,
                        component = n.component,
                    )
                }

                is ItemModelNode.RangeDispatch -> {
                    val optimizedFallback = n.fallback?.let { prune(it) }
                    val sortedEntries = n.entries.sortedBy { it.threshold }
                    val newEntries = mutableListOf<ItemModelNode.Entry>()

                    for (entry in sortedEntries) {
                        val optModel = prune(entry.model)

                        // If lowest entry at 0.0 matches fallback, it is redundant
                        if (newEntries.isEmpty() && entry.threshold <= 0.0 && optimizedFallback != null && optModel == optimizedFallback) {
                            pruned++
                            issues += Issue(
                                level = IssueLevel.WARNING,
                                item = itemPath,
                                overrideIndex = null,
                                message = "Redundant range_dispatch threshold ${entry.threshold} matches fallback model; pruned.",
                                rule = MigrationRule.PM015,
                                path = itemPath,
                            )
                            continue
                        }

                        // Duplicate threshold check
                        if (newEntries.isNotEmpty() && newEntries.last().threshold == entry.threshold) {
                            pruned++
                            issues += Issue(
                                level = IssueLevel.WARNING,
                                item = itemPath,
                                overrideIndex = null,
                                message = "Duplicate range_dispatch threshold ${entry.threshold}; shadowed branch pruned.",
                                rule = MigrationRule.PM015,
                                path = itemPath,
                            )
                            newEntries[newEntries.lastIndex] = ItemModelNode.Entry(entry.threshold, optModel)
                            continue
                        }

                        newEntries += ItemModelNode.Entry(entry.threshold, optModel)
                    }

                    if (newEntries.isEmpty() && optimizedFallback != null) {
                        return optimizedFallback
                    }

                    ItemModelNode.RangeDispatch(
                        property = n.property,
                        entries = newEntries,
                        fallback = optimizedFallback,
                        index = n.index,
                        scale = n.scale,
                        target = n.target,
                        period = n.period,
                        source = n.source,
                    )
                }

                is ItemModelNode.Select -> {
                    val optimizedFallback = n.fallback?.let { prune(it) }
                    val caseMap = mutableMapOf<ItemModelNode, MutableList<String>>()

                    for (c in n.cases) {
                        val optModel = prune(c.model)
                        if (optimizedFallback != null && optModel == optimizedFallback) {
                            pruned++
                            issues += Issue(
                                level = IssueLevel.WARNING,
                                item = itemPath,
                                overrideIndex = null,
                                message = "Select case '${c.whenValues.joinToString(",")}' produces identical model to fallback; pruned.",
                                rule = MigrationRule.PM015,
                                path = itemPath,
                            )
                            continue
                        }
                        caseMap.getOrPut(optModel) { mutableListOf() }.addAll(c.whenValues)
                    }

                    val coalescedCases = caseMap.map { (model, values) ->
                        ItemModelNode.Case(values.distinct(), model)
                    }

                    if (coalescedCases.isEmpty() && optimizedFallback != null) {
                        return optimizedFallback
                    }

                    ItemModelNode.Select(
                        property = n.property,
                        cases = coalescedCases,
                        fallback = optimizedFallback,
                        blockStateProperty = n.blockStateProperty,
                    )
                }
            }
        }

        val optimized = prune(node)
        return TreeOptimizationResult(optimized, pruned, issues)
    }
}
