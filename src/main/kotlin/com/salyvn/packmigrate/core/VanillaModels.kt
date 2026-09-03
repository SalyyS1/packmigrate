package com.salyvn.packmigrate.core

/**
 * The item model paths vanilla ships, so a definition that references `minecraft:item/bow_pulling_1` is
 * not reported as missing when the pack simply relies on the game's own file.
 *
 * The list is names only — 1 309 paths under `assets/minecraft/models/item/` at 26.3-pre-1 — and was
 * generated from the vanilla asset tree misode/mcmeta publishes. It is a list of file names, not the
 * files; nothing of Mojang's is reproduced. A name that was added in a later version will be reported as
 * missing until the list is regenerated, which is the safe direction to be wrong in.
 */
object VanillaModels {
    private val paths: Set<String> by lazy {
        VanillaModels::class.java.getResourceAsStream("/vanilla-item-models.txt")
            ?.bufferedReader()
            ?.readLines()
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    /** True when `minecraft:<path>` is a model the game ships. */
    fun has(path: String): Boolean = path in paths

    val count: Int get() = paths.size
}
