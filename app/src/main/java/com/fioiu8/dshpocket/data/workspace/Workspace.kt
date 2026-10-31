package com.fioiu8.dshpocket.data.workspace

import android.content.Context
import com.fioiu8.dshpocket.engine.EnginePaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A user-visible local workspace. The engine still owns sessions; this is app-local metadata. */
data class Workspace(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long,
    val lastUsedAt: Long,
)

/**
 * Stores workspace selection and metadata in the app preferences file.
 *
 * Paths are deliberately plain filesystem paths. A `content://` URI cannot be passed to the
 * Node process as a cwd, so callers must validate a real directory before creating a record.
 */
class WorkspaceStore private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureInitialized(): List<Workspace> {
        val existing = read()
        if (existing.isNotEmpty()) return existing
        val now = System.currentTimeMillis()
        val legacy = EnginePaths.defaultWorkspace(context)
        legacy.mkdirs()
        val initial = Workspace(
            id = DEFAULT_ID,
            name = "Default workspace",
            path = canonical(legacy),
            createdAt = now,
            lastUsedAt = now,
        )
        write(listOf(initial), initial.id)
        return listOf(initial)
    }

    @Synchronized fun list(): List<Workspace> = ensureInitialized()

    @Synchronized fun active(): Workspace {
        val rows = ensureInitialized()
        val id = prefs.getString(ACTIVE_ID, null)
        return rows.firstOrNull { it.id == id } ?: rows.first().also { select(it.id) }
    }

    @Synchronized fun select(id: String): Workspace {
        val rows = ensureInitialized()
        val chosen = rows.firstOrNull { it.id == id } ?: error("Unknown workspace: $id")
        val now = System.currentTimeMillis()
        val updated = rows.map { if (it.id == id) it.copy(lastUsedAt = now) else it }
        write(updated, id)
        return chosen.copy(lastUsedAt = now)
    }

    @Synchronized fun create(name: String, path: File): Workspace {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Workspace name is required" }
        val dir = path.canonicalFile
        require(dir.isDirectory || dir.mkdirs()) { "Workspace path is not a directory" }
        require(dir.canRead() && dir.canWrite()) { "Workspace path is not writable" }
        val rows = ensureInitialized()
        require(rows.none { canonical(File(it.path)) == dir.path }) { "Workspace path already exists" }
        val now = System.currentTimeMillis()
        val workspace = Workspace(UUID.randomUUID().toString(), cleanName, dir.path, now, now)
        write(rows + workspace, workspace.id)
        return workspace
    }

    @Synchronized fun createPrivate(name: String): Workspace {
        val root = File(context.filesDir, "workspaces")
        root.mkdirs()
        val dir = File(root, UUID.randomUUID().toString())
        return create(name, dir)
    }

    @Synchronized fun rename(id: String, name: String): Workspace {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Workspace name is required" }
        val rows = ensureInitialized()
        val target = rows.firstOrNull { it.id == id } ?: error("Unknown workspace: $id")
        val updated = target.copy(name = cleanName)
        write(rows.map { if (it.id == id) updated else it }, prefs.getString(ACTIVE_ID, id))
        return updated
    }

    @Synchronized fun deleteIfEmpty(id: String): Boolean {
        val rows = ensureInitialized()
        require(rows.size > 1) { "At least one workspace is required" }
        require(prefs.getString(ACTIVE_ID, rows.first().id) != id) { "Select another workspace first" }
        val target = rows.firstOrNull { it.id == id } ?: return false
        val dir = File(target.path)
        require(dir.isDirectory && dir.listFiles().isNullOrEmpty()) { "Workspace must be empty" }
        write(rows.filterNot { it.id == id }, prefs.getString(ACTIVE_ID, rows.first().id))
        return true
    }

    fun activePath(): File = File(active().path)

    private fun read(): List<Workspace> {
        val json = prefs.getString(DATA, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
                val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
                add(Workspace(id, name, path, item.optLong("createdAt"), item.optLong("lastUsedAt")))
            }
        }
    }

    private fun write(rows: List<Workspace>, activeId: String?) {
        val array = JSONArray()
        rows.forEach { row ->
            array.put(JSONObject().apply {
                put("id", row.id)
                put("name", row.name)
                put("path", row.path)
                put("createdAt", row.createdAt)
                put("lastUsedAt", row.lastUsedAt)
            })
        }
        prefs.edit().putString(DATA, array.toString()).apply {
            if (activeId != null) putString(ACTIVE_ID, activeId)
        }.apply()
    }

    companion object {
        const val PREFS = "workspace_preferences"
        const val DATA = "workspaces"
        const val ACTIVE_ID = "active_workspace_id"
        const val DEFAULT_ID = "default"
        @Volatile private var shared: WorkspaceStore? = null

        fun canonical(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

        fun get(context: Context): WorkspaceStore = shared ?: synchronized(this) {
            shared ?: WorkspaceStore(context.applicationContext).also { shared = it }
        }

        fun resetForTests() {
            shared = null
        }
    }
}
