package com.salyvn.packmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.packmigrate.bundle.BundleExportResult
import com.salyvn.packmigrate.bundle.DualEraBundleExporter
import com.salyvn.packmigrate.core.Format97OverlayGenerator
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.PackMeta
import com.salyvn.packmigrate.core.PackMetaException
import com.salyvn.packmigrate.core.ZipArtifact
import com.salyvn.packmigrate.core.ZipPacks
import com.salyvn.packmigrate.dedup.DedupOptions
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.JsonMigrationReport
import com.salyvn.packmigrate.migrate.MigrationPlan
import com.salyvn.packmigrate.migrate.PackMigration
import com.salyvn.packmigrate.migrate.SarifReport
import com.salyvn.packmigrate.modern.ItemComponentCodegen
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * CLI entrypoint for planning, auditing, and applying 1.21.4 through 26.3+ resource pack migrations.
 */
class MigrateCommand : CliktCommand(name = "packmigrate") {

    override fun help(context: Context) =
        "Convert legacy CustomModelData overrides in a pack directory or .zip into 1.21.4..26.3+ item model definitions."

    private val pack by argument("pack", help = "Pack directory or .zip archive").path(mustExist = true)

    private val versions by option(
        "--versions",
        help = "Range pack.mcmeta should claim, e.g. 1.21.4..26.3, 1.20.1..26.3, or 84..[97,1] (default: 1.21.4..newest)",
    )

    private val overlayItems by option(
        "--overlay-items",
        help = "Write migrated items/*.json into <dir>/assets/<ns>/items/ and register <dir> in pack.mcmeta overlays.entries",
    )

    private val overlayFormat97 by option(
        "--overlay-format-97",
        help = "Generate an isolated format_97 overlay for Minecraft 26.3 definitions",
    ).flag()

    private val write by option("--write", help = "Apply migration instead of only printing the plan").flag()

    private val out by option("--out", help = "Write migrated pack directory to this path instead of in place").path()

    private val zip by option(
        "--zip",
        help = "Write a byte-deterministic migrated .zip archive and print its resource-pack-sha1 digest",
    ).path()

    private val bundleExport by option(
        "--bundle-export",
        help = "Export deterministic legacy (1.20.1..1.21.3), modern (1.21.4..26.3), and hybrid .zip slices plus bundle-manifest.json to this directory",
    ).path()

    private val sarif by option(
        "--sarif",
        help = "Write an OASIS SARIF v2.1.0 migration diagnostic report to this file",
    ).path()

    private val force by option("--force", help = "Overwrite an existing --zip archive").flag()

    private val stripOverrides by option(
        "--strip-overrides",
        help = "Also remove the overrides list from each legacy model file (1.21.4+ only)",
    ).flag()

    private val dedup by option(
        "--dedup",
        help = "Prune redundant overlay assets that duplicate base pack assets and report duplicate textures",
    ).flag()

    private val phash by option(
        "--phash",
        help = "Enable 64-bit DCT-II perceptual image hash analysis for textures",
    ).flag()

    private val phashThreshold by option(
        "--phash-threshold",
        help = "Perceptual hash Hamming distance threshold in 0..64 (default: 2)",
    ).int().default(2)

    private val pruneOverrides by option(
        "--prune-overrides",
        help = "Prune redundant or unreachable override branches and fold tautological conditions",
    ).flag()

    private val simplifyTrees by option(
        "--simplify-trees",
        help = "Algebraically simplify predicate decision trees (step-knot coalescing, path cofactor folding, and interval narrowing)",
    ).flag()

    private val codegenComponents by option(
        "--codegen-components",
        help = "Generate standalone 26.3 assets/<ns>/items/<name>.json direct item_model definitions for custom-namespaced models",
    ).flag()

    private val componentManifest by option(
        "--component-manifest",
        help = "Write a 26.3 data component and /give command dispatch manifest JSON to this path",
    ).path()

    private val componentOverrides by option(
        "--component-overrides",
        help = "JSON file with modern component override rules to compile into item definitions",
    ).path(mustExist = true)

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

    private val json by option("--json", help = "Emit machine-readable JSON migration report to stdout").flag()

    override fun run() {
        try {
            execute()
        } catch (failure: IllegalArgumentException) {
            fail(failure.message)
        } catch (failure: PackMetaException) {
            fail(failure.message)
        } catch (failure: IOException) {
            fail(failure.message)
        }
    }

