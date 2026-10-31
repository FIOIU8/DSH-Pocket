package com.fioiu8.dshpocket.engine

import android.content.Context
import android.os.Build
import com.fioiu8.dshpocket.data.workspace.WorkspaceStore
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticsReport(
    val generatedAt: Long,
    val appVersion: String?,
    val androidVersion: String,
    val deviceAbi: List<String>,
    val runtimeAbi: String,
    val dshVersion: String?,
    val nodeVersion: String?,
    val engineState: String,
    val engineBaseUrl: String?,
    val workspacePath: String,
    val foregroundService: Boolean,
    val recentLog: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generatedAt", generatedAt)
        put("appVersion", appVersion ?: JSONObject.NULL)
        put("androidVersion", androidVersion)
        put("deviceAbi", JSONArray(deviceAbi))
        put("runtimeAbi", runtimeAbi)
        put("dshVersion", dshVersion ?: JSONObject.NULL)
        put("nodeVersion", nodeVersion ?: JSONObject.NULL)
        put("engineState", engineState)
        put("engineBaseUrl", engineBaseUrl ?: JSONObject.NULL)
        put("workspacePath", workspacePath)
        put("foregroundService", foregroundService)
        put("recentLog", JSONArray(recentLog.map(::redact)))
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(token|cookie|authorization|api[_-]?key|password|secret)(\\s*[:=]\\s*)[^\\s,;]+"), "$1$2[redacted]")
        .replace(Regex("(?i)(https?://[^/]+/\\?token=)[^\\s&]+"), "$1[redacted]")
}

object Diagnostics {
    fun collect(context: Context, state: EngineState, log: List<String>): DiagnosticsReport {
        val root = EnginePaths.root(context)
        val ready = state as? EngineState.Ready
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return DiagnosticsReport(
            generatedAt = System.currentTimeMillis(),
            appVersion = packageInfo?.versionName,
            androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceAbi = Build.SUPPORTED_ABIS.toList(),
            runtimeAbi = com.fioiu8.dshpocket.engine.RuntimeInstaller.BUNDLED_ABI,
            dshVersion = EnginePaths.dshVersion(root),
            nodeVersion = EnginePaths.probeNodeVersion(root, EnginePaths.dshHome(context)),
            engineState = when (state) {
                EngineState.Idle -> "idle"
                is EngineState.Busy -> "busy"
                is EngineState.Ready -> "ready"
                is EngineState.Failed -> "failed"
            },
            engineBaseUrl = ready?.baseUrl,
            workspacePath = WorkspaceStore.get(context).activePath().absolutePath,
            foregroundService = EngineService.isActive(),
            recentLog = log.takeLast(80),
        )
    }
}
