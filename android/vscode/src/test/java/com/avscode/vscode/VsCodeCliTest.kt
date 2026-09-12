package com.avscode.vscode

import org.junit.Assert.*
import org.junit.Test

class VsCodeCliTest {

    @Test
    fun testCliDownloadUrls() {
        assertNotNull(VsCodeCliManager.CLI_DOWNLOAD_URL)
        assertTrue(VsCodeCliManager.CLI_DOWNLOAD_URL.startsWith("https://"))
        assertTrue(VsCodeCliManager.CLI_DOWNLOAD_URL.contains("cli-alpine-arm64"))

        assertNotNull(VsCodeCliManager.CLI_FALLBACK_URL)
        assertTrue(VsCodeCliManager.CLI_FALLBACK_URL.startsWith("https://"))
        assertTrue(VsCodeCliManager.CLI_FALLBACK_URL.contains("cli-alpine-arm64"))
    }

    @Test
    fun testGuestPaths() {
        assertEquals("/usr/local/bin/code", VsCodeCliManager.GUEST_BIN_PATH)
        assertEquals("/home/user/.vscode-cli", VsCodeCliManager.GUEST_DATA_DIR)
        assertEquals("/home/user/projects", VsCodeCliManager.GUEST_PROJECTS_DIR)
        assertEquals("127.0.0.1", VsCodeCliManager.DEFAULT_SERVER_HOST)
    }

    @Test
    fun testBuildServerCommand() {
        val port = 9050
        val cmd = VsCodeCliManager.buildServerCommand(port, "127.0.0.1")

        assertTrue(cmd.startsWith("/usr/local/bin/code serve-web"))
        assertTrue(cmd.contains("--host 127.0.0.1"))
        assertTrue(cmd.contains("--port 9050"))
        assertTrue(cmd.contains("--without-connection-token"))
        assertTrue(cmd.contains("--accept-server-license-terms"))
        assertTrue(cmd.contains("--cli-data-dir /home/user/.vscode-cli"))
        assertTrue(cmd.contains("--user-data-dir /home/user/.vscode-cli/data"))
    }

    @Test
    fun testFindAvailablePort() {
        val port = VsCodeCliManager.findAvailablePort()
        assertTrue(port > 1024)
        assertTrue(port <= 65535)

        val port2 = VsCodeCliManager.findAvailablePort()
        assertTrue(port2 > 1024)
        assertTrue(port2 <= 65535)
    }

    @Test
    fun testCliStatus() {
        val status = VsCodeCliStatus(
            isInstalled = true,
            isRunning = true,
            serverPort = 8080,
            serverUrl = "http://127.0.0.1:8080/?folder=/home/user/projects"
        )
        assertTrue(status.isInstalled)
        assertTrue(status.isRunning)
        assertEquals(8080, status.serverPort)
        assertEquals("http://127.0.0.1:8080/?folder=/home/user/projects", status.serverUrl)
        assertEquals("http://127.0.0.1:8080/?folder=/home/user/projects", status.tunnelUrl)
    }
}
