package com.avscode.terminal

import com.avscode.core.AvsLogger
import com.avscode.runtime.LinuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Isolated Linux CLI Terminal Session.
 *
 * Runs an independent Linux shell session decoupled from the VS Code Server supervisor and its logs.
 * Tracks current working directory, command history, and in-place carriage-return terminal buffer.
 */
class TerminalSession(
    private val linuxRuntime: LinuxRuntime,
    val buffer: TerminalBuffer = TerminalBuffer()
) {

    companion object {
        private const val TAG = "TerminalSession"
        const val DEFAULT_CWD = "/home/user"
    }

    var currentWorkingDir: String = DEFAULT_CWD
        private set

    val history = mutableListOf<String>()
    var historyIndex: Int = -1

    var isRunningCommand: Boolean = false
        private set

    init {
        initSession()
    }

    fun initSession() {
        buffer.clear()
        buffer.appendLine("AVSCode Linux Terminal")
        buffer.appendLine("Ubuntu 26.04 LTS (ARM64) • PRoot Userspace")
        buffer.appendLine("Dedicated shell session initialized. Ready for commands.")
        buffer.appendLine("")
        buffer.append(getPrompt())
    }

    fun getPrompt(): String {
        val displayPath = formatPath(currentWorkingDir)
        return "guest:$displayPath$ "
    }

    private fun formatPath(path: String): String {
        return when {
            path == "/home/user" -> "~"
            path.startsWith("/home/user/") -> "~" + path.removePrefix("/home/user")
            path == "/root" -> "~"
            else -> path
        }
    }

    /**
     * Navigates command history.
     * @param up true for older command, false for newer command
     * @return the command text or null if at bounds
     */
    fun navigateHistory(up: Boolean): String? {
        if (history.isEmpty()) return null

        if (up) {
            if (historyIndex == -1) {
                historyIndex = history.size - 1
            } else if (historyIndex > 0) {
                historyIndex--
            }
        } else {
            if (historyIndex != -1) {
                if (historyIndex < history.size - 1) {
                    historyIndex++
                } else {
                    historyIndex = -1
                    return ""
                }
            }
        }

        return if (historyIndex in 0 until history.size) history[historyIndex] else null
    }

    /**
     * Executes a user command inside the isolated Linux session.
     */
    suspend fun executeCommand(rawCommand: String, onBufferUpdated: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) {
            return@withContext 0
        }

        // Add to history
        if (history.isEmpty() || history.last() != cmd) {
            history.add(cmd)
        }
        historyIndex = -1

        // Handle internal clear command
        if (cmd == "clear") {
            buffer.clear()
            buffer.append(getPrompt())
            onBufferUpdated(buffer.render())
            return@withContext 0
        }

        isRunningCommand = true
        buffer.append(cmd)
        buffer.append("\n")
        onBufferUpdated(buffer.render())

        // Wrap execution so cwd changes are tracked:
        // Execute the command, followed by a marker printing the new working directory
        val marker = "__AVSCODE_CWD__"
        val wrappedCommand = buildString {
            append(cmd)
            append("; __RET=$?; printf '\\n%s:%s\\n' \"$marker\" \"\$PWD\"; exit \$__RET")
        }

        var detectedCwd: String? = null

        val result = try {
            linuxRuntime.executeStreaming(wrappedCommand, currentWorkingDir) { chunk ->
                // Check if chunk contains the cwd marker
                if (chunk.contains(marker)) {
                    val lines = chunk.split("\n")
                    for (line in lines) {
                        if (line.startsWith("$marker:")) {
                            detectedCwd = line.removePrefix("$marker:").trim()
                        } else if (line.isNotEmpty()) {
                            buffer.append(line + "\n")
                        }
                    }
                } else {
                    buffer.append(chunk)
                }
                onBufferUpdated(buffer.render())
            }
        } catch (e: Exception) {
            AvsLogger.e(TAG, "Error executing command: ${e.message}", e)
            buffer.appendLine("Error: ${e.message}")
            onBufferUpdated(buffer.render())
            com.avscode.core.Result.Failure(e)
        } finally {
            isRunningCommand = false
        }

        // Update working directory if changed
        if (!detectedCwd.isNullOrEmpty() && detectedCwd != currentWorkingDir) {
            currentWorkingDir = detectedCwd!!
            AvsLogger.d(TAG, "Working directory changed to: $currentWorkingDir")
        }

        // Print final prompt
        buffer.append(getPrompt())
        onBufferUpdated(buffer.render())

        result.getOrDefault(-1)
    }
}

