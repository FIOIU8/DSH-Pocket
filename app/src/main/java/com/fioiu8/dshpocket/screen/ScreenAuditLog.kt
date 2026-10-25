package com.fioiu8.dshpocket.screen

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** One adjudication chain entry: what the AI asked, what the pipeline decided, what happened. */
data class ScreenAuditEntry(
    val timestamp: Long,
    val action: String,
    val reason: String,
    val foregroundPackage: String?,
    /** "OK" when executed, otherwise the refusal/failure code (RULE_BLOCKED, USER_DENIED, …). */
    val outcome: String,
    val message: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", timestamp)
        .put("action", action)
        .put("reason", reason)
        .put("foreground", foregroundPackage ?: JSONObject.NULL)
        .put("outcome", outcome)
        .put("message", message ?: JSONObject.NULL)

    companion object {
        fun fromJson(root: JSONObject): ScreenAuditEntry = ScreenAuditEntry(
            timestamp = root.optLong("ts"),
            action = root.optString("action"),
            reason = root.optString("reason"),
            foregroundPackage = root.optString("foreground").takeIf { root.has("foreground") && !root.isNull("foreground") },
            outcome = root.optString("outcome"),
            message = root.optString("message").takeIf { root.has("message") && !root.isNull("message") },
        )
    }
}

/**
 * Local audit trail of the last [MAX_ENTRIES] screen-op decisions (plan §4). A single
 * JSON array file under `filesDir/screen-control/` — outside `$DSH_HOME`, so it never
 * enters the backup zip and no BackupManager rule changes. Rewritten on append: 200
 * short records make that a few-kilobyte write, and no database dependency is needed.
 */
class ScreenAuditLog(private val file: File) {

    constructor(context: android.content.Context) : this(
        File(File(context.filesDir, DIR), FILE),
    )

    @Synchronized
    fun append(entry: ScreenAuditEntry) {
        withEntries { ScreenAuditLog.appendCapped(it, entry) }
    }

    @Synchronized
    fun entries(): List<ScreenAuditEntry> = readEntries()

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun withEntries(transform: (List<ScreenAuditEntry>) -> List<ScreenAuditEntry>) {
        write(transform(readEntries()))
    }

    private fun readEntries(): List<ScreenAuditEntry> {
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ScreenAuditEntry.fromJson(item)
            }
        } catch (_: Throwable) {
            // A torn or hand-mangled file must not brick the feature; start a fresh log.
            emptyList()
        }
    }

    private fun write(entries: List<ScreenAuditEntry>) {
        file.parentFile?.mkdirs()
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }

    companion object {
        const val MAX_ENTRIES = 200
        private const val DIR = "screen-control"
        private const val FILE = "audit.json"

        /** Newest first, hard cap. Pure so the ring semantics are unit-testable. */
        fun appendCapped(existing: List<ScreenAuditEntry>, entry: ScreenAuditEntry): List<ScreenAuditEntry> =
            (listOf(entry) + existing).take(MAX_ENTRIES)
    }
}