    private fun execute() {
        if (!pack.isDirectory() && !pack.isRegularFile()) {
            fail("$pack is neither a pack directory nor a .zip archive.")
        }
        val (min, max) = PackFormats.parseRange(versions ?: "1.21.4..${PackFormats.newestRelease}")
        val format1214 = PackFormats.formatOf("1.21.4")
        if (max.major < format1214) {
            fail("Item model definitions exist from 1.21.4 (format 46); a range ending at $max never uses them.")
        }
        if (stripOverrides && min.major < format1214) {
            fail(
                "--strip-overrides cannot be combined with a range starting at $min (below 1.21.4 / format 46), " +
                    "because pre-1.21.4 clients still read legacy overrides. Drop --strip-overrides for a hybrid pack.",
            )
        }

        val effectiveOverlayItems = if (overlayFormat97) {
            Format97OverlayGenerator.FORMAT_97_DIR
        } else {
            overlayItems
        }

        effectiveOverlayItems?.let { dir ->
            if (!PackMeta.isValidOverlayDirectory(dir)) {
                fail("--overlay-items '$dir' is invalid; Minecraft requires lowercase [a-z0-9_-]+ with no path separators or dots.")
            }
        }

        val applying = write || zip != null
        val isZipInput = pack.isRegularFile()
        if (write && isZipInput && out == null && zip == null && bundleExport == null) {
            fail("$pack is a .zip archive; pass --zip <migrated.zip>, --out <dir>, or --bundle-export <dir> when applying --write.")
        }
        val zipTarget = zip
        if (applying && zipTarget != null && zipTarget.exists() && !force) {
            fail("$zipTarget already exists. Pass --force to overwrite it.")
        }

        val writeInPlace = write && !isZipInput && out == null
        val tempExtracted = if (isZipInput) Files.createTempDirectory("packmigrate-in-") else null
        val tempStaged = if (applying && !writeInPlace && out == null && zipTarget != null) {
            Files.createTempDirectory("packmigrate-out-")
        } else {
            null
        }

        try {
            val workingInput = if (tempExtracted != null) {
                ZipPacks.extractSafely(pack, tempExtracted)
                tempExtracted
            } else {
                pack
            }

            val parsedComponentOverrides = componentOverrides?.let { path ->
                try {
                    Json.parseToJsonElement(Files.readString(path))
                } catch (e: Exception) {
                    fail("Failed to parse component overrides JSON from $path: ${e.message}")
                }
            }

            val dedupOptions = DedupOptions(
                enableDedup = dedup,
                enablePhash = phash || dedup,
                phashThreshold = phashThreshold,
            )

            // Run bundle export first (before writeInPlace mutates workingInput) when requested
            val bundleResult: BundleExportResult? = bundleExport?.let { bundleDir ->
                DualEraBundleExporter.export(
                    packRoot = workingInput,
                    bundleOutDir = bundleDir,
                    dedupOptions = dedupOptions,
                    pruneOverrides = pruneOverrides,
                    simplifyTrees = simplifyTrees,
                    codegenComponents = codegenComponents,
                    componentOverrides = parsedComponentOverrides,
                )
            }

            val migration = PackMigration(workingInput, min, max, overlayItemsDir = effectiveOverlayItems)
            var plan = migration.plan(
                stripOverrides = stripOverrides,
                dedupOptions = dedupOptions,
                pruneOverrides = pruneOverrides,
                componentOverrides = parsedComponentOverrides,
                simplifyTrees = simplifyTrees,
                codegenComponents = codegenComponents,
                buildCodegenManifest = componentManifest != null,
            )

            if (bundleResult != null && bundleResult.issues.isNotEmpty()) {
                val combinedIssues = (plan.issues + bundleResult.issues).sortedBy { it.level.ordinal }
                plan = plan.copy(issues = combinedIssues)
            }

            var zipArtifact: ZipArtifact? = null
            val destinationDir: Path? = when {
                !applying -> null
                out != null -> out
                tempStaged != null -> tempStaged
                else -> workingInput
            }

            if (applying && destinationDir != null) {
                if (destinationDir != workingInput) {
                    copyTree(workingInput, destinationDir)
                }
                migration.apply(plan, destinationDir)
                if (zipTarget != null) {
                    zipArtifact = ZipPacks.writeDeterministicZip(destinationDir, zipTarget)
                }
            }

            componentManifest?.let { manifestPath ->
                val manifest = plan.codegenManifest
                if (manifest != null) {
                    manifestPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                    Files.writeString(manifestPath, ItemComponentCodegen.renderManifest(manifest))
                }
            }

            sarif?.let { sarifPath ->
                sarifPath.toAbsolutePath().parent?.let { Files.createDirectories(it) }
                Files.writeString(sarifPath, SarifReport.render(plan))
            }

            val reportedOutDir = if (applying && tempStaged == null) destinationDir else out

            if (json) {
                echo(
                    JsonMigrationReport.render(
                        pack = pack,
                        plan = plan,
                        minFormat = min,
                        maxFormat = max,
                        applied = applying || bundleResult != null,
                        outputDir = reportedOutDir,
                        zipArtifact = zipArtifact,
                        overlayItemsDir = effectiveOverlayItems,
                        componentManifestPath = componentManifest,
                        bundleManifest = bundleResult?.manifest,
                    ),
                )
            } else {
                report(plan, effectiveOverlayItems)
                if (applying || bundleResult != null || componentManifest != null) {
                    echo("")
                    val strippedNote = if (plan.strippedOverrides.isNotEmpty()) {
                        " and stripped ${plan.strippedOverrides.size} override list(s)"
                    } else {
                        ""
                    }
                    if (reportedOutDir != null) {
                        echo("Wrote ${plan.files.size} file(s)$strippedNote to $reportedOutDir")
                    }
                    if (plan.dedupedFiles.isNotEmpty()) {
                        echo("Pruned ${plan.dedupedFiles.size} redundant overlay asset(s) (saved ${plan.savedBytes} bytes)")
                    }
                    if (plan.prunedEntries > 0) {
                        echo("Pruned ${plan.prunedEntries} redundant/unreachable override branch(es)")
                    }
                    if (plan.simplifiedReductions > 0) {
                        echo("Simplified ${plan.simplifiedReductions} predicate tree node(s)")
                    }
                    if (plan.standaloneDefinitionCount > 0) {
                        echo("Generated ${plan.standaloneDefinitionCount} standalone 26.3 item_model definition(s)")
                    }
                    if (componentManifest != null && plan.codegenManifest != null) {
                        echo("Wrote 26.3 component manifest (${plan.codegenManifest.entryCount} entries) to $componentManifest")
                    }
                    if (zipArtifact != null) {
                        echo("Zipped migrated pack to ${zipArtifact.path} (${zipArtifact.sizeBytes} bytes)")
                        echo("resource-pack-sha1=${zipArtifact.sha1}")
                    }
                    if (bundleResult != null) {
                        echo("Exported dual-era bundle to ${bundleResult.manifest.bundleDirectory}:")
                        echo("   legacy  (${bundleResult.manifest.legacySlice.minecraftRange}): ${bundleResult.legacyZip.sha1}")
                        echo("   modern  (${bundleResult.manifest.modernSlice.minecraftRange}): ${bundleResult.modernZip.sha1}")
                        echo("   hybrid  (${bundleResult.manifest.hybridUniversalSlice.minecraftRange}): ${bundleResult.hybridZip.sha1}")
                    }
                } else if (plan.itemsMigrated > 0) {
                    echo("")
                    echo("Nothing written. Pass --write to apply, --write --out <dir>, --zip <migrated.zip>, or --bundle-export <dir>.")
                }
            }

            if (plan.errors > 0 || (strict && plan.warnings > 0)) throw ProgramResult(1)
        } finally {
            tempExtracted?.toFile()?.deleteRecursively()
            tempStaged?.toFile()?.deleteRecursively()
        }
    }

