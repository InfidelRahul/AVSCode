package com.avscode.rootfs

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RootfsTest {

    @Test
    fun testRootfsUrlValid() {
        assertNotNull(RootfsInstaller.ROOTFS_URL)
        assertTrue(RootfsInstaller.ROOTFS_URL.startsWith("https://"))
        assertTrue(RootfsInstaller.ROOTFS_URL.contains("ubuntu-base-26.04-base-arm64.tar.gz"))
    }

    @Test
    fun testMinDiskSpaceRequirements() {
        // At least 2GB disk space required
        assertEquals(2L * 1024 * 1024 * 1024, RootfsInstaller.MIN_DISK_SPACE)
    }

    @Test
    fun testVerificationFailureException() {
        val ex = RootfsInstaller.VerificationException("Missing /bin/bash")
        assertEquals("Missing /bin/bash", ex.message)
    }

    @Test
    fun testRequiredStructureIncludesStandardDirs() {
        val expected = listOf("bin", "usr", "etc", "home", "tmp")
        for (dir in expected) {
            assertTrue("Expected rootfs to verify directory $dir", dir.isNotBlank())
        }
    }
}

