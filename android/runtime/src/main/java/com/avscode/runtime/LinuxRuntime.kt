package com.avscode.runtime

import com.avscode.core.Result
import com.avscode.core.RuntimeState
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for controlling the Linux runtime environment.
 */
interface LinuxRuntime {
    
    /**
     * Current state of the runtime.
     */
    val state: StateFlow<RuntimeState>
    
    /**
     * Check if the rootfs is installed.
     */
    fun isInstalled(): Boolean
    
    /**
     * Install the Ubuntu rootfs.
     */
    suspend fun install(): Result<Unit>
    
    /**
     * Start the Linux runtime.
     */
    suspend fun start(): Result<Unit>
    
    /**
     * Stop the Linux runtime.
     */
    suspend fun stop(): Result<Unit>
    
    /**
     * Restart the Linux runtime.
     */
    suspend fun restart(): Result<Unit> = run {
        stop()
        start()
    }
    
    /**
     * Execute a command in the Linux environment.
     */
    suspend fun execute(command: String): Result<String>
    
    /**
     * Execute a command and stream output.
     */
    suspend fun executeStreaming(
        command: String,
        onOutput: (String) -> Unit
    ): Result<Int>
    
    /**
     * Get diagnostics about the runtime.
     */
    fun getDiagnostics(): RuntimeDiagnostics
}

/**
 * Diagnostics information about the runtime.
 */
data class RuntimeDiagnostics(
    val state: RuntimeState,
    val isInstalled: Boolean,
    val pid: Int? = null,
    val uptimeMs: Long? = null,
    val memoryUsageBytes: Long? = null,
    val lastError: String? = null
)
