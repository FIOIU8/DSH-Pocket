package com.fioiu8.dshpocket.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal client for the engine's typert RPC gateway (`/api/<namespace>/<method>`).
 *
 * The wire protocol and its discovery loop are documented in
 * `docs/protocol-notes.md`: one POST per call, envelope
 * `{type:"client-request", rpcId, method, payload:{args}}`, reply
 * `{type:"server-response", rpcId, result:{ok, value|error}}`. Authentication reuses
 * the session cookie [SessionCookie] already minted for the WebView, so the native
 * pages add no auth surface of their own.
 *
 * JSON stays on org.json (already on the platform) to keep the app's zero-third-party
 * runtime dependencies intact.
 */
object EngineApi {

    /** One server outcome: `value` is whatever the endpoint returns (object/array/primitive). */
    sealed interface RpcResult {
        data class Ok(val value: Any?) : RpcResult
        data class Err(val code: String, val message: String) : RpcResult
    }

    /**
     * Calls `endpoint` with `args` against the engine at [baseUrl].
     *
     * A 401 means the WebView cookie is gone (fresh engine install or expiry); when
     * [launchToken] is provided the token→cookie exchange runs once and the call is
     * retried before giving up.
     */
    suspend fun rpc(
        baseUrl: String,
        endpoint: String,
        args: JSONObject,
        launchToken: String? = null,
    ): RpcResult = withContext(Dispatchers.IO) {
        var response = exchange(baseUrl, endpoint, args)
        if (response.status == 401 && !launchToken.isNullOrBlank()) {
            if (SessionCookie.establish(baseUrl, launchToken)) {
                response = exchange(baseUrl, endpoint, args)
            }
        }
        parse(response)
    }

    private class RawResponse(val status: Int, val body: String)

    private fun exchange(baseUrl: String, endpoint: String, args: JSONObject): RawResponse {
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", "native-${System.nanoTime()}")
            .put("method", endpoint)
            .put("payload", JSONObject().put("args", args))
        val connection = (URL("$baseUrl/api/$endpoint").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // SameSite=Strict cookie minted for this exact authority; the loopback Host
            // satisfies the engine's trusted-fence check for native clients.
            CookieManager.getInstance().getCookie(baseUrl)?.let { setRequestProperty("Cookie", it) }
            outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            return RawResponse(connection.responseCode, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(response: RawResponse): RpcResult {
        if (response.status != 200) {
            return RpcResult.Err("http/${response.status}", response.body.take(200).ifBlank { "HTTP ${response.status}" })
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return RpcResult.Err("protocol", "响应不是 JSON：${response.body.take(120)}")
        val result = json.optJSONObject("result")
            ?: return RpcResult.Err("protocol", "响应缺少 result 字段")
        return if (result.optBoolean("ok")) {
            RpcResult.Ok(result.opt("value"))
        } else {
            val error = result.optJSONObject("error")
            RpcResult.Err(
                error?.optString("code").orEmpty().ifBlank { "unknown" },
                error?.optString("message").orEmpty().ifBlank { "未知错误" },
            )
        }
    }

    /** Convenience for endpoints whose single parameter is named `request`. */
    fun requestArgs(request: JSONObject): JSONObject = JSONObject().put("request", request)

    /** Convenience for endpoints taking no parameters. */
    fun emptyArgs(): JSONObject = JSONObject()

    /** Reads a list out of an `Ok` value: either a bare array or `{"items":[...]}`. */
    fun itemsOf(value: Any?): JSONArray = when (value) {
        is JSONArray -> value
        is JSONObject -> value.optJSONArray("items") ?: JSONArray()
        else -> JSONArray()
    }
}
