package com.avscode.vscode

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

class VsCodeServerLifecycleTest {

    @Test
    fun testBuildServerCommandCustomHostAndPort() {
        val cmd = VsCodeCliManager.buildServerCommand(10050, "127.0.0.1")
        assertEquals(
            "/usr/local/bin/code serve-web --host 127.0.0.1 --port 10050 --without-connection-token --accept-server-license-terms --cli-data-dir /home/user/.avscode/cli --server-data-dir /home/user/.avscode/server --default-folder /home/user/projects",
            cmd
        )
    }

    @Test
    fun testFindAvailablePortIsUsable() {
        val port = VsCodeCliManager.findAvailablePort()
        assertTrue("Port should be > 1024", port > 1024)

        // Verify we can bind immediately to the port
        val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        assertTrue(socket.isBound)
        socket.close()
    }

    @Test
    fun testHttpReachableProbe() {
        val port = ServerSocket(0).use { it.localPort }

        // When nothing is listening, checkHttpReachable must return false
        assertFalse(VsCodeCliManager.checkHttpReachable(port))

        // Start a minimal loopback HTTP server
        val server = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread {
            try {
                val client = server.accept()
                val out: OutputStream = client.getOutputStream()
                val resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK"
                out.write(resp.toByteArray(StandardCharsets.UTF_8))
                out.flush()
                client.close()
            } catch (_: Exception) {}
        }

        try {
            // Should detect server as reachable
            val reachable = VsCodeCliManager.checkHttpReachable(port)
            assertTrue(reachable)
        } finally {
            server.close()
            serverThread.join(2000)
        }
    }
}

