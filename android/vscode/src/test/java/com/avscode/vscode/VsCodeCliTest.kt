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

    @Test
    fun testDependencyValidationReportFormatSatisfied() {
        val runtime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Ubuntu",
            distroVersion = "22.04",
            arch = "aarch64",
            bitness = 64,
            libc = "glibc",
            libcVersion = "2.35",
            kernelVersion = "6.6.127"
        )
        val report = DependencyValidationReport(
            runtimeInfo = runtime,
            glibcValid = true,
            glibcInstalled = "2.35",
            glibcRequired = "2.28",
            libstdcxxValid = true,
            glibcxxSymbolValid = true,
            missingPackages = emptyList(),
            missingTools = emptyList(),
            toolVersions = mapOf("git" to "git version 2.34.1", "node" to "v18.19.0", "npm" to "9.2.0"),
            isSatisfied = true
        )
        val text = report.formatReport()
        assertTrue(text.contains("AVSCode Linux Runtime"))
        assertTrue(text.contains("Distribution: Ubuntu"))
        assertTrue(text.contains("Release: 22.04"))
        assertTrue(text.contains("Architecture: aarch64"))
        assertTrue(text.contains("Bitness: 64"))
        assertTrue(text.contains("libc: glibc"))
        assertTrue(text.contains("glibc version: 2.35 (required >= 2.28, valid: true)"))
        assertTrue(text.contains("libstdc++ availability: true"))
        assertTrue(text.contains("GLIBCXX availability: true"))
        assertTrue(text.contains("tar: available"))
        assertTrue(text.contains("bash: available"))
        assertTrue(text.contains("git: git version 2.34.1"))
        assertTrue(text.contains("Node.js: v18.19.0"))
        assertTrue(text.contains("npm: 9.2.0"))
        assertTrue(text.contains("Status: SATISFIED"))
    }

    @Test
    fun testDependencyValidationReportFormatUnsatisfied() {
        val runtime = GuestRuntimeInfo(
            os = "Linux",
            distro = "Debian",
            distroVersion = "9",
            arch = "aarch64",
            bitness = 64,
            libc = "glibc",
            libcVersion = "2.24",
            kernelVersion = "4.19.0"
        )
        val report = DependencyValidationReport(
            runtimeInfo = runtime,
            glibcValid = false,
            glibcInstalled = "2.24",
            glibcRequired = "2.28",
            libstdcxxValid = false,
            glibcxxSymbolValid = false,
            missingPackages = listOf("libstdc++6", "git"),
            missingTools = listOf("git", "curl"),
            toolVersions = emptyMap(),
            isSatisfied = false
        )
        val text = report.formatReport()
        assertTrue(text.contains("Status: INCOMPLETE"))
        assertTrue(text.contains("Installed glibc: 2.24"))
        assertTrue(text.contains("Required glibc: 2.28"))
        assertTrue(text.contains("libstdc++: MISSING"))
        assertTrue(text.contains("GLIBCXX symbol: MISSING"))
        assertTrue(text.contains("Missing dependencies: git, curl"))
    }

    @Test
    fun testRuntimeDiagnosticsReportFormat() {
        val report = RuntimeDiagnosticsReport(
            distribution = "Ubuntu",
            release = "24.04",
            architecture = "aarch64",
            bitness = 64,
            kernel = "6.6.127-android15",
            libc = "glibc",
            glibcVersion = "2.39",
            libstdcxxVersion = "6.0.33",
            glibcxxVersion = "GLIBCXX_3.4.33",
            nodeVersion = "v20.11.0",
            gitVersion = "git version 2.43.0",
            vsCodeVersion = "1.86.0",
            vsCodeTarget = "cli-linux-arm64",
            vsCodeCliDirectory = "/home/user/.avscode/cli",
            vsCodeServerDirectory = "/home/user/.avscode/server",
            vsCodeUserDataDirectory = "/home/user/.avscode/user-data",
            vsCodeExtensionDirectory = "/home/user/.avscode/extensions",
            workspaceDirectory = "/home/user/projects",
            serverPort = 33000
        )
        val formatted = report.formatReport()
        assertTrue(formatted.contains("AVSCode Runtime"))
        assertTrue(formatted.contains("Distribution: Ubuntu"))
        assertTrue(formatted.contains("Release: 24.04"))
        assertTrue(formatted.contains("Architecture: aarch64"))
        assertTrue(formatted.contains("Bitness: 64"))
        assertTrue(formatted.contains("Kernel: 6.6.127-android15"))
        assertTrue(formatted.contains("libc: glibc"))
        assertTrue(formatted.contains("glibc version: 2.39"))
        assertTrue(formatted.contains("libstdc++ version: 6.0.33"))
        assertTrue(formatted.contains("GLIBCXX version: GLIBCXX_3.4.33"))
        assertTrue(formatted.contains("Node version: v20.11.0"))
        assertTrue(formatted.contains("Git version: git version 2.43.0"))
        assertTrue(formatted.contains("VS Code version: 1.86.0"))
        assertTrue(formatted.contains("VS Code target: cli-linux-arm64"))
        assertTrue(formatted.contains("VS Code CLI directory: /home/user/.avscode/cli"))
        assertTrue(formatted.contains("VS Code server directory: /home/user/.avscode/server"))
        assertTrue(formatted.contains("VS Code user-data directory: /home/user/.avscode/user-data"))
        assertTrue(formatted.contains("VS Code extension directory: /home/user/.avscode/extensions"))
        assertTrue(formatted.contains("Workspace directory: /home/user/projects"))
        assertTrue(formatted.contains("Server port: 33000"))
    }

    @Test
    fun testDetermineFailureCauses() {
        val validRuntime = GuestRuntimeInfo(
            os = "Linux", distro = "Ubuntu", distroVersion = "22.04",
            arch = "aarch64", bitness = 64, libc = "glibc", libcVersion = "2.35", kernelVersion = "6.6"
        )

        // Invalid environment
        val causeEnv = VsCodeCliManager.determineFailureCause(null, 1, 33000, "")
        assertTrue(causeEnv.contains("invalid environment"))

        // Wrong architecture
        val badArch = validRuntime.copy(arch = "x86_64")
        val causeArch = VsCodeCliManager.determineFailureCause(badArch, 1, 33000, "")
        assertTrue(causeArch.contains("wrong architecture"))

        // Unsupported libc (musl)
        val muslRuntime = validRuntime.copy(libc = "musl")
        val causeMusl = VsCodeCliManager.determineFailureCause(muslRuntime, 1, 33000, "")
        assertTrue(causeMusl.contains("unsupported libc: musl"))

        // Missing libc
        val noLibc = validRuntime.copy(libc = "")
        val causeNoLibc = VsCodeCliManager.determineFailureCause(noLibc, 1, 33000, "")
        assertTrue(causeNoLibc.contains("missing libc"))

        // Old glibc
        val oldGlibc = validRuntime.copy(libcVersion = "2.23")
        val causeOldGlibc = VsCodeCliManager.determineFailureCause(oldGlibc, 1, 33000, "")
        assertTrue(causeOldGlibc.contains("unsupported glibc version"))

        // Missing libstdc++
        val causeLibStdCpp = VsCodeCliManager.determineFailureCause(
            validRuntime, 127, 33000,
            "error while loading shared libraries: libstdc++.so.6: cannot open shared object file: No such file or directory"
        )
        assertTrue(causeLibStdCpp.contains("missing libstdc++"))

        // Missing GLIBCXX symbol
        val causeSymbol = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "/usr/lib/libstdc++.so.6: version `GLIBCXX_3.4.25' not found"
        )
        assertTrue(causeSymbol.contains("missing libstdc++ (GLIBCXX symbol missing)"))

        // Wrong artifact
        val causeArtifact = VsCodeCliManager.determineFailureCause(
            validRuntime, 126, 33000,
            "cannot execute binary file: Exec format error"
        )
        assertTrue(causeArtifact.contains("wrong artifact"))

        // Missing dependency
        val causeDep = VsCodeCliManager.determineFailureCause(
            validRuntime, 127, 33000,
            "tar: command not found"
        )
        assertTrue(causeDep.contains("missing dependency"))

        // Port conflict
        val causePort = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "Error: listen EADDRINUSE: address already in use 127.0.0.1:33000"
        )
        assertTrue(causePort.contains("port conflict"))

        // Permissions
        val causePerm = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "EACCES: permission denied, open '/home/user/.avscode/server/lock'"
        )
        assertTrue(causePerm.contains("permissions"))

        // Workspace
        val causeWs = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "Workspace path does not exist"
        )
        assertTrue(causeWs.contains("workspace"))

        // Extension failure
        val causeExt = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "ExtensionHost crashed unexpectedly"
        )
        assertTrue(causeExt.contains("extension failure"))

        // WebView connection
        val causeWeb = VsCodeCliManager.determineFailureCause(
            validRuntime, 1, 33000,
            "ERR_CONNECTION_REFUSED while loading WebView"
        )
        assertTrue(causeWeb.contains("WebView connection"))
    }

    @Test
    fun testElfAarch64Validation() {
        val tempDir = java.nio.file.Files.createTempDirectory("elf_test").toFile()
        try {
            // Valid ELF 64-bit ARM little-endian header (52 bytes)
            val validElf = ByteArray(52)
            validElf[0] = 0x7F
            validElf[1] = 'E'.code.toByte()
            validElf[2] = 'L'.code.toByte()
            validElf[3] = 'F'.code.toByte()
            validElf[4] = 2 // 64-bit
            validElf[5] = 1 // Little-endian
            validElf[18] = 0xB7.toByte() // EM_AARCH64 lower byte
            validElf[19] = 0x00 // EM_AARCH64 upper byte

            val validFile = java.io.File(tempDir, "code_valid")
            validFile.writeBytes(validElf)
            assertTrue("Valid ELF AArch64 must pass", VsCodeCliManager.verifyElfAarch64(validFile))

            // Non-ELF file
            val invalidFile = java.io.File(tempDir, "code_invalid")
            invalidFile.writeBytes("Not an ELF binary".toByteArray())
            assertFalse("Non-ELF must fail", VsCodeCliManager.verifyElfAarch64(invalidFile))

            // 32-bit ELF
            val elf32 = validElf.clone()
            elf32[4] = 1 // 32-bit
            val file32 = java.io.File(tempDir, "code_32")
            file32.writeBytes(elf32)
            assertFalse("32-bit ELF must fail", VsCodeCliManager.verifyElfAarch64(file32))

            // Wrong arch (x86_64: 0x3E)
            val elfX86 = validElf.clone()
            elfX86[18] = 0x3E
            elfX86[19] = 0x00
            val fileX86 = java.io.File(tempDir, "code_x86")
            fileX86.writeBytes(elfX86)
            assertFalse("x86_64 ELF must fail", VsCodeCliManager.verifyElfAarch64(fileX86))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
