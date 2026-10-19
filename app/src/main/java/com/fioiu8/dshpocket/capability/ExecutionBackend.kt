package com.fioiu8.dshpocket.capability

import android.content.Context

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
 * The seam exists so the Termux and Shizuku bridges can arrive later without
 * changing anything above this line: [Capabilities.snapshot] asks the
 * backends what they see and never touches a package manager itself.
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
}

/**
 * The backend the app ships today: it can see whether the companion's package
 * is installed, and nothing else.
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
                PackagePresenceBackend(ExecutionBackendKind.SHIZUKU, Capabilities.SHIZUKU_PACKAGE),
            ),
        )
    }
}
