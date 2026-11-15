package com.salyvn.packmigrate.migrate

import com.github.ajalt.clikt.core.parse
import com.salyvn.packmigrate.cli.MigrateCommand
import com.salyvn.packmigrate.core.Format97OverlayGenerator
import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.dedup.AssetDeduplicator
import com.salyvn.packmigrate.dedup.DedupOptions
import com.salyvn.packmigrate.dedup.PerceptualHash
import com.salyvn.packmigrate.modern.ComponentOverrideCompiler
import com.salyvn.packmigrate.modern.ItemModelNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class PackMigrateV040Test {

    private fun createPngBytes(width: Int, height: Int, colorRgb: Int, alpha: Int = 255): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val argb = (alpha shl 24) or (colorRgb and 0x00FFFFFF)
        for (y in 0 until height) {
            for (x in 0 until width) {
                img.setRGB(x, y, argb)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "png", baos)
        return baos.toByteArray()
    }

    @Test
    fun `perceptual hash recognizes png and computes identical distance for same raster`() {
        val png1 = createPngBytes(16, 16, 0xFF0000)
        val png2 = createPngBytes(16, 16, 0xFF0000)

        val fp1 = PerceptualHash.inspect(png1)
        val fp2 = PerceptualHash.inspect(png2)

        assertNotNull(fp1)
        assertNotNull(fp2)
        assertEquals(fp1!!.pixelSha256, fp2!!.pixelSha256)
        assertEquals(0, PerceptualHash.distance(fp1, fp2))
        assertTrue(fp1.phashHex.startsWith("0x"))
    }

    @Test
    fun `perceptual hash rejects non png bytes`() {
        val notPng = "Not a PNG file".toByteArray()
        assertFalse(PerceptualHash.isPngBytes(notPng))
        assertNull(PerceptualHash.inspect(notPng))
    }

    @Test
    fun `perceptual hash detects color differences`() {
        val redPng = createPngBytes(16, 16, 0xFF0000)
        val bluePng = createPngBytes(16, 16, 0x0000FF)

        val fpRed = PerceptualHash.inspect(redPng)!!
        val fpBlue = PerceptualHash.inspect(bluePng)!!

        val dist = PerceptualHash.distance(fpRed, fpBlue)
        assertTrue(dist > 0, "Distance between pure red and pure blue should be greater than 0")
    }

    @Test
    fun `asset deduplicator detects redundant overlay assets and duplicate textures`(@TempDir tempDir: Path) {
        // Create base pack
        val baseAssets = tempDir.resolve("assets/minecraft/textures/item")
        Files.createDirectories(baseAssets)
        val swordPng = createPngBytes(16, 16, 0x00FF00)
        Files.write(baseAssets.resolve("sword.png"), swordPng)

        // Create cross-path duplicate texture in base pack
        val customAssets = tempDir.resolve("assets/custom/textures/item")
        Files.createDirectories(customAssets)
        Files.write(customAssets.resolve("blade.png"), swordPng)

        // Create overlay with identical sword.png (redundant)
        val overlayAssets = tempDir.resolve("overlay_1/assets/minecraft/textures/item")
        Files.createDirectories(overlayAssets)
        Files.write(overlayAssets.resolve("sword.png"), swordPng)

        // Create unique overlay file (not redundant)
        Files.write(overlayAssets.resolve("shield.png"), createPngBytes(16, 16, 0x123456))

        val options = DedupOptions(enableDedup = true, enablePhash = true)
        val result = AssetDeduplicator.analyze(tempDir, listOf("overlay_1"), options)

        assertEquals(1, result.redundantOverlayFiles.size)
        assertEquals("overlay_1/assets/minecraft/textures/item/sword.png", result.redundantOverlayFiles.first())
        assertTrue(result.savedBytes > 0)

        // Verify PM012 rule generated for redundant overlay file
        assertTrue(result.issues.any { it.rule == MigrationRule.PM012 })

        // Verify PM013 rule generated for cross-path identical texture
        assertTrue(result.issues.any { it.rule == MigrationRule.PM013 })
    }

    @Test
    fun `component override compiler validates components and compiles has_component`() {
        assertTrue(ComponentOverrideCompiler.validateComponentId("minecraft:enchantments"))
        assertTrue(ComponentOverrideCompiler.validateComponentId("custom:special_ability"))
        assertFalse(ComponentOverrideCompiler.validateComponentId("INVALID_NAME!!"))

        val issues = mutableListOf<Issue>()
        val baseModel = ItemModelNode.Model("minecraft:item/iron_sword")

        val overridesJson = buildJsonArray {
            add(
                buildJsonObject {
                    put("has_component", "minecraft:enchantments")
                    put("model", "custom:item/enchanted_sword")
                },
            )
            add(
                buildJsonObject {
                    put("has_component", "INVALID!!")
                    put("model", "custom:item/invalid_sword")
                },
            )
        }

        val compiled = ComponentOverrideCompiler.compileComponentOverrides(
            overridesJson,
            baseModel,
            "assets/minecraft/models/item/iron_sword.json",
            issues,
        )

        assertTrue(compiled is ItemModelNode.Condition)
        val cond = compiled as ItemModelNode.Condition
        assertEquals("minecraft:has_component", cond.property)
        assertEquals("minecraft:enchantments", cond.component)

        // Verify PM014 generated for invalid component
        assertTrue(issues.any { it.rule == MigrationRule.PM014 })
    }

    @Test
    fun `decision tree optimizer folds tautological conditions and prunes shadowed entries`() {
        // Tautological condition: on_true == on_false
        val tautology = ItemModelNode.Condition(
            property = "minecraft:damaged",
            onTrue = ItemModelNode.Model("custom:item/sword"),
            onFalse = ItemModelNode.Model("custom:item/sword"),
        )
        val optTautology = ComponentOverrideCompiler.optimizeTree(tautology, "test/path.json")
        assertTrue(optTautology.root is ItemModelNode.Model)
        assertEquals(1, optTautology.prunedBranches)
        assertTrue(optTautology.issues.any { it.rule == MigrationRule.PM015 })

        // Shadowed range_dispatch entry
        val shadowedRange = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            index = 0,
            entries = listOf(
                ItemModelNode.Entry(1.0, ItemModelNode.Model("custom:item/model_1")),
                ItemModelNode.Entry(1.0, ItemModelNode.Model("custom:item/model_1_shadow")),
            ),
            fallback = ItemModelNode.Model("custom:item/fallback"),
        )
        val optRange = ComponentOverrideCompiler.optimizeTree(shadowedRange, "test/path.json")
        assertTrue(optRange.root is ItemModelNode.RangeDispatch)
        val rangeNode = optRange.root as ItemModelNode.RangeDispatch
        assertEquals(1, rangeNode.entries.size)
        assertEquals(1, optRange.prunedBranches)
        assertTrue(optRange.issues.any { it.rule == MigrationRule.PM015 })

        // Select case matching fallback
        val redundantSelect = ItemModelNode.Select(
            property = "minecraft:main_hand",
            cases = listOf(
                ItemModelNode.Case(listOf("left"), ItemModelNode.Model("custom:item/fallback")),
            ),
            fallback = ItemModelNode.Model("custom:item/fallback"),
        )
        val optSelect = ComponentOverrideCompiler.optimizeTree(redundantSelect, "test/path.json")
        assertTrue(optSelect.root is ItemModelNode.Model)
        assertEquals(1, optSelect.prunedBranches)
        assertTrue(optSelect.issues.any { it.rule == MigrationRule.PM015 })
    }

    @Test
    fun `format 97 overlay generator creates valid spec and audits bounds`() {
        val spec = Format97OverlayGenerator.createSpec()
        assertEquals("format_97", spec.directory)
        assertEquals(PackFormat(97, 0), spec.min)
        assertEquals(PackFormat(97, 1), spec.max)

        val min = PackFormat(15)
        val max = PackFormat(97, 1)

        val issues = Format97OverlayGenerator.auditFormat97(min, max, emptyList())
        assertTrue(issues.any { it.rule == MigrationRule.PM016 })
    }

    @Test
    fun `sarif report includes PM012 through PM016 rule descriptors`() {
        val root = Path.of("src/test/resources/legacypack")
        val (min, max) = PackFormats.parseRange("1.21.4..26.3")
        val plan = PackMigration(root, min, max).plan(stripOverrides = false)

        val sarifJson = SarifReport.render(plan)
        assertTrue("PM012" in sarifJson)
        assertTrue("PM013" in sarifJson)
        assertTrue("PM014" in sarifJson)
        assertTrue("PM015" in sarifJson)
        assertTrue("PM016" in sarifJson)
        assertTrue("RedundantOverlayAsset" in sarifJson)
        assertTrue("DuplicateAssetTexture" in sarifJson)
        assertTrue("ComponentPredicateMalformed" in sarifJson)
        assertTrue("UnreachableFallbackBranch" in sarifJson)
        assertTrue("Format97OverlayDrift" in sarifJson)
    }

    @Test
    fun `cli supports dedup, phash, and prune-overrides flags`(@TempDir tempDir: Path) {
        val packDir = tempDir.resolve("cleanpack")
        Files.createDirectories(packDir.resolve("assets/minecraft/models/item"))
        Files.createDirectories(packDir.resolve("assets/myserver/models/item"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{"pack": {"pack_format": 46, "description": "Clean test pack"}}""",
        )
        Files.writeString(
            packDir.resolve("assets/myserver/models/item/ruby_sword.json"),
            """{"parent": "minecraft:item/handheld", "textures": {"layer0": "myserver:item/ruby_sword"}}""",
        )
        Files.writeString(
            packDir.resolve("assets/minecraft/models/item/diamond_sword.json"),
            """{"parent": "minecraft:item/handheld", "overrides": [{"predicate": {"custom_model_data": 1}, "model": "myserver:item/ruby_sword"}]}""",
        )

        val cmd = MigrateCommand()
        cmd.parse(listOf(packDir.toString(), "--dedup", "--phash", "--prune-overrides", "--json"))
    }
}
