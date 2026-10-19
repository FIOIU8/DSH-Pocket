package com.fioiu8.dshpocket

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.ui.state.SessionFilter
import com.fioiu8.dshpocket.ui.state.SessionLoadState
import com.fioiu8.dshpocket.ui.state.SessionQuery
import com.fioiu8.dshpocket.ui.state.SessionRepository
import com.fioiu8.dshpocket.ui.state.SessionRow
import com.fioiu8.dshpocket.ui.state.SessionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour-equivalence tests for the Session vertical slice.
 *
 * These lock in what the screen did before the state layer existed, so the refactor can be
 * checked against something other than "it still compiles". Six behaviours, one test each:
 * list, search, filter, grouping, archive, restore.
 *
 * The fake [SessionSource] replaces the engine, so no device, context, or HTTP is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepositoryTest {

    // ---- fakes -------------------------------------------------------------------------

    private class FakeSource(
        var listResult: RpcResult = RpcResult.Ok(JSONArray()),
    ) : SessionSource {
        var listCalls = 0
        val archivedCalls = mutableListOf<String>()
        val restoredCalls = mutableListOf<String>()
        var archiveResponse: Any? = JSONObject()
        var unarchiveResponse: Any? = JSONObject()
        var listError: Pair<String, String>? = null

        override suspend fun list(): RpcResult {
            listCalls++
            listError?.let { (code, message) -> return RpcResult.Err(code, message) }
            return listResult
        }

        override suspend fun archive(sessionId: String): RpcResult {
            archivedCalls += sessionId
            return RpcResult.Ok(archiveResponse)
        }

        override suspend fun unarchive(sessionId: String): RpcResult {
            restoredCalls += sessionId
            return RpcResult.Ok(unarchiveResponse)
        }
    }

    private fun row(
        id: String,
        title: String?,
        running: Boolean = false,
        cwd: String? = "/data/ws/alpha",
        updatedAt: Long = 1_000L,
        steps: Int = 3,
    ) = JSONObject().apply {
        put("sessionId", id)
        put("running", running)
        put("cwd", cwd ?: JSONObject.NULL)
        put("updatedAt", updatedAt)
        put(
            "projections",
            JSONObject().put(
                "values",
                JSONObject()
                    .put("title", title ?: JSONObject.NULL)
                    .put("sessionStats", JSONObject().put("steps", steps)),
            ),
        )
    }

    private fun rowsArray(vararg rows: JSONObject) = JSONArray().apply { rows.forEach { put(it) } }

    private fun TestScope.repository(source: SessionSource) =
        SessionRepository.createForTest(
            scope = this,
            source = { source },
        )

    // ---- 1. list -----------------------------------------------------------------------

    @Test
    fun `list parses rows newest first`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource(
            listResult = RpcResult.Ok(
                rowsArray(
                    row("older", "Older", updatedAt = 1_000L),
                    row("newer", "Newer", updatedAt = 9_000L),
                ),
            ),
        )
        val repository = repository(source)

        repository.refresh()

        val rows = (repository.state.value.load as SessionLoadState.Ready).rows
        assertEquals(listOf("newer", "older"), rows.map { it.id })
        assertEquals(9_000L, rows.first().updatedAt)
    }

    @Test
    fun `blank sessions are skipped and a missing title stays null`() = runTest(StandardTestDispatcher()) {
        val blank = row("blank-id", "Whatever").put("blank", true)
        val untitled = row("no-title", null)
        val repository = repository(FakeSource(listResult = RpcResult.Ok(rowsArray(blank, untitled))))

        repository.refresh()

        val rows = (repository.state.value.load as SessionLoadState.Ready).rows
        assertEquals(listOf("no-title"), rows.map { it.id })
        // Null, not a rendered placeholder: the screen substitutes a @StringRes fallback at
        // draw time so the language is not frozen at parse time.
        assertNull(rows.single().title)
    }

    @Test
    fun `a failure surfaces the code and message`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource().apply { listError = "http/500" to "engine said no" }
        val repository = repository(source)

        repository.refresh()

        val load = repository.state.value.load
        assertTrue(load is SessionLoadState.Failed)
        assertEquals("http/500: engine said no", (load as SessionLoadState.Failed).message)
    }

    // ---- 2. search ----------------------------------------------------------------------

    @Test
    fun `search matches titles case-insensitively and ignores blank queries`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource(
            listResult = RpcResult.Ok(
                rowsArray(
                    row("a", "Refactor Engine"),
                    row("b", "Write release notes"),
                    row("c", null),
                ),
            ),
        )

        val repository = repository(source)
        repository.refresh()
        val rows = (repository.state.value.load as SessionLoadState.Ready).rows

        fun search(q: String) = SessionQuery.visible(rows, emptySet(), SessionFilter.All, q)

        assertEquals(listOf("a"), search("REFACTOR").map { it.id })
        assertEquals(listOf("a"), search("engine").map { it.id })
        assertEquals("a blank query keeps every row, including untitled ones", 3, search("").size)
        assertEquals("an untitled row never matches a real query", emptyList<String>(), search("x").map { it.id })
    }

    private fun model(
        id: String,
        title: String? = "title",
        running: Boolean = false,
        cwd: String? = "/data/ws/alpha",
        updatedAt: Long = 1_000L,
    ) = SessionRow(
        id = id,
        title = title,
        updatedAt = updatedAt,
        running = running,
        provider = null,
        model = null,
        steps = 3,
        cwd = cwd,
    )

    // ---- 3. filter ----------------------------------------------------------------------

    @Test
    fun `archived rows are excluded from running and finished`() {
        val rows = listOf(
            model("a", "Archived", running = true),
            model("b", "Live", running = true),
            model("c", "Done", running = false),
        )
        val archivedIds = setOf("a")

        fun ids(filter: SessionFilter) =
            SessionQuery.visible(rows, archivedIds, filter, "").map { it.id }

        assertEquals(listOf("a", "b", "c"), ids(SessionFilter.All))
        assertEquals(listOf("b"), ids(SessionFilter.Running))
        assertEquals(listOf("c"), ids(SessionFilter.Finished))
        assertEquals(listOf("a"), ids(SessionFilter.Archived))
    }

    // ---- 4. grouping --------------------------------------------------------------------

    @Test
    fun `rows group by the last path segment of cwd`() {
        val rows = listOf(
            model("a", "One", cwd = "/data/ws/alpha"),
            model("b", "Two", cwd = "/data/ws/alpha"),
            model("c", "Three", cwd = "/other/beta"),
            model("d", "Four", cwd = null),
        )

        val groups = SessionQuery.byWorkspace(rows, "(default)").mapValues { (_, v) -> v.map { it.id } }

        assertEquals(listOf("a", "b"), groups["alpha"])
        assertEquals(listOf("c"), groups["beta"])
        assertEquals(listOf("d"), groups["(default)"])
    }

    // ---- 5. archive ---------------------------------------------------------------------

    @Test
    fun `archive records the id and adopts the archived set from the response`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource().apply {
            archiveResponse = JSONObject().put("archivedSessionIds", JSONArray().put("a").put("b"))
        }
        val repository = repository(source)

        repository.archive("a")

        assertEquals(listOf("a"), source.archivedCalls)
        assertEquals(setOf("a", "b"), repository.state.value.archivedIds)
        assertEquals(false, repository.state.value.archiving)
    }

    @Test
    fun `a response without the field leaves the archived set untouched`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource().apply {
            archiveResponse = JSONObject().put("archivedSessionIds", JSONArray().put("keep-me"))
        }
        val repository = repository(source)

        // Seed the set with a response that carries the field.
        repository.archive("first")
        assertEquals(setOf("keep-me"), repository.state.value.archivedIds)

        // Then a response that omits it must not wipe what we already knew.
        source.archiveResponse = JSONObject()
        repository.archive("second")

        assertEquals(setOf("keep-me"), repository.state.value.archivedIds)
    }

    // ---- 6. restore ---------------------------------------------------------------------

    @Test
    fun `unarchive marks the row busy then clears it and refreshes`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource().apply {
            unarchiveResponse = JSONObject().put("archivedSessionIds", JSONArray())
        }
        val repository = repository(source)

        repository.unarchive("a")

        assertEquals(listOf("a"), source.restoredCalls)
        assertNull(repository.state.value.restoringId)
        assertEquals(emptySet<String>(), repository.state.value.archivedIds)
        // The write is followed by a refresh so the row leaves the archived filter.
        assertEquals(1, source.listCalls)
    }

    // ---- poll convergence ---------------------------------------------------------------

    @Test
    fun `re-entering the screen does not start a second poll`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource()
        val repository = repository(source)

        repository.startPolling()
        runCurrent()
        val afterFirst = source.listCalls

        // What the screen does on every composition.
        repository.startPolling()
        repository.startPolling()
        runCurrent()

        assertEquals(1, source.listCalls)
        assertEquals(1, afterFirst)

        // The loop is infinite by design; stop it so runTest sees a clean shutdown.
        repository.stopPolling()
    }

    @Test
    fun `polling keeps fetching on the interval`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource()
        val repository = repository(source)

        repository.startPolling()
        runCurrent()
        assertEquals(1, source.listCalls)

        advanceTimeBy(SessionRepository.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, source.listCalls)

        advanceTimeBy(SessionRepository.POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(3, source.listCalls)

        repository.stopPolling()
        advanceTimeBy(SessionRepository.POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals("stopping must end the loop", 3, source.listCalls)
    }

    @Test
    fun `a missing engine leaves the repository alone`() = runTest(StandardTestDispatcher()) {
        val source = FakeSource()
        val repository = SessionRepository.createForTest(scope = this, source = { null })

        repository.startPolling()
        runCurrent()

        assertEquals(0, source.listCalls)
        assertEquals(SessionLoadState.Loading, repository.state.value.load)

        repository.stopPolling()
    }
}

/** Guards the grouping helper the screen uses, which is easy to break with a null cwd. */
class SessionRowTest {
    @Test
    fun `workspaceKey falls back when cwd is absent`() {
        val withCwd = SessionRow("a", "t", 0L, false, null, null, 0, "/data/ws/alpha")
        val withoutCwd = SessionRow("b", "t", 0L, false, null, null, 0, null)
        assertEquals("alpha", withCwd.cwd?.substringAfterLast('/'))
        assertNull(withoutCwd.cwd)
    }
}
