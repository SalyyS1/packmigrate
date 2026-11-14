package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.modern.ItemModelNode

/** One legacy override, carrying the predicates that are still undecided on the branch being built. */
internal data class Candidate(val index: Int, val model: String, val remaining: Map<String, Double>)

/**
 * Turns a list of legacy overrides into an item model definition tree.
 */
internal class PredicateTreeBuilder(
    private val itemName: String,
    private val label: String,
    private val sourcePath: String,
    private val issues: MutableList<Issue>,
) {

    fun build(candidates: List<Candidate>, base: ItemModelNode): ItemModelNode {
        val settledAt = candidates.indexOfLast { it.remaining.isEmpty() }
        val fallback = if (settledAt >= 0) ItemModelNode.Model(candidates[settledAt].model) else base
        val rest = if (settledAt >= 0) candidates.drop(settledAt + 1) else candidates
        if (rest.isEmpty()) return fallback

        val chosen = choose(rest)
        return when (chosen.kind) {
            PredicateKind.NUMERIC -> range(chosen, rest, fallback)
            PredicateKind.BOOLEAN -> condition(sharing(rest, chosen), rest, fallback)
            PredicateKind.SELECT -> select(sharing(rest, chosen), rest, fallback)
        }
    }

    private fun choose(rest: List<Candidate>): PredicateInfo = active(rest)
        .sortedWith(compareBy({ rank(it) }, { -lastIndex(rest, it.legacy) }, { it.legacy }))
        .first()

    private fun rank(info: PredicateInfo): Int = when {
        info.legacy == "custom_model_data" -> 0
        info.kind == PredicateKind.SELECT -> 1
        info.kind == PredicateKind.BOOLEAN -> 2
        else -> 3
    }

    private fun active(rest: List<Candidate>): List<PredicateInfo> =
        rest.flatMap { it.remaining.keys }.distinct().map { info(it) }

    private fun sharing(rest: List<Candidate>, chosen: PredicateInfo): List<PredicateInfo> =
        active(rest).filter { it.property == chosen.property && it.kind == chosen.kind }

    private fun lastIndex(rest: List<Candidate>, name: String): Int =
        rest.filter { name in it.remaining }.maxOf { it.index }

    private fun info(name: String): PredicateInfo = ItemQuirks.propertyFor(itemName, PredicateMapping.of(name)!!)

    private fun note(message: String) {
        if (issues.none { it.message == message && it.path == sourcePath }) {
            issues += Issue(
                level = IssueLevel.NOTE,
                item = label,
                overrideIndex = null,
                message = message,
                rule = MigrationRule.PM007,
                path = sourcePath,
            )
        }
    }

    private fun range(info: PredicateInfo, rest: List<Candidate>, base: ItemModelNode): ItemModelNode {
        val name = info.legacy
        val arrivals = rest.filter { name in it.remaining }.groupBy { it.remaining.getValue(name) }
        val free = rest.filter { name !in it.remaining }
        val below = build(free, base)

        val entries = mutableListOf<ItemModelNode.Entry>()
        var inPlay = free
        var branchBase = base
        var previous = below
        arrivals.keys.sorted().forEach { threshold ->
            val arrived = arrivals.getValue(threshold).map { it.copy(remaining = it.remaining - name) }
            inPlay = (inPlay + arrived).sortedBy { it.index }
            val settledAt = inPlay.indexOfLast { it.remaining.isEmpty() }
            if (settledAt >= 0) {
                branchBase = ItemModelNode.Model(inPlay[settledAt].model)
                inPlay = inPlay.drop(settledAt + 1)
            }
            val node = build(inPlay, branchBase)
            if (node != previous) {
                entries += ItemModelNode.Entry(threshold, node)
                previous = node
            }
        }
        if (entries.isEmpty()) return below

        val dispatch = ItemModelNode.RangeDispatch(
            property = info.property,
            entries = entries,
            fallback = below,
            scale = info.scale,
            period = info.period,
        )
        return ItemQuirks.wrapRange(itemName, info, dispatch, ::note)
    }

    private fun condition(group: List<PredicateInfo>, rest: List<Candidate>, base: ItemModelNode): ItemModelNode {
        val names = group.map { it.legacy }
        val onTrue = build(restrict(rest, names.associateWith { 1.0 }), base)
        val onFalse = build(restrict(rest, names.associateWith { 0.0 }), base)
        if (onTrue == onFalse) return onTrue
        return ItemModelNode.Condition(group.first().property, onTrue = onTrue, onFalse = onFalse)
    }

    private fun select(group: List<PredicateInfo>, rest: List<Candidate>, base: ItemModelNode): ItemModelNode {
        group.forEach { ItemQuirks.noteGaps(it, ::note) }
        val fallback = build(restrict(rest, group.associate { it.legacy to it.select!!.absentValue }), base)

        val byNode = LinkedHashMap<ItemModelNode, MutableList<String>>()
        group.flatMap { info -> info.select!!.values.map { it.first } }.distinct().forEach { value ->
            val scored = group.associate { info ->
                val values = info.select!!
                info.legacy to (values.values.firstOrNull { it.first == value }?.second ?: values.absentValue)
            }
            val node = build(restrict(rest, scored), base)
            if (node != fallback) byNode.getOrPut(node) { mutableListOf() } += value
        }
        if (byNode.isEmpty()) return fallback

        val values = group.first().select!!
        return ItemModelNode.Select(
            property = values.property,
            cases = byNode.map { (node, whenValues) -> ItemModelNode.Case(whenValues, node) },
            fallback = fallback,
            blockStateProperty = values.blockStateProperty,
        )
    }

    private fun restrict(candidates: List<Candidate>, values: Map<String, Double>): List<Candidate> =
        candidates.mapNotNull { candidate ->
            var remaining = candidate.remaining
            for ((name, value) in values) {
                val threshold = remaining[name] ?: continue
                if (value < threshold) return@mapNotNull null
                remaining = remaining - name
            }
            if (remaining.size == candidate.remaining.size) candidate else candidate.copy(remaining = remaining)
        }
}
