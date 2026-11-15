package com.salyvn.packmigrate.migrate

import com.github.ajalt.clikt.core.parse
import com.salyvn.packmigrate.bundle.DualEraBundleExporter
import com.salyvn.packmigrate.cli.MigrateCommand
import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.migrate.PredicateTreeSimplifier
import com.salyvn.packmigrate.core.ZipPacks
import com.salyvn.packmigrate.modern.ComponentOverrideCompiler
import com.salyvn.packmigrate.modern.ItemComponentCodegen
import com.salyvn.packmigrate.modern.ItemModelDefinition
import com.salyvn.packmigrate.modern.ItemModelNode
import com.salyvn.packmigrate.modern.TintSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class PackMigrateV050Test {

    private fun createPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    private fun evaluate(node: ItemModelNode, cmd: Double, usingItem: Boolean, hand: String): String = when (node) {
        is ItemModelNode.Model -> node.model
        is ItemModelNode.Composite -> node.models.joinToString("+") { evaluate(it, cmd, usingItem, hand) }
        is ItemModelNode.Condition -> {
            val v = if (node.property == "minecraft:using_item") usingItem else false
            evaluate(if (v) node.onTrue else node.onFalse, cmd, usingItem, hand)
        }
        is ItemModelNode.Select -> {
            val v = if (node.property == "minecraft:main_hand") hand else ""
            val m = node.cases.firstOrNull { v in it.whenValues }?.model ?: node.fallback!!
            evaluate(m, cmd, usingItem, hand)
        }
        is ItemModelNode.RangeDispatch -> {
            val sorted = node.entries.sortedBy { it.threshold }
            val matched = sorted.lastOrNull { it.threshold <= cmd }?.model ?: node.fallback!!
            evaluate(matched, cmd, usingItem, hand)
        }
    }

    @Test
    fun `item model definition renders 26_3 oversized_in_gui, swap_animation_scale, tints, and composite`() {
        val def = ItemModelDefinition(
            handAnimationOnSwap = false,
            oversizedInGui = true,
            swapAnimationScale = 1.5,
            model = ItemModelNode.Composite(
                listOf(
                    ItemModelNode.Model(
                        "myserver:item/greatsword_base",
                        tints = listOf(
                            TintSource.Constant(0xFF3366),
                            TintSource.CustomModelData(index = 1, defaultColor = -1),
                        ),
                    ),
                    ItemModelNode.Model("myserver:item/greatsword_emissive"),
                ),
            ),
        )
        val rendered = def.render()
        val parsed = Json.parseToJsonElement(rendered).jsonObject
        assertEquals("false", parsed["hand_animation_on_swap"]?.jsonPrimitive?.content)
        assertEquals("true", parsed["oversized_in_gui"]?.jsonPrimitive?.content)
        assertEquals("1.5", parsed["swap_animation_scale"]?.jsonPrimitive?.content)
        val modelObj = parsed["model"]!!.jsonObject
        assertEquals("minecraft:composite", modelObj["type"]?.jsonPrimitive?.content)
        assertEquals(2, modelObj["models"]!!.jsonArray.size)
    }

    @Test
    fun `component override compiler supports 26_3 tints, composite, flag_index, string select, and PM018 validation`() {
        val issues = mutableListOf<Issue>()
        val base = ItemModelNode.Model("minecraft:item/paper")

        val spec = buildJsonArray {
            add(
                buildJsonObject {
                    put("flag_index", 2)
                    put(
                        "models",
                        buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive("myserver:item/relic_base"))
                            add(kotlinx.serialization.json.JsonPrimitive("myserver:item/relic_aura"))
                        },
                    )
                    put(
                        "tints",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "constant")
                                    put("value", "#33AAFF")
                                },
                            )
                        },
                    )
                },
            )
            add(
                buildJsonObject {
                    put("property", "minecraft:component")
                    put("component", "minecraft:rarity")
                    put("when", "epic")
                    put("model", "myserver:item/epic_paper")
                },
            )
            add(
                buildJsonObject {
                    put("flag_index", -1)
                    put("model", "myserver:item/broken")
                },
            )
            add(
                buildJsonObject {
                    put("custom_model_data", 5)
                    put("model", "myserver:item/bad_tint")
                    put(
                        "tints",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "unknown_codec") })
                        },
                    )
                },
            )
        }

        val compiled = ComponentOverrideCompiler.compileComponentOverrides(
            spec,
            base,
            "assets/minecraft/models/item/paper.json",
            issues,
        )
        assertTrue(compiled is ItemModelNode.Select)
        val select = compiled as ItemModelNode.Select
        assertEquals("minecraft:component", select.property)
        assertEquals("minecraft:rarity", select.component)
        assertTrue(select.fallback is ItemModelNode.Condition)
        val flagCond = select.fallback as ItemModelNode.Condition
        assertEquals(2, flagCond.index)
        assertTrue(flagCond.onTrue is ItemModelNode.Composite)

        // Verify PM018 flagged negative flag_index and unknown tint codec
        val pm018 = issues.filter { it.rule == MigrationRule.PM018 }
        assertEquals(2, pm018.size)
    }

    @Test
    fun `predicate tree simplifier coalesces step knots, folds Shannon cofactors, and narrows intervals`() {
        // Construct bloated tree with:
        // 1) step knot equal to fallback at threshold 5.0
        // 2) adjacent identical step knots at 10.0 and 15.0
        // 3) nested range_dispatch on same custom_model_data inside [20.0, 30.0) with unreachable thresholds 5.0 & 40.0
        // 4) nested condition on minecraft:using_item inside outer Condition(minecraft:using_item)
        // 5) nested Select(main_hand) on fallback excluding "left"
        val innerSameRange = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            entries = listOf(
                ItemModelNode.Entry(5.0, ItemModelNode.Model("myserver:item/early")),
                ItemModelNode.Entry(18.0, ItemModelNode.Model("myserver:item/mid")),
                ItemModelNode.Entry(25.0, ItemModelNode.Model("myserver:item/tier_25")),
                ItemModelNode.Entry(40.0, ItemModelNode.Model("myserver:item/unreachable_40")),
            ),
            fallback = ItemModelNode.Model("myserver:item/never"),
        )

        val dominatedCondition = ItemModelNode.Condition(
            property = "minecraft:using_item",
            onTrue = ItemModelNode.Condition(
                property = "minecraft:using_item",
                onTrue = ItemModelNode.Model("myserver:item/active"),
                onFalse = ItemModelNode.Model("myserver:item/dead_branch"),
            ),
            onFalse = ItemModelNode.Select(
                property = "minecraft:main_hand",
                cases = listOf(
                    ItemModelNode.Case(listOf("left"), ItemModelNode.Model("myserver:item/left_idle")),
                ),
                fallback = ItemModelNode.Select(
                    property = "minecraft:main_hand",
                    cases = listOf(
                        ItemModelNode.Case(listOf("left"), ItemModelNode.Model("myserver:item/impossible_left")),
                        ItemModelNode.Case(listOf("right"), ItemModelNode.Model("myserver:item/right_idle")),
                    ),
                    fallback = ItemModelNode.Model("myserver:item/base"),
                ),
            ),
        )

        val rawTree = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            entries = listOf(
                ItemModelNode.Entry(5.0, ItemModelNode.Model("myserver:item/base")),
                ItemModelNode.Entry(10.0, ItemModelNode.Model("myserver:item/scroll")),
                ItemModelNode.Entry(15.0, ItemModelNode.Model("myserver:item/scroll")),
                ItemModelNode.Entry(20.0, innerSameRange),
                ItemModelNode.Entry(30.0, ItemModelNode.Composite(listOf(dominatedCondition))),
            ),
            fallback = ItemModelNode.Model("myserver:item/base"),
        )

        val res = PredicateTreeSimplifier.simplify(rawTree, "assets/minecraft/models/item/paper.json", "minecraft:item/paper")
        assertTrue(res.stepKnotsRemoved >= 2)
        assertTrue(res.cofactorsFolded >= 2)
        assertTrue(res.intervalsTightened >= 2)
        assertTrue(res.compositesFlattened >= 1)
        assertTrue(res.issues.any { it.rule == MigrationRule.PM019 })

        val rendered = ItemModelDefinition(res.root).render()
        assertFalse("dead_branch" in rendered)
        assertFalse("impossible_left" in rendered)
        assertFalse("unreachable_40" in rendered)

        // Verify exact semantic equivalence across every combination of inputs!
        val testCmds = listOf(-5.0, 0.0, 5.0, 9.9, 10.0, 12.0, 15.0, 19.9, 20.0, 22.0, 25.0, 29.9, 30.0, 50.0)
        for (cmd in testCmds) {
            for (using in listOf(false, true)) {
                for (hand in listOf("left", "right")) {
                    assertEquals(
                        evaluate(rawTree, cmd, using, hand),
                        evaluate(res.root, cmd, using, hand),
                        "Mismatch for cmd=$cmd using=$using hand=$hand",
                    )
                }
            }
        }
    }

    @Test
    fun `codegen synthesizes standalone 26_3 item definitions, detects oversized GUI, audits PM017, and builds manifest`(@TempDir tempDir: Path) {
        val pack = tempDir.resolve("pack")
        Files.createDirectories(pack.resolve("assets/minecraft/models/item"))
        Files.createDirectories(pack.resolve("assets/myserver/models/item"))
        Files.createDirectories(pack.resolve("assets/myserver/textures/item"))

        Files.writeString(pack.resolve("pack.mcmeta"), """{"pack":{"pack_format":46,"description":"codegen test"}}""")

        // Oversized 64x64 texture without .mcmeta animation -> triggers oversized_in_gui = true
        Files.write(pack.resolve("assets/myserver/textures/item/titan_blade.png"), createPng(64, 64))
        Files.writeString(
            pack.resolve("assets/myserver/models/item/titan_blade.json"),
            """{"parent":"minecraft:item/handheld","textures":{"layer0":"myserver:item/titan_blade"}}""",
        )

        // Animated 16x64 sprite strip WITH .mcmeta -> should NOT be flagged oversized
        Files.write(pack.resolve("assets/myserver/textures/item/pulse_gem.png"), createPng(16, 64))
        Files.writeString(pack.resolve("assets/myserver/textures/item/pulse_gem.png.mcmeta"), """{"animation":{"frametime":2}}""")
        Files.writeString(
            pack.resolve("assets/myserver/models/item/pulse_gem.json"),
            """{"parent":"minecraft:item/generated","textures":{"layer0":"myserver:item/pulse_gem"}}""",
        )

        Files.writeString(
            pack.resolve("assets/minecraft/models/item/diamond_sword.json"),
            """
            {
              "parent": "minecraft:item/handheld",
              "overrides": [
                {"predicate": {"custom_model_data": 1}, "model": "myserver:item/titan_blade"},
                {"predicate": {"custom_model_data": 2}, "model": "myserver:item/pulse_gem"}
              ]
            }
            """.trimIndent(),
        )

        // Floor 46..97 without overlay -> oversized_in_gui triggers PM017 warning!
        val (min46, max97) = PackFormats.parseRange("1.21.4..26.3")
        val plan = PackMigration(pack, min46, max97).plan(
            stripOverrides = false,
            codegenComponents = true,
            buildCodegenManifest = true,
        )

        assertEquals(2, plan.standaloneDefinitionCount)
        val titanFile = plan.files.first { it.relative == "assets/myserver/items/titan_blade.json" }
        val gemFile = plan.files.first { it.relative == "assets/myserver/items/pulse_gem.json" }
        assertTrue("\"oversized_in_gui\": true" in titanFile.text)
        assertFalse("oversized_in_gui" in gemFile.text)
        assertTrue(plan.issues.any { it.rule == MigrationRule.PM017 })

        val manifest = plan.codegenManifest
        assertNotNull(manifest)
        assertEquals(2, manifest!!.entryCount)
        val titanEntry = manifest.entries.first { it.targetModel == "myserver:item/titan_blade" }
        assertEquals("myserver:titan_blade", titanEntry.directItemModelId)
        assertTrue(titanEntry.giveCommand26_3.contains("minecraft:custom_model_data={floats:[1.0f]}"))
        assertEquals("/give @s minecraft:diamond_sword[minecraft:item_model=\"myserver:titan_blade\"]", titanEntry.directItemModelGive26_3)

        // Floor 63..97 (1.21.6..26.3) -> zero PM017 warnings!
        val planModern = PackMigration(pack, PackFormat(63), max97).plan(
            stripOverrides = false,
            codegenComponents = true,
        )
        assertFalse(planModern.issues.any { it.rule == MigrationRule.PM017 })
    }

    @Test
    fun `dual era bundle exporter writes deterministic legacy, modern, and hybrid slices plus PM020 warning`(@TempDir tempDir: Path) {
        val pack = tempDir.resolve("networkpack")
        Files.createDirectories(pack.resolve("assets/minecraft/models/item"))
        Files.createDirectories(pack.resolve("assets/minecraft/items"))
        Files.createDirectories(pack.resolve("assets/myserver/models/item"))
        Files.createDirectories(pack.resolve("format_97/assets/myserver/textures/item"))

        Files.writeString(
            pack.resolve("pack.mcmeta"),
            """
            {
              "pack": {"pack_format": 15, "description": "Network Pack"},
              "overlays": {
                "entries": [
                  {"min_format": [97, 0], "max_format": [97, 1], "directory": "format_97"}
                ]
              }
            }
            """.trimIndent(),
        )
        Files.write(pack.resolve("format_97/assets/myserver/textures/item/hd.png"), createPng(16, 16))
        Files.writeString(
            pack.resolve("assets/myserver/models/item/ruby_staff.json"),
            """{"parent":"minecraft:item/handheld"}""",
        )
        Files.writeString(
            pack.resolve("assets/minecraft/models/item/blaze_rod.json"),
            """{"parent":"minecraft:item/handheld","overrides":[{"predicate":{"custom_model_data":7},"model":"myserver:item/ruby_staff"}]}""",
        )
        // Hand-authored modern item definition without legacy overrides -> triggers PM020!
        Files.writeString(
            pack.resolve("assets/minecraft/items/mace.json"),
            """{"model":{"type":"minecraft:model","model":"myserver:item/ruby_staff"}}""",
        )

        val bundleDir1 = tempDir.resolve("bundle1")
        val bundleDir2 = tempDir.resolve("bundle2")
        val res1 = DualEraBundleExporter.export(pack, bundleDir1, simplifyTrees = true, codegenComponents = true)
        val res2 = DualEraBundleExporter.export(pack, bundleDir2, simplifyTrees = true, codegenComponents = true)

        // Byte-reproducible SHA-1 determinism across runs
        assertEquals(res1.legacyZip.sha1, res2.legacyZip.sha1)
        assertEquals(res1.modernZip.sha1, res2.modernZip.sha1)
        assertEquals(res1.hybridZip.sha1, res2.hybridZip.sha1)

        // Verify PM020 emitted for unpaired mace.json
        assertTrue(res1.issues.any { it.rule == MigrationRule.PM020 && "mace.json" in it.path })

        // Inspect legacy zip: has blaze_rod overrides, NO assets/*/items/*.json, NO format_97 overlay
        ZipFile(res1.legacyZip.path.toFile()).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("assets/minecraft/models/item/blaze_rod.json" in names)
            assertFalse(names.any { "/items/" in it })
            assertFalse(names.any { it.startsWith("format_97/") })
            val blazeText = zf.getInputStream(zf.getEntry("assets/minecraft/models/item/blaze_rod.json")).bufferedReader().readText()
            assertTrue("\"overrides\"" in blazeText)
        }

        // Inspect modern zip: has migrated blaze_rod items/*.json AND standalone myserver/items/ruby_staff.json, overrides stripped!
        ZipFile(res1.modernZip.path.toFile()).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("assets/minecraft/items/blaze_rod.json" in names)
            assertTrue("assets/myserver/items/ruby_staff.json" in names)
            assertTrue("format_97/assets/myserver/textures/item/hd.png" in names)
            val blazeText = zf.getInputStream(zf.getEntry("assets/minecraft/models/item/blaze_rod.json")).bufferedReader().readText()
            assertFalse("\"overrides\"" in blazeText)
        }

        // Inspect hybrid zip: isolates migrated items under overlay_1_21_4 while keeping root overrides!
        ZipFile(res1.hybridZip.path.toFile()).use { zf ->
            val names = zf.entries().asSequence().map { it.name }.toSet()
            assertTrue("overlay_1_21_4/assets/minecraft/items/blaze_rod.json" in names)
            val blazeText = zf.getInputStream(zf.getEntry("assets/minecraft/models/item/blaze_rod.json")).bufferedReader().readText()
            assertTrue("\"overrides\"" in blazeText)
        }
    }

    @Test
    fun `cli end-to-end supports simplify-trees, codegen-components, component-manifest, and bundle-export`(@TempDir tempDir: Path) {
        val pack = tempDir.resolve("clipack")
        Files.createDirectories(pack.resolve("assets/minecraft/models/item"))
        Files.createDirectories(pack.resolve("assets/myserver/models/item"))
        Files.writeString(pack.resolve("pack.mcmeta"), """{"pack":{"pack_format":46,"description":"cli v050"}}""")
        Files.writeString(pack.resolve("assets/myserver/models/item/spear.json"), """{"parent":"minecraft:item/handheld"}""")
        Files.writeString(
            pack.resolve("assets/minecraft/models/item/stick.json"),
            """
            {
              "parent": "minecraft:item/handheld",
              "overrides": [
                {"predicate": {"custom_model_data": 1}, "model": "minecraft:item/stick"},
                {"predicate": {"custom_model_data": 2}, "model": "myserver:item/spear"}
              ]
            }
            """.trimIndent(),
        )

        val zipIn = tempDir.resolve("clipack.zip")
        ZipPacks.writeDeterministicZip(pack, zipIn)

        val bundleOut = tempDir.resolve("bundle-out")
        val manifestOut = tempDir.resolve("components-263.json")
        val sarifOut = tempDir.resolve("v050.sarif")

        val cmd = MigrateCommand()
        cmd.parse(
            listOf(
                zipIn.toString(),
                "--versions", "1.21.6..26.3",
                "--simplify-trees",
                "--codegen-components",
                "--component-manifest", manifestOut.toString(),
                "--bundle-export", bundleOut.toString(),
                "--sarif", sarifOut.toString(),
                "--json",
            ),
        )

        assertTrue(Files.exists(manifestOut))
        assertTrue(Files.exists(bundleOut.resolve(DualEraBundleExporter.MANIFEST_NAME)))
        assertTrue(Files.exists(bundleOut.resolve(DualEraBundleExporter.LEGACY_ZIP_NAME)))
        assertTrue(Files.exists(bundleOut.resolve(DualEraBundleExporter.MODERN_ZIP_NAME)))
        assertTrue(Files.exists(bundleOut.resolve(DualEraBundleExporter.HYBRID_ZIP_NAME)))
        val sarifText = Files.readString(sarifOut)
        assertTrue("PM017" in sarifText && "PM018" in sarifText && "PM019" in sarifText && "PM020" in sarifText)
    }

    @Test
    fun `component override compiler skips tree insertion when tint codec is unknown PM018`() {
        val issues = mutableListOf<Issue>()
        val base = ItemModelNode.Model("minecraft:item/diamond_sword")

        val spec = buildJsonArray {
            // Entry 0: valid flag_index=1, model=mysword_a. Should add Condition to tree.
            add(buildJsonObject {
                put("flag_index", 1)
                put("model", "myserver:item/mysword_a")
            })
            // Entry 1: flag_index=0 with unknown tint codec. The PM018 error MUST prevent insertion.
            add(buildJsonObject {
                put("flag_index", 0)
                put("model", "myserver:item/mysword_b")
                put("tints", buildJsonArray {
                    add(buildJsonObject { put("type", "xyzzy:nonexistent") })
                })
            })
        }

        val result = ComponentOverrideCompiler.compileComponentOverrides(spec, base, "assets/minecraft/models/item/diamond_sword.json", issues)

        // Only 1 PM018: the unknown tint codec on entry 1
        val pm018 = issues.filter { it.rule == MigrationRule.PM018 }
        assertEquals(1, pm018.size)
        assertTrue("nonexistent" in pm018[0].message || "xyzzy" in pm018[0].message)

        // Result must be a Condition from entry 0 (flag_index=1), NOT modified by entry 1
        assertTrue(result is ItemModelNode.Condition, "Expected Condition but got ${result::class.simpleName}")
        val cond = result as ItemModelNode.Condition
        assertEquals("minecraft:custom_model_data", cond.property)
        assertEquals(1, cond.index)
        assertEquals("myserver:item/mysword_a", (cond.onTrue as ItemModelNode.Model).model)
        assertEquals("minecraft:item/diamond_sword", (cond.onFalse as ItemModelNode.Model).model)
    }

    @Test
    fun `component override compiler optimizeTree folds tautological condition and coalesces select cases PM015`() {
        val modelX = ItemModelNode.Model("myserver:item/special")
        val modelY = ItemModelNode.Model("minecraft:item/paper")
        // Both select cases map to modelX (not fallback modelY); optimizer coalesces epic+rare into one Case.
        val duplicateSelect = ItemModelNode.Select(
            property = "minecraft:component",
            component = "minecraft:rarity",
            cases = listOf(
                ItemModelNode.Case(listOf("epic"), modelX),
                ItemModelNode.Case(listOf("rare"), modelX),
            ),
            fallback = modelY,
        )
        // Wrap in a tautological Condition (both branches identical) -> should be folded away.
        val tautCond = ItemModelNode.Condition(
            property = "minecraft:using_item",
            onTrue = duplicateSelect,
            onFalse = duplicateSelect,
        )

        val result = ComponentOverrideCompiler.optimizeTree(tautCond, "dummy/path.json")

        assertTrue(result.prunedBranches >= 1)
        val pm015 = result.issues.filter { it.rule == MigrationRule.PM015 }
        assertTrue(pm015.any { "Tautological" in it.message })
        // Root should be the Select after tautology fold
        val sel = result.root as? ItemModelNode.Select ?: error("Expected Select, got ${result.root::class.simpleName}")
        // Cases coalesced: exactly one Case with both values
        assertEquals(1, sel.cases.size)
        assertTrue(sel.cases[0].whenValues.containsAll(listOf("epic", "rare")))
        assertEquals(modelX, sel.cases[0].model)
    }

    @Test
    fun `predicate tree simplifier narrows intervals across nested range_dispatch on same property`() {
        // Within outer entry at threshold=10, inner effective range is [10, +inf).
        // Inner entry at threshold=5 (< lowerInclusive 10) collapsed -> intervals++.
        val baseModel = ItemModelNode.Model("minecraft:item/stone")
        val modelA = ItemModelNode.Model("myserver:item/a")
        val modelB = ItemModelNode.Model("myserver:item/b")

        val inner = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            entries = listOf(
                ItemModelNode.Entry(5.0, baseModel),
                ItemModelNode.Entry(15.0, modelA),
                ItemModelNode.Entry(20.0, modelB),
            ),
            fallback = baseModel,
        )
        val outer = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            entries = listOf(ItemModelNode.Entry(10.0, inner)),
            fallback = baseModel,
        )

        val result = PredicateTreeSimplifier.simplify(outer, "assets/minecraft/items/stone.json")
        assertTrue(result.intervalsTightened >= 1, "Expected >=1 interval tightened, got ${result.intervalsTightened}")
        assertTrue(result.issues.any { it.rule == MigrationRule.PM019 })

        // Semantics must be preserved: evaluate original and simplified identically
        fun evalRD(node: ItemModelNode, cmd: Double): String = when (node) {
            is ItemModelNode.Model -> node.model
            is ItemModelNode.RangeDispatch -> {
                val sorted = node.entries.sortedBy { it.threshold }
                val m = sorted.lastOrNull { it.threshold <= cmd }?.model ?: node.fallback!!
                evalRD(m, cmd)
            }
            else -> "??"
        }
        for (cmd in listOf(8.0, 12.0, 16.0, 22.0)) {
            assertEquals(evalRD(outer, cmd), evalRD(result.root, cmd), "Semantic mismatch at cmd=$cmd")
        }
    }

    @Test
    fun `predicate tree simplifier step knot coalescing removes duplicate-model adjacent entries`() {
        val base = ItemModelNode.Model("minecraft:item/barrier")
        val modelA = ItemModelNode.Model("myserver:item/alpha")
        val modelB = ItemModelNode.Model("myserver:item/beta")

        val rd = ItemModelNode.RangeDispatch(
            property = "minecraft:custom_model_data",
            entries = listOf(
                ItemModelNode.Entry(1.0, modelA),
                ItemModelNode.Entry(2.0, modelA),   // duplicate of prev -> stepKnot
                ItemModelNode.Entry(3.0, modelB),
                ItemModelNode.Entry(4.0, modelB),   // duplicate of prev -> stepKnot
            ),
            fallback = base,
        )

        val result = PredicateTreeSimplifier.simplify(rd, "assets/minecraft/items/barrier.json")
        assertEquals(2, result.stepKnotsRemoved)
        val simplified = result.root as ItemModelNode.RangeDispatch
        assertEquals(2, simplified.entries.size)
        assertEquals(1.0, simplified.entries[0].threshold)
        assertEquals(modelA, simplified.entries[0].model)
        assertEquals(3.0, simplified.entries[1].threshold)
        assertEquals(modelB, simplified.entries[1].model)
    }

    @Test
    fun `item component codegen audit emits PM018 for negative swap_animation_scale`() {
        val issues = mutableListOf<Issue>()
        val defNegScale = ItemModelDefinition(
            swapAnimationScale = -0.5,
            model = ItemModelNode.Model("myserver:item/x"),
        )
        ItemComponentCodegen.auditDefinitionFloor(
            defNegScale,
            PackFormat(PackFormats.formatOf("26.3"), 1),
            "myserver:x",
            "assets/myserver/items/x.json",
            issues,
        )
        assertTrue(issues.any { it.rule == MigrationRule.PM018 && "negative" in it.message })
    }

    @Test
    fun `item component codegen audit emits PM017 for swap_animation_scale below format 84`() {
        val issues = mutableListOf<Issue>()
        val defScale = ItemModelDefinition(
            swapAnimationScale = 1.2,
            model = ItemModelNode.Model("myserver:item/y"),
        )
        // Format 46 = 1.21.4, below 84 (26.1+) -> PM017 warning
        ItemComponentCodegen.auditDefinitionFloor(
            defScale,
            PackFormat(46),
            "myserver:y",
            "assets/myserver/items/y.json",
            issues,
        )
        assertTrue(issues.any { it.rule == MigrationRule.PM017 && "swap_animation_scale" in it.message })
    }
}
