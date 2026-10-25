package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.notify.TurnNotifier
import com.fioiu8.dshpocket.ui.state.SessionRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The transition rule behind turn-finished notifications. */
class TurnNotifierTest {

    private fun row(id: String, running: Boolean) =
        SessionRow(id, "t", 0L, running, null, null, 0, null)

    @Test
    fun `a session that stopped running since the last poll is finished`() {
        val previous = setOf("a", "b", "c")
        val rows = listOf(row("a", false), row("b", true), row("d", false))

        val finished = TurnNotifier.newlyFinished(previous, rows)

        assertEquals(listOf("a"), finished.map { it.id })
    }

    @Test
    fun `sessions that were never running are not reported`() {
        val previous = emptySet<String>()

        val finished = TurnNotifier.newlyFinished(previous, listOf(row("a", false), row("b", true)))

        assertEquals("a still-running row is tracked, a finished one without a run start is not", emptyList<SessionRow>(), finished)
    }

    @Test
    fun `a session that keeps running across polls is not finished`() {
        val previous = setOf("a")

        val finished = TurnNotifier.newlyFinished(previous, listOf(row("a", true)))

        assertEquals(emptyList<SessionRow>(), finished)
    }
}
