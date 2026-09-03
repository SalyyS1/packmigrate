package com.salyvn.packmigrate.legacy

/**
 * Model references as packs write them, normalised to what the client resolves them to.
 *
 * Legacy override models are written every way: `item/foo`, `minecraft:item/foo`, `mypack:item/foo`,
 * and occasionally `foo` with the `item/` prefix forgotten. The client reads the first three as a path
 * under `assets/<ns>/models/`; the fourth is a file that does not exist, and the tool has to say so rather
 * than emit a definition that fails quietly.
 */
data class ModelId(val namespace: String, val path: String) {
    /** `minecraft:item/foo`, always with the namespace, which is how a definition spells it. */
    val qualified: String get() = "$namespace:$path"

    /** Where the client looks for it. */
    val relativeFile: String get() = "assets/$namespace/models/$path.json"

    companion object {
        fun parse(reference: String): ModelId {
            val trimmed = reference.trim()
            val colon = trimmed.indexOf(':')
            return if (colon >= 0) {
                ModelId(trimmed.substring(0, colon), trimmed.substring(colon + 1))
            } else {
                ModelId("minecraft", trimmed)
            }
        }
    }
}
