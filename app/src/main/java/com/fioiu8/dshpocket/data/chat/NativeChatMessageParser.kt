package com.fioiu8.dshpocket.data.chat

import com.fioiu8.dshpocket.engine.EngineApi
import org.json.JSONArray
import org.json.JSONObject

internal fun assistantStreamSnapshotText(stream: JSONArray?): String = buildString {
    if (stream == null) return@buildString
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        when (record.optString("type")) {
            "text-chunks" -> {
                val chunks = record.optJSONArray("texts") ?: continue
                for (chunkIndex in 0 until chunks.length()) append(chunks.optString(chunkIndex))
            }
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                if (chunk.optString("type") == "text-delta") append(chunk.optString("text"))
            }
        }
    }
}

/**
 * Reasoning text from one assistant stream record list. Three shapes exist:
 * - live accumulator / reconnect baseline: `{time, chunk:{type:"reasoning-delta", text}}`
 * - durable assistant/message stream: `{type:"chunk", time, chunk:{type:"block-end",
 *   block:{type:"reasoning", text}}}` (the final text) and batched
 *   `{type:"reasoning-chunks", texts:[...]}` records.
 */
internal fun streamReasoningText(stream: JSONArray?): String {
    if (stream == null) return ""
    val blocks = StringBuilder()
    val deltas = StringBuilder()
    var sawDelta = false
    val batched = StringBuilder()
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        when (record.optString("type")) {
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-end" -> {
                        val block = chunk.optJSONObject("block") ?: continue
                        if (block.optString("type") == "reasoning") blocks.append(block.optString("text"))
                    }
                    "reasoning-delta" -> {
                        sawDelta = true
                        deltas.append(chunk.optString("text"))
                    }
                }
            }
            "reasoning-chunks" -> {
                val texts = record.optJSONArray("texts") ?: continue
                for (textIndex in 0 until texts.length()) batched.append(texts.optString(textIndex))
            }
            // Live accumulator records are bare `{time, chunk}` with no record type.
            else -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-end" -> {
                        val block = chunk.optJSONObject("block") ?: continue
                        if (block.optString("type") == "reasoning") blocks.append(block.optString("text"))
                    }
                    "reasoning-delta" -> {
                        sawDelta = true
                        deltas.append(chunk.optString("text"))
                    }
                }
            }
        }
    }
    return when {
        blocks.isNotBlank() -> blocks.toString()
        sawDelta -> deltas.toString()
        else -> batched.toString()
    }
}

/** Time span of the reasoning in one assistant stream record list; null when undatable. */
internal fun streamReasoningDurationMs(stream: JSONArray?): Long? {
    if (stream == null) return null
    var blockStart = -1L
    var blockEnd = -1L
    var deltaFirst = -1L
    var deltaLast = -1L
    var batchedStart = -1L
    var batchedSpan = 0L
    for (index in 0 until stream.length()) {
        val record = stream.optJSONObject(index) ?: continue
        val time = record.optLong("time", if (record.has("time0")) record.optLong("time0") else -1L)
        when (record.optString("type")) {
            "chunk" -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-start" -> if (chunk.optString("blockType") == "reasoning") blockStart = time
                    "block-end" -> if (chunk.optJSONObject("block")?.optString("type") == "reasoning") blockEnd = time
                    "reasoning-delta" -> {
                        if (deltaFirst < 0) deltaFirst = time
                        if (time > deltaLast) deltaLast = time
                    }
                }
            }
            "reasoning-chunks" -> {
                if (batchedStart < 0) batchedStart = time
                record.optJSONArray("dt")?.let { dt ->
                    for (dtIndex in 0 until dt.length()) batchedSpan += dt.optLong(dtIndex)
                }
            }
            else -> {
                val chunk = record.optJSONObject("chunk") ?: continue
                when (chunk.optString("type")) {
                    "block-start" -> if (chunk.optString("blockType") == "reasoning") blockStart = time
                    "block-end" -> if (chunk.optJSONObject("block")?.optString("type") == "reasoning") blockEnd = time
                    "reasoning-delta" -> {
                        if (deltaFirst < 0) deltaFirst = time
                        if (time > deltaLast) deltaLast = time
                    }
                }
            }
        }
    }
    return when {
        blockStart > 0 && blockEnd > blockStart -> blockEnd - blockStart
        deltaFirst > 0 && deltaLast > deltaFirst -> deltaLast - deltaFirst
        batchedStart > 0 && batchedSpan > 0 -> batchedSpan
        else -> null
    }
}

