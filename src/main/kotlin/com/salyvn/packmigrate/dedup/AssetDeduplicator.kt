package com.salyvn.packmigrate.dedup

import com.salyvn.packmigrate.migrate.Issue
import com.salyvn.packmigrate.migrate.IssueLevel
import com.salyvn.packmigrate.migrate.MigrationRule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

data class DedupOptions(
    val enableDedup: Boolean = false,
    val enablePhash: Boolean = false,
    val phashThreshold: Int = 2,
)

data class DedupResult(
    val redundantOverlayFiles: List<String>,
    val savedBytes: Long,
    val issues: List<Issue>,
)

object AssetDeduplicator {

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Audits and optionally prunes redundant overlay assets and scans for duplicate textures across paths.
     */
    fun analyze(
        root: Path,
        overlayDirs: List<String>,
        options: DedupOptions,
    ): DedupResult {
        val issues = mutableListOf<Issue>()
        val redundantFiles = mutableListOf<String>()
        var savedBytes = 0L

        // 1. Audit overlay files against root base assets
        for (overlay in overlayDirs) {
            val overlayPath = root.resolve(overlay)
            if (!overlayPath.exists()) continue
            val assetsPath = overlayPath.resolve("assets")
            if (!assetsPath.exists()) continue

            Files.walk(assetsPath).use { stream ->
                stream.filter { it.isRegularFile() }.forEach { file ->
                    val relInsideOverlay = overlayPath.relativize(file).toString().replace('\\', '/')
                    val fullRelPath = "$overlay/$relInsideOverlay"
                    val baseFile = root.resolve(relInsideOverlay)

                    if (baseFile.exists() && baseFile.isRegularFile()) {
                        val overlayBytes = Files.readAllBytes(file)
                        val baseBytes = Files.readAllBytes(baseFile)

                        val exactMatch = overlayBytes.contentEquals(baseBytes)
                        var pixelMatch = false
                        var nearDist = -1
                        var fpOverlay: ImageFingerprint? = null

                        if (!exactMatch && options.enablePhash && file.extension.equals("png", ignoreCase = true)) {
                            val fp1 = PerceptualHash.inspect(overlayBytes)
                            val fp2 = PerceptualHash.inspect(baseBytes)
                            if (fp1 != null && fp2 != null) {
                                fpOverlay = fp1
                                if (fp1.pixelSha256 == fp2.pixelSha256) {
                                    pixelMatch = true
                                } else {
                                    val dist = PerceptualHash.distance(fp1, fp2)
                                    if (dist <= options.phashThreshold) {
                                        nearDist = dist
                                    }
                                }
                            }
                        }

                        if (exactMatch || pixelMatch) {
                            redundantFiles += fullRelPath
                            savedBytes += overlayBytes.size
                            val detail = if (exactMatch) {
                                "Overlay file '$fullRelPath' has identical bytes to '$relInsideOverlay' in base pack."
                            } else {
                                "Overlay texture '$fullRelPath' has identical RGBA raster (${fpOverlay?.phashHex}) to '$relInsideOverlay' in base pack."
                            }
                            issues += Issue(
                                level = IssueLevel.WARNING,
                                item = fullRelPath,
                                overrideIndex = null,
                                message = "$detail Minecraft overlays automatically inherit base assets; this file is redundant.",
                                rule = MigrationRule.PM012,
                                path = fullRelPath,
                            )
                        } else if (nearDist >= 0) {
                            issues += Issue(
                                level = IssueLevel.NOTE,
                                item = fullRelPath,
                                overrideIndex = null,
                                message = "Overlay texture '$fullRelPath' is perceptually near-identical to base texture '$relInsideOverlay' (hamming=$nearDist/64, ${fpOverlay?.phashHex}).",
                                rule = MigrationRule.PM013,
                                path = fullRelPath,
                            )
                        }
                    }
                }
            }
        }

        // 2. Scan cross-path texture duplicates across all PNGs in the pack
        if (options.enablePhash) {
            val pngFiles = mutableListOf<Pair<String, ByteArray>>()
            Files.walk(root).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.equals("png", ignoreCase = true) }
                    .forEach { path ->
                        val rel = root.relativize(path).toString().replace('\\', '/')
                        if (!redundantFiles.contains(rel)) {
                            pngFiles += rel to Files.readAllBytes(path)
                        }
                    }
            }

            val byPixelSha = mutableMapOf<String, MutableList<Pair<String, ImageFingerprint>>>()
            for ((rel, bytes) in pngFiles) {
                val fp = PerceptualHash.inspect(bytes) ?: continue
                byPixelSha.getOrPut(fp.pixelSha256) { mutableListOf() } += (rel to fp)
            }

            for ((_, group) in byPixelSha) {
                if (group.size > 1) {
                    val fp = group.first().second
                    val paths = group.map { it.first }
                    issues += Issue(
                        level = IssueLevel.NOTE,
                        item = paths.first(),
                        overrideIndex = null,
                        message = "Identical ${fp.width}x${fp.height} RGBA texture shared across ${paths.size} distinct paths: ${paths.joinToString(", ")} (${fp.phashHex}).",
                        rule = MigrationRule.PM013,
                        path = paths.first(),
                    )
                }
            }
        }

        return DedupResult(
            redundantOverlayFiles = redundantFiles,
            savedBytes = savedBytes,
            issues = issues,
        )
    }
}
