package com.avscode.runtime

import android.content.Context
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.RuntimeState
import com.avscode.core.runCatchingResult
import com.avscode.rootfs.RootfsInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * PRoot-based Linux runtime implementation.
 * 
 * This class integrates with the LinuxDroid PRoot implementation to provide
 * a working Linux userspace on Android.
 */
class PRootRuntime(
    private val context: Context,
    private val rootfsInstaller: RootfsInstaller
) : LinuxRuntime {
    
    companion object {
        private const val TAG = "PRootRuntime"
        
        init {
            System.loadLibrary("avscode-runtime")
        }
    }
    
    private val _state = MutableStateFlow(RuntimeState.NOT_INSTALLED)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()
    
    private var currentPid: Int? = null
    private var startTime: Long? = null
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Native methods for PRoot integration.
     */
    private external fun nativeInit(rootfsPath: String, nativeLibDir: String): Int
    private external fun nativeStartProcess(command: String, args: Array<String>?): Int
    private external fun nativeStopProcess(pid: Int): Int
    private external fun nativeExecute(command: String, outputCallback: Any?): Int
    private external fun nativeCheckAvailability(): Boolean
    private external fun nativeCleanup()
    private external fun nativeGetCurrentPid(): Int
    private external fun nativeIsInitialized(): Boolean
    
    override fun isInstalled(): Boolean {
        return rootfsInstaller.isInstalled()
    }
    
    override suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting rootfs installation")
        _state.value = RuntimeState.INSTALLING
        
        runCatchingResult {
            rootfsInstaller.install().getOrNull() ?: throw RuntimeException("Installation failed")
            
            // Verify installation
            if (!rootfsInstaller.isInstalled()) {
                throw IllegalStateException("Installation failed - rootfs not found")
            }
            
            _state.value = RuntimeState.READY
            AvsLogger.i(TAG, "Rootfs installation completed")
        }
    }
    
    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting Linux runtime")
        
        if (_state.value == RuntimeState.RUNNING) {
            AvsLogger.w(TAG, "Runtime already running")
            return@withContext Result.Success(Unit)
        }
        
        _state.value = RuntimeState.STARTING
        
        runCatchingResult {
            // Check installation
            if (!rootfsInstaller.isInstalled()) {
                AvsLogger.i(TAG, "Rootfs not installed, installing first")
                install().getOrNull() ?: throw RuntimeException("Installation failed")
            }
            
            val rootfsPath = rootfsInstaller.getRootfsPath()
            
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            AvsLogger.d(TAG, "Initializing PRoot with path: $rootfsPath, native lib dir: $nativeLibDir")
            val initResult = nativeInit(rootfsPath, nativeLibDir)
            if (initResult != 0) {
                throw RuntimeException("Failed to initialize PRoot: error code $initResult")
            }
            
            // Start a shell process to keep the runtime alive
            currentPid = nativeStartProcess("/bin/sh", null)
            if (currentPid == null || currentPid!! < 0) {
                throw RuntimeException("Failed to start PRoot process")
            }
            
            startTime = System.currentTimeMillis()
            _state.value = RuntimeState.RUNNING
            
            AvsLogger.i(TAG, "Linux runtime started with PID: $currentPid")
        }
    }
    
    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping Linux runtime")
        
        if (_state.value != RuntimeState.RUNNING && _state.value != RuntimeState.STARTING) {
            AvsLogger.w(TAG, "Runtime not running")
            _state.value = RuntimeState.READY
            return@withContext Result.Success(Unit)
        }
        
        _state.value = RuntimeState.STOPPING
        
        runCatchingResult {
            currentPid?.let { pid ->
                nativeStopProcess(pid)
                AvsLogger.d(TAG, "Stopped process $pid")
            }
            
            nativeCleanup()
            
            currentPid = null
            startTime = null
            _state.value = RuntimeState.READY
            
            AvsLogger.i(TAG, "Linux runtime stopped")
        }
    }
    
    override suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatchingResult {
            AvsLogger.d(TAG, "Executing command: $command")
            
            // Ensure runtime is running
            if (_state.value != RuntimeState.RUNNING) {
                start().getOrNull() ?: throw IllegalStateException("Runtime not running")
            }
            
            // Execute command and capture output
            val result = StringBuilder()
            val exitCode = nativeExecute(command, object : Any() {
                @Suppress("unused")
                fun onOutput(line: String) {
                    result.appendLine(line)
                }
            })
            
            if (exitCode < 0) {
                throw RuntimeException("Command execution failed with code: $exitCode")
            }
            
            result.toString()
        }
    }
    
    override suspend fun executeStreaming(
        command: String,
        onOutput: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingResult {
            AvsLogger.d(TAG, "Executing streaming command: $command")
            
            if (_state.value != RuntimeState.RUNNING) {
                start().getOrNull() ?: throw IllegalStateException("Runtime not running")
            }
            
            val exitCode = nativeExecute(command, object : Any() {
                @Suppress("unused")
                fun onOutput(line: String) {
                    onOutput(line)
                }
            })
            
            exitCode
        }
    }
    
    override fun getDiagnostics(): RuntimeDiagnostics {
        return RuntimeDiagnostics(
            state = _state.value,
            isInstalled = isInstalled(),
            pid = currentPid,
            uptimeMs = startTime?.let { System.currentTimeMillis() - it },
            lastError = null // TODO: Track errors
        )
    }
    
    /**
     * Check if PRoot is available on this device.
     */
    fun checkAvailability(): Boolean {
        return nativeCheckAvailability()
    }
    
    /**
     * Cleanup resources when the runtime is destroyed.
     */
    fun destroy() {
        scope.cancel()
        if (_state.value == RuntimeState.RUNNING) {
            runBlocking { stop() }
        }
        nativeCleanup()
    }
}
