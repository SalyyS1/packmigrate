package com.salyvn.packmigrate.modern

import com.salyvn.packmigrate.core.Json

/**
 * The 1.21.4+ item model definition tree, `assets/<ns>/items/<item>.json`.
 *
 * Only the node types a migration emits are modelled. Their fields were read from real vanilla files and
 * cross-checked against the client's codecs: a `range_dispatch` picks the entry with the largest
 * `threshold` that is `<=` the property's value (scaled by `scale`), and falls back when none is; a
 * `condition` chooses `on_true`/`on_false`; a `select` matches `cases[].when` against a string property.
 */
sealed class ItemModelNode {
    abstract fun toJson(): Json

    /** `{"type": "minecraft:model", "model": "ns:item/foo"}` — a leaf. */
    data class Model(val model: String) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:model"),
            "model" to Json.Text(model),
        )
    }

    /** A threshold with the node it selects. */
    data class Entry(val threshold: Double, val model: ItemModelNode)

    /**
     * `range_dispatch` on a numeric property. For `minecraft:custom_model_data` the property reads
     * `floats[index]` of the item's component, which is where a legacy integer `CustomModelData` lands.
     *
     * The three property-specific fields are emitted only when set: `target` is required by
     * `minecraft:compass` (`spawn`, `lodestone`, `recovery`, `none`), `period` belongs to
     * `minecraft:use_cycle`, and `source` to `minecraft:time` (`daytime`, `moon_phase`, `random`).
     */
    data class RangeDispatch(
        val property: String,
        val entries: List<Entry>,
        val fallback: ItemModelNode?,
        val index: Int? = null,
        val scale: Double? = null,
        val target: String? = null,
        val period: Double? = null,
        val source: String? = null,
    ) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:range_dispatch"))
                add("property" to Json.Text(property))
                index?.let { add("index" to Json.Num(it)) }
                target?.let { add("target" to Json.Text(it)) }
                source?.let { add("source" to Json.Text(it)) }
                period?.let { add("period" to Json.Dec(it)) }
                scale?.let { add("scale" to Json.Dec(it)) }
                add(
                    "entries" to Json.Arr(
                        entries.sortedBy { it.threshold }.map { entry ->
                            Json.Obj("threshold" to Json.Dec(entry.threshold), "model" to entry.model.toJson())
                        },
                    ),
                )
                fallback?.let { add("fallback" to it.toJson()) }
            },
        )
    }

    /**
     * `condition` on a boolean property. `component` is the argument of `minecraft:has_component`
     * (vanilla compass.json tests for `minecraft:lodestone_tracker`).
     */
    data class Condition(
        val property: String,
        val onTrue: ItemModelNode,
        val onFalse: ItemModelNode,
        val index: Int? = null,
        val component: String? = null,
    ) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:condition"))
                add("property" to Json.Text(property))
                index?.let { add("index" to Json.Num(it)) }
                component?.let { add("component" to Json.Text(it)) }
                add("on_true" to onTrue.toJson())
                add("on_false" to onFalse.toJson())
            },
        )
    }

    /** One `select` case. Vanilla writes `when` as a bare string for one value and an array for several. */
    data class Case(val whenValues: List<String>, val model: ItemModelNode) {
        fun toJson(): Json = Json.Obj(
            "when" to if (whenValues.size == 1) Json.Text(whenValues.single()) else Json.Arr(whenValues.map { Json.Text(it) }),
            "model" to model.toJson(),
        )
    }

    /**
     * `select` on a string property. `blockStateProperty` is only meaningful for `minecraft:block_state`,
     * which reads that key out of the item's `block_state` component (light level, beehive honey level).
     */
    data class Select(
        val property: String,
        val cases: List<Case>,
        val fallback: ItemModelNode?,
        val blockStateProperty: String? = null,
    ) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:select"))
                add("property" to Json.Text(property))
                blockStateProperty?.let { add("block_state_property" to Json.Text(it)) }
                add("cases" to Json.Arr(cases.map { it.toJson() }))
                fallback?.let { add("fallback" to it.toJson()) }
            },
        )
    }
}

/** The file: one root node under `"model"`. */
data class ItemModelDefinition(val model: ItemModelNode) {
    fun render(): String = Json.Obj("model" to model.toJson()).render() + "\n"
}
