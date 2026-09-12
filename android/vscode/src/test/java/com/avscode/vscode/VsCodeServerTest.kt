package com.avscode.vscode

import org.junit.Assert.*
import org.junit.Test

class VsCodeServerTest {

    @Test
    fun testServerVersionAndDownloadUrl() {
        assertEquals("4.96.4", VsCodeServerManager.SERVER_VERSION)
        assertTrue(VsCodeServerManager.SERVER_DOWNLOAD_URL.contains("code-server-4.96.4-linux-arm64.tar.gz"))
        assertFalse("Must not use underscore format", VsCodeServerManager.SERVER_DOWNLOAD_URL.contains("code-server_"))
    }

    @Test
    fun testServerPortAndUrl() {
        assertEquals(8080, VsCodeServerManager.SERVER_PORT)
        assertEquals("/opt/code-server", VsCodeServerManager.GUEST_INSTALL_DIR)
        assertEquals("/opt/code-server/bin/code-server", VsCodeServerManager.GUEST_BIN_PATH)
        assertEquals("/home/user/projects", VsCodeServerManager.GUEST_PROJECTS_DIR)
    }
}

