package com.salyvn.packmigrate.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PackMetaTest {

    @Test
    fun `a modern-only range writes min and max and nothing else`() {
        val text = PackMeta.render("p", PackFormat(84), PackFormat(97, 1))
        assertTrue("\"min_format\": 84" in text)
        assertTrue("\"max_format\": [" in text)
        assertTrue("pack_format" !in text)
        assertTrue("supported_formats" !in text)
    }

    @Test
    fun `a range that spans the era boundary carries all four fields`() {
        val text = PackMeta.render("p", PackFormat(46), PackFormat(97))
        assertTrue("\"pack_format\": 46" in text)
        assertTrue("supported_formats" in text)
        assertTrue("\"min_format\": 46" in text)
        assertTrue("\"max_format\": 97" in text)
    }

    @Test
    fun `a multi-version pack cannot start below format 15`() {
        assertThrows(PackMetaException::class.java) { PackMeta.render("p", PackFormat(9), PackFormat(97)) }
    }

    @Test
    fun `version names, 26_3, and tuple formats parse to the expected ranges`() {
        assertEquals(PackFormats.parseRange("46..88"), PackFormats.parseRange("1.21.4..26.2"))
        assertEquals(PackFormats.parseRange("46..97"), PackFormats.parseRange("1.21.4..26.3"))
        assertEquals(PackFormat(84) to PackFormat(97, 1), PackFormats.parseRange("26.1..[97, 1]"))
        val (min, max) = PackFormats.parseRange("97.1")
        assertEquals(PackFormat(97, 1), min)
        assertEquals(min, max)
    }

    @Test
    fun `migrateExisting upgrades legacy overlays and preserves filter section`() {
        val existing = """
            {
              "pack": {
                "pack_format": 34,
                "description": {"text": "Custom Pack", "color": "gold"}
              },
              "overlays": {
                "entries": [
                  {"formats": [34, 42], "directory": "v1_21_backport"},
                  {"formats": {"min_inclusive": 46, "max_inclusive": 64}, "directory": "v1_21_4_plus"}
                ]
              },
              "filter": {
                "block": [{"namespace": "minecraft", "path": "recipes/.*"}]
              }
            }
        """.trimIndent()

        val result = PackMeta.migrateExisting(existing, PackFormat(15), PackFormat(97, 1))
        assertEquals(listOf("v1_21_backport", "v1_21_4_plus"), result.validOverlayDirectories)
        assertEquals(2, result.findings.count { it.kind == PackMetaFindingKind.OVERLAY_ERA_MIGRATED })
        assertTrue("\"filter\":" in result.rendered)
        assertTrue("\"color\": \"gold\"" in result.rendered)
        // Bounded backport stays capped at 42 while gaining min_format/max_format
        assertTrue("\"max_format\": 42" in result.rendered)
        // Open-ended 64 overlay extends to [97, 1]
        assertTrue("97" in result.rendered)

        // Second pass over migrated text is byte-for-byte idempotent with zero new upgrade notes
        val rerun = PackMeta.migrateExisting(result.rendered, PackFormat(15), PackFormat(97, 1))
        assertEquals(result.rendered, rerun.rendered)
        assertTrue(rerun.findings.isEmpty())
    }

    @Test
    fun `invalid overlay directory names are flagged`() {
        assertFalse(PackMeta.isValidOverlayDirectory("../escape"))
        assertFalse(PackMeta.isValidOverlayDirectory("v1.21.4"))
        assertFalse(PackMeta.isValidOverlayDirectory("UpperCase"))
        assertTrue(PackMeta.isValidOverlayDirectory("overlay_26_3-a"))

        val existing = """
            {
              "pack": {"pack_format": 46, "description": "bad overlay"},
              "overlays": {"entries": [{"formats": [46, 64], "directory": "../escape"}]}
            }
        """.trimIndent()
        val result = PackMeta.migrateExisting(existing, PackFormat(46), PackFormat(97))
        assertEquals(1, result.findings.count { it.kind == PackMetaFindingKind.INVALID_OVERLAY_DIRECTORY })
    }
}
