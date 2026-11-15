package com.salyvn.packmigrate.modern

import com.salyvn.packmigrate.core.PackFormat
import com.salyvn.packmigrate.legacy.ModelId
import com.salyvn.packmigrate.migrate.Conversion
import com.salyvn.packmigrate.migrate.Issue
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationRule
import com.salyvn.packmigrate.migrate.PlannedFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

@Serializable
data class ComponentCodegenEntry(
    val baseItem: String,
    val targetModel: String,
    val directItemModelId: String? = null,
    val floats: Map<Int, Double> = emptyMap(),
    val flags: Map<Int, Boolean> = emptyMap(),
    val strings: Map<Int, String> = emptyMap(),
    val tintDefaults: List<Int> = emptyList(),
    val requiredComponents: List<String> = emptyList(),
    val componentValues: Map<String, String> = emptyMap(),
    val oversizedInGui: Boolean = false,
    val giveCommand26_3: String,
    val directItemModelGive26_3: String? = null,
)

@Serializable
data class ComponentCodegenManifest(
    val schemaVersion: String = "1.0",
    val targetMinecraft: String = "26.3",
    val targetPackFormat: String = "97.1",
    val entryCount: Int,
    val standaloneDefinitionsGenerated: Int,
    val entries: List<ComponentCodegenEntry>,
)

data class CodegenSynthesisResult(
    val standaloneFiles: List<PlannedFile>,
    val manifest: ComponentCodegenManifest,
    val issues: List<Issue>,
)

/**
 * Minecraft 26.3 (`format 97`) item model component code generator (`--codegen-components`, `--component-manifest`).
 *
 * 1. Synthesizes direct-addressable `assets/<custom_ns>/items/<name>.json` definitions so plugins on Paper/Vanilla
 *    1.21.4..26.3 can bind custom items via `[minecraft:item_model="custom_ns:name"]` without `custom_model_data`
 *    integer slot collisions, preserving interactive state subtrees (e.g. bow pull stages or shield blocking).
 * 2. Detects oversized GUI models (`display.gui.scale > 1.0` or non-animated textures exceeding 16x16) and emits
 *    `oversized_in_gui: true` when supported, reporting `PM017` when emitted below format 63 without overlay isolation.
 * 3. Extracts exact component constraints (`floats`, `flags`, `strings`, `colors`, `has_component`) from every
 *    migrated item dispatch tree and renders ready-to-run 26.3 `/give` commands and JSON component manifests.
 */
object ItemComponentCodegen {

    private const val FORMAT_OVERSIZED_GUI_FLOOR = 63 // 1.21.6+
    private const val FORMAT_SWAP_SCALE_FLOOR = 84    // 26.1+

    private val PARSER = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PRETTY = Json { prettyPrint = true; prettyPrintIndent = "  "; encodeDefaults = true }

    fun renderManifest(manifest: ComponentCodegenManifest): String = PRETTY.encodeToString(manifest) + "\n"

    /**
     * Audits an [ItemModelDefinition] against the effective minimum format of its target scope (rule PM017).
     */
    fun auditDefinitionFloor(
        definition: ItemModelDefinition,
        effectiveMin: PackFormat,
        item: String,
        targetPath: String,
        issues: MutableList<Issue>,
    ) {
        if (definition.oversizedInGui != null && effectiveMin.major < FORMAT_OVERSIZED_GUI_FLOOR) {
            issues += Issue(
                level = IssueLevel.WARNING,
                item = item,
                overrideIndex = null,
                message = "Item definition '$targetPath' sets oversized_in_gui, which requires pack format $FORMAT_OVERSIZED_GUI_FLOOR (1.21.6+), but target floor is $effectiveMin. Isolate inside an overlay.",
                rule = MigrationRule.PM017,
                path = targetPath,
            )
        }
        if (definition.swapAnimationScale != null) {
            if (definition.swapAnimationScale < 0.0) {
                issues += Issue(
                    level = IssueLevel.ERROR,
                    item = item,
                    overrideIndex = null,
                    message = "Item definition '$targetPath' sets negative swap_animation_scale (${definition.swapAnimationScale}).",
                    rule = MigrationRule.PM018,
                    path = targetPath,
                )
            } else if (effectiveMin.major < FORMAT_SWAP_SCALE_FLOOR) {
                issues += Issue(
                    level = IssueLevel.WARNING,
                    item = item,
                    overrideIndex = null,
                    message = "Item definition '$targetPath' sets swap_animation_scale, which requires pack format $FORMAT_SWAP_SCALE_FLOOR (26.1+), but target floor is $effectiveMin.",
                    rule = MigrationRule.PM017,
                    path = targetPath,
                )
            }
        }
    }

