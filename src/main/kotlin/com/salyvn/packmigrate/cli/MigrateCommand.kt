package com.salyvn.packmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.salyvn.packmigrate.core.PackFormats
import com.salyvn.packmigrate.core.PackMetaException
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationPlan
import com.salyvn.packmigrate.migrate.PackMigration
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isDirectory

/**
 * The one command.
 *
 * Default is a plan: what would be written, and every issue, without touching the pack. `--write` applies
 * it in place, or into `--out` if you would rather keep the original. The legacy model files are left
 * alone either way unless `--strip-overrides` is given, because a pack that still has to serve a 1.21.3
 * client needs them and a definition that references them does not mind.
 */
class MigrateCommand : CliktCommand(name = "packmigrate") {

    override fun help(context: Context) =
        "Convert CustomModelData overrides into 1.21.4+ item model definitions, and say what could not be converted."

    private val pack by argument("pack", help = "Pack directory").path(mustExist = true)

    private val versions by option(
        "--versions",
        help = "Range pack.mcmeta should claim, e.g. 1.21.4..26.2 (default: 1.21.4 to the newest known release)",
    )

    private val write by option("--write", help = "Write the files instead of listing them").flag()

    private val out by option("--out", help = "Write into a copy here instead of in place").path()

    private val stripOverrides by option(
        "--strip-overrides",
        help = "Also remove the overrides list from each legacy model file",
    ).flag()

    private val strict by option("--strict", help = "Exit 1 on warnings too").flag()

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
        if (!pack.isDirectory()) fail("$pack is not a directory. Unzip the pack first.")
        val (min, max) = PackFormats.parseRange(versions ?: "1.21.4..${PackFormats.newestRelease}")
        if (min.major < PackFormats.formatOf("1.21.4")) {
            fail("Item model definitions exist from 1.21.4 (format 46); a range starting at $min cannot use them.")
        }

        val plan = PackMigration(pack, min, max).plan(stripOverrides)
        report(plan)

        if (write) {
            val destination = out ?: pack
            if (out != null) copyTree(pack, destination)
            PackMigration(pack, min, max).apply(plan, destination)
            echo("")
            echo("Wrote ${plan.files.size} file(s)${if (plan.strippedOverrides.isNotEmpty()) " and stripped ${plan.strippedOverrides.size} override list(s)" else ""} to $destination")
        } else if (plan.itemsMigrated > 0) {
            echo("")
            echo("Nothing written. Pass --write to apply, or --write --out <dir> to keep the original.")
        }

        if (plan.errors > 0 || (strict && plan.warnings > 0)) throw ProgramResult(1)
    }

    private fun report(plan: MigrationPlan) {
        echo("== ${pack.fileName}: ${plan.itemsMigrated} item(s) with overrides")
        plan.conversions.filter { it.definition != null }.forEach { conversion ->
            val values = conversion.customModelDataValues.sorted()
            val summary = when {
                values.isEmpty() -> "no custom_model_data, other predicates only"
                values.size <= 6 -> "custom_model_data ${values.joinToString(", ")}"
                else -> "custom_model_data ${values.first()}..${values.last()} (${values.size} values)"
            }
            echo("   ${conversion.item.padEnd(40)} $summary")
        }
        val toWrite = plan.files.filter { it.relative != "pack.mcmeta" }
        echo("")
        echo("   ${toWrite.size} definition(s) under assets/<ns>/items/, plus pack.mcmeta")
        if (plan.strippedOverrides.isNotEmpty()) echo("   ${plan.strippedOverrides.size} legacy file(s) would lose their overrides list")

        if (plan.issues.isNotEmpty()) {
            echo("")
            plan.issues.forEach { issue ->
                val where = issue.overrideIndex?.let { "${issue.item} overrides[$it]" } ?: issue.item
                echo("   ${issue.level.name.lowercase().padEnd(7)} $where")
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
