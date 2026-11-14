package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.core.ZipArtifact
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

@Serializable
data class MigratedItemJson(
    val item: String,
    val customModelDataValues: List<Int>,
    val referencedModels: List<String>,
)

@Serializable
data class MigrationIssueJson(
    val level: String,
    val item: String,
    val overrideIndex: Int? = null,
    val message: String,
)

@Serializable
data class MigrationReportPayload(
    val pack: String,
    val applied: Boolean,
    val outputDir: String? = null,
    val zipPath: String? = null,
    val zipSha1: String? = null,
    val zipSha256: String? = null,
    val minFormat: String,
    val maxFormat: String,
    val itemsMigrated: Int,
    val plannedFiles: List<String>,
    val strippedOverrideCount: Int,
    val errorCount: Int,
    val warningCount: Int,
    val noteCount: Int,
    val items: List<MigratedItemJson>,
    val issues: List<MigrationIssueJson>,
)

object JsonMigrationReport {
    private val FORMAT = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun render(
        pack: Path,
        plan: MigrationPlan,
        minFormat: PackFormat,
        maxFormat: PackFormat,
        applied: Boolean,
        outputDir: Path?,
        zipArtifact: ZipArtifact?,
    ): String {
        val payload = MigrationReportPayload(
            pack = pack.toString(),
            applied = applied,
            outputDir = outputDir?.toString(),
            zipPath = zipArtifact?.path?.toString(),
            zipSha1 = zipArtifact?.sha1,
            zipSha256 = zipArtifact?.sha256,
            minFormat = minFormat.toString(),
            maxFormat = maxFormat.toString(),
            itemsMigrated = plan.itemsMigrated,
            plannedFiles = plan.files.map { it.relative },
            strippedOverrideCount = plan.strippedOverrides.size,
            errorCount = plan.errors,
            warningCount = plan.warnings,
            noteCount = plan.issues.count { it.level == IssueLevel.NOTE },
            items = plan.conversions
                .filter { it.definition != null }
                .map { conv ->
                    MigratedItemJson(
                        item = conv.item,
                        customModelDataValues = conv.customModelDataValues.sorted(),
                        referencedModels = conv.referencedModels.map { it.qualified },
                    )
                },
            issues = plan.issues.map { issue ->
                MigrationIssueJson(
                    level = issue.level.name.lowercase(),
                    item = issue.item,
                    overrideIndex = issue.overrideIndex,
                    message = issue.message,
                )
            },
        )
        return FORMAT.encodeToString(payload)
    }
}