    /**
     * Synthesizes standalone `minecraft:item_model` definitions (when [emitStandaloneDefinitions] is true)
     * and builds the complete 26.3 component dispatch manifest across [conversions].
     */
    fun synthesize(
        packRoot: Path,
        conversions: List<Conversion>,
        minFormat: PackFormat,
        maxFormat: PackFormat,
        overlayItemsDir: String?,
        emitStandaloneDefinitions: Boolean,
    ): CodegenSynthesisResult {
        val issues = mutableListOf<Issue>()
        val standaloneByRel = LinkedHashMap<String, PlannedFile>()
        val standaloneIdsByModel = LinkedHashMap<String, String>()
        val oversizedCache = HashMap<String, Boolean>()

        val effectiveMin = if (overlayItemsDir != null) {
            maxOf(minFormat, PackFormat(46))
        } else {
            minFormat
        }
        val canEmitOversizedFlag = maxFormat.major >= FORMAT_OVERSIZED_GUI_FLOOR

        fun isModelOversized(qualifiedModel: String, sourceOverlay: String?): Boolean {
            return oversizedCache.getOrPut("$sourceOverlay|$qualifiedModel") {
                detectOversizedGuiModel(packRoot, qualifiedModel, sourceOverlay)
            }
        }

        if (emitStandaloneDefinitions) {
            for (conv in conversions) {
                val def = conv.definition ?: continue
                val customCandidates = extractStandaloneSubtrees(def.model)
                for ((primaryModel, subtree) in customCandidates) {
                    val modelId = ModelId.parse(primaryModel)
                    if (modelId.namespace == "minecraft") continue
                    val shortItemName = modelId.path.removePrefix("item/")
                    val directId = "${modelId.namespace}:$shortItemName"
                    val prefix = conv.sourceOverlay ?: overlayItemsDir
                    val relPath = if (prefix != null) {
                        "$prefix/assets/${modelId.namespace}/items/$shortItemName.json"
                    } else {
                        "assets/${modelId.namespace}/items/$shortItemName.json"
                    }
                    if (relPath in standaloneByRel) {
                        standaloneIdsByModel.putIfAbsent(primaryModel, directId)
                        continue
                    }

                    val existingTarget = packRoot.resolve(relPath)
                    val oversized = canEmitOversizedFlag && isModelOversized(primaryModel, conv.sourceOverlay)
                    val standaloneDef = ItemModelDefinition(
                        model = subtree,
                        oversizedInGui = if (oversized) true else null,
                    )
                    auditDefinitionFloor(standaloneDef, effectiveMin, directId, relPath, issues)
                    standaloneByRel[relPath] = PlannedFile(
                        relative = relPath,
                        text = standaloneDef.render(),
                        overwrites = existingTarget.exists(),
                    )
                    standaloneIdsByModel[primaryModel] = directId
                }
            }
        }

        val entries = mutableListOf<ComponentCodegenEntry>()
        for (conv in conversions) {
            val def = conv.definition ?: continue
            val baseItemId = normalizeBaseItemId(conv.item)
            collectDispatchEntries(
                node = def.model,
                baseItem = baseItemId,
                selfModel = conv.item,
                sourceOverlay = conv.sourceOverlay,
                floats = emptyMap(),
                flags = emptyMap(),
                strings = emptyMap(),
                requiredComps = emptyList(),
                compValues = emptyMap(),
                standaloneIdsByModel = standaloneIdsByModel,
                isOversized = ::isModelOversized,
                out = entries,
            )
        }

        val deduplicatedEntries = entries.distinctBy {
            listOf(it.baseItem, it.targetModel, it.floats, it.flags, it.strings, it.requiredComponents, it.componentValues)
        }

        val manifest = ComponentCodegenManifest(
            targetPackFormat = maxFormat.toString(),
            entryCount = deduplicatedEntries.size,
            standaloneDefinitionsGenerated = standaloneByRel.size,
            entries = deduplicatedEntries,
        )

        return CodegenSynthesisResult(
            standaloneFiles = standaloneByRel.values.toList(),
            manifest = manifest,
            issues = issues,
        )
    }

