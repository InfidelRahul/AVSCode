package com.avscode.core

import org.junit.Assert.*
import org.junit.Test

class CoreTest {

    @Test
    fun testResultSuccess() {
        val result: Result<String> = Result.Success("hello")
        assertTrue(result.isSuccess)
        assertFalse(result.isFailure)
        assertEquals("hello", result.getOrNull())
        assertEquals("hello", result.getOrThrow())
        assertNull(result.exceptionOrNull())
    }

    @Test
    fun testResultFailure() {
        val exception = IllegalArgumentException("test error")
        val result: Result<String> = Result.Failure(exception)
        assertFalse(result.isSuccess)
        assertTrue(result.isFailure)
        assertNull(result.getOrNull())
        assertEquals(exception, result.exceptionOrNull())
    }

    @Test
    fun testResultMap() {
        val result: Result<Int> = Result.Success(21)
        val mapped = result.map { it * 2 }
        assertEquals(42, mapped.getOrNull())
    }

    @Test
    fun testAppStateTransitions() {
        val notInstalled: AppState = AppState.NotInstalled
        assertFalse(notInstalled.isReady)
        assertFalse(notInstalled.isFailed)
        assertFalse(notInstalled.canAccessCli)

        val needsStorage: AppState = AppState.NeedsStorageAccess
        assertFalse(needsStorage.isReady)
        assertFalse(needsStorage.isFailed)
        assertFalse(needsStorage.canAccessCli)

        val linuxReady: AppState = AppState.LinuxReady
        assertFalse(linuxReady.isReady)
        assertFalse(linuxReady.isFailed)
        assertTrue(linuxReady.canAccessCli)

        val ready: AppState = AppState.Ready("http://127.0.0.1:8080")
        assertTrue(ready.isReady)
        assertFalse(ready.isFailed)
        assertTrue(ready.canAccessCli)
        assertEquals("http://127.0.0.1:8080", (ready as AppState.Ready).url)

        val failed: AppState = AppState.Failed("Disk error")
        assertTrue(failed.isFailed)
        assertEquals("Disk error", (failed as AppState.Failed).message)

        val vscodeFailed: AppState = AppState.VsCodeFailed("Server crash")
        assertTrue(vscodeFailed.isFailed)
        assertTrue(vscodeFailed.canAccessCli) // CLI remains accessible on VS Code failure!
        assertEquals("Server crash", (vscodeFailed as AppState.VsCodeFailed).message)
    }

    @Test
    fun testAvsLogger() {
        AvsLogger.clear()
        AvsLogger.i("TestTag", "Info message")
        AvsLogger.e("TestTag", "Error message")

        val recent = AvsLogger.getRecentLogs(10)
        assertTrue(recent.size >= 2)
        assertEquals("Info message", recent[0].message)
        assertEquals("Error message", recent[1].message)

        val errors = AvsLogger.errors.value
        assertEquals(1, errors.size)
        assertEquals("Error message", errors[0].message)
    }
}

