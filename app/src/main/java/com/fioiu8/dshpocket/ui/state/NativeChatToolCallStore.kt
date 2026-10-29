package com.fioiu8.dshpocket.ui.state

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-side record of the durable `tool/call` events of one session.
 *
 * `session/page` serves surface events only (messages + tool results), so the moment a
 * refresh replaces the live list, tool-call rows would vanish — and they are exactly
 * what the user wants to see. The follow stream, however, delivers every durable event
 * live, so each `tool/call` seen there is appended here and merged back into parsed
 * history by journal sequence ([NativeChatScreen] owns the merge).
 *
 * One small JSON file per session under `filesDir/native-chat/tool-calls/`; capped at
 * [MAX_ENTRIES] records per session, nothing leaves the device.
 */
class NativeChatToolCallStore(private val directory: File) {

    constructor(context: Context) : this(File(File(context.filesDir, "native-chat"), "tool-calls"))

    data class Entry(
        val seq: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val turn: Int? = null,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("seq", seq)
            .put("callId", callId)
            .put("name", name)
            .put("arguments", arguments)
            .apply { turn?.let { put("turn", it) } }

        companion object {
            fun fromJson(root: JSONObject): Entry? {
                val callId = root.optString("callId").takeIf { it.isNotBlank() } ?: return null
                return Entry(
                    seq = root.optLong("seq"),
                    callId = callId,
                    name = root.optString("name").ifBlank { "unknown" },
                    arguments = root.optString("arguments"),
                    turn = root.optInt("turn").takeIf { root.has("turn") },
                )
            }
        }
    }

    @Synchronized
    fun append(sessionId: String, entry: Entry) {
        val file = fileFor(sessionId)
        file.parentFile?.mkdirs()
        val entries = entries(sessionId).toMutableList()
        if (entries.any { it.callId == entry.callId }) return
        entries.add(entry)
        write(file, entries.takeLast(MAX_ENTRIES))
    }

    @Synchronized
    fun entries(sessionId: String): List<Entry> {
        val file = fileFor(sessionId)
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { Entry.fromJson(it) }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fileFor(sessionId: String): File = File(directory, "${sessionId.hashForFileName()}.json")

    private fun write(file: File, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
    }

    private fun String.hashForFileName(): String = fold(0L) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFFFFFFFFFFL }.toString(36)

    companion object {
        const val MAX_ENTRIES = 200
    }
}
