package com.fioiu8.dshpocket.engine

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Layout and environment of the bundled bionic runtime.
 *
 * The zip produced by `tools/collect_runtime.py` is unpacked into `filesDir/runtime`
 * and mirrors the Termux prefix layout (`bin/`, `lib/`, `etc/`), so the engine can be
 * started exactly like it was on the device during the D9 validation.
 */
object EnginePaths {

    const val PORT = 3080

    /** Marker written after a successful extraction; bump when the layout changes. */
    const val INSTALL_MARKER = ".dsh-pocket-runtime-v3"

    /** User-visible folder used once "all files access" is granted. */
    const val SHARED_WORKSPACE = "/storage/emulated/0/DSHPocket"

    fun root(context: Context): File = File(context.filesDir, "runtime")

    fun node(root: File): File = File(root, "bin/node")

    /** Entry point declared by `@deepseek-ai/dsh`'s package.json (`bin: bin.js`). */
    fun dshEntry(root: File): File =
        File(root, "lib/node_modules/@deepseek-ai/dsh/lib/bin.js")

    fun certFile(root: File): File = File(root, "etc/tls/cert.pem")

    fun opensslConf(root: File): File = File(root, "etc/tls/openssl.cnf")

    fun installMarker(context: Context): File = File(root(context), INSTALL_MARKER)

    /** Harness home: profiles, sessions, credentials, logs. Kept out of the runtime dir. */
    fun dshHome(context: Context): File = File(context.filesDir, "dsh-home")

    /**
     * Where the agent works.
     *
     * Defaults to app-private storage, which is all the default tier can reach. Once the
     * user grants all-files access the app switches this to [SHARED_WORKSPACE] so the
     * agent operates on real, user-visible files.
     */
    fun workspace(context: Context): File {
        val shared = File(Environment.getExternalStorageDirectory(), "DSHPocket")
        return if (shared.isDirectory || shared.mkdirs()) shared else File(context.filesDir, "workspace")
    }

    fun privateWorkspace(context: Context): File = File(context.filesDir, "workspace")

    /**
     * Termux builds bake `/data/data/com.termux/files/usr` into node (OpenSSL config in
     * particular), so the prefix and the TLS paths must be handed over explicitly.
     */
    fun environment(root: File, dshHome: File): Map<String, String> = mapOf(
        "PREFIX" to root.absolutePath,
        "LD_LIBRARY_PATH" to File(root, "lib").absolutePath,
        "PATH" to "${File(root, "bin").absolutePath}:/system/bin:/system/xbin",
        "SSL_CERT_FILE" to certFile(root).absolutePath,
        "OPENSSL_CONF" to opensslConf(root).absolutePath,
        "HOME" to File(root, "home").absolutePath,
        "TMPDIR" to File(root, "tmp").absolutePath,
        // Pin the harness home so profiles, sessions and credentials survive a
        // runtime re-extraction.
        "DSH_HOME" to dshHome.absolutePath,
        // Keep node's own output deterministic inside the log buffer.
        "NO_COLOR" to "1",
    )

    fun command(root: File): List<String> = listOf(
        node(root).absolutePath,
        // Required by the pure-JS replacement of node-addon-require-builtin
        // (see tools/collect_runtime.py -> patch_require_builtin).
        "--expose-internals",
        dshEntry(root).absolutePath,
        "web",
        "--no-open",
        "--port",
        PORT.toString(),
    )

    fun installNativeChatSuggestionsTool(context: Context, root: File): File {
        val module = File(root, "lib/node_modules/@deepseek-ai/dsh-pocket-native-chat-suggestions/index.mjs")
        module.parentFile?.mkdirs()
        context.assets.open("native-chat-suggestions.mjs").use { input ->
            module.outputStream().use(input::copyTo)
        }
        return module
    }

    /**
     * Home-level patch layer (`$DSH_HOME/cordis.patch.yml`).
     *
     * The `web` profile derives its default workspace from `<documentsDirectory>/deepseek-harness`,
     * which means "the system Documents directory" — a concept Android does not have. Left
     * unset, the engine answers the client with `defaultWorkspaceFailed` and the agent has
     * nowhere to work. Patches replace the target row's whole config, so this restates the
     * only field we set.
     */
    fun writeHomePatch(dshHome: File, workspace: File, nativeChatSuggestionsModule: File) {
        dshHome.mkdirs()
        val yaml = buildString {
            appendLine("# Written by DSH Pocket on every boot; any manual edit is overwritten.")
            appendLine("# The web profile creates its default workspace under")
            appendLine("# <documentsDirectory>/deepseek-harness. Android has no system Documents")
            appendLine("# directory, so it has to be pointed at a writable location.")
            appendLine("- id: workspace-controller")
            appendLine("  config:")
            appendLine("    documentsDirectory: \"${workspace.absolutePath}\"")
            appendLine("- insert:")
            appendLine("    - id: dsh-pocket-native-chat-suggestions")
            appendLine("      name: \"${nativeChatSuggestionsModule.toURI().toASCIIString()}\"")
        }
        File(dshHome, "cordis.patch.yml").writeText(yaml)
    }

    /** Version reported by the packaged dsh, read straight from its manifest. */
    fun dshVersion(root: File): String? = try {
        val manifest = File(root, "lib/node_modules/@deepseek-ai/dsh/package.json")
        if (manifest.isFile) {
            Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
                .find(manifest.readText())
                ?.groupValues
                ?.get(1)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    /** Runs `node --version` once; used only by the diagnostics panel. */
    fun probeNodeVersion(root: File, dshHome: File): String? = try {
        val builder = ProcessBuilder(node(root).absolutePath, "--version")
        builder.directory(root)
        builder.environment().putAll(environment(root, dshHome))
        builder.redirectErrorStream(true)
        val proc = builder.start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.ifBlank { null }
    } catch (_: Throwable) {
        null
    }
}