    /**
     * Extracts custom-namespaced subtrees from top-level custom_model_data dispatches and leaf models.
     * If a custom_model_data entry dispatches to an interactive state subtree (such as bow pulling stages)
     * whose custom models share a primary custom root, preserves the interactive subtree in the standalone item.
     */
    private fun extractStandaloneSubtrees(root: ItemModelNode): Map<String, ItemModelNode> {
        val out = LinkedHashMap<String, ItemModelNode>()

        fun visitAllLeaves(n: ItemModelNode, acc: MutableList<String>) {
            when (n) {
                is ItemModelNode.Model -> acc += n.model
                is ItemModelNode.Composite -> n.models.forEach { visitAllLeaves(it, acc) }
                is ItemModelNode.Condition -> {
                    visitAllLeaves(n.onTrue, acc)
                    visitAllLeaves(n.onFalse, acc)
                }
                is ItemModelNode.RangeDispatch -> {
                    n.entries.forEach { visitAllLeaves(it.model, acc) }
                    n.fallback?.let { visitAllLeaves(it, acc) }
                }
                is ItemModelNode.Select -> {
                    n.cases.forEach { visitAllLeaves(it.model, acc) }
                    n.fallback?.let { visitAllLeaves(it, acc) }
                }
            }
        }

        fun defaultLeaf(n: ItemModelNode): String? = when (n) {
            is ItemModelNode.Model -> n.model
            is ItemModelNode.Composite -> n.models.firstNotNullOfOrNull { defaultLeaf(it) }
            is ItemModelNode.Condition -> defaultLeaf(n.onFalse) ?: defaultLeaf(n.onTrue)
            is ItemModelNode.RangeDispatch -> n.fallback?.let { defaultLeaf(it) } ?: n.entries.firstOrNull()?.let { defaultLeaf(it.model) }
            is ItemModelNode.Select -> n.fallback?.let { defaultLeaf(it) } ?: n.cases.firstOrNull()?.let { defaultLeaf(it.model) }
        }

        if (root is ItemModelNode.RangeDispatch && root.property == "minecraft:custom_model_data") {
            for (entry in root.entries) {
                val primary = defaultLeaf(entry.model) ?: continue
                if (ModelId.parse(primary).namespace != "minecraft") {
                    out.putIfAbsent(primary, entry.model)
                }
            }
        }

        val allLeaves = mutableListOf<String>()
        visitAllLeaves(root, allLeaves)
        for (leaf in allLeaves) {
            if (ModelId.parse(leaf).namespace != "minecraft") {
                out.putIfAbsent(leaf, ItemModelNode.Model(leaf))
            }
        }
        return out
    }

