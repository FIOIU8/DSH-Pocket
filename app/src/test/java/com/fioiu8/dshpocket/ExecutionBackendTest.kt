package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.capability.AgentBriefing
import com.fioiu8.dshpocket.capability.Capabilities
import com.fioiu8.dshpocket.capability.CapabilityState
import com.fioiu8.dshpocket.capability.CompanionBackends
import com.fioiu8.dshpocket.capability.CompanionStateMachine
import com.fioiu8.dshpocket.capability.CompanionStatus
import com.fioiu8.dshpocket.capability.DeviceQuery
import com.fioiu8.dshpocket.capability.ExecutionBackend
import com.fioiu8.dshpocket.capability.ExecutionBackendKind
import com.fioiu8.dshpocket.capability.PackagePresenceBackend
import com.fioiu8.dshpocket.capability.ProcessExecutionCapability
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The execution seam: what a backend reports, and what that means for the agent.
 *
 * The seam is what D11 asked for from the start, and until now there was nowhere
 * for a Termux or Shizuku bridge to plug in. These tests pin the two things
 * that matter about it: the shipped detection is unchanged, and the rungs that
 * were unreachable (authorized / running / bridge connected) now reach the
 * snapshot, the wire format and the agent briefing.
 */
class ExecutionBackendTest {

    // ---- the backends ----------------------------------------------------------------------

    @Test
    fun `a package that is present reports installed`() {
        val backend = PackagePresenceBackend(ExecutionBackendKind.TERMUX, "com.termux")

        assertEquals(CompanionStatus.INSTALLED, backend.probe(FakeDevice(setOf("com.termux"))))
    }

    @Test
    fun `a package that is absent reports not installed`() {
        val backend = PackagePresenceBackend(ExecutionBackendKind.TERMUX, "com.termux")

        assertEquals(CompanionStatus.NOT_INSTALLED, backend.probe(FakeDevice(emptySet())))
    }

    @Test
    fun `a kind with no backend reports unsupported`() {
        val backends = CompanionBackends(emptyList())

        assertEquals(
            "no backend means this companion can never be driven here",
            CompanionStatus.UNSUPPORTED,
            backends.statusOf(FakeDevice(emptySet()), ExecutionBackendKind.TERMUX),
        )
    }

    @Test
    fun `the production registry detects both companions by package name`() {
        val device = RecordingDevice(setOf("com.termux"))
        val backends = CompanionBackends.default

        assertEquals(CompanionStatus.INSTALLED, backends.statusOf(device, ExecutionBackendKind.TERMUX))
        assertEquals(CompanionStatus.NOT_INSTALLED, backends.statusOf(device, ExecutionBackendKind.SHIZUKU))

        // The shipped detection, unchanged: presence of these two packages, nothing else.
        assertEquals(listOf("com.termux", "moe.shizuku.privileged.api"), device.queried)
    }

    // ---- what the statuses mean ---------------------------------------------------------------

    @Test
    fun `no rung below bridge connected makes execution available`() {
        for (termux in CompanionStatus.values()) {
            for (shizuku in CompanionStatus.values()) {
                if (termux == CompanionStatus.BRIDGE_CONNECTED || shizuku == CompanionStatus.BRIDGE_CONNECTED) continue
                val capability = CompanionStateMachine.executionCapability(termux, shizuku)
                assertTrue(
                    "termux=$termux shizuku=$shizuku must not make execution available",
                    capability is ProcessExecutionCapability.Unavailable,
                )
            }
        }
    }

    @Test
    fun `an unavailable capability keeps the shipped reason string`() {
        val capability = CompanionStateMachine.executionCapability(
            CompanionStatus.INSTALLED,
            CompanionStatus.NOT_INSTALLED,
        )

        // This string is rendered in the settings screen and written into
        // capabilities.json and AGENTS.md, so a drift here is a visible change.
        assertEquals(
            "No Termux or Shizuku execution bridge is connected to DSH Pocket.",
            (capability as ProcessExecutionCapability.Unavailable).reason,
        )
    }

    @Test
    fun `a connected termux bridge makes execution available`() {
        val capability = CompanionStateMachine.executionCapability(
            CompanionStatus.BRIDGE_CONNECTED,
            CompanionStatus.NOT_INSTALLED,
        )

        assertEquals(ProcessExecutionCapability.Available(ExecutionBackendKind.TERMUX), capability)
    }

