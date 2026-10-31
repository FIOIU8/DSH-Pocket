package com.fioiu8.dshpocket.engine

import org.json.JSONArray
import org.json.JSONObject

/** Typed boundary for the small RPC surface used by the native UI. The wire format stays JSON. */
object TypedEngineApi {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Err(
            val code: String,
            val message: String,
            val details: String,
            val requestId: String? = null,
            val retryable: Boolean = false,
        ) : Result<Nothing>
    }

    data class SessionRef(val sessionId: String, val cwd: String?)
    data class PromptAck(val sessionId: String, val requestId: String?)
    data class SessionPage(val items: JSONArray, val nextCursor: String?)
    data class ProtocolInfo(
        val dshVersion: String?,
        val protocolVersion: Int?,
        val capabilities: Set<String>,
    )

    suspend fun createSession(baseUrl: String, token: String, cwd: String): Result<SessionRef> =
        call(baseUrl, token, "session/create", EngineApi.requestArgs(JSONObject().put("cwd", cwd))) { value ->
            parseSessionRef(value)
        }

    fun parseSessionRef(value: Any?): Result<SessionRef> {
        val objectValue = value as? JSONObject ?: return failure("protocol", "session response is not an object")
        val id = objectValue.optString("sessionId").takeIf { it.isNotBlank() }
            ?: return failure("protocol", "session response lacks sessionId")
        return Result.Ok(SessionRef(id, objectValue.optString("cwd").takeIf { it.isNotBlank() && it != "null" }))
    }

    suspend fun listSessions(baseUrl: String, token: String): Result<SessionPage> =
        call(baseUrl, token, "session/list", EngineApi.emptyArgs().put("_request", JSONObject())) { value ->
            when (value) {
                is JSONArray -> Result.Ok(SessionPage(value, null))
                is JSONObject -> Result.Ok(
                    SessionPage(
                        items = value.optJSONArray("items") ?: value.optJSONArray("sessions") ?: JSONArray(),
                        nextCursor = value.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
                else -> failure("protocol", "session/list returned an unsupported value")
            }
        }

    suspend fun pageSession(baseUrl: String, token: String, sessionId: String, cursor: String? = null): Result<SessionPage> {
        val request = JSONObject().put("sessionId", sessionId)
        cursor?.takeIf { it.isNotBlank() }?.let { request.put("cursor", it) }
        return call(baseUrl, token, "session/page", EngineApi.requestArgs(request)) { value ->
            val objectValue = value as? JSONObject ?: return@call failure("protocol", "session/page returned a non-object")
            Result.Ok(
                SessionPage(
                    items = objectValue.optJSONArray("items") ?: objectValue.optJSONArray("messages") ?: JSONArray(),
                    nextCursor = objectValue.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" },
                ),
            )
        }
    }

    suspend fun prompt(
        baseUrl: String,
        token: String,
        sessionId: String,
        requestId: String,
        text: String,
    ): Result<PromptAck> = call(
        baseUrl,
        token,
        "session/prompt",
        EngineApi.requestArgs(
            JSONObject()
                .put("requestId", requestId)
                .put("sessionId", sessionId)
                .put("mode", "queue")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("clientTimeZone", java.util.TimeZone.getDefault().id),
        ),
    ) { value ->
        val objectValue = value as? JSONObject
        Result.Ok(PromptAck(objectValue?.optString("sessionId")?.takeIf { it.isNotBlank() } ?: sessionId, objectValue?.optString("requestId")?.takeIf { it.isNotBlank() }))
    }

    suspend fun cancel(baseUrl: String, token: String, sessionId: String): Result<Unit> =
        call(baseUrl, token, "session/cancel", EngineApi.requestArgs(JSONObject().put("sessionId", sessionId))) { Result.Ok(Unit) }

    suspend fun fork(baseUrl: String, token: String, sessionId: String, atSeq: Long): Result<SessionRef> =
        call(baseUrl, token, "session/fork", EngineApi.requestArgs(JSONObject().put("sessionId", sessionId).put("atSeq", atSeq))) { value ->
            val objectValue = value as? JSONObject ?: return@call failure("protocol", "session/fork returned a non-object")
            val id = objectValue.optString("sessionId").takeIf { it.isNotBlank() }
                ?: return@call failure("protocol", "session/fork response lacks sessionId")
            Result.Ok(SessionRef(id, objectValue.optString("cwd").takeIf { it.isNotBlank() && it != "null" }))
        }

    suspend fun editMessage(baseUrl: String, token: String, sessionId: String, atSeq: Long, content: String): Result<Unit> =
        call(baseUrl, token, "session/editMessage", EngineApi.requestArgs(JSONObject().put("sessionId", sessionId).put("atSeq", atSeq).put("content", content))) { Result.Ok(Unit) }

    fun parseProtocolInfo(value: Any?): Result<ProtocolInfo> {
        val objectValue = value as? JSONObject ?: return failure("protocol", "protocol info is not an object")
        val version = if (objectValue.has("protocolVersion") && !objectValue.isNull("protocolVersion")) objectValue.optInt("protocolVersion") else null
        val capabilities = buildSet {
            val array = objectValue.optJSONArray("capabilities")
            if (array != null) for (index in 0 until array.length()) array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            val objectCapabilities = objectValue.optJSONObject("capabilities")
            if (objectCapabilities != null) objectCapabilities.keys().forEach { key -> if (objectCapabilities.optBoolean(key)) add(key) }
        }
        return Result.Ok(ProtocolInfo(objectValue.optString("dshVersion").takeIf { it.isNotBlank() && it != "null" }, version, capabilities))
    }

    private suspend fun <T> call(
        baseUrl: String,
        token: String,
        endpoint: String,
        args: JSONObject,
        parse: (Any?) -> Result<T>,
    ): Result<T> {
        return when (val result = EngineApi.rpc(baseUrl, endpoint, args, token)) {
            is EngineApi.RpcResult.Ok -> parse(result.value)
            is EngineApi.RpcResult.Err -> Result.Err(result.code, result.message, result.details, result.requestId, result.retryable)
        }
    }

    private fun failure(code: String, message: String): Result<Nothing> = Result.Err(code, message, message)
}