    private fun report(plan: MigrationPlan, overlayDir: String?) {
        echo("== ${pack.fileName}: ${plan.itemsMigrated} item(s) with overrides")
        plan.conversions.filter { it.definition != null }.forEach { conversion ->
            val values = conversion.customModelDataValues.sorted()
            val summary = when {
                values.isEmpty() -> "no custom_model_data, other predicates only"
                values.size <= 6 -> "custom_model_data ${values.joinToString(", ")}"
                else -> "custom_model_data ${values.first()}..${values.last()} (${values.size} values)"
            }
            val label = if (conversion.sourceOverlay != null) "[${conversion.sourceOverlay}] ${conversion.item}" else conversion.item
            echo("   ${label.padEnd(40)} $summary")
        }
        val toWrite = plan.files.filter { it.relative != "pack.mcmeta" }
        val targetFolder = overlayDir?.let { "$it/assets/<ns>/items/" } ?: "assets/<ns>/items/"
        echo("")
        echo("   ${toWrite.size} definition(s) under $targetFolder, plus pack.mcmeta")
        if (plan.strippedOverrides.isNotEmpty()) {
            echo("   ${plan.strippedOverrides.size} legacy file(s) would lose their overrides list")
        }
        if (plan.dedupedFiles.isNotEmpty()) {
            echo("   ${plan.dedupedFiles.size} redundant overlay asset(s) to prune (saving ${plan.savedBytes} bytes)")
        }
        if (plan.prunedEntries > 0) {
            echo("   ${plan.prunedEntries} redundant/unreachable override branch(es) pruned")
        }
        if (plan.simplifiedReductions > 0) {
            echo("   ${plan.simplifiedReductions} predicate tree reduction(s) applied")
        }
        if (plan.standaloneDefinitionCount > 0) {
            echo("   ${plan.standaloneDefinitionCount} standalone 26.3 item_model definition(s) synthesized")
        }

        if (plan.issues.isNotEmpty()) {
            echo("")
            plan.issues.forEach { issue ->
                val where = issue.overrideIndex?.let { "${issue.item} overrides[$it]" } ?: issue.item
                echo("   ${issue.level.name.lowercase().padEnd(7)} [${issue.rule.id}] $where")
                echo("           ${issue.message}")
            }
        }
        echo("")
        echo("${plan.errors} error(s), ${plan.warnings} warning(s), ${plan.issues.count { it.level == IssueLevel.NOTE }} note(s)")
    }

    private fun fail(message: String?): Nothing {
        echo(message ?: "The command could not be carried out.", err = true)
        throw ProgramResult(2)
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { stream ->
            stream.forEach { file ->
                val target = to.resolve(from.relativize(file).toString())
                if (Files.isDirectory(file)) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}
