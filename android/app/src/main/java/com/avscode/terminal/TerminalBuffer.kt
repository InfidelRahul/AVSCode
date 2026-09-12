package com.avscode.terminal

import java.util.regex.Pattern

/**
 * Terminal output buffer that properly handles carriage return (\r) in-place overwriting,
 * line feed (\n), backspace (\b), ANSI escape sequence stripping, and scrollback buffering.
 *
 * Guarantees that progress updates (apt, curl, wget, pip, npm, git) overwrite the current line
 * in-place instead of printing hundreds of repetitive progress lines.
 */
class TerminalBuffer(private val maxLines: Int = 2000) {

    private val lines = ArrayDeque<String>()
    private val currentLine = StringBuilder()
    private var cursorCol = 0

    companion object {
        // Regex for ANSI escape sequences: CSI codes (\u001B\[...), OSC codes, charset selects
        private val ANSI_GENERIC_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]|\u001B\\].*?\u0007|\u001B[()][A-Z0-9]")
    }

    @Synchronized
    fun append(text: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]

            // Check for ANSI Escape Sequences
            if (ch == '\u001B' && i + 1 < text.length) {
                val next = text[i + 1]
                if (next == '[') {
                    // CSI sequence: parse up to final character
                    var j = i + 2
                    while (j < text.length && (text[j] in '0'..'9' || text[j] == ';' || text[j] == '?' || text[j] == ' ')) {
                        j++
                    }
                    if (j < text.length) {
                        val command = text[j]
                        val param = text.substring(i + 2, j)
                        handleCsi(command, param)
                        i = j + 1
                        continue
                    }
                } else if (next == ']' || next == '(' || next == ')') {
                    // OSC / Charset: skip to end
                    var j = i + 2
                    while (j < text.length && text[j] != '\u0007' && text[j] != '\u001B') {
                        j++
                    }
                    if (j < text.length && text[j] == '\u0007') j++
                    i = j
                    continue
                }
            }

            when (ch) {
                '\r' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') {
                        // \r\n: full newline
                        commitCurrentLine()
                        i++ // skip \n
                    } else {
                        // \r only: return cursor to start of line for in-place overwrite
                        cursorCol = 0
                    }
                }
                '\n' -> {
                    commitCurrentLine()
                }
                '\b' -> {
                    if (cursorCol > 0) {
                        cursorCol--
                    }
                }
                '\t' -> {
                    val spaces = 4 - (cursorCol % 4)
                    for (s in 0 until spaces) {
                        writeChar(' ')
                    }
                }
                else -> {
                    if (ch.code in 32..126 || ch.code >= 160) {
                        writeChar(ch)
                    }
                }
            }
            i++
        }
    }

    private fun handleCsi(command: Char, param: String) {
        when (command) {
            'K' -> {
                // Clear in line: 0 or empty = from cursor to end, 1 = from start to cursor, 2 = all
                when (param) {
                    "", "0" -> {
                        if (cursorCol < currentLine.length) {
                            currentLine.setLength(cursorCol)
                        }
                    }
                    "1" -> {
                        for (c in 0 until cursorCol.coerceAtMost(currentLine.length)) {
                            currentLine.setCharAt(c, ' ')
                        }
                    }
                    "2" -> {
                        currentLine.setLength(0)
                        cursorCol = 0
                    }
                }
            }
            'J' -> {
                // Clear display
                if (param == "2" || param == "3") {
                    lines.clear()
                    currentLine.setLength(0)
                    cursorCol = 0
                }
            }
            'C' -> {
                // Move cursor right
                val count = param.toIntOrNull() ?: 1
                cursorCol += count
            }
            'D' -> {
                // Move cursor left
                val count = param.toIntOrNull() ?: 1
                cursorCol = (cursorCol - count).coerceAtLeast(0)
            }
        }
    }

    private fun writeChar(ch: Char) {
        if (cursorCol < currentLine.length) {
            currentLine.setCharAt(cursorCol, ch)
        } else {
            while (currentLine.length < cursorCol) {
                currentLine.append(' ')
            }
            currentLine.append(ch)
        }
        cursorCol++
    }

    private fun commitCurrentLine() {
        lines.addLast(currentLine.toString())
        currentLine.setLength(0)
        cursorCol = 0
        while (lines.size > maxLines) {
            lines.removeFirst()
        }
    }

    @Synchronized
    fun appendLine(line: String) {
        append(line)
        commitCurrentLine()
    }

    @Synchronized
    fun clear() {
        lines.clear()
        currentLine.setLength(0)
        cursorCol = 0
    }

    @Synchronized
    fun render(): String {
        val sb = StringBuilder()
        for (l in lines) {
            sb.append(l).append('\n')
        }
        if (currentLine.isNotEmpty()) {
            sb.append(currentLine)
        }
        return sb.toString()
    }

    @Synchronized
    fun getCurrentLine(): String = currentLine.toString()

    @Synchronized
    fun getLineCount(): Int = lines.size + (if (currentLine.isNotEmpty()) 1 else 0)
}

