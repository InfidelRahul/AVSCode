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
    }

    @Test
    fun testTunnelUrlRegexParsing() {
        val line1 = "Open this link in your browser https://vscode.dev/tunnel/avscode"
        val match1 = VsCodeCliManager.TUNNEL_URL_REGEX.find(line1)
        assertNotNull(match1)
        assertEquals("https://vscode.dev/tunnel/avscode", match1?.value)

        val line2 = "Connected to tunnel: https://vscode.dev/tunnel/my-device/home/user/projects"
        val match2 = VsCodeCliManager.TUNNEL_URL_REGEX.find(line2)
        assertNotNull(match2)
        assertEquals("https://vscode.dev/tunnel/my-device/home/user/projects", match2?.value)

        val line3 = "https://insiders.vscode.dev/tunnel/test-tunnel"
        val match3 = VsCodeCliManager.TUNNEL_URL_REGEX.find(line3)
        assertNotNull(match3)
        assertEquals("https://insiders.vscode.dev/tunnel/test-tunnel", match3?.value)

        val nonMatch = "Starting server at 127.0.0.1:8080"
        val matchNone = VsCodeCliManager.TUNNEL_URL_REGEX.find(nonMatch)
        assertNull(matchNone)
    }

    @Test
    fun testDeviceAuthRegexParsing() {
        val githubPrompt = "To grant access to the server, please log into https://github.com/login/device and use code CBA0-1ED9"
        val ghUrlMatch = VsCodeCliManager.AUTH_URL_REGEX.find(githubPrompt)
        val ghCodeMatch = VsCodeCliManager.AUTH_CODE_REGEX.find(githubPrompt)
        assertNotNull(ghUrlMatch)
        assertEquals("https://github.com/login/device", ghUrlMatch?.value)
        assertNotNull(ghCodeMatch)
        assertEquals("CBA0-1ED9", ghCodeMatch?.groupValues?.getOrNull(1))

        val msPrompt = "To sign in, use a web browser to open the page https://login.microsoft.com/device and enter the code A2ENVQLFZ to authenticate."
        val msUrlMatch = VsCodeCliManager.AUTH_URL_REGEX.find(msPrompt)
        val msCodeMatch = VsCodeCliManager.AUTH_CODE_REGEX.find(msPrompt)
        assertNotNull(msUrlMatch)
        assertEquals("https://login.microsoft.com/device", msUrlMatch?.value)
        assertNotNull(msCodeMatch)
        assertEquals("A2ENVQLFZ", msCodeMatch?.groupValues?.getOrNull(1))
    }

    @Test
    fun testCliStatus() {
        val status = VsCodeCliStatus(
            isInstalled = true,
            isRunning = true,
            tunnelUrl = "https://vscode.dev/tunnel/avscode"
        )
        assertTrue(status.isInstalled)
        assertTrue(status.isRunning)
        assertEquals("https://vscode.dev/tunnel/avscode", status.tunnelUrl)
    }
}

