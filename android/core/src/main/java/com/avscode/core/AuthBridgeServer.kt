package com.avscode.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, zero-dependency Android <-> Linux Authentication Bridge.
 *
 * Runs strictly on local loopback (127.0.0.1:<bridgePort>) to facilitate secure,
 * session-scoped authentication interactions between guest processes (git, extensions,
 * CLI tools) and Android host capabilities (browser intents, OAuth callbacks).
 */
class AuthBridgeServer(
    private val requestedPort: Int = 0,
    private val onAuthRequested: ((requestId: String, authUrl: String, title: String?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AuthBridgeServer"
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes TTL for auth sessions
    }

    data class AuthSession(
        val requestId: String,
        val authUrl: String,
        val title: String?,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var token: String? = null,
        @Volatile var code: String? = null,
        @Volatile var isCompleted: Boolean = false
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() - createdAt > DEFAULT_TIMEOUT_MS
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)
    private val sessions = ConcurrentHashMap<String, AuthSession>()

    var activePort: Int = 0
        private set

    /**
     * Starts the loopback HTTP bridge server.
     */
    @Synchronized
    fun start(): Int {
        if (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) {
            return activePort
        }

        val socket = ServerSocket(requestedPort, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        activePort = socket.localPort
        isRunning.set(true)

        executor.submit {
            listenLoop(socket)
        }

        AvsLogger.i(TAG, "AuthBridgeServer started on 127.0.0.1:$activePort")
        return activePort
    }

    /**
     * Stops the bridge server and clears all active sessions.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            AvsLogger.d(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
        sessions.clear()
        AvsLogger.i(TAG, "AuthBridgeServer stopped")
    }

    fun isAlive(): Boolean = isRunning.get() && serverSocket?.isClosed == false

    /**
     * Register or trigger an auth request from Android side or programmatic source.
     */
    fun createSession(authUrl: String, title: String? = null, customId: String? = null): String {
        cleanExpiredSessions()
        val id = customId ?: UUID.randomUUID().toString()
        val session = AuthSession(requestId = id, authUrl = authUrl, title = title)
        sessions[id] = session
        onAuthRequested?.invoke(id, authUrl, title)
        return id
    }

    /**
     * Resolves an authentication session with credentials/code from an Android callback.
     */
    fun completeSession(requestId: String, code: String?, token: String?): Boolean {
        val session = sessions[requestId] ?: return false
        if (session.isExpired) {
            sessions.remove(requestId)
            return false
        }
        session.code = code
        session.token = token
        session.isCompleted = true
        return true
    }

    private fun cleanExpiredSessions() {
        sessions.entries.removeIf { it.value.isExpired }
    }

    private fun listenLoop(socket: ServerSocket) {
        while (isRunning.get() && !socket.isClosed) {
            try {
                val client = socket.accept()
                executor.submit {
                    handleConnection(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    AvsLogger.w(TAG, "Exception accepting connection: ${e.message}")
                }
            }
        }
    }

    private fun handleConnection(client: Socket) {
        client.use { s ->
            s.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            val output = s.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val fullPath = parts[1]

            // Read headers
            var contentLength = 0
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substring(15).trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }

            // Read body if present
            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val r = reader.read(buf, readTotal, contentLength - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else ""

            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryParams = parseQueryParams(if (fullPath.contains("?")) fullPath.substringAfter("?") else "")

            when {
                path == "/health" || path == "/status" -> {
                    val resp = """{"status":"ok","bridgePort":$activePort,"activeSessions":${sessions.size}}"""
                    sendResponse(output, 200, "application/json", resp)
                }
                path == "/auth/request" && method == "POST" -> {
                    handleAuthRequest(body, queryParams, output)
                }
                path == "/auth/callback" -> {
                    handleAuthCallback(queryParams, output)
                }
                path == "/auth/token" || path == "/auth/poll" -> {
                    handleAuthPoll(queryParams, output)
                }
                else -> {
                    sendResponse(output, 404, "application/json", """{"error":"Not Found","path":"$path"}""")
                }
            }
        }
    }

    private fun handleAuthRequest(body: String, queryParams: Map<String, String>, output: OutputStream) {
        cleanExpiredSessions()

        // Support both JSON body and urlencoded params
        val authUrl = extractField(body, "authUrl") ?: queryParams["authUrl"]
        val title = extractField(body, "title") ?: queryParams["title"]
        val requestedId = extractField(body, "requestId") ?: queryParams["requestId"] ?: UUID.randomUUID().toString()

        if (authUrl.isNullOrBlank()) {
            sendResponse(output, 400, "application/json", """{"error":"Missing required parameter 'authUrl'"}""")
            return
        }

        val session = AuthSession(requestId = requestedId, authUrl = authUrl, title = title)
        sessions[requestedId] = session
        onAuthRequested?.invoke(requestedId, authUrl, title)

        val resp = """{"status":"pending","requestId":"$requestedId","authUrl":"$authUrl"}"""
        sendResponse(output, 200, "application/json", resp)
    }

    private fun handleAuthCallback(queryParams: Map<String, String>, output: OutputStream) {
        val requestId = queryParams["requestId"] ?: queryParams["state"]
        val code = queryParams["code"]
        val token = queryParams["token"]

        if (requestId == null) {
            val html = "<html><body><h3>Error: Missing requestId</h3></body></html>"
            sendResponse(output, 400, "text/html", html)
            return
        }

        val session = sessions[requestId]
        if (session == null || session.isExpired) {
            val html = "<html><body><h3>Authentication request expired or not found.</h3></body></html>"
            sendResponse(output, 404, "text/html", html)
            return
        }

        session.code = code
        session.token = token
        session.isCompleted = true

        val html = "<html><body style=\"font-family:sans-serif;text-align:center;padding-top:40px;\">" +
                "<h2>AVSCode Authentication Complete</h2>" +
                "<p>You can now return to the AVSCode editor.</p>" +
                "</body></html>"
        sendResponse(output, 200, "text/html", html)
    }

    private fun handleAuthPoll(queryParams: Map<String, String>, output: OutputStream) {
        val requestId = queryParams["requestId"]
        if (requestId == null) {
            sendResponse(output, 400, "application/json", """{"error":"Missing 'requestId' parameter"}""")
            return
        }

        val session = sessions[requestId]
        if (session == null || session.isExpired) {
            sessions.remove(requestId)
            sendResponse(output, 404, "application/json", """{"status":"expired_or_not_found"}""")
            return
        }

        if (!session.isCompleted) {
            sendResponse(output, 200, "application/json", """{"status":"pending","requestId":"$requestId"}""")
            return
        }

        // Single-use token retrieval: once consumed, remove session immediately
        val code = session.code.orEmpty()
        val token = session.token.orEmpty()
        sessions.remove(requestId)

        val resp = """{"status":"completed","requestId":"$requestId","code":"$code","token":"$token"}"""
        sendResponse(output, 200, "application/json", resp)
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val map = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun extractField(jsonOrForm: String, field: String): String? {
        if (jsonOrForm.isBlank()) return null
        // Simple JSON extractor: "field"\s*:\s*"([^"]+)"
        val jsonPattern = Regex("\"$field\"\\s*:\\s*\"([^\"]+)\"")
        val match = jsonPattern.find(jsonOrForm)
        if (match != null) {
            return match.groupValues[1]
        }
        // Fallback form param: field=value
        val formPattern = Regex("(?:^|&)$field=([^&]+)")
        val formMatch = formPattern.find(jsonOrForm)
        return formMatch?.groupValues?.getOrNull(1)?.let {
            URLDecoder.decode(it, "UTF-8")
        }
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "OK"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