    private fun collectDispatchEntries(
        node: ItemModelNode,
        baseItem: String,
        selfModel: String,
        sourceOverlay: String?,
        floats: Map<Int, Double>,
        flags: Map<Int, Boolean>,
        strings: Map<Int, String>,
        requiredComps: List<String>,
        compValues: Map<String, String>,
        standaloneIdsByModel: Map<String, String>,
        isOversized: (String, String?) -> Boolean,
        out: MutableList<ComponentCodegenEntry>,
    ) {
        when (node) {
            is ItemModelNode.Model -> {
                if (node.model == selfModel && floats.isEmpty() && flags.isEmpty() && strings.isEmpty() && requiredComps.isEmpty() && compValues.isEmpty()) {
                    return
                }
                val directId = standaloneIdsByModel[node.model]
                val tintColors = node.tints.mapNotNull { tint ->
                    when (tint) {
                        is TintSource.Constant -> tint.value
                        is TintSource.CustomModelData -> tint.defaultColor
                        is TintSource.Dye -> tint.defaultColor
                        else -> null
                    }
                }
                val oversized = isOversized(node.model, sourceOverlay)
                val giveCmd = buildGiveCommand(baseItem, floats, flags, strings, requiredComps, compValues)
                val directGive = directId?.let { "/give @s $baseItem[minecraft:item_model=\"$it\"]" }
                out += ComponentCodegenEntry(
                    baseItem = baseItem,
                    targetModel = node.model,
                    directItemModelId = directId,
                    floats = floats,
                    flags = flags,
                    strings = strings,
                    tintDefaults = tintColors,
                    requiredComponents = requiredComps.distinct(),
                    componentValues = compValues,
                    oversizedInGui = oversized,
                    giveCommand26_3 = giveCmd,
                    directItemModelGive26_3 = directGive,
                )
            }

            is ItemModelNode.Composite -> {
                val primary = node.models.firstOrNull() ?: return
                collectDispatchEntries(
                    primary, baseItem, selfModel, sourceOverlay,
                    floats, flags, strings, requiredComps, compValues,
                    standaloneIdsByModel, isOversized, out,
                )
            }

            is ItemModelNode.RangeDispatch -> {
                if (node.property == "minecraft:custom_model_data") {
                    val slot = node.index ?: 0
                    for (entry in node.entries) {
                        collectDispatchEntries(
                            entry.model, baseItem, selfModel, sourceOverlay,
                            floats + (slot to entry.threshold), flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                    node.fallback?.let {
                        collectDispatchEntries(
                            it, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                } else {
                    node.fallback?.let {
                        collectDispatchEntries(
                            it, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                    for (entry in node.entries) {
                        collectDispatchEntries(
                            entry.model, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                }
            }

            is ItemModelNode.Condition -> {
                when (node.property) {
                    "minecraft:has_component" -> {
                        val comp = node.component
                        val trueComps = if (comp != null) requiredComps + comp else requiredComps
                        collectDispatchEntries(
                            node.onTrue, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, trueComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                        collectDispatchEntries(
                            node.onFalse, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                    "minecraft:custom_model_data" -> {
                        val slot = node.index ?: 0
                        collectDispatchEntries(
                            node.onTrue, baseItem, selfModel, sourceOverlay,
                            floats, flags + (slot to true), strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                        collectDispatchEntries(
                            node.onFalse, baseItem, selfModel, sourceOverlay,
                            floats, flags + (slot to false), strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                    else -> {
                        collectDispatchEntries(
                            node.onFalse, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                        collectDispatchEntries(
                            node.onTrue, baseItem, selfModel, sourceOverlay,
                            floats, flags, strings, requiredComps, compValues,
                            standaloneIdsByModel, isOversized, out,
                        )
                    }
                }
            }

            is ItemModelNode.Select -> {
                when (node.property) {
                    "minecraft:custom_model_data" -> {
                        val slot = node.index ?: 0
                        for (c in node.cases) {
                            val firstVal = c.whenValues.firstOrNull() ?: continue
                            collectDispatchEntries(
                                c.model, baseItem, selfModel, sourceOverlay,
                                floats, flags, strings + (slot to firstVal), requiredComps, compValues,
                                standaloneIdsByModel, isOversized, out,
                            )
                        }
                    }
                    "minecraft:component" -> {
                        val comp = node.component
                        for (c in node.cases) {
                            val firstVal = c.whenValues.firstOrNull() ?: continue
                            val nextMap = if (comp != null) compValues + (comp to firstVal) else compValues
                            collectDispatchEntries(
                                c.model, baseItem, selfModel, sourceOverlay,
                                floats, flags, strings, requiredComps, nextMap,
                                standaloneIdsByModel, isOversized, out,
                            )
                        }
                    }
                    else -> {
                        for (c in node.cases) {
                            collectDispatchEntries(
                                c.model, baseItem, selfModel, sourceOverlay,
                                floats, flags, strings, requiredComps, compValues,
                                standaloneIdsByModel, isOversized, out,
                            )
                        }
                    }
                }
                node.fallback?.let {
                    collectDispatchEntries(
                        it, baseItem, selfModel, sourceOverlay,
                        floats, flags, strings, requiredComps, compValues,
                        standaloneIdsByModel, isOversized, out,
                    )
                }
            }
        }
    }

    private fun buildGiveCommand(
        baseItem: String,
        floats: Map<Int, Double>,
        flags: Map<Int, Boolean>,
        strings: Map<Int, String>,
        requiredComps: List<String>,
        compValues: Map<String, String>,
    ): String {
        val parts = mutableListOf<String>()

        if (floats.isNotEmpty() || flags.isNotEmpty() || strings.isNotEmpty()) {
            val cmdFields = mutableListOf<String>()
            if (floats.isNotEmpty()) {
                val maxIdx = floats.keys.maxOrNull() ?: 0
                val list = (0..maxIdx).joinToString(",") { i ->
                    val v = floats[i] ?: 0.0
                    val fmt = if (v == v.toLong().toDouble()) "${v.toLong()}.0f" else "${v}f"
                    fmt
                }
                cmdFields += "floats:[$list]"
            }
            if (flags.isNotEmpty()) {
                val maxIdx = flags.keys.maxOrNull() ?: 0
                val list = (0..maxIdx).joinToString(",") { i -> if (flags[i] == true) "1b" else "0b" }
                cmdFields += "flags:[$list]"
            }
            if (strings.isNotEmpty()) {
                val maxIdx = strings.keys.maxOrNull() ?: 0
                val list = (0..maxIdx).joinToString(",") { i -> "\"${(strings[i] ?: "").replace("\"", "\\\"")}\"" }
                cmdFields += "strings:[$list]"
            }
            parts += "minecraft:custom_model_data={${cmdFields.joinToString(",")}}"
        }

        for ((comp, value) in compValues) {
            parts += "$comp=\"${value.replace("\"", "\\\"")}\""
        }
        for (comp in requiredComps.distinct()) {
            if (comp !in compValues) {
                parts += "$comp={}"
            }
        }

        return if (parts.isEmpty()) {
            "/give @s $baseItem"
        } else {
            "/give @s $baseItem[${parts.joinToString(",")}]"
        }
    }

    private fun normalizeBaseItemId(modelRef: String): String {
        val id = ModelId.parse(modelRef)
        return "${id.namespace}:${id.path.removePrefix("item/")}"
    }

    /**
     * Checks whether a model JSON sets `display.gui.scale > 1.0` or points to a non-animated PNG > 16x16.
     */
    private fun detectOversizedGuiModel(packRoot: Path, qualifiedModel: String, sourceOverlay: String?): Boolean {
        val id = ModelId.parse(qualifiedModel)
        val candidates = buildList {
            if (sourceOverlay != null) add(packRoot.resolve(sourceOverlay).resolve(id.relativeFile))
            add(packRoot.resolve(id.relativeFile))
        }
        val modelFile = candidates.firstOrNull { it.exists() && it.isRegularFile() } ?: return false
        val rootObj = try {
            PARSER.parseToJsonElement(Files.readString(modelFile)).jsonObject
        } catch (_: Exception) {
            return false
        }

        // Check display.gui.scale > 1.0
        val guiScale = ((rootObj["display"] as? JsonObject)?.get("gui") as? JsonObject)?.get("scale") as? JsonArray
        if (guiScale != null) {
            val scales = guiScale.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            if (scales.any { it > 1.0 }) return true
        }

        // Check referenced PNG texture dimensions (> 16x16 without .mcmeta animation frametime)
        val textures = rootObj["textures"] as? JsonObject ?: return false
        for ((_, texEl) in textures) {
            val texRef = (texEl as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            if (texRef.startsWith("#")) continue
            val (tNs, tPath) = if (':' in texRef) {
                texRef.substringBefore(':') to texRef.substringAfter(':')
            } else {
                "minecraft" to texRef
            }
            val pngRel = "assets/$tNs/textures/$tPath.png"
            val pngPath = (if (sourceOverlay != null) packRoot.resolve(sourceOverlay).resolve(pngRel) else null)
                ?.takeIf { it.exists() }
                ?: packRoot.resolve(pngRel).takeIf { it.exists() }
                ?: continue
            val mcmetaPath = pngPath.resolveSibling("${pngPath.fileName}.mcmeta")
            if (mcmetaPath.exists()) continue // Animated sprite strip; height > 16 is expected
            val dims = readPngDimensions(pngPath) ?: continue
            if (dims.first > 32 || dims.second > 32) return true
        }
        return false
    }

    private fun readPngDimensions(path: Path): Pair<Int, Int>? {
        return try {
            val header = Files.newInputStream(path).use { it.readNBytes(24) }
            if (header.size < 24) return null
            if (header[0] != 0x89.toByte() || header[1] != 0x50.toByte() || header[2] != 0x4E.toByte() || header[3] != 0x47.toByte()) {
                return null
            }
            val buf = ByteBuffer.wrap(header, 16, 8)
            val width = buf.int
            val height = buf.int
            if (width > 0 && height > 0) width to height else null
        } catch (_: Exception) {
            null
        }
    }
}
