package com.avscode.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun testCarriageReturnOverwritesLineInPlace() {
        val buffer = TerminalBuffer()

        // Simulating apt/curl downloading progress updates on a single line
        buffer.append("Downloading package 10%\r")
        assertEquals("Downloading package 10%", buffer.render())
        assertEquals(1, buffer.getLineCount())

        buffer.append("Downloading package 50%\r")
        assertEquals("Downloading package 50%", buffer.render())
        assertEquals(1, buffer.getLineCount())

        buffer.append("Downloading package 100%\n")
        assertEquals("Downloading package 100%\n", buffer.render())
        assertEquals(1, buffer.getLineCount())
    }

    @Test
    fun testMultiplePackagesOnSeparateLines() {
        val buffer = TerminalBuffer()

        // Package 1 updates then commits
        buffer.append("Pkg1: 20%\rPkg1: 100%\n")
        // Package 2 updates then commits
        buffer.append("Pkg2: 50%\rPkg2: 100%\n")

        val expected = "Pkg1: 100%\nPkg2: 100%\n"
        assertEquals(expected, buffer.render())
        assertEquals(2, buffer.getLineCount())
    }

    @Test
    fun testAnsiEscapeCodeStripping() {
        val buffer = TerminalBuffer()

        // Green OK with reset
        buffer.append("\u001B[32m[OK]\u001B[0m Service started\n")
        assertEquals("[OK] Service started\n", buffer.render())
    }

    @Test
    fun testAnsiClearLine() {
        val buffer = TerminalBuffer()

        // Clear line with \u001B[2K
        buffer.append("Old long text\r\u001B[2KDone!\n")
        assertEquals("Done!\n", buffer.render())
    }

    @Test
    fun testBackspace() {
        val buffer = TerminalBuffer()
        buffer.append("Helloo\b world\n")
        assertEquals("Hello world\n", buffer.render())
    }

    @Test
    fun testMaxLinesBufferLimit() {
        val buffer = TerminalBuffer(maxLines = 5)
        for (i in 1..10) {
            buffer.appendLine("Line $i")
        }
        val rendered = buffer.render()
        assertFalse(rendered.contains("Line 1\n"))
        assertFalse(rendered.contains("Line 5\n"))
        assertTrue(rendered.contains("Line 6\n"))
        assertTrue(rendered.contains("Line 10\n"))
        assertEquals(5, buffer.getLineCount())
    }
}

