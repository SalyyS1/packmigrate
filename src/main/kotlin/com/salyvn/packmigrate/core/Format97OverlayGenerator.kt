package com.salyvn.packmigrate.core

import com.salyvn.packmigrate.migrate.Issue
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationRule

object Format97OverlayGenerator {

    const val FORMAT_97_DIR = "format_97"
    val FORMAT_97_MIN = PackFormat(97, 0)
    val FORMAT_97_MAX = PackFormat(97, 1)

    /**
     * Checks a pack's metadata and overlay specs for format 97 / Minecraft 26.3 compatibility and drift (PM016).
     */
    fun auditFormat97(
        minFormat: PackFormat,
        maxFormat: PackFormat,
        overlays: List<OverlayEntrySpec>,
    ): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (maxFormat.major >= 97) {
            val has97Overlay = overlays.any { it.min.major <= 97 && it.max.major >= 97 }
            if (minFormat.major < 65 && !has97Overlay) {
                issues += Issue(
                    level = IssueLevel.NOTE,
                    item = "pack.mcmeta",
                    overrideIndex = null,
                    message = "Pack targets dual-era range ${minFormat}..${maxFormat} containing Minecraft 26.3 (format 97). Consider using an isolated format_97 overlay for modern 26.3 definitions.",
                    rule = MigrationRule.PM016,
                    path = "pack.mcmeta",
                )
            }

            for (ov in overlays) {
                if (ov.max.major >= 97 && ov.min.major > ov.max.major) {
                    issues += Issue(
                        level = IssueLevel.WARNING,
                        item = "pack.mcmeta",
                        overrideIndex = null,
                        message = "Overlay '${ov.directory}' format bounds drift: min_format (${ov.min}) exceeds max_format (${ov.max}).",
                        rule = MigrationRule.PM016,
                        path = "pack.mcmeta",
                    )
                }
            }
        }

        return issues
    }

    /**
     * Creates an overlay specification for format 97 / Minecraft 26.3.
     */
    fun createSpec(directory: String = FORMAT_97_DIR): OverlayEntrySpec {
        return OverlayEntrySpec(
            directory = directory,
            min = FORMAT_97_MIN,
            max = FORMAT_97_MAX,
        )
    }
}
