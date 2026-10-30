package com.fioiu8.dshpocket.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.fioiu8.dshpocket.R
import com.fioiu8.dshpocket.BuildConfig
import com.fioiu8.dshpocket.core.AppLanguagePreferences
import com.fioiu8.dshpocket.core.UiText
import com.fioiu8.dshpocket.core.text
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Unpacks `assets/runtime.zip` (produced by `tools/collect_runtime.py`) into
 * `filesDir/runtime`.
 *
 * Two things the zip cannot carry across from the build machine:
 *  - Unix permission bits, so the executables under `bin/` are made runnable again;
 *  - the ABI, so a mismatched asset is reported clearly instead of failing later
 *    with a linker error.
 */
object RuntimeInstaller {

    private const val TAG = "RuntimeInstaller"

    /** Android ABI of the native runtime packaged in `assets/runtime.zip`. */
    val BUNDLED_ABI = BuildConfig.BUNDLED_ABI
    private val runtimeArch = BuildConfig.RUNTIME_ARCH

    private const val ASSET = "runtime.zip"

    fun isInstalled(context: Context): Boolean = try {
        val root = EnginePaths.root(context)
        val marker = EnginePaths.installMarker(context)
        marker.isFile && marker.readText().lineSequence().firstOrNull() == markerHeader(context) &&
            EnginePaths.node(root).isFile &&
            EnginePaths.dshEntry(root).isFile &&
            EnginePaths.certFile(root).isFile &&
            EnginePaths.opensslConf(root).isFile
    } catch (_: Throwable) {
        false
    }

    /** On-disk size of the unpacked runtime; walks ~20k files, so call off the main thread. */
    fun sizeBytes(root: File): Long =
        if (root.isDirectory) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    fun abiSupported(): Boolean = Build.SUPPORTED_ABIS.any { it == BUNDLED_ABI }

    /**
     * Extracts the runtime. Safe to call again: a completed extraction short-circuits.
     * @param onProgress receives a human readable status, called from a background thread.
     * @param onFraction receives extraction progress as 0..1 (called from a background
     *   thread), or nothing when the packaged size cannot be determined up front.
     */
    fun install(context: Context, onProgress: (UiText) -> Unit, onFraction: ((Float) -> Unit)? = null) {
        val localized = AppLanguagePreferences.localizedContext(context)
        val root = EnginePaths.root(context)
        val marker = EnginePaths.installMarker(context)
        if (isInstalled(context)) {
            onProgress(text(R.string.runtime_ready))
            onFraction?.invoke(1f)
            return
        }

        if (!abiSupported()) {
            throw IllegalStateException(
                text(
                    R.string.runtime_abi_mismatch,
                    BUNDLED_ABI,
                    Build.SUPPORTED_ABIS.joinToString(),
                ).resolve(localized),
            )
        }

        onProgress(text(R.string.runtime_extracting))
        onFraction?.invoke(0f)
        if (root.exists() && !root.deleteRecursively()) {
            error(localized.getString(R.string.runtime_cleanup_failed, root.absolutePath))
        }
        root.mkdirs()
        var files = 0
        val rootPath = root.canonicalPath + File.separator

        context.assets.open(ASSET).use { raw ->
            val counter = CountingInputStream(raw)
            val totalBytes = assetTotalBytes(context)
            var reported = -1f
            fun reportFraction() {
                val total = totalBytes ?: return
                val fraction = (counter.count.toFloat() / total).coerceIn(0f, 1f)
                if (fraction - reported >= 0.05f || fraction == 1f) {
                    reported = fraction
                    onFraction?.invoke(fraction)
                }
            }
            ZipInputStream(BufferedInputStream(counter, 1 shl 20)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name)
                    // zip-slip guard: never write outside the runtime root
                    if (!target.canonicalPath.startsWith(rootPath)) {
                        error(localized.getString(R.string.runtime_invalid_archive_path, entry.name))
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out, 1 shl 16) }
                        files++
                        if (files % 2000 == 0) {
                            onProgress(text(R.string.runtime_extracting_files, files))
                            reportFraction()
                        }
                    }
                    zip.closeEntry()
                }
            }
            reportFraction()
        }

        onProgress(text(R.string.runtime_restoring_permissions))
        val bin = File(root, "bin")
        var executables = bin.listFiles()?.count { it.isFile && it.setExecutable(true, false) } ?: 0
        val ripgrep = ripgrepBinary(root)
        if (!ripgrep.isFile || !ripgrep.setExecutable(true, false)) {
            error(localized.getString(R.string.runtime_ripgrep_permission_failed, ripgrep.absolutePath))
        }
        executables++
        Log.i(TAG, "extracted $files files, marked $executables executables")

        File(root, "home").mkdirs()
        File(root, "tmp").mkdirs()
        marker.writeText("${markerHeader(context)}\n$files files\n")
        onProgress(text(R.string.runtime_ready_files, files))
        onFraction?.invoke(1f)
    }

    /**
     * Uncompressed size of the packaged zip, read from the APK's own central directory —
     * O(1), no second pass over the asset. Null when the APK layout defeats the lookup
     * (split APKs, streaming writes), in which case callers fall back to an
     * indeterminate progress display.
     */
    private fun assetTotalBytes(context: Context): Long? = try {
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.getEntry("assets/$ASSET")?.size?.takeIf { it > 0 }
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Identity of the packaged zip: uncompressed size plus CRC-32, both read from the
     * APK's central directory. The marker keys off this so rebuilding `runtime.zip` at an
     * unchanged app version still forces a re-extract — without it the engine keeps
     * running the previously unpacked copy and engine patches look like no-ops.
     * @returns the stamp, or null when the APK layout defeats the lookup.
     */
    private fun assetStamp(context: Context): String? = try {
        ZipFile(context.applicationInfo.sourceDir).use { apk ->
            apk.getEntry("assets/$ASSET")?.let { entry ->
                if (entry.size <= 0 || entry.crc < 0) null else "${entry.size}-${entry.crc}"
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun markerHeader(context: Context): String {
        val stamp = assetStamp(context)?.let { " asset=$it" } ?: ""
        return "runtime-format=3 abi=$BUNDLED_ABI app-version=${appVersion(context)}$stamp"
    }

    private fun ripgrepBinary(root: File): File {
        val nodeArch = when (runtimeArch) {
            "x86_64" -> "x64"
            "aarch64" -> "arm64"
            else -> error("Unsupported runtime architecture: $runtimeArch")
        }
        return File(root, "lib/node_modules/@vscode/ripgrep-android-$nodeArch/bin/rg")
    }

    @Suppress("DEPRECATION")
    private fun appVersion(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
    } catch (_: Throwable) {
        -1L
    }
}
