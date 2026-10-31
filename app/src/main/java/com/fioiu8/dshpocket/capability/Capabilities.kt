package com.fioiu8.dshpocket.capability

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * The capability ladder from D12.
 *
 * The bundled engine runs with the app's own privileges, so what the agent can reach is
 * exactly what this app has been granted. Each rung is therefore something the *user*
 * grants, and the UI must be able to explain and request it.
 */
enum class CapabilityState {
    /** Available right now. */
    GRANTED,

    /** Installable / grantable, but not granted yet. */
    AVAILABLE,

    /** The companion APK is present, but its execution bridge is not connected yet. */
    INSTALLED,

    /** The user must install something else first (Termux, Shizuku). */
    NOT_INSTALLED,

    /** Not applicable on this device or Android version. */
    UNSUPPORTED,
}

/** Lifecycle of an optional execution companion; installation alone grants no execution. */
enum class CompanionStatus {
    NOT_INSTALLED,
    INSTALLED,
    AUTHORIZED,
    RUNNING,
    BRIDGE_CONNECTED,
    UNSUPPORTED,
}

val CompanionStatus.isInstalled: Boolean
    get() = this != CompanionStatus.NOT_INSTALLED && this != CompanionStatus.UNSUPPORTED

sealed interface ProcessExecutionCapability {
    val available: Boolean
    val backend: ExecutionBackendKind?
    val reason: String?

    data class Available(override val backend: ExecutionBackendKind) : ProcessExecutionCapability {
        override val available = true
        override val reason: String? = null
    }

    data class Unavailable(override val reason: String?) : ProcessExecutionCapability {
        override val available = false
        override val backend: ExecutionBackendKind? = null
    }
}

data class CapabilitySnapshot(
    val workspace: String,
    val allFiles: CapabilityState,
    val termux: CompanionStatus,
    val shizuku: CompanionStatus,
    val processExecution: ProcessExecutionCapability,
)

object Capabilities {

    const val TERMUX_PACKAGE = "com.termux"
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /**
     * "All files access" — the rung that makes the default tier actually useful.
     *
     * SAF is not an option here: it hands out `content://` URIs, which Node's `fs`
     * cannot open. Only real paths work, and on Android 11+ that means
     * MANAGE_EXTERNAL_STORAGE.
     */
    fun allFilesAccess(context: Context): CapabilityState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                CapabilityState.GRANTED
            } else {
                CapabilityState.AVAILABLE
            }
        } else {
            // Pre-R the legacy model applies and normal runtime permissions suffice.
            CapabilityState.GRANTED
        }

    /** Settings screen that grants all-files access to this app, or null when not applicable. */
    fun allFilesAccessIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.packageName, null),
            )
        } else {
            null
        }

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Termux supplies a full Linux userland (and a second, unmodified path to dsh). */
    fun termux(context: Context, backends: CompanionBackends = CompanionBackends.default): CompanionStatus =
        backends.statusOf(AndroidDeviceQuery(context), ExecutionBackendKind.TERMUX)

    /**
     * Shizuku supplies privilege, not a runtime. The backend probes its service and
     * authorization state; process execution remains unavailable until a bridge call succeeds.
     */
    fun shizuku(context: Context, backends: CompanionBackends = CompanionBackends.default): CompanionStatus =
        backends.statusOf(AndroidDeviceQuery(context), ExecutionBackendKind.SHIZUKU)

    /** One capability view shared by the UI and all agent-facing representations. */
    fun snapshot(
        context: Context,
        workspace: File,
        backends: CompanionBackends = CompanionBackends.default,
    ): CapabilitySnapshot {
        val device = AndroidDeviceQuery(context)
        return snapshotOf(
            workspace = workspace.absolutePath,
            allFiles = allFilesAccess(context),
            termux = backends.statusOf(device, ExecutionBackendKind.TERMUX),
            shizuku = backends.statusOf(device, ExecutionBackendKind.SHIZUKU),
        )
    }

    /**
     * The pure core of a capability snapshot.
     *
     * No Android types, so every rule the snapshot encodes can be tested on
     * the JVM. The Android-facing [snapshot] only gathers the inputs.
     */
    fun snapshotOf(
        workspace: String,
        allFiles: CapabilityState,
        termux: CompanionStatus,
        shizuku: CompanionStatus,
    ): CapabilitySnapshot = CapabilitySnapshot(
        workspace = workspace,
        allFiles = allFiles,
        termux = termux,
        shizuku = shizuku,
        processExecution = CompanionStateMachine.executionCapability(termux, shizuku),
    )
}