internal fun toolResultText(result: JSONObject, imagePlaceholder: String, filePlaceholder: String): String {
    val message = result.optJSONObject("message")
    val fromContent = contentText(message?.optJSONArray("content"), imagePlaceholder, filePlaceholder)
    return fromContent.ifBlank { message?.optString("text").orEmpty() }
}

internal fun parseNativeChatMessages(
    records: JSONArray,
    imagePlaceholder: String,
    filePlaceholder: String,
    toolCallOverlay: List<com.fioiu8.dshpocket.data.chat.NativeChatToolCallStore.Entry> = emptyList(),
): List<NativeChatMessage> {
    val messages = ArrayList<NativeChatMessage>()
    val turnsWithUsers = buildSet {
        for (index in 0 until records.length()) {
            val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
            if (event.optString("type") == "user/message") {
                val data = event.optJSONObject("data") ?: continue
                if (data.has("turn")) add(data.optInt("turn"))
            }
        }
    }
    val turnsWithTools = buildSet {
        for (index in 0 until records.length()) {
            val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
            if (event.optString("type") == "tool/call") {
                event.optJSONObject("data")?.optInt("turn")?.let(::add)
            }
        }
        toolCallOverlay.mapNotNullTo(this) { it.turn }
    }
    val hasUnattributedToolCalls = toolCallOverlay.any { it.turn == null }
    // callId -> index of the tool_call row still waiting for its result.
    val pendingCalls = HashMap<String, Int>()
    data class ToolFailure(val details: String?, val code: String?, val requestId: String?)
    // Results whose tool/call is not in the surface page (it lives in the overlay).
    val orphanResults = LinkedHashMap<String, Triple<String, Boolean, ToolFailure?>>()
    // True once this step's thinking already came from an `assistant/attempt` event, so
    // the assistant message's own stream must not produce a second, identical card.
    var attemptThinkingThisStep = false
    // The pocket edit flow shadows an assistant message with a sentinel developer note
    // (a surface replace); the appended assistant message right after it is the edited one.
    var lastEventWasEditShadow = false
    for (index in 0 until records.length()) {
        val event = records.optJSONObject(index)?.optJSONObject("event") ?: continue
        val type = event.optString("type")
        val data = event.optJSONObject("data") ?: continue
        val seq = event.optLong("seq")
        if (type == "turn/end") {
            val reason = data.optJSONObject("reason") ?: continue
            val kind = reason.optString("kind")
            if (kind in setOf("error", "blocked", "max-tokens", "interrupted")) {
                val failure = reason.optJSONObject("error")
                val code = when (kind) {
                    "error" -> failure?.optString("code")?.takeIf(String::isNotBlank) ?: "turn/error"
                    else -> "turn/$kind"
                }
                val message = failure?.optString("message")?.takeIf(String::isNotBlank)
                    ?: reason.optString("message").takeIf(String::isNotBlank)
                    ?: kind
                val safeMessage = EngineApi.safeDiagnostic(message)
                val turn = data.optInt("turn").takeIf { data.has("turn") }
                val turnNumber = turn ?: 0
                val hasTools = turn != null && turn in turnsWithTools
                val requestId = failure?.optString("requestId")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: data.optString("requestId").takeIf { it.isNotBlank() && it != "null" }
                val detail = buildString {
                    append("turn: ").append(turnNumber)
                    append("\nreason: ").append(kind)
                    failure?.optString("code")?.takeIf(String::isNotBlank)?.let { append("\ncode: ").append(it) }
                    append("\nmessage: ").append(safeMessage)
                    failure?.optInt("status")?.takeIf { failure.has("status") }?.let { append("\nstatus: ").append(it) }
                    requestId?.let { append("\nrequestId: ").append(it) }
                    append("\ntoolCalls: ").append(if (hasTools) "present" else "none")
                }
                messages.add(
                    NativeChatMessage(
                        id = "turn-error-$seq",
                        role = "error",
                        text = safeMessage,
                        time = event.optLong("time"),
                        seq = seq,
                        errorDetails = com.fioiu8.dshpocket.engine.EngineApi.safeDiagnostic(detail),
                        errorCode = code,
                        requestId = requestId,
                        retryable = kind == "error" && turn != null && turn in turnsWithUsers && !hasUnattributedToolCalls && canRegenerateTurn(hasTools),
                        turn = turn,
                    ),
                )
            }
            continue
        }
        if (type == "tool/call") {
            val name = data.optString("name")
            // The suggestions row is a rendered tool result already; its call would be noise.
            if (name == "show_chat_suggestions") continue
            attemptThinkingThisStep = false
            pendingCalls[data.optString("callId")] = messages.size
            messages.add(
                NativeChatMessage(
                    id = "tool-call-$seq",
                    role = "tool_call",
                    text = "",
                    time = event.optLong("time"),
                    seq = seq,
                    toolName = name,
                    callId = data.optString("callId"),
                    argumentsText = data.optString("arguments"),
                    turn = data.optInt("turn").takeIf { data.has("turn") },
                ),
            )
            continue
        }
        if (type == "tool/result") {
            val meta = data.optJSONObject("meta")
            if (meta?.optString("kind") == "dsh-pocket/native-chat-suggestions") {
                val suggestions = meta.optJSONArray("suggestions") ?: continue
                val values = buildList {
                    for (suggestionIndex in 0 until suggestions.length()) {
                        val suggestion = suggestions.optString(suggestionIndex).trim()
                        if (suggestion.length in 2..80 && suggestion !in this) add(suggestion)
                    }
                }.take(4)
                if (values.size >= 2) {
                    messages.add(
                        NativeChatMessage(
                            id = "suggestions-$seq",
                            role = "suggestions",
                            text = "",
                            time = event.optLong("time"),
                            seq = seq,
                            suggestions = values,
                        ),
                    )
                }
                continue
            }
            val message = data.optJSONObject("message")
            val callId = message?.optString("toolCallId").orEmpty()
            val result = toolResultText(data, imagePlaceholder, filePlaceholder)
            val structuredError = data.optJSONObject("error")
            val rawError = data.opt("error")?.takeUnless { it == JSONObject.NULL }
            val isError = message?.optBoolean("isError", false) == true || rawError != null
            val errorDetails = structuredError?.let { error ->
                buildList {
                    error.optString("code").takeIf(String::isNotBlank)?.let { add("code: $it") }
                    error.optString("message").takeIf(String::isNotBlank)?.let { add("message: $it") }
                    error.optString("reason").takeIf(String::isNotBlank)?.let { add("reason: $it") }
                    error.optString("requestId").takeIf(String::isNotBlank)?.let { add("requestId: $it") }
                }.joinToString("\n").ifBlank { null }
            } ?: rawError?.toString()?.takeIf { isError }
            val errorCode = structuredError?.optString("code")?.takeIf(String::isNotBlank)
            val requestId = structuredError?.optString("requestId")?.takeIf(String::isNotBlank)
            val safeErrorDetails = errorDetails?.let(EngineApi::safeDiagnostic)
            val safeResult = if (isError) EngineApi.safeDiagnostic(result) else result
            val toolFailure = if (isError) ToolFailure(safeErrorDetails, errorCode, requestId) else null
            val target = pendingCalls.remove(callId)
            if (target != null && target < messages.size) {
                val call = messages[target]
                if (call.callId == callId) {
                    messages[target] = call.copy(resultText = safeResult, isError = isError, errorDetails = safeErrorDetails, errorCode = errorCode, requestId = requestId)
                    continue
                }
            }
            if (callId.isNotBlank()) orphanResults[callId] = Triple(safeResult, isError, toolFailure)
            continue
        }
        if (type == "assistant/attempt") {
            val reasoning = streamReasoningText(data.optJSONArray("stream"))
            if (reasoning.isNotBlank()) {
                messages.add(
                    NativeChatMessage(
                        id = "thinking-$seq",
                        role = "thinking",
                        text = reasoning,
                        time = event.optLong("time"),
                        seq = seq,
                        durationMs = streamReasoningDurationMs(data.optJSONArray("stream")),
                    ),
                )
                attemptThinkingThisStep = true
            }
            continue
        }
        val message = when (type) {
            "user/message" -> data
            "system/message" -> data.optJSONObject("message") ?: data
            "developer/message" -> data.optJSONObject("message") ?: data
            "assistant/message" -> data.optJSONObject("message")
            else -> null
        } ?: continue
        // The edit sentinel (developer note replacing a shadowed assistant message) never
        // renders; it only marks the appended assistant message as the edited one.
        val shadowing = event.optJSONObject("surfaceOp")?.optString("op") == "replace"
        if (type == "developer/message" && shadowing) {
            lastEventWasEditShadow = true
            continue
        }
        val editedMessage = type == "assistant/message" && lastEventWasEditShadow
        lastEventWasEditShadow = false
        val text = contentText(message.optJSONArray("content"), imagePlaceholder, filePlaceholder)
            .ifBlank { message.optString("text").takeIf { it != "null" }.orEmpty() }
        if (text.isBlank()) continue
        val isSystemPrompt = type == "system/message" || type == "developer/message" || isNativeSystemPrompt(text)
        val role = when {
            type == "assistant/message" -> "assistant"
            type == "user/message" && !isSystemPrompt -> "user"
            else -> "system"
        }
        // Fallback for engines that log no `assistant/attempt` events: lift the final
        // step's reasoning out of the assistant message's own stream record.
        if (role == "assistant" && !attemptThinkingThisStep) {
            val reasoning = streamReasoningText(data.optJSONArray("stream"))
            if (reasoning.isNotBlank()) {
                messages.add(
                    NativeChatMessage(
                        id = "thinking-$seq",
                        role = "thinking",
                        text = reasoning,
                        time = event.optLong("time"),
                        seq = seq,
                        durationMs = streamReasoningDurationMs(data.optJSONArray("stream")),
                    ),
                )
            }
        }
        if (role != "system") attemptThinkingThisStep = false
        val usage = listOfNotNull(
            data.optJSONObject("usage"),
            data.optJSONObject("tokenUsage"),
            data.optJSONObject("tokens"),
            message.optJSONObject("usage"),
            message.optJSONObject("metadata"),
        ).firstOrNull()
        val inputTokens = firstLong(usage, "inputTokens", "promptTokens", "input_tokens", "prompt_tokens")
        val outputTokens = firstLong(usage, "outputTokens", "completionTokens", "output_tokens", "completion_tokens")
        val durationMs = firstDurationMs(
            listOf(usage, data, message),
            "durationMs",
            "duration_ms",
            "elapsedMs",
            "latencyMs",
            "duration",
            "elapsed",
        )
        messages.add(
            NativeChatMessage(
                message.optString("id", "$seq"),
                role,
                text,
                event.optLong("time"),
                isSystemPrompt,
                inputTokens,
                outputTokens,
                durationMs,
                seq = seq,
                edited = editedMessage,
                turn = data.optInt("turn").takeIf { data.has("turn") },
            ),
        )
    }
    if (toolCallOverlay.isEmpty()) return messages.sortedBy { it.seq }
    // The overlay carries the tool/call events `session/page` never serves. But the
    // follow snapshot's records DO include raw events on some paths — skip any call the
    // parsed history already produced, or the row would appear twice under one key.
    val existingCallIds = messages.mapNotNull { it.callId }.toSet()
    val overlayRows = toolCallOverlay
        .filter { it.callId !in existingCallIds }
        .map { entry ->
            val orphan = orphanResults[entry.callId]
            NativeChatMessage(
                id = "tool-call-${entry.seq}",
                role = "tool_call",
                text = "",
                time = 0L,
                seq = entry.seq,
                toolName = entry.name,
                callId = entry.callId,
                argumentsText = entry.arguments,
                resultText = orphan?.first,
                isError = orphan?.second == true,
                errorDetails = orphan?.third?.details,
                errorCode = orphan?.third?.code,
                requestId = orphan?.third?.requestId,
                turn = entry.turn,
            )
        }
    if (overlayRows.isEmpty()) return messages
    return (messages + overlayRows).sortedBy { it.seq }
}

