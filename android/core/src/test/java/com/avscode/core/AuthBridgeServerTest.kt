package com.avscode.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class AuthBridgeServerTest {

    private var server: AuthBridgeServer? = null

    @Before
    fun setUp() {
        server = AuthBridgeServer(0)
    }

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun testServerLifecycle() {
        val s = server ?: return
        assertFalse(s.isAlive())
        assertEquals(0, s.authBridgePort)
        assertEquals(0, s.activePort)

        val port = s.start()
        assertTrue(port > 0)
        assertTrue(s.isAlive())
        assertEquals(port, s.authBridgePort)
        assertEquals(port, s.activePort)

        // Repeat start returns same active port
        val port2 = s.start()
        assertEquals(port, port2)

        s.stop()
        assertFalse(s.isAlive())
    }

    @Test
    fun testHealthEndpoint() {
        val s = server ?: return
        val port = s.start()

        // Test reachable via 127.0.0.1
        val urlIpv4 = URL("http://127.0.0.1:$port/health")
        val connIpv4 = urlIpv4.openConnection() as HttpURLConnection
        connIpv4.connectTimeout = 3000
        connIpv4.readTimeout = 3000

        assertEquals(200, connIpv4.responseCode)
        assertTrue(connIpv4.contentType.startsWith("application/json"))
        val bodyIpv4 = connIpv4.inputStream.bufferedReader().use { it.readText() }
        assertTrue(bodyIpv4.contains("\"status\":\"ok\""))
        assertTrue(bodyIpv4.contains("\"authBridgePort\":$port"))
        assertTrue(bodyIpv4.contains("\"bridgePort\":$port"))
        connIpv4.disconnect()

        // Test reachable via localhost (resolves to 127.0.0.1 or ::1)
        val urlLocalhost = URL("http://localhost:$port/health")
        val connLocalhost = urlLocalhost.openConnection() as HttpURLConnection
        connLocalhost.connectTimeout = 3000
        connLocalhost.readTimeout = 3000
        assertEquals(200, connLocalhost.responseCode)
        val bodyLocalhost = connLocalhost.inputStream.bufferedReader().use { it.readText() }
        assertTrue(bodyLocalhost.contains("\"status\":\"ok\""))
        connLocalhost.disconnect()
    }

    @Test
    fun testAuthRequestAndCallbackLifecycle() {
        var requestedId: String? = null
        var requestedUrl: String? = null
        var requestedTitle: String? = null

        val s = AuthBridgeServer(0) { id, url, title ->
            requestedId = id
            requestedUrl = url
            requestedTitle = title
        }
        val port = s.start()

        // 1. Post auth request
        val reqUrl = URL("http://127.0.0.1:$port/auth/request")
        val conn = reqUrl.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val jsonPayload = """{"authUrl":"https://github.com/login/oauth/authorize?client_id=123","title":"GitHub Auth"}"""
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(jsonPayload) }

        assertEquals(200, conn.responseCode)
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(resp.contains("\"status\":\"pending\""))
        assertTrue(resp.contains("\"requestId\":"))
        conn.disconnect()

        assertNotNull(requestedId)
        assertEquals("https://github.com/login/oauth/authorize?client_id=123", requestedUrl)
        assertEquals("GitHub Auth", requestedTitle)

        // 2. Poll token before completion -> should be pending
        val pollUrl = URL("http://127.0.0.1:$port/auth/token?requestId=$requestedId")
        val pollConn = pollUrl.openConnection() as HttpURLConnection
        assertEquals(200, pollConn.responseCode)
        val pollResp = pollConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(pollResp.contains("\"status\":\"pending\""))
        pollConn.disconnect()

        // 3. Trigger callback (browser redirect)
        val callbackUrl = URL("http://127.0.0.1:$port/auth/callback?requestId=$requestedId&code=AUTH_CODE_987")
        val cbConn = callbackUrl.openConnection() as HttpURLConnection
        assertEquals(200, cbConn.responseCode)
        val cbResp = cbConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(cbResp.contains("AVSCode Authentication Complete"))
        cbConn.disconnect()

        // 4. Poll token after completion -> should return code and be single-use
        val tokenConn = URL("http://127.0.0.1:$port/auth/token?requestId=$requestedId").openConnection() as HttpURLConnection
        assertEquals(200, tokenConn.responseCode)
        val tokenResp = tokenConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(tokenResp.contains("\"status\":\"completed\""))
        assertTrue(tokenResp.contains("\"code\":\"AUTH_CODE_987\""))
        tokenConn.disconnect()

        // 5. Subsequent poll for the same requestId must be invalidated (single-use)
        val secondPollConn = URL("http://127.0.0.1:$port/auth/token?requestId=$requestedId").openConnection() as HttpURLConnection
        assertEquals(404, secondPollConn.responseCode)
        val secondPollResp = secondPollConn.errorStream?.bufferedReader()?.use { it.readText() }
            ?: secondPollConn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(secondPollResp.contains("expired_or_not_found"))
        secondPollConn.disconnect()

        s.stop()
    }

    @Test
    fun testProgrammaticSessionResolution() {
        val s = server ?: return
        s.start()

        val sessionId = s.createSession("https://example.com/oauth", "Test")
        assertNotNull(sessionId)

        // Resolve programmatically
        val resolved = s.completeSession(sessionId, "TEST_CODE", "TEST_TOKEN")
        assertTrue(resolved)

        // Fetch token via HTTP
        val url = URL("http://127.0.0.1:${s.activePort}/auth/token?requestId=$sessionId")
        val conn = url.openConnection() as HttpURLConnection
        assertEquals(200, conn.responseCode)
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        assertTrue(resp.contains("TEST_CODE"))
        assertTrue(resp.contains("TEST_TOKEN"))
        conn.disconnect()
    }
}
