package com.avscode.vscode

import org.junit.Assert.*
import org.junit.Test

class VsCodeCliTest {

    @Test
    fun testCliDownloadUrls() {
        assertNotNull(VsCodeCliManager.CLI_DOWNLOAD_URL)
        assertTrue(VsCodeCliManager.CLI_DOWNLOAD_URL.startsWith("https://"))
        assertTrue("Primary URL must target cli-linux-arm64", VsCodeCliManager.CLI_DOWNLOAD_URL.contains("cli-linux-arm64"))
        assertFalse("Primary URL must NOT contain Alpine", VsCodeCliManager.CLI_DOWNLOAD_URL.contains("alpine"))

        assertNotNull(VsCodeCliManager.CLI_FALLBACK_URL)
        assertTrue(VsCodeCliManager.CLI_FALLBACK_URL.startsWith("https://"))
        assertTrue("Fallback URL must target cli-linux-arm64", VsCodeCliManager.CLI_FALLBACK_URL.contains("cli-linux-arm64"))
        assertFalse("Fallback URL must NOT contain Alpine", VsCodeCliManager.CLI_FALLBACK_URL.contains("alpine"))
    }

    @Test
    fun testPlatformSelectionUbuntuGlibc() {
        val ubuntuRuntime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Ubuntu",
            distroVersion = "26.04",
            arch = "aarch64",
            bitness = 64,
            libc = "glibc",
            libcVersion = "2.43",
            kernelVersion = "6.6.127"
        )
        val target = VsCodeCliManager.selectTargetPlatform(ubuntuRuntime)
        assertEquals("cli-linux-arm64", target)
    }

    @Test
    fun testPlatformSelectionDebianGlibc() {
        val debianRuntime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Debian GNU/Linux",
            distroVersion = "12",
            arch = "aarch64",
            bitness = 64,
            libc = "glibc",
            libcVersion = "2.36",
            kernelVersion = "6.1.0"
        )
        val target = VsCodeCliManager.selectTargetPlatform(debianRuntime)
        assertEquals("cli-linux-arm64", target)
    }

    @Test
    fun testPlatformSelectionRejectsAlpineMusl() {
        val alpineRuntime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Alpine Linux",
            distroVersion = "3.20",
            arch = "aarch64",
            bitness = 64,
            libc = "musl",
            libcVersion = "1.2.5",
            kernelVersion = "6.6.0"
        )
        try {
            VsCodeCliManager.selectTargetPlatform(alpineRuntime)
            fail("Alpine/musl runtime must be rejected with an IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Alpine") == true)
        }
    }

    @Test
    fun testPlatformSelectionRejectsNonAarch64() {
        val x86Runtime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Ubuntu",
            distroVersion = "24.04",
            arch = "x86_64",
            bitness = 64,
            libc = "glibc",
            libcVersion = "2.39",
            kernelVersion = "6.6.0"
        )
        try {
            VsCodeCliManager.selectTargetPlatform(x86Runtime)
            fail("x86_64 runtime must be rejected on ARM64 platform")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("Unsupported architecture") == true)
        }
    }

    @Test
    fun testGlibcVersionComparison() {
        // Equal or newer versions must be supported
        assertTrue(VsCodeCliManager.isGlibcVersionSupported("2.43", "2.28"))
        assertTrue(VsCodeCliManager.isGlibcVersionSupported("2.35", "2.28"))
        assertTrue(VsCodeCliManager.isGlibcVersionSupported("2.31", "2.28"))
        assertTrue(VsCodeCliManager.isGlibcVersionSupported("2.28", "2.28"))

        // Older versions must be rejected
        assertFalse(VsCodeCliManager.isGlibcVersionSupported("2.27", "2.28"))
        assertFalse(VsCodeCliManager.isGlibcVersionSupported("2.17", "2.28"))
        assertFalse(VsCodeCliManager.isGlibcVersionSupported("2.4", "2.28"))
    }

    @Test
    fun testGuestPaths() {
        assertEquals("/usr/local/bin/code", VsCodeCliManager.GUEST_BIN_PATH)
        assertEquals("/home/user/.avscode", VsCodeCliManager.GUEST_BASE_DIR)
        assertEquals("/home/user/.avscode/cli", VsCodeCliManager.GUEST_CLI_DIR)
        assertEquals("/home/user/.avscode/server", VsCodeCliManager.GUEST_SERVER_DIR)
        assertEquals("/home/user/.avscode/user-data", VsCodeCliManager.GUEST_USER_DATA_DIR)
        assertEquals("/home/user/.avscode/extensions", VsCodeCliManager.GUEST_EXTENSIONS_DIR)
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
        assertTrue(cmd.contains("--cli-data-dir /home/user/.avscode/cli"))
        assertTrue(cmd.contains("--server-data-dir /home/user/.avscode/server"))
        assertTrue(cmd.contains("--default-folder /home/user/projects"))
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
    }
}
