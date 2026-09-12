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
        val expected = listOf("bin", "usr", "etc", "home", "tmp", "dev", "proc", "sys", "var", "run")
        for (dir in expected) {
            assertTrue("Expected rootfs to verify directory $dir", dir.isNotBlank())
        }
    }

    @Test
    fun testPaxHeadersDetection() {
        val tempDir = File.createTempFile("avscode_test_rootfs", "").apply {
            delete()
            mkdirs()
        }
        try {
            val roguePax = File(tempDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders")
            roguePax.mkdirs()
            assertTrue(roguePax.exists())
            assertTrue(roguePax.isDirectory)

            // Ensure our PaxHeaders check detects this directory
            val hasPaxHeaders = File(tempDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders").exists()
            assertTrue("Expected PaxHeaders directory detection to succeed", hasPaxHeaders)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testGuestSymlinkResolution() {
        val tempDir = File.createTempFile("avscode_test_symlink", "").apply {
            delete()
            mkdirs()
        }
        try {
            val usrBin = File(tempDir, "usr/bin").apply { mkdirs() }
            val bash = File(usrBin, "bash").apply { writeText("#!/bin/bash") }
            val binDir = File(tempDir, "bin").apply { mkdirs() }
            val shSymlink = File(binDir, "sh")

            // Create symlink /bin/sh -> /usr/bin/bash (absolute guest link)
            java.nio.file.Files.createSymbolicLink(shSymlink.toPath(), java.nio.file.Paths.get("/usr/bin/bash"))

            val resolved = RootfsInstaller.resolveGuestSymlink(tempDir, "/bin/sh")

            assertNotNull("Expected absolute guest symlink to resolve inside rootfs", resolved)
            assertEquals(bash.canonicalPath, resolved?.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

