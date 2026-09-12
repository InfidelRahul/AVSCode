package com.avscode.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central logger for AVscode components.
 * Provides structured logging with history, export capabilities, and log levels.
 */
object AvsLogger {
    private const val TAG = "AVscode"
    
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()
    
    // Separate flows for filtering by level
    private val _errors = MutableStateFlow<List<LogEntry>>(emptyList())
    val errors: StateFlow<List<LogEntry>> = _errors.asStateFlow()
    
    private val _warnings = MutableStateFlow<List<LogEntry>>(emptyList())
    val warnings: StateFlow<List<LogEntry>> = _warnings.asStateFlow()
    
    private val maxLogs = 1000
    private val maxErrors = 200
    private val logHistory = mutableListOf<LogEntry>()
    private val errorHistory = mutableListOf<LogEntry>()
    
    // Callback for real-time log listeners
    var onNewLog: ((LogEntry) -> Unit)? = null
    
    data class LogEntry(
        val level: Level,
        val tag: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val throwable: Throwable? = null,
        val threadName: String = Thread.currentThread().name
    ) {
        enum class Level { VERBOSE, DEBUG, INFO, WARN, ERROR }
        
        fun toFormattedString(): String {
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp))
            val base = "[$time] [$level] [$threadName] $tag: $message"
            return if (throwable != null) {
                "$base\n${throwable.stackTraceToString()}"
            } else {
                base
            }
        }
    }
    
    /**
     * Log a message at the specified level.
     */
    fun log(level: LogEntry.Level, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(level, tag, message, System.currentTimeMillis(), throwable)
        
        // Add to main history
        synchronized(logHistory) {
            logHistory.add(entry)
            while (logHistory.size > maxLogs) {
                logHistory.removeAt(0)
            }
            _logs.value = logHistory.toList()
        }
        
        // Track errors and warnings separately
        when (level) {
            LogEntry.Level.ERROR -> {
                synchronized(errorHistory) {
                    errorHistory.add(entry)
                    while (errorHistory.size > maxErrors) {
                        errorHistory.removeAt(0)
                    }
                    _errors.value = errorHistory.toList()
                }
                try {
                    Log.e(tag, message, throwable)
                } catch (_: Throwable) {}
            }
            LogEntry.Level.WARN -> {
                _warnings.value = (_warnings.value + entry).takeLast(maxErrors)
                try {
                    Log.w(tag, message)
                } catch (_: Throwable) {}
            }
            LogEntry.Level.INFO -> try { Log.i(tag, message) } catch (_: Throwable) {}
            LogEntry.Level.DEBUG -> try { Log.d(tag, message) } catch (_: Throwable) {}
            LogEntry.Level.VERBOSE -> try { Log.v(tag, message) } catch (_: Throwable) {}
        }
        
        // Notify callback
        onNewLog?.invoke(entry)
    }
    
    // Convenience methods
    fun v(tag: String, message: String) = log(LogEntry.Level.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogEntry.Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogEntry.Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogEntry.Level.WARN, tag, message)
    fun e(tag: String, message: String, throwable: Throwable? = null) = 
        log(LogEntry.Level.ERROR, tag, message, throwable)
    
    /**
     * Log an exception with context message.
     */
    fun exception(tag: String, message: String, throwable: Throwable) {
        e(tag, "$message: ${throwable.javaClass.simpleName} - ${throwable.message}", throwable)
    }
    
    /**
     * Log a block execution with timing.
     */
    inline fun <T> timed(tag: String, operation: String, block: () -> T): T {
        d(tag, "Starting: $operation")
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            val duration = System.currentTimeMillis() - start
            d(tag, "Completed: $operation in ${duration}ms")
            result
        } catch (e: Throwable) {
            val duration = System.currentTimeMillis() - start
            e(tag, "Failed: $operation after ${duration}ms", e)
            throw e
        }
    }
    
    /**
     * Get logs filtered by level.
     */
    fun getLogsByLevel(level: LogEntry.Level): List<LogEntry> {
        return logHistory.filter { it.level == level }
    }
    
    /**
     * Get logs from a specific time range.
     */
    fun getLogsSince(timestamp: Long): List<LogEntry> {
        return logHistory.filter { it.timestamp >= timestamp }
    }
    
    /**
     * Get recent logs (last N entries).
     */
    fun getRecentLogs(count: Int = 50): List<LogEntry> {
        return logHistory.takeLast(count)
    }
    
    /**
     * Export all logs to a formatted string.
     */
    fun exportLogs(includeVerbose: Boolean = false): String {
        val sb = StringBuilder()
        sb.appendLine("=== AVscode Log Export ===")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total entries: ${logHistory.size}")
        sb.appendLine()
        
        val filteredLogs = if (includeVerbose) {
            logHistory
        } else {
            logHistory.filter { it.level != LogEntry.Level.VERBOSE }
        }
        
        filteredLogs.forEach { entry ->
            sb.appendLine(entry.toFormattedString())
        }
        
        return sb.toString()
    }
    
    /**
     * Export only errors to a string.
     */
    fun exportErrors(): String {
        val sb = StringBuilder()
        sb.appendLine("=== AVscode Error Report ===")
        sb.appendLine("Generated: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        sb.appendLine("Total errors: ${errorHistory.size}")
        sb.appendLine()
        
        errorHistory.forEach { entry ->
            sb.appendLine(entry.toFormattedString())
        }
        
        return sb.toString()
    }
    
    /**
     * Clear all logs.
     */
    fun clear() {
        synchronized(logHistory) {
            logHistory.clear()
            errorHistory.clear()
            _logs.value = emptyList()
            _errors.value = emptyList()
            _warnings.value = emptyList()
        }
    }
    
    /**
     * Get log statistics.
     */
    fun getStats(): LogStats {
        return LogStats(
            total = logHistory.size,
            verbose = logHistory.count { it.level == LogEntry.Level.VERBOSE },
            debug = logHistory.count { it.level == LogEntry.Level.DEBUG },
            info = logHistory.count { it.level == LogEntry.Level.INFO },
            warn = logHistory.count { it.level == LogEntry.Level.WARN },
            error = errorHistory.size,
            oldestLog = logHistory.firstOrNull()?.timestamp,
            newestLog = logHistory.lastOrNull()?.timestamp
        )
    }
}

/**
 * Statistics about logged messages.
 */
data class LogStats(
    val total: Int,
    val verbose: Int,
    val debug: Int,
    val info: Int,
    val warn: Int,
    val error: Int,
    val oldestLog: Long?,
    val newestLog: Long?
) {
    fun summary(): String {
        return "Total: $total | V:$verbose D:$debug I:$info W:$warn E:$error"
    }
}
