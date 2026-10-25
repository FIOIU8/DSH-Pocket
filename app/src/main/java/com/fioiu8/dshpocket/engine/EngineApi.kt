package com.fioiu8.dshpocket.engine

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

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
 * JSON stays on org.json (already on the platform) so the RPC wire format has no
 * separate JSON library dependency.
 */
object EngineApi {

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** One server outcome: `value` is whatever the endpoint returns (object/array/primitive). */
    sealed interface RpcResult {
        data class Ok(val value: Any?) : RpcResult
        data class Err(val code: String, val message: String) : RpcResult
    }

    /** Default response budget for engine RPCs. */
    const val DEFAULT_READ_TIMEOUT_MS: Int = 20_000

    /**
     * Response budget for package operations (`pluginManager/installBundle` /
     * `removeBundle`): a pnpm run over the network can legitimately take minutes.
     */
    const val PACKAGE_READ_TIMEOUT_MS: Int = 10 * 60_000

    /**
     * Calls `endpoint` with `args` against the engine at [baseUrl].
     *
     * A 401 means the WebView cookie is gone (fresh engine install or expiry); when
     * [launchToken] is provided the token→cookie exchange runs once and the call is
     * retried before giving up.
     *
     * Transport failures (the engine being down or restarting is routine) come back as
     * [RpcResult.Err] rather than propagating, so every caller can treat the result
     * uniformly; only cancellation escapes.
     */
    suspend fun rpc(
        baseUrl: String,
        endpoint: String,
        args: JSONObject,
        launchToken: String? = null,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    ): RpcResult = withContext(Dispatchers.IO) {
        try {
            var response = exchange(baseUrl, endpoint, args, readTimeoutMillis)
            if (response.status == 401 && !launchToken.isNullOrBlank()) {
                if (SessionCookie.establish(baseUrl, launchToken)) {
                    response = exchange(baseUrl, endpoint, args, readTimeoutMillis)
                }
            }
            parse(response)
        } catch (e: IOException) {
            RpcResult.Err("transport", e.message.orEmpty().ifBlank { e.javaClass.simpleName })
        }
    }

    private class RawResponse(val status: Int, val body: String)

    private fun exchange(baseUrl: String, endpoint: String, args: JSONObject, readTimeoutMillis: Int): RawResponse {
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", "native-${System.nanoTime()}")
            .put("method", endpoint)
            .put("payload", JSONObject().put("args", args))
        val connection = (URL("$baseUrl/api/$endpoint").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5_000
            readTimeout = readTimeoutMillis
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // SameSite=Strict cookie minted for this exact authority; the loopback Host
            // satisfies the engine's trusted-fence check for native clients.
            CookieManager.getInstance().getCookie(baseUrl)?.let { setRequestProperty("Cookie", it) }
        }
        try {
            connection.outputStream.use { it.write(envelope.toString().toByteArray(Charsets.UTF_8)) }
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
            ?: return RpcResult.Err("protocol", "Response is not JSON: ${response.body.take(120)}")
        val result = json.optJSONObject("result")
            ?: return RpcResult.Err("protocol", "Response lacks the result field")
        return if (result.optBoolean("ok")) {
            RpcResult.Ok(result.opt("value"))
        } else {
            val error = result.optJSONObject("error")
            RpcResult.Err(
                error?.optString("code").orEmpty().ifBlank { "unknown" },
                error?.optString("message").orEmpty().ifBlank { "Unknown error" },
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

    /** Opens the DSH Remote stream mux and follows durable events plus live assistant chunks. */
    fun followSession(baseUrl: String, sessionId: String): Flow<JSONObject> = callbackFlow {
        val channel = this
        val streamId = UUID.randomUUID().toString()
        val request = JSONObject()
            .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("assistantStream", true)
            .put("maxMessages", 100)
        val openFrame = JSONObject()
            .put("type", "open")
            .put("streamId", streamId)
            .put("endpoint", "session/follow")
            .put("payload", JSONObject().put("args", JSONObject().put("request", request)))
        val wsUrl = baseUrl.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") + "/api/remote.mux"
        val builder = Request.Builder().url(wsUrl)
        CookieManager.getInstance().getCookie(baseUrl)?.let { builder.header("Cookie", it) }
        val socket = streamClient.newWebSocket(
            builder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!webSocket.send(openFrame.toString())) channel.close(IllegalStateException("Could not open the session event stream"))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { JSONObject(text) }.getOrNull()
                    if (frame == null || frame.optString("streamId") != streamId) return
                    when (frame.optString("type")) {
                        "item" -> (frame.opt("value") as? JSONObject)?.let { channel.trySend(it) }
                        "error" -> {
                            val error = frame.optJSONObject("error")
                            channel.close(IllegalStateException(error?.optString("message").orEmpty().ifBlank { "Session event stream failed" }))
                        }
                        "end" -> channel.close()
                    }
                }

                // A server-side close handshake must end the flow too, or the collector
                // would stay suspended forever after an orderly shutdown.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    channel.close()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.close()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    channel.close(t)
                }
            },
        )
        awaitClose {
            socket.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString())
            socket.close(1000, "screen closed")
        }
    }
}
