package com.fioiu8.dshpocket.ui.state

import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import com.fioiu8.dshpocket.engine.EngineState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the session screen needs from the engine, and nothing about how it is drawn.
 *
 * Kept as an interface so [SessionRepository] can be exercised against a fake without an
 * Android context, a running engine, or a device.
 */
interface SessionSource {
    suspend fun list(): RpcResult
    suspend fun archive(sessionId: String): RpcResult
    suspend fun unarchive(sessionId: String): RpcResult
}

/** The real source: the loopback engine the app started. */
class EngineSessionSource(
    private val baseUrl: String,
    private val token: String,
) : SessionSource {

    private suspend fun call(endpoint: String, args: JSONObject): RpcResult =
        EngineApi.rpc(baseUrl, endpoint, args, token)

    override suspend fun list(): RpcResult =
        call("session/list", EngineApi.emptyArgs().put("_request", JSONObject()))

    override suspend fun archive(sessionId: String): RpcResult =
        call("workspace/archiveSession", EngineApi.requestArgs(JSONObject().put("sessionId", sessionId)))

    override suspend fun unarchive(sessionId: String): RpcResult =
        call("workspace/unarchiveSession", EngineApi.requestArgs(JSONObject().put("sessionId", sessionId)))
}

data class SessionUiState(
    val load: SessionLoadState = SessionLoadState.Loading,
    val archivedIds: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val archiving: Boolean = false,
    val restoringId: String? = null,
)

/**
 * Persists the archived-session set across process and engine restarts.
 *
 * `session/list` does not report archived flags; the archive *write* responses carry the
 * engine's full `archivedSessionIds`, which is what makes persistence possible at all:
 * the set is learned from writes and replayed from storage on cold start.
 */
interface ArchiveStore {
    fun load(): Set<String>
    fun save(ids: Set<String>)
}

/** The shipped store: a string-set in the app's own preferences file. */
class SharedPreferencesArchiveStore(private val prefs: android.content.SharedPreferences) : ArchiveStore {
    override fun load(): Set<String> = prefs.getStringSet(KEY, null).orEmpty().toSet()

    override fun save(ids: Set<String>) {
        prefs.edit().putStringSet(KEY, ids).apply()
    }

    private companion object {
        const val KEY = "archived_session_ids"
    }
}

/**
 * Owns the session data and the single poll loop that used to be duplicated per screen.
 *
 * The archived set is write-learned ([archivedIdsFromResponse]) and persisted through
 * [ArchiveStore] when one is provided, so archiving survives engine and app restarts.
 */
class SessionRepository(
    private val scope: CoroutineScope,
    private val source: () -> SessionSource?,
    private val archiveStore: ArchiveStore? = null,
) {

    companion object {
        const val POLL_INTERVAL_MS = 15_000L

        /** One per process, so the home card and the session screen read the same data. */
        @Volatile
        private var shared: SessionRepository? = null

        fun get(scope: CoroutineScope, source: () -> SessionSource?, archiveStore: ArchiveStore? = null): SessionRepository =
            shared ?: synchronized(this) {
                shared ?: SessionRepository(scope, source, archiveStore).also { shared = it }
            }

        /** Test seam; the shared instance is process-wide and cannot be reset otherwise. */
        fun createForTest(scope: CoroutineScope, source: () -> SessionSource?, archiveStore: ArchiveStore? = null) =
            SessionRepository(scope, source, archiveStore)
    }

    private val _state = MutableStateFlow(SessionUiState(archivedIds = archiveStore?.load().orEmpty()))
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    @Volatile
    private var pollJob: Job? = null

    /**
     * Transport safety net: [EngineApi.rpc] maps IOExceptions to [RpcResult.Err], this
     * keeps any other unexpected failure from crashing the poll loop or wedging the busy
     * flags — an error state beats a dead app.
     */
    private suspend fun call(block: suspend () -> RpcResult): RpcResult =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            RpcResult.Err("transport", t.message ?: t.toString())
        }

    /**
     * Begins polling if it is not already running.
     *
     * Idempotent on purpose: re-entering the screen must not start a second loop or fetch
     * again, so callers can invoke this on every composition.
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refresh() {
        val source = source() ?: return
        val result = call { source.list() }
        _state.update { current ->
            when (result) {
                is RpcResult.Ok -> current.copy(
                    load = SessionLoadState.Ready(parseRows(EngineApi.itemsOf(result.value))),
                    refreshing = false,
                )
                is RpcResult.Err -> current.copy(
                    load = SessionLoadState.Failed("${result.code}: ${result.message}"),
                    refreshing = false,
                )
            }
        }
    }

    /** Manual pull-to-refresh; shows the indicator while the call is in flight. */
    suspend fun refreshUserInitiated() {
        _state.update { it.copy(refreshing = true) }
        refresh()
    }

    suspend fun archive(sessionId: String) {
        val source = source() ?: return
        _state.update { it.copy(archiving = true) }
        val result = call { source.archive(sessionId) }
        _state.update { current ->
            when (result) {
                is RpcResult.Ok -> {
                    val archived = archivedIdsFromResponse(result.value) ?: current.archivedIds
                    archiveStore?.save(archived)
                    current.copy(archiving = false, archivedIds = archived)
                }
                is RpcResult.Err -> current.copy(
                    archiving = false,
                    load = SessionLoadState.Failed("${result.code}: ${result.message}"),
                )
            }
        }
        refresh()
    }

    suspend fun unarchive(sessionId: String) {
        val source = source() ?: return
        _state.update { it.copy(restoringId = sessionId) }
        val result = call { source.unarchive(sessionId) }
        _state.update { current ->
            when (result) {
                is RpcResult.Ok -> {
                    val archived = archivedIdsFromResponse(result.value) ?: current.archivedIds
                    archiveStore?.save(archived)
                    current.copy(restoringId = null, archivedIds = archived)
                }
                is RpcResult.Err -> current.copy(
                    restoringId = null,
                    load = SessionLoadState.Failed("${result.code}: ${result.message}"),
                )
            }
        }
        refresh()
    }
}