/**
 * Folds one live durable event into the current message list. `tool/result` updates its
 * pending `tool/call` row in place (a single-event parse cannot see it); everything else
 * appends with the usual id dedupe.
 */
internal fun applySessionEvent(
    existing: List<NativeChatMessage>,
    event: JSONObject,
    imagePlaceholder: String,
    filePlaceholder: String,
): List<NativeChatMessage> {
    val type = event.optString("type")
    val data = event.optJSONObject("data") ?: return existing
    // A surface replace shadows older nodes out of the model-visible history; drop the
    // messages it cites so the live list matches what the next refresh will return.
    var base = existing
    if (event.optJSONObject("surfaceOp")?.optString("op") == "replace") {
        val sources = event.optJSONArray("sourceEventSeqs")
        if (sources != null && sources.length() > 0) {
            val shadowed = HashSet<Long>()
            for (index in 0 until sources.length()) shadowed.add(sources.optLong(index))
            base = base.filterNot { it.seq in shadowed }
        }
    }
    if (type == "tool/result") {
        val message = data.optJSONObject("message")
        val callId = message?.optString("toolCallId").orEmpty()
        if (callId.isBlank()) return existing
        val result = toolResultText(data, imagePlaceholder, filePlaceholder)
        val structuredError = data.optJSONObject("error")
        val rawError = data.opt("error")?.takeUnless { it == JSONObject.NULL }
        val isError = message?.optBoolean("isError", false) == true || rawError != null
        val errorDetails = structuredError?.let { error ->
            buildList {
                error.optString("code").takeIf(String::isNotBlank)?.let { add("code: $it") }
                error.optString("message").takeIf(String::isNotBlank)?.let { add("message: $it") }
                error.optString("reason").takeIf(String::isNotBlank)?.let { add("reason: $it") }
                error.optString("requestId").takeIf(String::isNotBlank)?.let { add("requestId: $it") }
            }.joinToString("\n").ifBlank { null }
        } ?: rawError?.toString()?.takeIf { isError }
        val errorCode = structuredError?.optString("code")?.takeIf(String::isNotBlank)
        val requestId = structuredError?.optString("requestId")?.takeIf(String::isNotBlank)
        val index = existing.indexOfLast { it.role == "tool_call" && it.callId == callId && it.resultText == null }
        if (index < 0) return existing
        val safeResult = if (isError) EngineApi.safeDiagnostic(result) else result
        val safeErrorDetails = errorDetails?.let(EngineApi::safeDiagnostic)
        return existing.mapIndexed { i, item -> if (i == index) item.copy(resultText = safeResult, isError = isError, errorDetails = safeErrorDetails, errorCode = errorCode, requestId = requestId) else item }
    }
    val records = JSONArray().put(JSONObject().put("event", event))
    var incoming = parseNativeChatMessages(records, imagePlaceholder, filePlaceholder)
    if (type == "turn/end") {
        val turn = data.optInt("turn").takeIf { data.has("turn") }
        val hadTools = turn != null && existing.any { it.role == "tool_call" && it.turn == turn }
        incoming = incoming.map { message ->
            if (message.role == "error") message.copy(retryable = message.retryable && canRegenerateTurn(hadTools)) else message
        }
    }
    if (incoming.isEmpty()) return base
    return (base + incoming).distinctBy { it.id }.sortedBy { it.seq }
}

