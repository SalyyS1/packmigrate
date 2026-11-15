package com.salyvn.packmigrate.modern

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
 * Compiles modern 1.21.4..26.3 component predicates, tint sources, and composite item layers (PM014, PM015, PM018).
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
        "minecraft:weapon",
        "minecraft:blocks_attacks",
        "minecraft:break_sound",
        "minecraft:provides_banner_patterns",
        "minecraft:provides_trim_material",
        "minecraft:tooltip_display",
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
     * Parses a JSON array of tint specifications into [TintSource] instances, reporting PM018 on codec errors.
     */
    fun parseTints(
        element: JsonElement?,
        itemPath: String,
        overrideIndex: Int?,
        issues: MutableList<Issue>,
    ): List<TintSource> {
        if (element == null) return emptyList()
        if (element !is JsonArray) {
            issues += Issue(
                level = IssueLevel.ERROR,
                item = itemPath,
                overrideIndex = overrideIndex,
                message = "Field 'tints' must be a JSON array of tint source objects.",
                rule = MigrationRule.PM018,
                path = itemPath,
            )
            return emptyList()
        }
        val out = mutableListOf<TintSource>()
        for (raw in element) {
            val obj = raw as? JsonObject
            if (obj == null) {
                issues += Issue(
                    level = IssueLevel.ERROR,
                    item = itemPath,
                    overrideIndex = overrideIndex,
                    message = "Tint source entry must be a JSON object.",
                    rule = MigrationRule.PM018,
                    path = itemPath,
                )
                continue
            }
            val rawType = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: ""
            val normType = if (!rawType.contains(':') && rawType.isNotEmpty()) "minecraft:$rawType" else rawType
            when (normType) {
                "minecraft:constant" -> {
                    val v = parsePackedColor(obj["value"], itemPath, overrideIndex, issues)
                    if (v != null) out += TintSource.Constant(v)
                }
                "minecraft:dye" -> {
                    val def = if ("default" in obj) parsePackedColor(obj["default"], itemPath, overrideIndex, issues) else -6265536
                    if (def != null) out += TintSource.Dye(def)
                }
                "minecraft:custom_model_data" -> {
                    val idx = (obj["index"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (idx < 0) {
                        issues += Issue(
                            level = IssueLevel.ERROR,
                            item = itemPath,
                            overrideIndex = overrideIndex,
                            message = "custom_model_data tint source index ($idx) cannot be negative.",
                            rule = MigrationRule.PM018,
                            path = itemPath,
                        )
                        continue
                    }
                    val def = if ("default" in obj) parsePackedColor(obj["default"], itemPath, overrideIndex, issues) else -1
                    if (def != null) out += TintSource.CustomModelData(idx, def)
                }
                "minecraft:potion" -> {
                    val def = if ("default" in obj) parsePackedColor(obj["default"], itemPath, overrideIndex, issues) else -13083194
                    if (def != null) out += TintSource.Potion(def)
                }
                "minecraft:team" -> {
                    val def = if ("default" in obj) parsePackedColor(obj["default"], itemPath, overrideIndex, issues) else -1
                    if (def != null) out += TintSource.Team(def)
                }
                "minecraft:firework" -> {
                    val def = if ("default" in obj) parsePackedColor(obj["default"], itemPath, overrideIndex, issues) else -7697782
                    if (def != null) out += TintSource.Firework(def)
                }
                else -> {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = overrideIndex,
                        message = "Unknown 26.3 tint source type '$rawType'.",
                        rule = MigrationRule.PM018,
                        path = itemPath,
                    )
                }
            }
        }
        return out
    }

    private fun parsePackedColor(
        el: JsonElement?,
        itemPath: String,
        overrideIndex: Int?,
        issues: MutableList<Issue>,
    ): Int? {
        if (el is JsonPrimitive) {
            el.intOrNull?.let { return it }
            val text = el.contentOrNull?.trim()
            if (text != null && text.startsWith("#")) {
                val hex = text.removePrefix("#")
                if (hex.length == 6 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                    return hex.toInt(16)
                }
            }
        } else if (el is JsonArray && el.size == 3) {
            val rgb = el.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            if (rgb.size == 3 && rgb.all { it in 0.0..1.0 }) {
                val r = (rgb[0] * 255.0).toInt().coerceIn(0, 255)
                val g = (rgb[1] * 255.0).toInt().coerceIn(0, 255)
                val b = (rgb[2] * 255.0).toInt().coerceIn(0, 255)
                return (r shl 16) or (g shl 8) or b
            }
        }
        issues += Issue(
            level = IssueLevel.ERROR,
            item = itemPath,
            overrideIndex = overrideIndex,
            message = "Invalid packed RGB color '$el' in tint source; expected integer, '#RRGGBB', or normalized [r, g, b] in 0..1.",
            rule = MigrationRule.PM018,
            path = itemPath,
        )
        return null
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

            val issuesBefore = issues.size
            val tints = parseTints(item["tints"], itemPath, idx, issues)
            val tintHadError = issues.size > issuesBefore && issues.subList(issuesBefore, issues.size).any { it.rule == MigrationRule.PM018 }
            val compositeList = (item["models"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val targetModel = (item["model"] as? JsonPrimitive)?.contentOrNull

            val leafNode: ItemModelNode? = when {
                compositeList != null && compositeList.isNotEmpty() -> {
                    val invalid = compositeList.firstOrNull { !validateComponentId(it) }
                    if (invalid != null) {
                        issues += Issue(
                            level = IssueLevel.ERROR,
                            item = itemPath,
                            overrideIndex = idx,
                            message = "Composite item model entry '$invalid' at index $idx is not a valid resource identifier.",
                            rule = MigrationRule.PM018,
                            path = itemPath,
                        )
                        null
                    } else {
                        val layers = compositeList.mapIndexed { layerIdx, m ->
                            ItemModelNode.Model(m, if (layerIdx == 0) tints else emptyList())
                        }
                        ItemModelNode.Composite(layers)
                    }
                }
                targetModel != null -> {
                    val normModel = if (!targetModel.contains(':')) "minecraft:$targetModel" else targetModel
                    if (!validateComponentId(normModel)) {
                        issues += Issue(
                            level = IssueLevel.ERROR,
                            item = itemPath,
                            overrideIndex = idx,
                            message = "Target item model '$targetModel' at index $idx is not a valid resource identifier.",
                            rule = MigrationRule.PM018,
                            path = itemPath,
                        )
                        null
                    } else {
                        ItemModelNode.Model(targetModel, tints)
                    }
                }
                else -> {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "Component override entry at index $idx missing required 'model' or 'models' field.",
                        rule = MigrationRule.PM014,
                        path = itemPath,
                    )
                    null
                }
            }
            if (leafNode == null || tintHadError) continue

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
                    onTrue = leafNode,
                    onFalse = currentRoot,
                )
                continue
            }

            // Case B: custom_model_data boolean flag condition (flags[flag_index])
            val flagIndex = (item["flag_index"] as? JsonPrimitive)?.intOrNull
            if (flagIndex != null) {
                if (flagIndex < 0) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "custom_model_data flag_index ($flagIndex) at index $idx cannot be negative.",
                        rule = MigrationRule.PM018,
                        path = itemPath,
                    )
                    continue
                }
                currentRoot = ItemModelNode.Condition(
                    property = "minecraft:custom_model_data",
                    index = if (flagIndex == 0) null else flagIndex,
                    onTrue = leafNode,
                    onFalse = currentRoot,
                )
                continue
            }

            // Case C: select on component or custom_model_data string property
            val selectProperty = (item["property"] as? JsonPrimitive)?.contentOrNull
            val whenValues: List<String>? = when (val w = item["when"]) {
                is JsonPrimitive -> w.contentOrNull?.let { listOf(it) }
                is JsonArray -> w.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.takeIf { it.isNotEmpty() }
                else -> null
            }
            if (selectProperty != null && whenValues != null) {
                val normProp = if (!selectProperty.contains(':')) "minecraft:$selectProperty" else selectProperty
                val selectIdx = (item["index"] as? JsonPrimitive)?.intOrNull
                if (selectIdx != null && selectIdx < 0) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "Select index ($selectIdx) at index $idx cannot be negative.",
                        rule = MigrationRule.PM018,
                        path = itemPath,
                    )
                    continue
                }
                val compTarget = (item["component"] as? JsonPrimitive)?.contentOrNull?.let {
                    if (!it.contains(':')) "minecraft:$it" else it
                }
                if (normProp == "minecraft:component" && (compTarget == null || !validateComponentId(compTarget))) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "Select on 'minecraft:component' at index $idx requires a valid 'component' identifier.",
                        rule = MigrationRule.PM014,
                        path = itemPath,
                    )
                    continue
                }
                currentRoot = ItemModelNode.Select(
                    property = normProp,
                    cases = listOf(ItemModelNode.Case(whenValues, leafNode)),
                    fallback = currentRoot,
                    index = selectIdx,
                    component = compTarget,
                )
                continue
            }

            // Case D: direct custom_model_data range entry (floats[index])
            val cmd = (item["custom_model_data"] as? JsonPrimitive)?.doubleOrNull
            if (cmd != null) {
                val floatIdx = (item["index"] as? JsonPrimitive)?.intOrNull ?: 0
                if (floatIdx < 0) {
                    issues += Issue(
                        level = IssueLevel.ERROR,
                        item = itemPath,
                        overrideIndex = idx,
                        message = "custom_model_data float index ($floatIdx) at index $idx cannot be negative.",
                        rule = MigrationRule.PM018,
                        path = itemPath,
                    )
                    continue
                }
                currentRoot = ItemModelNode.RangeDispatch(
                    property = "minecraft:custom_model_data",
                    index = floatIdx,
                    entries = listOf(ItemModelNode.Entry(cmd, leafNode)),
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

                is ItemModelNode.Composite -> {
                    val flat = n.models.map { prune(it) }.flatMap { child ->
                        if (child is ItemModelNode.Composite) child.models else listOf(child)
                    }
                    if (flat.size == 1) flat.single() else ItemModelNode.Composite(flat)
                }

                is ItemModelNode.Condition -> {
                    val trueBranch = prune(n.onTrue)
                    val falseBranch = prune(n.onFalse)

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
                        index = n.index,
                        component = n.component,
                    )
                }
            }
        }

        val optimized = prune(node)
        return TreeOptimizationResult(optimized, pruned, issues)
    }
}
