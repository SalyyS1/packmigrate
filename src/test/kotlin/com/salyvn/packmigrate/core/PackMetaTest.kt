package com.salyvn.packmigrate.core

import org.junit.jupiter.api.Assertions.assertEquals
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
        val text = PackMeta.render("p", PackFormat(46), PackFormat(88))
        assertTrue("\"pack_format\": 46" in text)
        assertTrue("supported_formats" in text)
        assertTrue("\"min_format\": 46" in text)
        assertTrue("\"max_format\": 88" in text)
    }

    @Test
    fun `a multi-version pack cannot start below format 15`() {
        assertThrows(PackMetaException::class.java) { PackMeta.render("p", PackFormat(9), PackFormat(88)) }
    }

    @Test
    fun `version names and raw formats parse to the same range`() {
        assertEquals(PackFormats.parseRange("46..88"), PackFormats.parseRange("1.21.4..26.2"))
        val (min, max) = PackFormats.parseRange("97.1")
        assertEquals(PackFormat(97, 1), min)
        assertEquals(min, max)
    }
}
