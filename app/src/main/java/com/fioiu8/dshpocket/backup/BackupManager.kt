package com.fioiu8.dshpocket.backup

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Engine-data backup: a plain zip of `$DSH_HOME` minus everything credential-shaped.
 *
 * Why zip and not tar.gz: java.util.zip is on the platform, so the archive has no new
 * dependency, and the SAF document picker hands back an opaque stream either way.
 * Why a manifest entry first: restore validates the archive shape before touching a
 * single existing file, so a truncated or foreign archive fails with a reason instead
 * of wiping the engine home.
 *
 * What is deliberately NOT backed up: the workspace (the user's own files, reachable
 * and often huge) and the credential store (machine secrets and API keys must not
 * leave the device in a shareable file; a restored install asks for keys again).
 * `$DSH_HOME/cordis.patch.yml` is regenerated on every boot, but shipping it makes the
 * archive self-describing, so it rides along.
 */
object BackupManager {

    const val MANIFEST_ENTRY = "dsh-pocket-backup.json"
    const val SCHEMA_VERSION = 1

    data class Manifest(
        val schemaVersion: Int,
        val createdAt: String,
        val appVersion: String,
        val dshVersion: String?,
        val fileCount: Int,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("createdAt", createdAt)
            .put("appVersion", appVersion)
            .put("dshVersion", dshVersion ?: JSONObject.NULL)
            .put("fileCount", fileCount)

        companion object {
            fun fromJson(text: String): Manifest? = runCatching {
                val json = JSONObject(text)
                Manifest(
                    schemaVersion = json.getInt("schemaVersion"),
                    createdAt = json.getString("createdAt"),
                    appVersion = json.optString("appVersion"),
                    dshVersion = json.optString("dshVersion").takeIf { it.isNotBlank() },
                    fileCount = json.optInt("fileCount", -1),
                )
            }.getOrNull()
        }
    }

    data class ExportResult(val fileCount: Int, val totalBytes: Long)

    sealed interface RestoreOutcome {
        data class Done(val fileCount: Int) : RestoreOutcome
        data class BadArchive(val reason: String) : RestoreOutcome
    }

    /**
     * The exclusion rule, applied to `'/'`-separated paths relative to the engine home.
     *
     * Credential-shaped everything: the engine's credential directory and yaml, private
     * key material, `.ssh`/`.gnupg`. Logs are regenerable noise and often the largest
     * part of the home. Kept conservative on purpose: excluding one file too many costs
     * a re-entered API key, excluding one too few costs a secret in a shareable file.
     */
    fun isExcluded(relativePath: String): Boolean {
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return true
        val name = segments.last()
        if (segments.any { it.equals("credentials", ignoreCase = true) }) return true
        if (segments.any { it == ".ssh" || it == ".gnupg" }) return true
        // Logs live at the top of the home; the whole tree is regenerable noise.
        if (segments.first().equals("logs", ignoreCase = true)) return true
        if (name.startsWith(".credentials")) return true
        if (name.endsWith(".key")) return true
        return false
    }

    /** Archives [dshHome] into [output]. A missing home archives the manifest alone. */
    fun export(dshHome: File, output: OutputStream, manifest: Manifest): ExportResult {
        var count = 0
        var bytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifest.toJson().toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            if (dshHome.isDirectory) {
                dshHome.walkTopDown()
                    .filter { it.isFile }
                    .sortedBy { it.absolutePath }
                    .forEach { file ->
                        val relative = file.relativeTo(dshHome).invariantSeparatorsPath
                        if (!isExcluded(relative)) {
                            val entry = ZipEntry(relative).apply { time = file.lastModified() }
                            zip.putNextEntry(entry)
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                            count += 1
                            bytes += file.length()
                        }
                    }
            }
        }
        return ExportResult(count, bytes)
    }

    /**
     * Restores [archive] over [dshHome].
     *
     * The two-pass swap keeps a mid-air failure from destroying the current home: the
     * archive is fully extracted into a sibling temp directory first, and only a complete
     * extraction is swapped in (old home renamed aside, temp renamed in, old deleted).
     * A bad archive — no manifest, wrong schema, hostile paths — aborts before the swap.
     */
    fun restore(archive: InputStream, dshHome: File, stagingDir: File = File(dshHome.parentFile, "dsh-home-restore-tmp")): RestoreOutcome {
        val manifest: Manifest
        val entries = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(archive.buffered()).use { zip ->
            val first = zip.nextEntry ?: return RestoreOutcome.BadArchive("archive is empty")
            if (first.name != MANIFEST_ENTRY) {
                return RestoreOutcome.BadArchive("first entry is not $MANIFEST_ENTRY")
            }
            manifest = Manifest.fromJson(zip.readBytes().toString(Charsets.UTF_8))
                ?: return RestoreOutcome.BadArchive("manifest is not readable JSON")
            if (manifest.schemaVersion != SCHEMA_VERSION) {
                return RestoreOutcome.BadArchive("unsupported schema version ${manifest.schemaVersion}")
            }
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val relative = entry.name
                when {
                    relative == MANIFEST_ENTRY -> Unit
                    isExcluded(relative) -> Unit
                    !isSafeEntryName(relative) -> return RestoreOutcome.BadArchive("unsafe path in archive: $relative")
                    else -> entries += relative to zip.readBytes()
                }
            }
        }

        stagingDir.deleteRecursively()
        stagingDir.mkdirs()
        return try {
            for ((relative, bytes) in entries.sortedBy { it.first }) {
                val target = File(stagingDir, relative)
                if (!target.canonicalPath.startsWith(stagingDir.canonicalPath + File.separator)) {
                    return RestoreOutcome.BadArchive("unsafe path in archive: $relative")
                }
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
            // Swap. renameTo is same-volume here (both under filesDir), which makes the
            // window between the two renames as small as the platform allows.
            val previous = File(dshHome.parentFile, "dsh-home-previous")
            previous.deleteRecursively()
            val hadHome = dshHome.isDirectory
            if (hadHome && !dshHome.renameTo(previous)) {
                return RestoreOutcome.BadArchive("could not set the current engine data aside")
            }
            if (!stagingDir.renameTo(dshHome)) {
                if (hadHome) previous.renameTo(dshHome)
                return RestoreOutcome.BadArchive("could not move the restored data into place")
            }
            previous.deleteRecursively()
            RestoreOutcome.Done(entries.size)
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    /** Rejects absolute paths and traversal; restore re-checks canonically afterwards. */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.startsWith('/') || name.contains('\\')) return false
        if (name.contains(':')) return false
        return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
