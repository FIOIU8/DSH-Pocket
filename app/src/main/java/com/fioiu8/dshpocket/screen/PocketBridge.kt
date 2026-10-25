package com.fioiu8.dshpocket.screen

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Minimal loopback HTTP server exposing exactly one endpoint, `POST /screen` (plan §2.2).
 *
 * Handwritten on purpose: a single POST with fixed headers is ~150 lines and zero
 * dependencies, while a server framework would be a second HTTP surface in the APK. Only
 * the engine's node process ever connects — URL and token reach it as environment
 * variables — and any request without the exact bearer token is answered with a silent
 * 403 (no reason leaks to a caller that should not exist).
 *
 * Requests are served sequentially on the accept thread: screen operations are serialized
 * by design, and the executor's rule stage enforces a rate limit on top.
 */
class PocketBridge(
    private val port: Int,
    private val tokenProvider: () -> String,
    private val handler: (ScreenOpRequest) -> ScreenOpResult,
) {

    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Returns false when the port could not be bound; the feature then reports BRIDGE_UNAVAILABLE. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val socket = ServerSocket()
        return try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port))
            serverSocket = socket
            acceptThread = Thread({ loop(socket) }, "pocket-bridge").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: Throwable) {
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        acceptThread?.join(STOP_JOIN_MS)
        acceptThread = null
        serverSocket = null
    }

    private fun loop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break // server socket closed by stop()
            }
            try {
                client.use { serve(it) }
            } catch (_: Throwable) {
                // One broken connection must not take the accept loop down.
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = SOCKET_TIMEOUT_MS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val head = PocketBridgeProtocol.readHead(input)
        if (head == null) {
            write(output, PocketBridgeProtocol.reply(400, errorJson("malformed request head")))
            return
        }
        val parts = head.requestLine.split(" ")
        val method = parts.getOrNull(0) ?: ""
        val path = parts.getOrNull(1) ?: ""
        val token = tokenProvider()
        val auth = head.headers[AUTHORIZATION_HEADER]
        if (auth.isNullOrBlank() || !constantTimeEquals(auth.trim(), "Bearer $token")) {
            // Silent by design: an unauthorized caller learns nothing, not even that the endpoint exists.
            write(output, PocketBridgeProtocol.reply(403, errorJson("forbidden")))
            return
        }
        if (!method.equals("POST", ignoreCase = true)) {
            write(output, PocketBridgeProtocol.reply(405, errorJson("method not allowed")))
            return
        }
        if (path != PATH) {
            write(output, PocketBridgeProtocol.reply(404, errorJson("not found")))
            return
        }
        val length = head.headers[CONTENT_LENGTH_HEADER]?.trim()?.toIntOrNull()
        if (length == null || length !in 1..PocketBridgeProtocol.MAX_BODY_BYTES) {
            write(output, PocketBridgeProtocol.reply(400, errorJson("missing or oversized body")))
            return
        }
        val body = PocketBridgeProtocol.readBody(input, length)
        if (body == null) {
            write(output, PocketBridgeProtocol.reply(400, errorJson("body does not match content-length")))
            return
        }
        val request = ScreenOpCodec.parseRequest(String(body, StandardCharsets.UTF_8))
        if (request == null) {
            write(output, PocketBridgeProtocol.reply(400, errorJson("unrecognized screen operation")))
            return
        }
        val result = try {
            handler(request)
        } catch (t: Throwable) {
            ScreenOpResult.execFailed("执行异常：${t.message ?: t.javaClass.simpleName}")
        }
        write(output, PocketBridgeProtocol.reply(200, result.toJson().toString()))
    }

    private fun write(output: OutputStream, bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (_: Throwable) {
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("code", ScreenOpCodes.BRIDGE_UNAVAILABLE).put("message", message).toString()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val left = a.toByteArray(StandardCharsets.UTF_8)
        val right = b.toByteArray(StandardCharsets.UTF_8)
        var diff = left.size xor right.size
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            diff = diff or (left.getOrElse(i) { 0 }.toInt() xor right.getOrElse(i) { 0 }.toInt())
        }
        return diff == 0
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/screen"
        const val AUTHORIZATION_HEADER = "authorization"
        const val CONTENT_LENGTH_HEADER = "content-length"
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val STOP_JOIN_MS = 1_000L
    }
}

/** Pure protocol helpers — head/body framing and response rendering — so the wire rules are JVM-testable. */
object PocketBridgeProtocol {

    const val MAX_HEAD_BYTES = 8_192
    const val MAX_BODY_BYTES = 65_536

    data class Head(val requestLine: String, val headers: Map<String, String>)

    /** Reads up to the double CRLF; null on EOF before the head is complete or on oversize. */
    fun readHead(input: InputStream): Head? {
        val raw = readUntilDoubleCrlf(input, MAX_HEAD_BYTES) ?: return null
        return parseHead(raw)
    }

    fun readUntilDoubleCrlf(input: InputStream, cap: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var window = 0
        while (out.size() < cap) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            // Int arithmetic keeps exactly the last four bytes seen; the terminator
            // (\\r\\n\\r\\n) is recognized the moment it is complete.
            window = (window shl 8) or b
            if (window == HEAD_TERMINATOR) return out.toByteArray()
        }
        return null
    }

    fun parseHead(raw: ByteArray): Head? {
        val text = String(raw, StandardCharsets.ISO_8859_1)
        val lines = text.removeSuffix("\r\n\r\n").split("\r\n")
        val requestLine = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        return Head(requestLine, headers)
    }

    fun readBody(input: InputStream, length: Int): ByteArray? {
        if (length !in 1..MAX_BODY_BYTES) return null
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n < 0) return null
            read += n
        }
        return out
    }

    fun reply(status: Int, body: String): ByteArray {
        val payload = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${payload.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        return head.toByteArray(StandardCharsets.ISO_8859_1) + payload
    }

    fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private const val HEAD_TERMINATOR = 0x0D0A0D0A
}