/** Which slice of the list the user asked for. Mirrors the screen's segmented control. */
enum class SessionFilter { All, Running, Finished, Archived }

/**
 * The search, filter and grouping rules.
 *
 * These live here rather than in the composable for one reason: inline in a Composable
 * they cannot be tested, and the first draft of these tests "passed" by reimplementing
 * the same three lines instead of calling them. Anything the screen decides about which
 * rows to show is behaviour worth locking down.
 */
object SessionQuery {

    /** Title search. A row with no title matches only a blank query, as it did before. */
    fun matches(row: SessionRow, query: String): Boolean =
        query.isBlank() || row.title.orEmpty().contains(query, ignoreCase = true)

    fun isArchived(row: SessionRow, archivedIds: Set<String>): Boolean = row.id in archivedIds

    fun visible(
        rows: List<SessionRow>,
        archivedIds: Set<String>,
        filter: SessionFilter,
        query: String,
    ): List<SessionRow> = rows.filter { row ->
        val archived = isArchived(row, archivedIds)
        val inFilter = when (filter) {
            SessionFilter.All -> true
            SessionFilter.Running -> !archived && row.running
            SessionFilter.Finished -> !archived && !row.running
            SessionFilter.Archived -> archived
        }
        inFilter && matches(row, query)
    }

    /** Groups by the last path segment of `cwd`, falling back for rows that have none. */
    fun byWorkspace(rows: List<SessionRow>, fallback: String): Map<String, List<SessionRow>> =
        rows.groupBy { it.cwd?.substringAfterLast('/') ?: fallback }
}

/** Reads `archivedSessionIds` out of a write response; a missing field keeps the current set. */
internal fun archivedIdsFromResponse(value: Any?): Set<String>? {
    val array = (value as? JSONObject)?.optJSONArray("archivedSessionIds") ?: return null
    return archivedIdsFromValues(buildList { for (i in 0 until array.length()) add(array.optString(i)) })
}

/** Keeps a missing response distinct from a valid empty archive list. */
internal fun archivedIdsFromValues(values: List<String>?): Set<String>? = values?.toSet()

internal fun parseRows(items: JSONArray): List<SessionRow> = buildList {
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        // One malformed row must not blank the whole list: org.json's opt* accessors
        // absorb wrong-typed fields, this catch is the floor for anything that still
        // escapes (a schema change, an unexpected primitive) — skip the row, keep the rest.
        val row = runCatching { parseRow(item) }.getOrNull() ?: continue
        add(row)
    }
}.sortedByDescending { it.updatedAt }

private fun parseRow(item: JSONObject): SessionRow? {
    // blank = just created and still empty (the official Web UI creates one on open).
    if (item.optBoolean("blank")) return null
    val id = item.optString("sessionId").takeIf { it.isNotBlank() } ?: return null
    val values = item.optJSONObject("projections")?.optJSONObject("values")
    val model = values?.optJSONObject("modelSelection")?.optJSONObject("lastUsed")
    val stats = values?.optJSONObject("sessionStats")
    // optString turns JSON null into the string "null"; treat that as missing.
    val title = values?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
    // optInt returns 0 (not null) when the field is missing, so the turnOutline
    // fallback has to check presence explicitly.
    val steps = stats?.takeIf { it.has("steps") && !it.isNull("steps") }?.optInt("steps")
        ?: values?.optJSONArray("turnOutline")?.length()
        ?: 0
    return SessionRow(
        id = id,
        title = title,
        updatedAt = item.optLong("updatedAt"),
        running = item.optBoolean("running"),
        provider = model?.optString("provider")?.takeIf { it.isNotBlank() && it != "null" },
        model = model?.optString("model")?.takeIf { it.isNotBlank() && it != "null" },
        steps = steps,
        cwd = item.optString("cwd").takeIf { it.isNotBlank() && it != "null" },
    )
}
