package com.salyvn.packmigrate.core

import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile

data class ZipArtifact(
    val path: Path,
    val sha1: String,
    val sha256: String,
    val sizeBytes: Long,
)

object ZipPacks {
    private const val MAX_ZIP_ENTRIES = 25_000
    private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024L
    private val FIXED_ZIP_TIME: FileTime = FileTime.fromMillis(315_532_800_000L)

    fun extractSafely(zipPath: Path, targetDir: Path) {
        val normalizedRoot = targetDir.toAbsolutePath().normalize()
        Files.createDirectories(normalizedRoot)
        try {
            ZipFile(zipPath.toFile()).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    count++
                    if (count > MAX_ZIP_ENTRIES) {
                        throw IOException("Zip pack $zipPath exceeds maximum entry count ($MAX_ZIP_ENTRIES)")
                    }
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val clean = normalizeEntryName(entry.name, zipPath) ?: continue
                    val dest = normalizedRoot.resolve(clean).normalize()
                    if (!dest.startsWith(normalizedRoot)) {
                        throw IOException("Refusing unsafe zip entry '${entry.name}' in $zipPath")
                    }
                    if (entry.size > MAX_ENTRY_BYTES) {
                        throw IOException("Zip entry '$clean' in $zipPath exceeds ${MAX_ENTRY_BYTES}B safety limit")
                    }
                    Files.createDirectories(dest.parent)
                    zip.getInputStream(entry).use { input ->
                        val bytes = input.readNBytes((MAX_ENTRY_BYTES + 1).toInt())
                        if (bytes.size > MAX_ENTRY_BYTES) {
                            throw IOException("Zip entry '$clean' in $zipPath exceeds ${MAX_ENTRY_BYTES}B decompressed limit")
                        }
                        Files.write(dest, bytes)
                    }
                }
            }
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("$zipPath is not a readable .zip resource pack (${failure.message})", failure)
        }
    }

    fun writeDeterministicZip(sourceDir: Path, zipTarget: Path): ZipArtifact {
        val root = sourceDir.toAbsolutePath().normalize()
        val files = Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() }
                .map { file -> root.relativize(file).invariantSeparatorsPathString to file }
                .toList()
                .sortedBy { it.first }
        }
        zipTarget.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        BufferedOutputStream(Files.newOutputStream(zipTarget)).use { rawOut ->
            ZipOutputStream(rawOut).use { zos ->
                for ((relative, file) in files) {
                    val entry = ZipEntry(relative).apply {
                        lastModifiedTime = FIXED_ZIP_TIME
                    }
                    zos.putNextEntry(entry)
                    zos.write(Files.readAllBytes(file))
                    zos.closeEntry()
                }
            }
        }
        val zipBytes = Files.readAllBytes(zipTarget)
        return ZipArtifact(
            path = zipTarget,
            sha1 = hexDigest("SHA-1", zipBytes),
            sha256 = hexDigest("SHA-256", zipBytes),
            sizeBytes = zipBytes.size.toLong(),
        )
    }

    private fun normalizeEntryName(raw: String, zipPath: Path): String? {
        val clean = raw.replace('\\', '/').trimStart('/')
        if (clean.isEmpty()) return null
        val segments = clean.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            throw IOException("Refusing unsafe zip entry '$raw' in $zipPath")
        }
        return clean
    }

    private fun hexDigest(algorithm: String, bytes: ByteArray): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
