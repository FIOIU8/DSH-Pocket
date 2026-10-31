package com.fioiu8.dshpocket.capability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku

/**
 * Which companion a backend drives.
 *
 * The identity used in the wire format: `processExecutionBackendKind` in
 * capabilities.json carries [wireValue], and the settings row shows
 * [displayName]. Moved here from the old `ExecutionBackend` enum, which held
 * exactly these two values.
 */
enum class ExecutionBackendKind(val wireValue: String, val displayName: String) {
    TERMUX("termux", "Termux"),
    SHIZUKU("shizuku", "Shizuku"),
}

/**
 * What a backend is allowed to look at.
 *
 * A narrow port instead of the Android [Context] so the seam can be exercised
 * on the JVM: the unit tests have no PackageManager, and a backend that
 * took a Context could not be tested at all without Robolectric, which this
 * project does not have.
 */
interface DeviceQuery {
    /** Whether the package is installed on the device. */
    fun isPackageInstalled(packageName: String): Boolean
}

/**
 * The device the backends look at, backed by the Android package manager.
 *
 * Created per read and never stored: callers pass an Activity context, and
 * holding one alive would leak it.
 */
class AndroidDeviceQuery(private val context: Context) : DeviceQuery {
    override fun isPackageInstalled(packageName: String): Boolean =
        Capabilities.isInstalled(context, packageName)
}

/**
 * A companion that can run processes for the agent (D11).
 *
 * The seam keeps companion state and process execution behind one small contract:
 * [Capabilities.snapshot] asks the backends what they see and never touches a package manager itself.
 */
interface ExecutionBackend {
    /** Which companion this backend drives. */
    val kind: ExecutionBackendKind

    /**
     * The companion's current state.
     *
     * Every situation has a rung on the ladder, so this never returns null:
     * a backend that cannot confirm the bridge reports the rung below it
     * (`RUNNING` — "running, execution bridge not connected"), which is a
     * fact rather than a guess.
     */
    fun probe(device: DeviceQuery): CompanionStatus

    /** Optional process bridge. Backends that cannot execute remain fail-closed. */
    fun execute(command: List<String>, workingDirectory: String, environment: Map<String, String>): ProcessResult =
        ProcessResult.Unavailable("Execution bridge is not connected")
}

sealed interface ProcessResult {
    data class Started(val process: Process) : ProcessResult
    data class Failed(val message: String) : ProcessResult
    data class Unavailable(val reason: String) : ProcessResult
}

/**
 * Fallback backend for companions that do not yet expose an execution bridge.
 *
 * Termux supplies a full Linux userland (and a second, unmodified path to
 * dsh). Shizuku supplies privilege, not a runtime: it can only raise the
 * identity of the processes we already ship. Merely detecting the APK is all
 * we can do without binding to its service, which is the next step (D11).
 */
class PackagePresenceBackend(
    override val kind: ExecutionBackendKind,
    private val packageName: String,
) : ExecutionBackend {
    override fun probe(device: DeviceQuery): CompanionStatus =
        if (device.isPackageInstalled(packageName)) {
            CompanionStatus.INSTALLED
        } else {
            CompanionStatus.NOT_INSTALLED
        }
}

/** Shizuku-backed process execution. A successful process start upgrades the state to bridge-connected. */
class ShizukuExecutionBackend : ExecutionBackend {
    override val kind: ExecutionBackendKind = ExecutionBackendKind.SHIZUKU

    @Volatile private var connected = false

    override fun probe(device: DeviceQuery): CompanionStatus {
        if (!device.isPackageInstalled(Capabilities.SHIZUKU_PACKAGE)) return CompanionStatus.NOT_INSTALLED
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return CompanionStatus.INSTALLED
        val permission = runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED)
        if (permission != PackageManager.PERMISSION_GRANTED) return CompanionStatus.AUTHORIZED
        return if (connected) CompanionStatus.BRIDGE_CONNECTED else CompanionStatus.RUNNING
    }

    override fun execute(command: List<String>, workingDirectory: String, environment: Map<String, String>): ProcessResult {
        if (command.isEmpty()) return ProcessResult.Failed("Command is empty")
        return try {
            if (!Shizuku.pingBinder()) return ProcessResult.Unavailable("Shizuku service is not running")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ProcessResult.Unavailable("Shizuku permission is not granted")
            }
            // Shizuku 13 keeps the low-level process factory private. The public UserService
            // API is asynchronous and needs an app-specific Binder contract, so this narrow
            // bridge uses the same method reflectively while preserving fail-closed behavior
            // when the installed API changes or blocks access.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(
                null,
                command.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                workingDirectory,
            ) as? Process ?: return ProcessResult.Failed("Shizuku did not return a process")
            connected = true
            ProcessResult.Started(process)
        } catch (failure: Throwable) {
            connected = false
            ProcessResult.Failed(failure.message ?: failure.javaClass.simpleName)
        }
    }
}

/**
 * The backends the app knows about.
 *
 * A kind with no backend registered is [CompanionStatus.UNSUPPORTED]: there is
 * nothing on this device that could ever drive that companion.
 */
class CompanionBackends(backends: List<ExecutionBackend>) {

    private val byKind: Map<ExecutionBackendKind, ExecutionBackend> = backends.associateBy { it.kind }

    /** The status of one companion. */
    fun statusOf(device: DeviceQuery, kind: ExecutionBackendKind): CompanionStatus {
        val backend = byKind[kind] ?: return CompanionStatus.UNSUPPORTED
        return backend.probe(device)
    }

    companion object {
        /**
         * The production set: package presence for both companions.
         *
         * A `val`, not a function — the registry holds no state, so one shared
         * instance is enough and there is nothing to allocate per read.
         */
        val default: CompanionBackends = CompanionBackends(
            listOf(
                PackagePresenceBackend(ExecutionBackendKind.TERMUX, Capabilities.TERMUX_PACKAGE),
                ShizukuExecutionBackend(),
            ),
        )
    }
}
