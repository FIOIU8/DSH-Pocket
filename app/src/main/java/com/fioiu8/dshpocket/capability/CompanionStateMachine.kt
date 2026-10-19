package com.fioiu8.dshpocket.capability

/**
 * The rules that turn two companion states into a capability snapshot.
 *
 * Pure, and free of Android types, so the whole ladder is testable on the JVM.
 * This is the one place that decides what the two statuses mean — the
 * snapshot used to gather that knowledge inline, which is why no rule about
 * it could be tested.
 */
object CompanionStateMachine {

    /**
     * Whether the agent can execute processes, given the two companion states.
     *
     * A companion grants execution only when its bridge is connected: the
     * service merely running is not enough, and the rungs below it
     * (installed / authorized / running) are not an execution capability on
     * their own. Termux is checked first, as the shipped snapshot did.
     */
    fun executionCapability(
        termux: CompanionStatus,
        shizuku: CompanionStatus,
    ): ProcessExecutionCapability = when {
        termux == CompanionStatus.BRIDGE_CONNECTED ->
            ProcessExecutionCapability.Available(ExecutionBackendKind.TERMUX)
        shizuku == CompanionStatus.BRIDGE_CONNECTED ->
            ProcessExecutionCapability.Available(ExecutionBackendKind.SHIZUKU)
        else -> ProcessExecutionCapability.Unavailable(
            reason = "No Termux or Shizuku execution bridge is connected to DSH Pocket.",
        )
    }
}
