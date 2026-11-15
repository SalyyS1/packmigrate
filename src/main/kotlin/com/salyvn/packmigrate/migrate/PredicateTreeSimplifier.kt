package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.modern.ItemModelNode

data class SimplificationResult(
    val root: ItemModelNode,
    val stepKnotsRemoved: Int,
    val cofactorsFolded: Int,
    val intervalsTightened: Int,
    val compositesFlattened: Int,
    val issues: List<Issue>,
) {
    val totalReductions: Int
        get() = stepKnotsRemoved + cofactorsFolded + intervalsTightened + compositesFlattened
}

/**
 * Algebraic simplifier for 1.21.4..26.3 item model decision trees (`--simplify-trees`, rule PM019).
 *
 * Performs four semantics-preserving compiler passes in one top-down/bottom-up traversal:
 * 1. Path-sensitive Shannon cofactor folding on repeated `Condition` (`(property, index, component)`) and
 *    `Select` (`(property, blockStateProperty, index, component)`) predicates along ancestor branches.
 * 2. Numeric interval narrowing `[lower, upper)` across nested `RangeDispatch` nodes on the same property.
 * 3. Step-knot coalescing on sorted `RangeDispatch` entries (`entries[0].model == fallback` for any threshold
 *    and adjacent `entries[i].model == entries[i - 1].model`) plus `Select` case unioning.
 * 4. Nested `Composite` flattening and singleton unwrapping.
 */
object PredicateTreeSimplifier {

    private data class BoolKey(val property: String, val index: Int?, val component: String?)

    private data class SelectKey(
        val property: String,
        val blockStateProperty: String?,
        val index: Int?,
        val component: String?,
    )

    private data class RangeKey(
        val property: String,
        val index: Int?,
        val scale: Double?,
        val target: String?,
        val period: Double?,
        val source: String?,
    )

    private data class SelectDomain(
        val boundValue: String? = null,
        val excludedValues: Set<String> = emptySet(),
    )

    private data class RangeInterval(
        val lowerInclusive: Double = Double.NEGATIVE_INFINITY,
        val upperExclusive: Double = Double.POSITIVE_INFINITY,
    )

    private data class PathContext(
        val boolBindings: Map<BoolKey, Boolean> = emptyMap(),
        val selectBindings: Map<SelectKey, SelectDomain> = emptyMap(),
        val rangeBounds: Map<RangeKey, RangeInterval> = emptyMap(),
    )

