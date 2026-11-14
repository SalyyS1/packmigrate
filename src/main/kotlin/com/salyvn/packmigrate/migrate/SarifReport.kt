package com.salyvn.packmigrate.migrate

import com.salyvn.packmigrate.core.BuildInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SarifLog(
    @SerialName("\$schema")
    val schema: String = "https://json.schemastore.org/sarif-2.1.0.json",
    val version: String = "2.1.0",
    val runs: List<SarifRun>,
)

@Serializable
data class SarifRun(
    val tool: SarifTool,
    val results: List<SarifResult>,
)

@Serializable
data class SarifTool(
    val driver: SarifDriver,
)

@Serializable
data class SarifDriver(
    val name: String,
    val version: String,
    val informationUri: String,
    val rules: List<SarifRuleDescriptor>,
)

@Serializable
data class SarifRuleDescriptor(
    val id: String,
    val name: String,
    val shortDescription: SarifMessage,
    val fullDescription: SarifMessage,
    val defaultConfiguration: SarifConfiguration,
    val helpUri: String,
)

@Serializable
data class SarifConfiguration(
    val level: String,
)

@Serializable
data class SarifMessage(
    val text: String,
)

@Serializable
data class SarifResult(
    val ruleId: String,
    val level: String,
    val message: SarifMessage,
    val locations: List<SarifLocation>,
)

@Serializable
data class SarifLocation(
    val physicalLocation: SarifPhysicalLocation,
)

@Serializable
data class SarifPhysicalLocation(
    val artifactLocation: SarifArtifactLocation,
    val region: SarifRegion = SarifRegion(startLine = 1),
)

@Serializable
data class SarifArtifactLocation(
    val uri: String,
    val uriBaseId: String = "%SRCROOT%",
)

@Serializable
data class SarifRegion(
    val startLine: Int,
)

/**
 * Renders OASIS SARIF v2.1.0 output for GitHub Code Scanning (`github/codeql-action/upload-sarif`) and CI pipelines.
 */
object SarifReport {
    private const val REPO_URL = "https://github.com/SalyyS1/packmigrate"

    private val FORMAT = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }

    fun render(plan: MigrationPlan): String {
        val rules = MigrationRule.entries.map { rule ->
            SarifRuleDescriptor(
                id = rule.id,
                name = rule.ruleName,
                shortDescription = SarifMessage(rule.shortDescription),
                fullDescription = SarifMessage(rule.fullDescription),
                defaultConfiguration = SarifConfiguration(level = toSarifLevel(rule.defaultLevel)),
                helpUri = "$REPO_URL#${rule.id.lowercase()}",
            )
        }

        val results = plan.issues.map { issue ->
            SarifResult(
                ruleId = issue.rule.id,
                level = toSarifLevel(issue.level),
                message = SarifMessage(issue.message),
                locations = listOf(
                    SarifLocation(
                        physicalLocation = SarifPhysicalLocation(
                            artifactLocation = SarifArtifactLocation(
                                uri = issue.path,
                                uriBaseId = "%SRCROOT%",
                            ),
                            region = SarifRegion(startLine = 1),
                        ),
                    ),
                ),
            )
        }

        val log = SarifLog(
            runs = listOf(
                SarifRun(
                    tool = SarifTool(
                        driver = SarifDriver(
                            name = "packmigrate",
                            version = BuildInfo.version,
                            informationUri = REPO_URL,
                            rules = rules,
                        ),
                    ),
                    results = results,
                ),
            ),
        )
        return FORMAT.encodeToString(log) + "\n"
    }

    private fun toSarifLevel(level: IssueLevel): String = when (level) {
        IssueLevel.ERROR -> "error"
        IssueLevel.WARNING -> "warning"
        IssueLevel.NOTE -> "note"
    }
}
