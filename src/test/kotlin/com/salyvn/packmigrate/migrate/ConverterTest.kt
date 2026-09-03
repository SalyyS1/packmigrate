package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.legacy.LegacyItemModel
import com.salyvn.packmigrate.modern.ItemModelNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Each test hands the converter a legacy file and checks the *shape* of the tree, because the shape is the
 * contract: the vanilla definitions are what the client's own migration produced for these exact items, so
 * matching them is how correctness is defined here.
 */
class ConverterTest {

    private fun convert(item: String, json: String) =
        Converter.convert(LegacyItemModel.parse("minecraft", item, json))

    private fun root(item: String, json: String): ItemModelNode = convert(item, json).definition!!.model

    @Test
    fun `bow becomes one condition with the pull range inside it`() {
        val root = root(
            "bow",
            """
            {"overrides": [
              {"predicate": {"pulling": 1}, "model": "item/bow_pulling_0"},
              {"predicate": {"pulling": 1, "pull": 0.65}, "model": "item/bow_pulling_1"},
              {"predicate": {"pulling": 1, "pull": 0.9}, "model": "item/bow_pulling_2"}
            ]}
            """,
        )
        val condition = root as ItemModelNode.Condition
        assertEquals("minecraft:using_item", condition.property)
        assertEquals(ItemModelNode.Model("minecraft:item/bow"), condition.onFalse)

        val range = condition.onTrue as ItemModelNode.RangeDispatch
        assertEquals("minecraft:use_duration", range.property)
        assertEquals(0.05, range.scale)
        assertEquals(ItemModelNode.Model("minecraft:item/bow_pulling_0"), range.fallback)
        assertEquals(listOf(0.65, 0.9), range.entries.map { it.threshold })
    }

    @Test
    fun `crossbow becomes the vanilla charge_type select`() {
        val root = root(
            "crossbow",
            """
            {"overrides": [
              {"predicate": {"pulling": 1}, "model": "item/crossbow_pulling_0"},
              {"predicate": {"pulling": 1, "pull": 0.58}, "model": "item/crossbow_pulling_1"},
              {"predicate": {"pulling": 1, "pull": 1.0}, "model": "item/crossbow_pulling_2"},
              {"predicate": {"charged": 1}, "model": "item/crossbow_arrow"},
              {"predicate": {"charged": 1, "firework": 1}, "model": "item/crossbow_firework"}
            ]}
            """,
        )
        val select = root as ItemModelNode.Select
        assertEquals("minecraft:charge_type", select.property)
        assertEquals(
            mapOf("arrow" to "minecraft:item/crossbow_arrow", "rocket" to "minecraft:item/crossbow_firework"),
            select.cases.associate { it.whenValues.single() to (it.model as ItemModelNode.Model).model },
        )

        // With nothing loaded the crossbow behaves like a bow: a using_item condition over crossbow/pull.
        val idle = select.fallback as ItemModelNode.Condition
        assertEquals("minecraft:using_item", idle.property)
        val range = idle.onTrue as ItemModelNode.RangeDispatch
        assertEquals("minecraft:crossbow/pull", range.property)
        assertEquals(null, range.scale)
    }

    @Test
    fun `custom_model_data with a conditional-only value falls back to the value below it`() {
        val root = root(
            "paper",
            """
            {"overrides": [
              {"predicate": {"custom_model_data": 1}, "model": "myserver:item/scroll"},
              {"predicate": {"custom_model_data": 2}, "model": "myserver:item/map_fragment"},
              {"predicate": {"custom_model_data": 3, "lefthanded": 1}, "model": "myserver:item/left_scroll"}
            ]}
            """,
        )
        val range = root as ItemModelNode.RangeDispatch
        assertEquals("minecraft:custom_model_data", range.property)
        assertEquals(listOf(1.0, 2.0, 3.0), range.entries.map { it.threshold })

        // At value 3 the legacy list still matched the value-2 override for a right-handed player, so the
        // select's fallback is the map fragment, not the paper itself.
        val select = range.entries.last().model as ItemModelNode.Select
        assertEquals("minecraft:main_hand", select.property)
        assertEquals(listOf("left"), select.cases.single().whenValues)
        assertEquals(ItemModelNode.Model("myserver:item/map_fragment"), select.fallback)
    }

