package com.fioiu8.dshpocket.engine

import android.content.Context
import android.os.Build
import android.util.Log
import com.fioiu8.dshpocket.BuildConfig
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
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
     */
    fun install(context: Context, onProgress: (String) -> Unit) {
        val root = EnginePaths.root(context)
        val marker = EnginePaths.installMarker(context)
        if (isInstalled(context)) {
            onProgress("运行时已就绪")
            return
        }

        if (!abiSupported()) {
            error(
                "内置运行时是为 $BUNDLED_ABI 构建的，本机 ABI 为 " +
                    Build.SUPPORTED_ABIS.joinToString()+ "；请安装匹配的运行时。"
            )
        }

        onProgress("解压运行时…")
        if (root.exists() && !root.deleteRecursively()) {
            error("无法清理旧运行时目录：${root.absolutePath}")
        }
        root.mkdirs()
        var files = 0
        val rootPath = root.canonicalPath + File.separator

        context.assets.open(ASSET).use { raw ->
            ZipInputStream(BufferedInputStream(raw, 1 shl 20)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name)
                    // zip-slip guard: never write outside the runtime root
                    if (!target.canonicalPath.startsWith(rootPath)) {
                        error("运行时压缩包包含非法路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zip.copyTo(out, 1 shl 16) }
                        files++
                        if (files % 2000 == 0) onProgress("解压运行时… $files 个文件")
                    }
                    zip.closeEntry()
                }
            }
        }

        onProgress("恢复执行权限…")
        val bin = File(root, "bin")
        var executables = bin.listFiles()?.count { it.isFile && it.setExecutable(true, false) } ?: 0
        val ripgrep = ripgrepBinary(root)
        if (!ripgrep.isFile || !ripgrep.setExecutable(true, false)) {
            error("无法恢复内置 ripgrep 执行权限：${ripgrep.absolutePath}")
        }
        executables++
        Log.i(TAG, "extracted $files files, marked $executables executables")

        File(root, "home").mkdirs()
        File(root, "tmp").mkdirs()
        marker.writeText("${markerHeader(context)}\n$files files\n")
        onProgress("运行时就绪（$files 个文件）")
    }

    private fun markerHeader(context: Context): String =
        "runtime-format=3 abi=$BUNDLED_ABI app-version=${appVersion(context)}"

    private fun ripgrepBinary(root: File): File {
        val nodeArch = when (runtimeArch) {
            "x86_64" -> "x64"
            "aarch64" -> "arm64"
            else -> error("不支持的运行时架构：$runtimeArch")
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