internal fun latestNativeSuggestions(messages: List<NativeChatMessage>): List<String> {
    var suggestions = emptyList<String>()
    messages.forEach { message ->
        when (message.role) {
            "user" -> suggestions = emptyList()
            "suggestions" -> suggestions = message.suggestions
        }
    }
    return suggestions
}

internal fun isNativeSystemPrompt(text: String): Boolean {
    val normalized = text.trim()
    return normalized.contains("<system-reminder>", ignoreCase = true) ||
        normalized.contains("</system-reminder>", ignoreCase = true) ||
        normalized.contains("Current runtime context.", ignoreCase = true) ||
        normalized.contains("Current DSH file policy:", ignoreCase = true) ||
        normalized.contains("Approval policy:", ignoreCase = true)
}

private fun firstLong(value: JSONObject?, vararg keys: String): Long? {
    value ?: return null
    keys.forEach { key ->
        if (value.has(key) && !value.isNull(key)) {
            val number = value.optLong(key, Long.MIN_VALUE)
            if (number != Long.MIN_VALUE) return number
        }
    }
    return null
}

private fun firstDurationMs(values: List<JSONObject?>, vararg keys: String): Long? {
    values.forEach { value ->
        value ?: return@forEach
        keys.forEach { key ->
            if (!value.has(key) || value.isNull(key)) return@forEach
            val raw = value.optLong(key, Long.MIN_VALUE)
            if (raw == Long.MIN_VALUE) return@forEach
            return if (key.contains("Ms", ignoreCase = true)) raw else if (raw < 1000) raw * 1000 else raw
        }
    }
    return null
}

private fun contentText(content: JSONArray?, imagePlaceholder: String, filePlaceholder: String): String = buildList {
    if (content == null) return@buildList
    for (index in 0 until content.length()) {
        val block = content.optJSONObject(index) ?: continue
        when (block.optString("type")) {
            "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            "image" -> add(imagePlaceholder)
            "file" -> add(filePlaceholder)
        }
    }
}.joinToString("\n")
