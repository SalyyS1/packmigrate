package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.modern.ItemModelNode

/**
 * The places where the correct modern definition depends on *which* item the legacy file belonged to.
 *
 * Three legacy predicates hid state that the item itself supplied. `pull` measured a 20-tick draw on a bow
 * but the charge time on a crossbow. `angle` followed a lodestone when the compass had one and world spawn
 * otherwise. `time` read the day cycle in the overworld and random noise anywhere else. The modern format
 * makes each of those explicit — a different property, a `target`, a `source` — so the migration has to say
 * out loud what the legacy predicate decided silently. Every shape here was copied from the vanilla 26.3
 * definition of the item in question.
 */
object ItemQuirks {

    /** The mapping to use for [info] on [item]. */
    fun propertyFor(item: String, info: PredicateInfo): PredicateInfo =
        if (info.legacy == "pull" && item == "crossbow") {
            info.copy(
                property = "minecraft:crossbow/pull",
                scale = null,
                note = "A crossbow's legacy pull ran over its charge time, which is what vanilla crossbow.json reads as minecraft:crossbow/pull.",
            )
        } else {
            info
        }

    /**
     * Wraps a finished `range_dispatch` so it aims at what the legacy predicate aimed at. [note] receives a
     * line for the report whenever the wrapping adds behaviour the author never wrote down.
     */
    fun wrapRange(
        item: String,
        info: PredicateInfo,
        dispatch: ItemModelNode.RangeDispatch,
        note: (String) -> Unit,
    ): ItemModelNode = when (info.property) {
        "minecraft:compass" -> compass(item, dispatch, note)
        "minecraft:time" -> clock(dispatch, note)
        else -> dispatch
    }

    /** Reports what a select cannot carry over, without changing the tree. */
    fun noteGaps(info: PredicateInfo, note: (String) -> Unit) {
        if (info.property == "minecraft:trim_material") {
            note(
                "The legacy file only knows the ten trim materials that existed before 1.21.4. Armour trimmed " +
                    "with a material added later, resin among them, falls through to the untrimmed model; add a case for it.",
            )
        }
    }

    private fun compass(item: String, dispatch: ItemModelNode.RangeDispatch, note: (String) -> Unit): ItemModelNode {
        // minecraft:compass has no default target, so one has to be chosen. A recovery compass only ever
        // pointed at the last death location; an ordinary one preferred a lodestone over spawn.
        if (item == "recovery_compass") {
            note("A recovery compass points at the last death location, so the dispatch uses target 'recovery'.")
            return dispatch.copy(target = "recovery")
        }
        note(
            "A legacy compass pointed at a lodestone when the item had one and at world spawn otherwise. " +
                "The definition keeps both, as vanilla compass.json does: a has_component condition over the same entries, " +
                "with target 'lodestone' on one side and 'spawn' on the other.",
        )
        return ItemModelNode.Condition(
            property = "minecraft:has_component",
            component = "minecraft:lodestone_tracker",
            onTrue = dispatch.copy(target = "lodestone"),
            onFalse = dispatch.copy(target = "spawn"),
        )
    }

    private fun clock(dispatch: ItemModelNode.RangeDispatch, note: (String) -> Unit): ItemModelNode {
        note(
            "A legacy clock read the day cycle in the overworld and spun at random anywhere else. The definition " +
                "keeps both, as vanilla clock.json does: a context_dimension select over the same entries, with " +
                "source 'daytime' in the overworld and 'random' outside it.",
        )
        return ItemModelNode.Select(
            property = "minecraft:context_dimension",
            cases = listOf(ItemModelNode.Case(listOf("minecraft:overworld"), dispatch.copy(source = "daytime"))),
            fallback = dispatch.copy(source = "random"),
        )
    }
}
