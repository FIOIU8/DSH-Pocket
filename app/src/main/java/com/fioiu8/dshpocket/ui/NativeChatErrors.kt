package com.fioiu8.dshpocket.ui

import com.fioiu8.dshpocket.engine.EngineApi.RpcResult

/** A user-facing chat failure with a safe, expandable diagnostic payload. */
data class ChatErrorState(
    val source: ChatErrorSource,
    val title: String,
    val message: String,
    val code: String,
    val details: String,
    val requestId: String? = null,
    val retryable: Boolean = false,
    val restoreDraft: Boolean = false,
    val recoveryText: String? = null,
)

enum class ChatErrorSource { Load, Send, Stream, Edit, Stop, Model, Turn }

internal fun rpcError(source: ChatErrorSource, title: String, result: RpcResult.Err, restoreDraft: Boolean = false) = ChatErrorState(
    source = source,
    title = title,
    message = result.message,
    code = result.code,
    details = result.details,
    requestId = result.requestId,
    retryable = result.retryable,
    restoreDraft = restoreDraft,
)
