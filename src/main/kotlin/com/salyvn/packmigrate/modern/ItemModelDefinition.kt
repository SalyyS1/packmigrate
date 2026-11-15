package com.salyvn.packmigrate.modern

import com.salyvn.packmigrate.core.Json

/**
 * Tint source attached to a `minecraft:model` leaf in 1.21.4 through 26.3 item definitions.
 */
sealed class TintSource {
    abstract fun toJson(): Json

    data class Constant(val value: Int) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:constant"),
            "value" to Json.Num(value),
        )
    }

    data class Dye(val defaultColor: Int = -6265536) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:dye"),
            "default" to Json.Num(defaultColor),
        )
    }

    data class CustomModelData(val index: Int = 0, val defaultColor: Int = -1) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:custom_model_data"))
                if (index != 0) add("index" to Json.Num(index))
                add("default" to Json.Num(defaultColor))
            },
        )
    }

    data class Potion(val defaultColor: Int = -13083194) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:potion"),
            "default" to Json.Num(defaultColor),
        )
    }

    data class Team(val defaultColor: Int = -1) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:team"),
            "default" to Json.Num(defaultColor),
        )
    }

    data class Firework(val defaultColor: Int = -7697782) : TintSource() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:firework"),
            "default" to Json.Num(defaultColor),
        )
    }
}

/**
 * The 1.21.4 through 26.3+ item model definition tree, `assets/<ns>/items/<item>.json`.
 *
 * A `range_dispatch` picks the entry with the largest `threshold` that is `<=` the property's value
 * (scaled by `scale`), and falls back when none is; a `condition` chooses `on_true`/`on_false`;
 * a `select` matches `cases[].when` against a string or component property; and a `composite` layers
 * multiple sub-models in order.
 */
sealed class ItemModelNode {
    abstract fun toJson(): Json

    /** `{"type": "minecraft:model", "model": "ns:item/foo", "tints": [...]}` - a leaf. */
    data class Model(
        val model: String,
        val tints: List<TintSource> = emptyList(),
    ) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:model"))
                add("model" to Json.Text(model))
                if (tints.isNotEmpty()) {
                    add("tints" to Json.Arr(tints.map { it.toJson() }))
                }
            },
        )
    }

    /** `{"type": "minecraft:composite", "models": [...]}` - multi-layer 1.21.4..26.3 composite model. */
    data class Composite(val models: List<ItemModelNode>) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            "type" to Json.Text("minecraft:composite"),
            "models" to Json.Arr(models.map { it.toJson() }),
        )
    }

    /** A threshold with the node it selects. */
    data class Entry(val threshold: Double, val model: ItemModelNode)

    /**
     * `range_dispatch` on a numeric property. For `minecraft:custom_model_data` the property reads
     * `floats[index]` of the item's component, which is where a legacy integer `CustomModelData` lands.
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
     * `condition` on a boolean property. `component` is the argument of `minecraft:has_component`,
     * and `index` selects `flags[index]` on `minecraft:custom_model_data`.
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
     * `select` on a string or component property (`minecraft:custom_model_data` at `strings[index]`,
     * `minecraft:component` matching `component`, `minecraft:block_state`, `minecraft:main_hand`, etc.).
     */
    data class Select(
        val property: String,
        val cases: List<Case>,
        val fallback: ItemModelNode?,
        val blockStateProperty: String? = null,
        val index: Int? = null,
        val component: String? = null,
    ) : ItemModelNode() {
        override fun toJson(): Json = Json.Obj(
            buildList {
                add("type" to Json.Text("minecraft:select"))
                add("property" to Json.Text(property))
                index?.let { add("index" to Json.Num(it)) }
                component?.let { add("component" to Json.Text(it)) }
                blockStateProperty?.let { add("block_state_property" to Json.Text(it)) }
                add("cases" to Json.Arr(cases.map { it.toJson() }))
                fallback?.let { add("fallback" to it.toJson()) }
            },
        )
    }
}

/**
 * Top-level `assets/<ns>/items/<item>.json` file across Minecraft 1.21.4 through 26.3 (`format 97`).
 */
data class ItemModelDefinition(
    val model: ItemModelNode,
    val handAnimationOnSwap: Boolean? = null,
    val oversizedInGui: Boolean? = null,
    val swapAnimationScale: Double? = null,
) {
    fun render(): String = Json.Obj(
        buildList {
            handAnimationOnSwap?.let { add("hand_animation_on_swap" to Json.Bool(it)) }
            oversizedInGui?.let { add("oversized_in_gui" to Json.Bool(it)) }
            swapAnimationScale?.let { add("swap_animation_scale" to Json.Dec(it)) }
            add("model" to model.toJson())
        },
    ).render() + "\n"
}
