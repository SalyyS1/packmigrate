package com.salyvn.packmigrate.migrate

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.salyvn.packmigrate.cli.MigrateCommand
import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.VanillaModels
import com.salyvn.packmigrate.core.ZipPacks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class PackMigrationTest {

    private fun plan(stripOverrides: Boolean = false): MigrationPlan {
        val root = Path.of("src/test/resources/legacypack")
        val (min, max) = PackFormats.parseRange("1.21.4..${PackFormats.newestRelease}")
        return PackMigration(root, min, max).plan(stripOverrides)
    }

    @Test
    fun `the fixture migrates four items and plans their definitions`() {
        val plan = plan()
        assertEquals(4, plan.itemsMigrated)
        val paths = plan.files.map { it.relative }.toSet()
        assertEquals(
            setOf(
                "assets/minecraft/items/bow.json",
                "assets/minecraft/items/compass.json",
                "assets/minecraft/items/diamond_sword.json",
                "assets/minecraft/items/paper.json",
                "pack.mcmeta",
            ),
            paths,
        )
    }

    @Test
    fun `the missing void_sword model is the only error`() {
        val errors = plan().issues.filter { it.level == IssueLevel.ERROR }
        assertEquals(1, errors.size)
        assertEquals(MigrationRule.PM003, errors.single().rule)
        assertTrue("myserver:item/void_sword" in errors.single().message)
    }

    @Test
    fun `shared custom_model_data values are pointed out`() {
        val notes = plan().issues.filter { it.level == IssueLevel.NOTE }.map { it.message }
        assertTrue(notes.any { "custom_model_data 1 is used by 3 items" in it })
        assertTrue(notes.any { "custom_model_data 2 is used by 2 items" in it })
    }

    @Test
    fun `pack mcmeta carries the old description and both era fields up to 26_3`() {
        val meta = plan().files.single { it.relative == "pack.mcmeta" }.text
        assertTrue("\"description\": \"myserver \\\"custom\\\" items\"" in meta)
        assertTrue("\"pack_format\": 46" in meta)
        assertTrue("\"supported_formats\"" in meta)
        assertTrue("\"min_format\": 46" in meta)
        assertTrue("\"max_format\": 97" in meta)
    }

    @Test
    fun `stripping keeps the model and drops only the overrides`() {
        val stripped = plan(stripOverrides = true).strippedOverrides
        val bow = stripped.single { it.relative == "assets/minecraft/models/item/bow.json" }.text
        assertTrue("overrides" !in bow)
        assertTrue("minecraft:item/generated" in bow)
    }

    @Test
    fun `apply writes every planned file`() {
        val out = Files.createTempDirectory("packmigrate-test")
        try {
            val plan = plan()
            PackMigration(Path.of("src/test/resources/legacypack"), PackFormat(46), PackFormat(97))
                .apply(plan, out)
            plan.files.forEach { planned ->
                assertEquals(planned.text, out.resolve(planned.relative).readText())
            }
        } finally {
            out.toFile().deleteRecursively()
        }
    }

    @Test
    fun `the vanilla model list is really bundled`() {
        assertTrue(VanillaModels.has("item/diamond_sword"))
        assertTrue(VanillaModels.has("item/bow_pulling_2"))
    }

    @Test
    fun `sarif report renders valid OASIS SARIF v2_1_0 with rule descriptors and SRCROOT locations`() {
        val sarifText = SarifReport.render(plan())
        val sarif = Json.parseToJsonElement(sarifText).jsonObject
        assertEquals("2.1.0", sarif["version"]!!.jsonPrimitive.content)
        val run = sarif["runs"]!!.jsonArray.single().jsonObject
        val driver = run["tool"]!!.jsonObject["driver"]!!.jsonObject
        assertEquals("packmigrate", driver["name"]!!.jsonPrimitive.content)
        assertEquals(MigrationRule.entries.size, driver["rules"]!!.jsonArray.size)

        val results = run["results"]!!.jsonArray
        val ruleIds = results.map { it.jsonObject["ruleId"]!!.jsonPrimitive.content }.toSet()
        assertTrue("PM003" in ruleIds)
        assertTrue("PM006" in ruleIds)
        assertTrue("PM007" in ruleIds)
        val firstLoc = results[0].jsonObject["locations"]!!.jsonArray[0]
            .jsonObject["physicalLocation"]!!.jsonObject["artifactLocation"]!!.jsonObject
        assertEquals("%SRCROOT%", firstLoc["uriBaseId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `overlay-items isolates generated definitions into overlay directory and registers overlays_entries`(@TempDir dir: Path) {
        val packDir = dir.resolve("hybrid")
        Files.createDirectories(packDir.resolve("assets/minecraft/models/item"))
        Files.createDirectories(packDir.resolve("assets/myserver/models/item"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """{"pack": {"pack_format": 15, "description": "Hybrid pack"}}""",
        )
        Files.writeString(
            packDir.resolve("assets/myserver/models/item/ruby_blade.json"),
            """{"parent": "minecraft:item/handheld", "textures": {"layer0": "myserver:item/ruby_blade"}}""",
        )
        Files.writeString(
            packDir.resolve("assets/minecraft/models/item/iron_sword.json"),
            """{"parent": "minecraft:item/handheld", "overrides": [{"predicate": {"custom_model_data": 5}, "model": "myserver:item/ruby_blade"}]}""",
        )

        val sarifOut = dir.resolve("audit.sarif")
        MigrateCommand().parse(
            listOf(
                packDir.toString(),
                "--versions",
                "1.20.1..26.3",
                "--overlay-items",
                "overlay_1_21_4",
                "--write",
                "--sarif",
                sarifOut.toString(),
                "--strict",
            ),
        )

        assertTrue(packDir.resolve("overlay_1_21_4/assets/minecraft/items/iron_sword.json").exists())
        assertTrue(!packDir.resolve("assets/minecraft/items/iron_sword.json").exists())
        assertTrue(sarifOut.exists())

        val meta = Json.parseToJsonElement(packDir.resolve("pack.mcmeta").readText()).jsonObject
        val entries = meta["overlays"]!!.jsonObject["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        assertEquals("overlay_1_21_4", entries[0].jsonObject["directory"]!!.jsonPrimitive.content)
        assertEquals("97", entries[0].jsonObject["max_format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `migrates legacy models inside existing overlay directories with base model fallback`(@TempDir dir: Path) {
        val packDir = dir.resolve("overlay-src")
        Files.createDirectories(packDir.resolve("assets/myserver/models/item"))
        Files.createDirectories(packDir.resolve("v1_21_overlay/assets/minecraft/models/item"))
        Files.writeString(
            packDir.resolve("pack.mcmeta"),
            """
            {
              "pack": {"pack_format": 34, "description": "Overlay source"},
              "overlays": {
                "entries": [
                  {"formats": [34, 64], "directory": "v1_21_overlay"},
                  {"formats": [34, 42], "directory": "missing_overlay"}
                ]
              }
            }
            """.trimIndent(),
        )
        // Referenced model lives in root pack, legacy override lives in v1_21_overlay
        Files.writeString(
            packDir.resolve("assets/myserver/models/item/emerald_staff.json"),
            """{"parent": "minecraft:item/handheld"}""",
        )
        Files.writeString(
            packDir.resolve("v1_21_overlay/assets/minecraft/models/item/stick.json"),
            """{"overrides": [{"predicate": {"custom_model_data": 9}, "model": "myserver:item/emerald_staff"}]}""",
        )

        val migration = PackMigration(packDir, PackFormat(34), PackFormat(97))
        val plan = migration.plan(stripOverrides = false)
        assertEquals(0, plan.errors)
        assertTrue(plan.issues.any { it.rule == MigrationRule.PM009 && "missing_overlay" in it.message })
        assertTrue(plan.issues.any { it.rule == MigrationRule.PM010 && "v1_21_overlay" in it.message })
        assertTrue(plan.files.any { it.relative == "v1_21_overlay/assets/minecraft/items/stick.json" })
    }

    @Test
    fun `migrates a zip pack into a byte-reproducible zip and supports hybrid 1_20_1 to 26_3 ranges`(@TempDir dir: Path) {
        val inputZip = dir.resolve("legacy.zip")
        ZipPacks.writeDeterministicZip(Path.of("src/test/resources/legacypack"), inputZip)

        val cleanSrc = dir.resolve("clean-src")
        ZipPacks.extractSafely(inputZip, cleanSrc)
        Files.delete(cleanSrc.resolve("assets/minecraft/models/item/diamond_sword.json"))
        val cleanZip = dir.resolve("clean-legacy.zip")
        ZipPacks.writeDeterministicZip(cleanSrc, cleanZip)

        val outZip1 = dir.resolve("migrated-1.zip")
        val outZip2 = dir.resolve("migrated-2.zip")
        val sarifFile = dir.resolve("report.sarif")
        MigrateCommand().parse(
            listOf(
                cleanZip.toString(),
                "--versions",
                "1.20.1..26.3",
                "--zip",
                outZip1.toString(),
                "--sarif",
                sarifFile.toString(),
                "--json",
            ),
        )
        MigrateCommand().parse(
            listOf(
                cleanZip.toString(),
                "--versions",
                "1.20.1..26.3",
                "--zip",
                outZip2.toString(),
                "--json",
            ),
        )
        assertTrue(Files.readAllBytes(outZip1).contentEquals(Files.readAllBytes(outZip2)))
        assertTrue(sarifFile.exists())

        val bad = assertThrows<ProgramResult> {
            MigrateCommand().parse(
                listOf(
                    cleanZip.toString(),
                    "--versions",
                    "1.20.1..26.3",
                    "--strip-overrides",
                    "--zip",
                    dir.resolve("bad.zip").toString(),
                ),
            )
        }
        assertEquals(2, bad.statusCode)

        val reportStr = JsonMigrationReport.render(
            pack = cleanZip,
            plan = plan(),
            minFormat = PackFormat(15),
            maxFormat = PackFormat(97),
            applied = false,
            outputDir = null,
            zipArtifact = null,
        )
        val parsed = Json.parseToJsonElement(reportStr).jsonObject
        assertEquals("4", parsed["itemsMigrated"]!!.jsonPrimitive.content)
    }
}
