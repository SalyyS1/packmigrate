package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.modern.ItemModelNode

/** One legacy override, carrying the predicates that are still undecided on the branch being built. */
internal data class Candidate(val index: Int, val model: String, val remaining: Map<String, Double>)

/**
 * Turns a list of legacy overrides into an item model definition tree.
 *
 * The legacy rule is one sentence: the *last* override whose every predicate satisfies
 * `value >= threshold` wins. Rather than recognising known file shapes, the builder reproduces that
 * sentence. It picks one property, splits the candidates over the values that property can take, and
 * recurses; on each branch the predicates the branch has decided are dropped, and candidates that cannot
 * match there are removed. A candidate with no predicates left matches unconditionally, so it becomes that
 * branch's fallback and hides every candidate before it.
 *
 * Two things follow from that, and both matter. A bow's `pulling` and `pulling` + `pull` overrides end up
 * as one `condition` with a `range_dispatch` inside it — not two layers where the inner range is
 * unreachable — because they are the same set of candidates seen at different depths. And a
 * `custom_model_data` entry whose only other override needs a second predicate falls back to the *lower*
 * custom_model_data model, exactly as the legacy list did, instead of to the item's own model.
 */
internal class PredicateTreeBuilder(
    private val itemName: String,
    private val label: String,
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

    /**
     * Which property to branch on. `custom_model_data` goes outermost or every other property would be
     * repeated under each of its hundreds of entries; then the discrete branches, with the range innermost,
     * which is the order vanilla's own definitions use. Between two of the same kind the one carrying the
     * later override goes outside, so that a later override keeps winning.
     */
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

    /** Legacy names that share [chosen]'s property, so a crossbow's `charged` and `firework` become one select. */
    private fun sharing(rest: List<Candidate>, chosen: PredicateInfo): List<PredicateInfo> =
        active(rest).filter { it.property == chosen.property && it.kind == chosen.kind }

    private fun lastIndex(rest: List<Candidate>, name: String): Int =
        rest.filter { name in it.remaining }.maxOf { it.index }

    private fun info(name: String): PredicateInfo = ItemQuirks.propertyFor(itemName, PredicateMapping.of(name)!!)

    private fun note(message: String) {
        if (issues.none { it.message == message }) issues += Issue(IssueLevel.NOTE, label, null, message)
    }

    /**
     * A `range_dispatch` on one numeric property.
     *
     * Thresholds are walked in ascending order because that is how the in-play set grows: at the property's
     * value `t` every candidate whose threshold is `<= t` can match, so the set only ever gains members, and
     * a plain candidate arriving in it settles the branch and clears everything below. Carrying that state
     * across the loop is what keeps a file of a thousand `custom_model_data` overrides linear.
     */
    private fun range(info: PredicateInfo, rest: List<Candidate>, base: ItemModelNode): ItemModelNode {
        val name = info.legacy
        val arrivals = rest.filter { name in it.remaining }.groupBy { it.remaining.getValue(name) }
        // Candidates that do not test this property at all are in play at every value, the fallback included.
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
            // An entry that selects what the range already selects below it changes nothing; leave it out.
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
        // `pulling: 0` was satisfied by every state, so both branches come out the same; drop the condition.
        if (onTrue == onFalse) return onTrue
        return ItemModelNode.Condition(group.first().property, onTrue = onTrue, onFalse = onFalse)
    }

    /**
     * A `select` on a string property. Each value the property can report is scored with the number the
     * legacy predicate would have returned in that state, the tree for it is built, and values that end up
     * selecting the same node share one case. Values that select what the fallback already selects are left
     * out entirely, which is how a crossbow's `charge_type` select comes out with the two cases vanilla has.
     */
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

    /** Fixes [values] for the named predicates: candidates that need more than that are out, the rest move on. */
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
