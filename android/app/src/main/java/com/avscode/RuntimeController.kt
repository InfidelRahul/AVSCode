package com.avscode

import android.content.Context
import com.avscode.core.*
import com.avscode.rootfs.RootfsInstaller
import com.avscode.runtime.LinuxRuntimeService
import com.avscode.runtime.PRootRuntime
import com.avscode.vscode.VsCodeServerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Authoritative single runtime controller for AVscode.
 *
 * Eliminates duplicate runtime ownership by coordinating:
 * 1. Rootfs installation
 * 2. Linux PRoot runtime
 * 3. Linux development environment bootstrap
 * 4. VS Code Server (code-server)
 * 5. Foreground Service lifecycle
 */
class RuntimeController private constructor(private val context: Context) {

    companion object {
        private const val TAG = "RuntimeController"

        @Volatile
        private var instance: RuntimeController? = null

        fun getInstance(context: Context): RuntimeController {
            return instance ?: synchronized(this) {
                instance ?: RuntimeController(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    val rootfsInstaller = RootfsInstaller(context)
    val linuxRuntime = PRootRuntime(context, rootfsInstaller)
    val vscodeServer = VsCodeServerManager(context, linuxRuntime)

    private val _appState = MutableStateFlow<AppState>(AppState.NotInstalled)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    init {
        updateInitialState()
    }

    private fun updateInitialState() {
        if (!rootfsInstaller.isInstalled()) {
            _appState.value = AppState.NotInstalled
        } else if (vscodeServer.isResponding()) {
            _appState.value = AppState.Ready(vscodeServer.getServerUrl())
        }
    }

    /**
     * Executes the complete runtime startup chain:
     * Rootfs -> PRoot -> Bootstrap -> VS Code Server.
     */
    suspend fun startAll(forceRestart: Boolean = false): Result<String> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val currentState = _appState.value
            if (!forceRestart && currentState is AppState.Ready && vscodeServer.isResponding()) {
                AvsLogger.i(TAG, "Runtime already ready at ${currentState.url}")
                return@withContext Result.Success(currentState.url)
            }

            // Ensure foreground service is running to avoid process killing
            try {
                LinuxRuntimeService.start(context)
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to start foreground service: ${e.message}")
            }

            try {
                // Step 1: Install rootfs if needed
                if (!rootfsInstaller.isInstalled()) {
                    _appState.value = AppState.InstallingRootfs(0.0f, "Preparing Ubuntu filesystem...")
                    rootfsInstaller.install { progress, status ->
                        _appState.value = AppState.InstallingRootfs(progress, status)
                    }.getOrThrow()
                }

                // Step 2: Start Linux PRoot runtime
                _appState.value = AppState.StartingLinux
                linuxRuntime.start().getOrThrow()

                // Step 3: Bootstrap Linux environment (one-time setup)
                _appState.value = AppState.Bootstrapping("Configuring Linux development tools...")
                vscodeServer.ensureBootstrap { status ->
                    _appState.value = AppState.Bootstrapping(status)
                }.getOrThrow()

                // Step 4: Install code-server if needed
                if (!vscodeServer.isInstalled()) {
                    _appState.value = AppState.Bootstrapping("Installing VS Code Server...")
                    vscodeServer.install { progress, status ->
                        _appState.value = AppState.InstallingRootfs(progress, status)
                    }.getOrThrow()
                }

                // Step 5: Start code-server
                _appState.value = AppState.StartingVsCode
                vscodeServer.start().getOrThrow()

                // Step 6: Ready!
                val url = vscodeServer.getServerUrl()
                _appState.value = AppState.Ready(url)
                AvsLogger.i(TAG, "Entire runtime chain started successfully. Ready at: $url")
                Result.Success(url)

            } catch (e: Throwable) {
                AvsLogger.e(TAG, "Runtime chain failed to start", e)
                _appState.value = AppState.Failed(e.message ?: "Unknown startup failure", e)
                Result.Failure(e)
            }
        }
    }

    /**
     * Gracefully stops VS Code Server, Linux runtime, and foreground service.
     */
    suspend fun stopAll(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            _appState.value = AppState.Stopping
            runCatchingResult {
                vscodeServer.stop()
                linuxRuntime.stop()
                LinuxRuntimeService.stop(context)
                _appState.value = if (rootfsInstaller.isInstalled()) AppState.NotInstalled else AppState.NotInstalled
                AvsLogger.i(TAG, "All runtime components stopped cleanly")
            }
        }
    }

    /**
     * Restarts the entire runtime.
     */
    suspend fun restartAll(): Result<String> {
        stopAll()
        return startAll(forceRestart = true)
    }
}

