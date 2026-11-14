package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.VanillaModels
import com.salyvn.packmigrate.core.ZipPacks
import com.salyvn.packmigrate.cli.MigrateCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Test

/** The fixture under `src/test/resources/legacypack` run end to end, the way the CLI runs it. */
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
        assertTrue("myserver:item/void_sword" in errors.single().message)
    }

    @Test
    fun `shared custom_model_data values are pointed out`() {
        val notes = plan().issues.filter { it.level == IssueLevel.NOTE }.map { it.message }
        assertTrue(notes.any { "custom_model_data 1 is used by 3 items" in it })
        assertTrue(notes.any { "custom_model_data 2 is used by 2 items" in it })
    }

    @Test
    fun `pack mcmeta carries the old description and both era fields`() {
        val meta = plan().files.single { it.relative == "pack.mcmeta" }.text
        // The description survives the rewrite, escaped quotes included.
        assertTrue("\"description\": \"myserver \\\"custom\\\" items\"" in meta)
        // 46 (1.21.4) is below the minor-version era, so all four fields have to be present.
        assertTrue("\"pack_format\": 46" in meta)
        assertTrue("\"supported_formats\"" in meta)
        assertTrue("\"min_format\": 46" in meta)
        assertTrue("\"max_format\": 88" in meta)
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
            PackMigration(Path.of("src/test/resources/legacypack"), com.salyvn.packmigrate.core.PackFormat(46), com.salyvn.packmigrate.core.PackFormat(88))
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
    fun `migrates a zip pack into a byte-reproducible zip and supports hybrid 1_20_1 to 26_2 ranges`(@TempDir dir: Path) {
        val inputZip = dir.resolve("legacy.zip")
        ZipPacks.writeDeterministicZip(Path.of("src/test/resources/legacypack"), inputZip)

        // Remove diamond_sword's missing void_sword from a clean copy so CLI exits 0 on hybrid migration
        val cleanSrc = dir.resolve("clean-src")
        ZipPacks.extractSafely(inputZip, cleanSrc)
        Files.delete(cleanSrc.resolve("assets/minecraft/models/item/diamond_sword.json"))
        val cleanZip = dir.resolve("clean-legacy.zip")
        ZipPacks.writeDeterministicZip(cleanSrc, cleanZip)

        val outZip1 = dir.resolve("migrated-1.zip")
        val outZip2 = dir.resolve("migrated-2.zip")
        MigrateCommand().parse(
            listOf(
                cleanZip.toString(),
                "--versions",
                "1.20.1..26.2",
                "--zip",
                outZip1.toString(),
                "--json",
            ),
        )
        MigrateCommand().parse(
            listOf(
                cleanZip.toString(),
                "--versions",
                "1.20.1..26.2",
                "--zip",
                outZip2.toString(),
                "--json",
            ),
        )
        assertTrue(Files.readAllBytes(outZip1).contentEquals(Files.readAllBytes(outZip2)))

        // --strip-overrides with a pre-1.21.4 floor is rejected with exit code 2
        val bad = assertThrows<ProgramResult> {
            MigrateCommand().parse(
                listOf(
                    cleanZip.toString(),
                    "--versions",
                    "1.20.1..26.2",
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
            minFormat = com.salyvn.packmigrate.core.PackFormat(15),
            maxFormat = com.salyvn.packmigrate.core.PackFormat(88),
            applied = false,
            outputDir = null,
            zipArtifact = null,
        )
        val parsed = Json.parseToJsonElement(reportStr).jsonObject
        assertEquals("4", parsed["itemsMigrated"]!!.jsonPrimitive.content)
    }
}