    @Test
    fun `a connected shizuku bridge makes execution available`() {
        val capability = CompanionStateMachine.executionCapability(
            CompanionStatus.NOT_INSTALLED,
            CompanionStatus.BRIDGE_CONNECTED,
        )

        assertEquals(ProcessExecutionCapability.Available(ExecutionBackendKind.SHIZUKU), capability)
    }

    @Test
    fun `termux wins when both bridges are connected`() {
        val capability = CompanionStateMachine.executionCapability(
            CompanionStatus.BRIDGE_CONNECTED,
            CompanionStatus.BRIDGE_CONNECTED,
        )

        // The shipped snapshot checked Termux first; the order decides which
        // backend the agent is told about.
        assertEquals(ProcessExecutionCapability.Available(ExecutionBackendKind.TERMUX), capability)
    }

    // ---- the agent-facing output ---------------------------------------------------------------

    @Test
    fun `a connected bridge reaches the wire format`() {
        val snapshot = Capabilities.snapshotOf(
            workspace = "/workspace",
            allFiles = CapabilityState.AVAILABLE,
            termux = CompanionStatus.BRIDGE_CONNECTED,
            shizuku = CompanionStatus.NOT_INSTALLED,
        )

        val json = JSONObject(AgentBriefing.capabilitiesJson(snapshot))

        assertEquals("bridge_connected", json.getString("termuxStatus"))
        assertTrue(json.getBoolean("termuxInstalled"))
        assertTrue(json.getBoolean("processExecution"))
        assertEquals("termux", json.getString("processExecutionBackendKind"))
    }

    @Test
    fun `a connected bridge reaches the agent briefing`() {
        val snapshot = Capabilities.snapshotOf(
            workspace = "/workspace",
            allFiles = CapabilityState.AVAILABLE,
            termux = CompanionStatus.BRIDGE_CONNECTED,
            shizuku = CompanionStatus.NOT_INSTALLED,
        )

        val instructions = AgentBriefing.renderCapabilities(snapshot)

        assertTrue(instructions.contains("execution bridge connected"))
        assertTrue(instructions.contains("available through the Termux backend"))
    }

    // ---- the whole path -------------------------------------------------------------------------

    @Test
    fun `the seam carries a bridge status all the way to the snapshot`() {
        val backends = CompanionBackends(
            listOf(FixedBackend(ExecutionBackendKind.TERMUX, CompanionStatus.BRIDGE_CONNECTED)),
        )
        val device = FakeDevice(emptySet())

        val termux = backends.statusOf(device, ExecutionBackendKind.TERMUX)
        val shizuku = backends.statusOf(device, ExecutionBackendKind.SHIZUKU)
        val snapshot = Capabilities.snapshotOf(
            workspace = "/workspace",
            allFiles = CapabilityState.AVAILABLE,
            termux = termux,
            shizuku = shizuku,
        )

        assertEquals(CompanionStatus.BRIDGE_CONNECTED, termux)
        assertEquals("no shizuku backend was registered", CompanionStatus.UNSUPPORTED, shizuku)
        assertEquals(
            ProcessExecutionCapability.Available(ExecutionBackendKind.TERMUX),
            snapshot.processExecution,
        )
    }

    // ---- doubles ------------------------------------------------------------------------------

    /** A device with a fixed set of installed packages. */
    private class FakeDevice(private val installed: Set<String>) : DeviceQuery {
        override fun isPackageInstalled(packageName: String): Boolean = packageName in installed
    }

    /** A device that also records what it was asked about. */
    private class RecordingDevice(private val installed: Set<String>) : DeviceQuery {
        val queried = mutableListOf<String>()

        override fun isPackageInstalled(packageName: String): Boolean {
            queried += packageName
            return packageName in installed
        }
    }

    /** A backend that reports a fixed state, standing in for a real bridge. */
    private class FixedBackend(
        override val kind: ExecutionBackendKind,
        private val status: CompanionStatus,
    ) : ExecutionBackend {
        override fun probe(device: DeviceQuery): CompanionStatus = status
    }
}
