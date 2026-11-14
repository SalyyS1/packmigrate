package com.salyvn.packmigrate.migrate

/**
 * Diagnostic rule catalog for OASIS SARIF v2.1.0 and JSON migration reports.
 */
enum class MigrationRule(
    val id: String,
    val ruleName: String,
    val defaultLevel: IssueLevel,
    val shortDescription: String,
    val fullDescription: String,
) {
    PM001(
        id = "PM001",
        ruleName = "LegacyModelParseError",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Malformed legacy item model or pack metadata JSON",
        fullDescription = "A legacy item model or pack.mcmeta file could not be parsed as valid JSON or contained an invalid overrides structure.",
    ),
    PM002(
        id = "PM002",
        ruleName = "UnknownLegacyPredicate",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Unknown legacy item model predicate",
        fullDescription = "An override entry specifies a predicate key that pre-1.21.4 Minecraft never defined and cannot be mapped to a 1.21.4+ property.",
    ),
    PM003(
        id = "PM003",
        ruleName = "MissingReferencedModel",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Referenced item model is missing from pack and vanilla",
        fullDescription = "A migrated item definition references a model identifier whose JSON file is absent from both the resource pack and vanilla Minecraft.",
    ),
    PM004(
        id = "PM004",
        ruleName = "ShadowedCustomModelData",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Duplicate unconditional custom_model_data entry shadows earlier override",
        fullDescription = "The same custom_model_data value is declared multiple times without secondary predicates, leaving earlier declarations unreachable.",
    ),
    PM005(
        id = "PM005",
        ruleName = "ExistingItemDefinitionOverwritten",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Existing 1.21.4+ item model definition would be overwritten",
        fullDescription = "The target assets/<ns>/items/<item>.json file already exists in the pack and would be replaced by the migrated definition.",
    ),
    PM006(
        id = "PM006",
        ruleName = "SharedCustomModelDataAcrossItems",
        defaultLevel = IssueLevel.NOTE,
        shortDescription = "Multiple items share the same custom_model_data threshold",
        fullDescription = "Two or more item models declare the same custom_model_data integer threshold; server plugin configs must pair the value with its base item.",
    ),
    PM007(
        id = "PM007",
        ruleName = "VanillaPredicateQuirkSynthesized",
        defaultLevel = IssueLevel.NOTE,
        shortDescription = "Item-specific vanilla predicate dispatch synthesized",
        fullDescription = "Implicit pre-1.21.4 item behavior (such as compass lodestone tracking, clock dimension source, or post-1.21.3 trim materials) was made explicit in the generated tree.",
    ),
    PM008(
        id = "PM008",
        ruleName = "InvalidOverlayDirectory",
        defaultLevel = IssueLevel.ERROR,
        shortDescription = "Invalid overlays.entries directory or format bounds in pack.mcmeta",
        fullDescription = "An overlay entry in pack.mcmeta violates Minecraft's [a-z0-9_-]+ directory naming rule or omits required format bounds.",
    ),
    PM009(
        id = "PM009",
        ruleName = "MissingOverlayDirectory",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Referenced overlay directory is missing from pack",
        fullDescription = "An entry in pack.mcmeta overlays.entries points to a directory that does not exist in the pack root.",
    ),
    PM010(
        id = "PM010",
        ruleName = "OverlayFormatEraMigrated",
        defaultLevel = IssueLevel.NOTE,
        shortDescription = "Legacy overlay formats upgraded for 1.21.9 through 26.3 compatibility",
        fullDescription = "An overlays.entries range using legacy formats was upgraded with min_format and max_format fields required by Minecraft 1.21.9 through 26.3.",
    ),
    PM011(
        id = "PM011",
        ruleName = "NonIntegerOrNegativeCustomModelData",
        defaultLevel = IssueLevel.WARNING,
        shortDescription = "Fractional or negative custom_model_data threshold",
        fullDescription = "A legacy custom_model_data predicate uses a negative or non-integer float threshold, which may not align with integer CustomModelData values converted to floats[0].",
    ),
}
