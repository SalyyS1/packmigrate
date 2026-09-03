package com.salyvn.packmigrate.migrate

/**
 * What each legacy predicate became.
 *
 * The legacy `overrides` system had one mechanism — a number compared with `>=` — and a fixed list of
 * predicate names. The modern format splits them by kind: numeric ones are `range_dispatch` properties,
 * on/off ones are `condition` properties, and the ones that were really an enum squeezed into a number
 * (handedness, crossbow charge, trim material, block states) are `select` properties with string cases.
 * This table is the tool's honesty: a name that is not in it is refused and reported, never dropped.
 *
 * Every row was checked against the 26.3 vanilla definitions in misode/mcmeta — `bow.json` for
 * `using_item` + `use_duration`, `crossbow.json`, `compass.json`, `clock.json`, `brush.json`, `light.json`,
 * `beehive.json`, the trimmed armour files — and against the 1.21.3 files they replaced. Where vanilla
 * migrated an item itself, its thresholds are the same numbers the legacy file used, which is why a legacy
 * threshold carries over untouched and the unit difference is expressed with `scale` instead.
 */
enum class PredicateKind { NUMERIC, BOOLEAN, SELECT }

/** `item_model_index` of each trim material that existed in 1.21.3, from the vanilla `trim_material` registry. */
private val TRIM_MATERIALS: List<Pair<String, Double>> = listOf(
    "minecraft:quartz" to 0.1,
    "minecraft:iron" to 0.2,
    "minecraft:netherite" to 0.3,
    "minecraft:redstone" to 0.4,
    "minecraft:copper" to 0.5,
    "minecraft:gold" to 0.6,
    "minecraft:emerald" to 0.7,
    "minecraft:diamond" to 0.8,
    "minecraft:lapis" to 0.9,
    "minecraft:amethyst" to 1.0,
)

/**
 * A legacy predicate whose modern property is a string `select`.
 *
 * [values] is every value the modern property can report, each with the number the legacy predicate
 * returned in that state, so a list of legacy thresholds can be turned into `cases`. [absentValue] is the
 * number the legacy predicate returned when the item had no such state at all; the modern property reports
 * that as "no case matched", so [absentValue] is what the select's `fallback` branch is built from.
 */
data class SelectValues(
    val property: String,
    val values: List<Pair<String, Double>>,
    val absentValue: Double,
    val blockStateProperty: String? = null,
)

data class PredicateInfo(
    val legacy: String,
    val kind: PredicateKind,
    val property: String,
    /** For a numeric predicate whose modern property counts in different units, what the property is multiplied by. */
    val scale: Double? = null,
    /** `minecraft:use_cycle` only: the length of the cycle, in ticks. */
    val period: Double? = null,
    val select: SelectValues? = null,
    val note: String,
)

object PredicateMapping {
    private fun numeric(legacy: String, property: String, note: String, scale: Double? = null, period: Double? = null) =
        PredicateInfo(legacy, PredicateKind.NUMERIC, property, scale = scale, period = period, note = note)

    private fun boolean(legacy: String, property: String, note: String) =
        PredicateInfo(legacy, PredicateKind.BOOLEAN, property, note = note)

    private fun select(legacy: String, values: SelectValues, note: String) =
        PredicateInfo(legacy, PredicateKind.SELECT, values.property, select = values, note = note)

    private val TABLE: Map<String, PredicateInfo> = listOf(
        numeric("custom_model_data", "minecraft:custom_model_data",
            "Reads floats[0] of the custom_model_data component, which is where the game's own data fixer puts a legacy integer."),
        numeric("damage", "minecraft:damage",
            "Legacy damage was damage / max_damage; the modern property normalizes the same way unless told not to."),
        boolean("damaged", "minecraft:damaged", "Direct equivalent."),
        boolean("broken", "minecraft:broken",
            "An elytra past its last point of durability; vanilla elytra.json reads minecraft:broken."),
        boolean("pulling", "minecraft:using_item", "pulling: 1 meant 'is being used', which is minecraft:using_item."),
        // A crossbow is the exception: its legacy pull ran over the charge time, which is what
        // minecraft:crossbow/pull reports. ItemQuirks swaps the property for that one item.
        numeric("pull", "minecraft:use_duration",
            "Legacy pull was use ticks / 20; vanilla bow.json reads use_duration in ticks and scales it by 0.05.",
            scale = 0.05),
        select("charged", SelectValues("minecraft:charge_type", listOf("none" to 0.0, "arrow" to 1.0, "rocket" to 1.0), absentValue = 0.0),
            "charged: 1 covered any loaded projectile; minecraft:charge_type says which one, so it becomes the cases 'arrow' and 'rocket'."),
        select("firework", SelectValues("minecraft:charge_type", listOf("none" to 0.0, "arrow" to 0.0, "rocket" to 1.0), absentValue = 0.0),
            "firework: 1 is charge_type 'rocket'."),
        boolean("blocking", "minecraft:using_item", "Shield blocking is 'is being used'."),
        boolean("throwing", "minecraft:using_item", "Trident throwing is 'is being used'."),
        boolean("cast", "minecraft:fishing_rod/cast", "Direct equivalent."),
        // Legacy clocks read the day cycle in the overworld and spun at random anywhere else; ItemQuirks
        // rebuilds that with the context_dimension select vanilla clock.json uses.
        numeric("time", "minecraft:time", "Clock time is a 0..1 turn of the day in both formats."),
        // Legacy compasses followed a lodestone when the item had one and spawn otherwise; ItemQuirks
        // rebuilds that with the has_component condition vanilla compass.json uses.
        numeric("angle", "minecraft:compass", "Compass angle is a 0..1 turn in both formats."),
        numeric("cooldown", "minecraft:cooldown", "Both report the remaining cooldown as 0..1."),
        select("level", SelectValues("minecraft:block_state", (0..15).map { it.toString() to it / 16.0 }, absentValue = 1.0, blockStateProperty = "level"),
            "Legacy level was a light block's level / 16; the modern select reads that level out of the block_state component. " +
                "Vanilla's own light.json puts the level-15 model in the fallback, which is what an item with no block_state shows."),
        numeric("filled", "minecraft:bundle/fullness",
            "Both report the weight of the bundle_contents component, where a full bundle is 1."),
        boolean("tooting", "minecraft:using_item", "Goat horn tooting is 'is being used'."),
        numeric("brushing", "minecraft:use_cycle",
            "Vanilla brush.json reads use_cycle with period 10 and scale 0.1 and keeps the legacy thresholds, so they carry over as they are.",
            scale = 0.1, period = 10.0),
        select("honey_level", SelectValues("minecraft:block_state", (0..5).map { it.toString() to it / 5.0 }, absentValue = 0.0, blockStateProperty = "honey_level"),
            "Legacy honey_level was a beehive's honey_level / 5; the modern select reads it out of the block_state component."),
        select("lefthanded", SelectValues("minecraft:main_hand", listOf("left" to 1.0, "right" to 0.0), absentValue = 0.0),
            "lefthanded: 1 is main_hand 'left'."),
        select("trim_type", SelectValues("minecraft:trim_material", TRIM_MATERIALS, absentValue = 0.0),
            "Legacy trim_type was the trim material's item_model_index; the modern select names the material. " +
                "Materials added after 1.21.3, resin among them, are not in the legacy file and show the untrimmed model."),
    ).associateBy { it.legacy }

    fun of(name: String): PredicateInfo? = TABLE[name]

    val known: Set<String> get() = TABLE.keys
}
