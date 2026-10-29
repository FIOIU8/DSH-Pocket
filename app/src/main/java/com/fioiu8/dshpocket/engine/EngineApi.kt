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
import java.net.SocketTimeoutException
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
        data class Err(
            val code: String,
            val message: String,
            val details: String = message,
            val requestId: String? = null,
            val retryable: Boolean = isRetryableCode(code),
        ) : RpcResult
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
        val requestId = "native-${System.nanoTime()}"
        try {
            var response = exchange(baseUrl, endpoint, args, readTimeoutMillis, requestId)
            if (response.status == 401 && !launchToken.isNullOrBlank()) {
                if (SessionCookie.establish(baseUrl, launchToken)) {
                    response = exchange(baseUrl, endpoint, args, readTimeoutMillis, requestId)
                }
            }
            parse(response)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = if (e is SocketTimeoutException) "timeout" else "transport"
            val message = safeDiagnostic(e.message.orEmpty().ifBlank { e.javaClass.simpleName })
            RpcResult.Err(code, message, safeDiagnostic("${e.javaClass.simpleName}: $message"), requestId, retryable = true)
        }
    }

    internal class RawResponse(val status: Int, val body: String, val requestId: String)

    private fun exchange(baseUrl: String, endpoint: String, args: JSONObject, readTimeoutMillis: Int, requestId: String): RawResponse {
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", requestId)
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
            return RawResponse(connection.responseCode, text, requestId)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(response: RawResponse): RpcResult {
        if (response.status != 200) {
            val code = "http/${response.status}"
            val detail = safeDiagnostic(response.body.take(2_000).ifBlank { "HTTP ${response.status}" })
            return RpcResult.Err(code, "HTTP ${response.status}", detail, response.requestId, response.status >= 500 || response.status == 408 || response.status == 429)
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return RpcResult.Err("protocol", "Response is not JSON", safeDiagnostic(response.body.take(2_000)), response.requestId)
        val result = json.optJSONObject("result")
            ?: return RpcResult.Err("protocol", "Response lacks the result field", safeDiagnostic(response.body.take(2_000)), response.requestId)
        return if (result.optBoolean("ok")) {
            RpcResult.Ok(result.opt("value"))
        } else {
            val error = result.optJSONObject("error")
            val code = error?.optString("code").orEmpty().ifBlank { "unknown" }
            val message = safeDiagnostic(error?.optString("message").orEmpty().ifBlank { "Unknown error" })
            val requestId = error?.optString("requestId")?.takeIf { it.isNotBlank() && it != "null" }
            val details = buildString {
                append("code: ").append(code)
                append("\nmessage: ").append(message)
                error?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }?.let { append("\nname: ").append(it) }
                error?.opt("details")?.let { append("\ndetails: ").append(it) }
                error?.optString("reason")?.takeIf { it.isNotBlank() && it != "null" }?.let { append("\nreason: ").append(it) }
            }
            RpcResult.Err(
                code,
                message,
                safeDiagnostic(details),
                requestId ?: response.requestId,
            )
        }
    }

    /** Redacts credential-shaped values before diagnostic text reaches the UI or clipboard. */
    internal fun safeDiagnostic(value: String): String = value
        .replace(Regex("(?i)([\"']?authorization[\"']?\\s*[:=]\\s*[\"']?)(?:bearer\\s+)?[^\"'\\s,;}]+"), "$1[redacted]")
        .replace(Regex("(?i)([\"']?(?:authorization|cookie|api[_-]?key|access[_-]?token|refresh[_-]?token|launch[_-]?token|password)[\"']?\\s*[:=]\\s*[\"']?)([^\"'\\s,;}]+)"), "$1[redacted]")
        .replace(Regex("(?i)(cookie\\s*[:=]\\s*)[^\\r\\n]+"), "$1[redacted]")
        .replace(Regex("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|launch[_-]?token|password)(\\s*[:=]\\s*)[^\\s,;]+"), "$1$2[redacted]")
        .take(4_000)

    internal fun isRetryableCode(code: String): Boolean =
        code == "transport" || code == "timeout" || code == "closed" || code.startsWith("http/5") || code == "http/408" || code == "http/429"

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
                    if (!webSocket.send(openFrame.toString())) channel.close(
                        SessionStreamException("stream", "Could not open the session event stream", "The WebSocket rejected the open frame.", streamId),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching { JSONObject(text) }.getOrNull()
                    if (frame == null) {
                        channel.close(SessionStreamException("protocol", "Invalid session stream response", safeDiagnostic(text), streamId))
                        return
                    }
                    if (frame.optString("streamId") != streamId) return
                    when (frame.optString("type")) {
                        "item" -> (frame.opt("value") as? JSONObject)?.let { channel.trySend(it) }
                        "error" -> {
                            val error = frame.optJSONObject("error")
                            channel.close(
                                SessionStreamException(
                                    error?.optString("code").orEmpty().ifBlank { "stream" },
                                    safeDiagnostic(error?.optString("message").orEmpty().ifBlank { "Session event stream failed" }),
                                    safeDiagnostic(error?.toString().orEmpty()),
                                    error?.optString("requestId")?.takeIf(String::isNotBlank) ?: streamId,
                                ),
                            )
                        }
                        "end" -> channel.close(SessionStreamException("closed", "Session event stream ended", "The engine closed the stream.", streamId))
                    }
                }

                // A server-side close handshake must end the flow too, or the collector
                // would stay suspended forever after an orderly shutdown.
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    channel.close(SessionStreamException("closed", "Session event stream closed", safeDiagnostic("close code: $code\nreason: $reason"), streamId))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    channel.close(SessionStreamException("closed", "Session event stream closed", safeDiagnostic("close code: $code\nreason: $reason"), streamId))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val status = response?.code
                    val code = if (status != null) "http/$status" else "transport"
                    channel.close(SessionStreamException(code, safeDiagnostic(t.message.orEmpty().ifBlank { "Session event stream failed" }), safeDiagnostic("${t.javaClass.simpleName}: ${t.message.orEmpty()}${status?.let { "\nHTTP status: $it" }.orEmpty()}"), streamId))
                }
            },
        )
        awaitClose {
            socket.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString())
            socket.close(1000, "screen closed")
        }
    }
}

class SessionStreamException(
    val code: String,
    override val message: String,
    val details: String,
    val requestId: String? = null,
) : IOException(message)
