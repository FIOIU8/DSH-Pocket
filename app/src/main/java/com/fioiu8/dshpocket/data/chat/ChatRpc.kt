package com.fioiu8.dshpocket.data.chat

import com.fioiu8.dshpocket.engine.EngineApi
import com.fioiu8.dshpocket.engine.EngineApi.RpcResult
import org.json.JSONObject

internal suspend fun chatRpc(
    baseUrl: String,
    endpoint: String,
    args: JSONObject,
    token: String,
): RpcResult = try {
    EngineApi.rpc(baseUrl, endpoint, args, token)
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    RpcResult.Err("transport", failure.message ?: "Unable to connect to local engine")
}