    @Test
    fun `trim_type thresholds become named material cases`() {
        val root = root(
            "diamond_chestplate",
            """
            {"overrides": [
              {"predicate": {"trim_type": 0.2}, "model": "item/diamond_chestplate_iron_trim"},
              {"predicate": {"trim_type": 0.8}, "model": "item/diamond_chestplate_diamond_trim"}
            ]}
            """,
        )
        val select = root as ItemModelNode.Select
        assertEquals("minecraft:trim_material", select.property)
        val byCase = select.cases.flatMap { case -> case.whenValues.map { it to (case.model as ItemModelNode.Model).model } }.toMap()
        // 0.2 catches iron (0.2) up to lapis (0.9)... until 0.8 takes over at diamond.
        assertEquals("minecraft:item/diamond_chestplate_iron_trim", byCase["minecraft:iron"])
        assertEquals("minecraft:item/diamond_chestplate_iron_trim", byCase["minecraft:gold"])
        assertEquals("minecraft:item/diamond_chestplate_diamond_trim", byCase["minecraft:diamond"])
        assertEquals("minecraft:item/diamond_chestplate_diamond_trim", byCase["minecraft:amethyst"])
        // Quartz is 0.1, below the lowest threshold, so it shows the untrimmed model: no case for it.
        assertTrue("minecraft:quartz" !in byCase)
        assertEquals(ItemModelNode.Model("minecraft:item/diamond_chestplate"), select.fallback)
    }

    @Test
    fun `compass gains the lodestone condition vanilla has`() {
        val root = root(
            "compass",
            """
            {"overrides": [
              {"predicate": {"angle": 0.0}, "model": "item/compass_00"},
              {"predicate": {"angle": 0.5}, "model": "item/compass_16"}
            ]}
            """,
        )
        val condition = root as ItemModelNode.Condition
        assertEquals("minecraft:has_component", condition.property)
        assertEquals("minecraft:lodestone_tracker", condition.component)
        assertEquals("lodestone", (condition.onTrue as ItemModelNode.RangeDispatch).target)
        assertEquals("spawn", (condition.onFalse as ItemModelNode.RangeDispatch).target)
    }

    @Test
    fun `clock gains the dimension select vanilla has`() {
        val root = root(
            "clock",
            """
            {"overrides": [
              {"predicate": {"time": 0.0}, "model": "item/clock_00"},
              {"predicate": {"time": 0.5}, "model": "item/clock_32"}
            ]}
            """,
        )
        val select = root as ItemModelNode.Select
        assertEquals("minecraft:context_dimension", select.property)
        assertEquals(listOf("minecraft:overworld"), select.cases.single().whenValues)
        assertEquals("daytime", (select.cases.single().model as ItemModelNode.RangeDispatch).source)
        assertEquals("random", (select.fallback as ItemModelNode.RangeDispatch).source)
    }

    @Test
    fun `brush keeps its thresholds on use_cycle`() {
        val root = root(
            "brush",
            """
            {"overrides": [
              {"predicate": {"brushing": 0.25}, "model": "item/brush_brushing_0"},
              {"predicate": {"brushing": 0.5}, "model": "item/brush_brushing_1"},
              {"predicate": {"brushing": 0.75}, "model": "item/brush_brushing_2"}
            ]}
            """,
        )
        val range = root as ItemModelNode.RangeDispatch
        assertEquals("minecraft:use_cycle", range.property)
        assertEquals(10.0, range.period)
        assertEquals(0.1, range.scale)
        assertEquals(listOf(0.25, 0.5, 0.75), range.entries.map { it.threshold })
    }

    @Test
    fun `an unknown predicate is refused with an error`() {
        val conversion = convert(
            "stick",
            """{"overrides": [{"predicate": {"glowing": 1}, "model": "item/stick_glow"}]}""",
        )
        assertTrue(conversion.issues.any { it.level == IssueLevel.ERROR && "glowing" in it.message })
        // The override is dropped from the tree, not silently kept.
        assertEquals(ItemModelNode.Model("minecraft:item/stick"), conversion.definition!!.model)
    }

    @Test
    fun `a duplicated plain value warns and the last declaration wins`() {
        val conversion = convert(
            "paper",
            """
            {"overrides": [
              {"predicate": {"custom_model_data": 7}, "model": "myserver:item/first"},
              {"predicate": {"custom_model_data": 7}, "model": "myserver:item/second"}
            ]}
            """,
        )
        assertTrue(conversion.issues.any { it.level == IssueLevel.WARNING && "declared 2 times" in it.message })
        val range = conversion.definition!!.model as ItemModelNode.RangeDispatch
        assertEquals(ItemModelNode.Model("myserver:item/second"), range.entries.single().model)
    }
}