    fun simplify(root: ItemModelNode, itemPath: String, itemLabel: String = itemPath): SimplificationResult {
        var stepKnots = 0
        var cofactors = 0
        var intervals = 0
        var composites = 0

        fun rewrite(node: ItemModelNode, ctx: PathContext): ItemModelNode {
            return when (node) {
                is ItemModelNode.Model -> node

                is ItemModelNode.Composite -> {
                    val children = mutableListOf<ItemModelNode>()
                    for (child in node.models) {
                        val simplified = rewrite(child, ctx)
                        if (simplified is ItemModelNode.Composite) {
                            composites++
                            children += simplified.models
                        } else {
                            children += simplified
                        }
                    }
                    if (children.size == 1) {
                        composites++
                        children.single()
                    } else {
                        ItemModelNode.Composite(children)
                    }
                }

                is ItemModelNode.Condition -> {
                    val key = BoolKey(node.property, node.index, node.component)
                    val known = ctx.boolBindings[key]
                    if (known != null) {
                        cofactors++
                        return if (known) rewrite(node.onTrue, ctx) else rewrite(node.onFalse, ctx)
                    }

                    val onTrue = rewrite(node.onTrue, ctx.copy(boolBindings = ctx.boolBindings + (key to true)))
                    val onFalse = rewrite(node.onFalse, ctx.copy(boolBindings = ctx.boolBindings + (key to false)))
                    if (onTrue == onFalse) {
                        cofactors++
                        onTrue
                    } else {
                        ItemModelNode.Condition(
                            property = node.property,
                            onTrue = onTrue,
                            onFalse = onFalse,
                            index = node.index,
                            component = node.component,
                        )
                    }
                }

                is ItemModelNode.Select -> {
                    val key = SelectKey(node.property, node.blockStateProperty, node.index, node.component)
                    val dom = ctx.selectBindings[key] ?: SelectDomain()

                    if (dom.boundValue != null) {
                        cofactors++
                        val matched = node.cases.firstOrNull { dom.boundValue in it.whenValues }
                        return when {
                            matched != null -> rewrite(matched.model, ctx)
                            node.fallback != null -> rewrite(node.fallback, ctx)
                            else -> node
                        }
                    }

                    val claimedValues = mutableSetOf<String>()
                    val survivingCases = mutableListOf<Pair<List<String>, ItemModelNode>>()

                    for (c in node.cases) {
                        val reachableWhen = c.whenValues.distinct().filter { it !in dom.excludedValues && it !in claimedValues }
                        if (reachableWhen.isEmpty()) {
                            cofactors++
                            continue
                        }
                        if (reachableWhen.size < c.whenValues.distinct().size) {
                            cofactors++
                        }
                        claimedValues += reachableWhen

                        val childDomain = if (reachableWhen.size == 1) {
                            SelectDomain(boundValue = reachableWhen.single())
                        } else {
                            SelectDomain(excludedValues = dom.excludedValues)
                        }
                        val sub = rewrite(c.model, ctx.copy(selectBindings = ctx.selectBindings + (key to childDomain)))
                        survivingCases += reachableWhen to sub
                    }

                    val fallbackDomain = SelectDomain(excludedValues = dom.excludedValues + claimedValues)
                    val simplifiedFallback = node.fallback?.let {
                        rewrite(it, ctx.copy(selectBindings = ctx.selectBindings + (key to fallbackDomain)))
                    }

                    // Coalesce identical case subtrees and prune cases equal to fallback
                    val byModel = LinkedHashMap<ItemModelNode, MutableList<String>>()
                    for ((whenVals, subModel) in survivingCases) {
                        if (simplifiedFallback != null && subModel == simplifiedFallback) {
                            stepKnots++
                            continue
                        }
                        byModel.getOrPut(subModel) { mutableListOf() }.addAll(whenVals)
                    }

                    if (byModel.isEmpty() && simplifiedFallback != null) {
                        return simplifiedFallback
                    }

                    val mergedCases = byModel.map { (m, vals) -> ItemModelNode.Case(vals.distinct(), m) }
                    if (mergedCases.size < survivingCases.size && byModel.isNotEmpty()) {
                        stepKnots += (survivingCases.size - mergedCases.size)
                    }

                    ItemModelNode.Select(
                        property = node.property,
                        cases = mergedCases,
                        fallback = simplifiedFallback,
                        blockStateProperty = node.blockStateProperty,
                        index = node.index,
                        component = node.component,
                    )
                }

                is ItemModelNode.RangeDispatch -> {
                    val key = RangeKey(node.property, node.index, node.scale, node.target, node.period, node.source)
                    val outer = ctx.rangeBounds[key] ?: RangeInterval()

                    // Deduplicate identical thresholds keeping last (Minecraft range_dispatch last-entry tie semantics)
                    val dedupedSorted = LinkedHashMap<Double, ItemModelNode>()
                    for (entry in node.entries.sortedBy { it.threshold }) {
                        if (entry.threshold in dedupedSorted) {
                            stepKnots++
                        }
                        dedupedSorted[entry.threshold] = entry.model
                    }
                    val ordered = dedupedSorted.entries.map { ItemModelNode.Entry(it.key, it.value) }

                    // Interval narrowing against outer [lowerInclusive, upperExclusive)
                    var effectiveFallbackNode: ItemModelNode? = node.fallback
                    val inRangeEntries = mutableListOf<ItemModelNode.Entry>()

                    for (entry in ordered) {
                        if (entry.threshold >= outer.upperExclusive) {
                            intervals++
                            continue
                        }
                        if (entry.threshold <= outer.lowerInclusive) {
                            // Entry covers the entire lower floor of our reachable interval!
                            effectiveFallbackNode = entry.model
                            intervals++
                            continue
                        }
                        inRangeEntries += entry
                    }

                    val firstThreshold = inRangeEntries.firstOrNull()?.threshold ?: outer.upperExclusive
                    val fallbackBounds = RangeInterval(
                        lowerInclusive = outer.lowerInclusive,
                        upperExclusive = minOf(outer.upperExclusive, firstThreshold),
                    )
                    val simplifiedFallback = effectiveFallbackNode?.let {
                        rewrite(it, ctx.copy(rangeBounds = ctx.rangeBounds + (key to fallbackBounds)))
                    }

                    val coalesced = mutableListOf<ItemModelNode.Entry>()
                    for (i in inRangeEntries.indices) {
                        val curr = inRangeEntries[i]
                        val nextUpper = inRangeEntries.getOrNull(i + 1)?.threshold ?: outer.upperExclusive
                        val entryBounds = RangeInterval(
                            lowerInclusive = maxOf(outer.lowerInclusive, curr.threshold),
                            upperExclusive = minOf(outer.upperExclusive, nextUpper),
                        )
                        val sub = rewrite(curr.model, ctx.copy(rangeBounds = ctx.rangeBounds + (key to entryBounds)))

                        val previousModel: ItemModelNode? = if (coalesced.isEmpty()) simplifiedFallback else coalesced.last().model
                        if (previousModel != null && sub == previousModel) {
                            stepKnots++
                            continue
                        }
                        coalesced += ItemModelNode.Entry(curr.threshold, sub)
                    }

                    if (coalesced.isEmpty() && simplifiedFallback != null) {
                        return simplifiedFallback
                    }

                    ItemModelNode.RangeDispatch(
                        property = node.property,
                        entries = coalesced,
                        fallback = simplifiedFallback,
                        index = node.index,
                        scale = node.scale,
                        target = node.target,
                        period = node.period,
                        source = node.source,
                    )
                }
            }
        }

        val simplified = rewrite(root, PathContext())
        val issues = mutableListOf<Issue>()
        val total = stepKnots + cofactors + intervals + composites
        if (total > 0) {
            val parts = buildList {
                if (stepKnots > 0) add("$stepKnots step knot(s)")
                if (cofactors > 0) add("$cofactors path cofactor(s)")
                if (intervals > 0) add("$intervals interval bound(s)")
                if (composites > 0) add("$composites composite wrapper(s)")
            }
            issues += Issue(
                level = IssueLevel.NOTE,
                item = itemLabel,
                overrideIndex = null,
                message = "Simplified predicate decision tree ($total reduction(s): ${parts.joinToString(", ")}).",
                rule = MigrationRule.PM019,
                path = itemPath,
            )
        }

        return SimplificationResult(
            root = simplified,
            stepKnotsRemoved = stepKnots,
            cofactorsFolded = cofactors,
            intervalsTightened = intervals,
            compositesFlattened = composites,
            issues = issues,
        )
    }
}
