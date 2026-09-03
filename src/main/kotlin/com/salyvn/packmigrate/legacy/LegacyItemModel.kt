package com.salyvn.packmigrate.legacy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** One `overrides` entry: the predicate values it needs and the model it selects. */
data class Override(val predicate: Map<String, Double>, val model: String, val index: Int) {
    val customModelData: Int? get() = predicate["custom_model_data"]?.toInt()

    /** Predicates other than `custom_model_data`, which the modern format handles differently. */
    val otherPredicates: Map<String, Double> get() = predicate.filterKeys { it != "custom_model_data" }
}

/**
 * A pre-1.21.4 `assets/<ns>/models/item/<item>.json`, read for what the migration needs: the overrides,
 * and whether the file also carries a model of its own that a definition has to keep pointing at.
 *
 * The file stays in place afterwards — the modern format references models, it does not replace them — so
 * only the `overrides` list is interpreted, and everything else (`parent`, `textures`, `display`) is left
 * exactly as the author wrote it.
 */
class LegacyItemModel(val namespace: String, val item: String, val overrides: List<Override>, val rawText: String) {
    val hasOverrides: Boolean get() = overrides.isNotEmpty()

    /** `minecraft:item/diamond_sword` — how a definition refers to this file's own geometry. */
    val selfModel: String get() = "$namespace:item/$item"

    companion object {
        private val FORMAT = Json { ignoreUnknownKeys = true; isLenient = true }

        fun parse(namespace: String, item: String, text: String): LegacyItemModel {
            val root = try {
                FORMAT.parseToJsonElement(text).jsonObject
            } catch (failure: Exception) {
                throw LegacyParseException("$namespace:item/$item is not a JSON object: ${failure.message?.lineSequence()?.firstOrNull()}")
            }
            val overrides = (root["overrides"] as? JsonArray)?.mapIndexed { index, element ->
                parseOverride(element, index, "$namespace:item/$item")
            } ?: emptyList()
            return LegacyItemModel(namespace, item, overrides, text)
        }

        private fun parseOverride(element: JsonElement, index: Int, owner: String): Override {
            val obj = element as? JsonObject ?: throw LegacyParseException("$owner overrides[$index] is not an object")
            val model = (obj["model"] as? JsonPrimitive)?.content
                ?: throw LegacyParseException("$owner overrides[$index] has no \"model\"")
            val predicate = (obj["predicate"] as? JsonObject)?.entries?.associate { (key, value) ->
                val number = (value as? JsonPrimitive)?.doubleOrNull
                    ?: throw LegacyParseException("$owner overrides[$index] predicate \"$key\" is not a number")
                key to number
            } ?: emptyMap()
            return Override(predicate, model, index)
        }

        /** Strips the file text down to a JSON object with `overrides` removed, for `--strip-overrides`. */
        fun withoutOverrides(text: String): String? {
            val root = try {
                FORMAT.parseToJsonElement(text).jsonObject
            } catch (failure: Exception) {
                return null
            }
            if ("overrides" !in root) return null
            val kept = JsonObject(root.filterKeys { it != "overrides" })
            return Json { prettyPrint = true; prettyPrintIndent = "  " }.encodeToString(JsonObject.serializer(), kept) + "\n"
        }
    }
}

class LegacyParseException(message: String) : Exception(message)
