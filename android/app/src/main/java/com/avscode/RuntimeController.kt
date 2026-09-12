package com.avscode

import android.content.Context
import com.avscode.core.*
import com.avscode.rootfs.RootfsInstaller
import com.avscode.runtime.LinuxRuntimeService
import com.avscode.runtime.PRootRuntime
import com.avscode.vscode.VsCodeServerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Authoritative single runtime controller for AVscode.
 *
 * Implements the 12-state runtime pipeline:
 * NEEDS_STORAGE_ACCESS -> DOWNLOADING_ROOTFS -> EXTRACTING_ROOTFS -> ROOTFS_READY ->
 * STARTING_LINUX -> VERIFYING_LINUX -> LINUX_READY -> INSTALLING_PACKAGES ->
 * INSTALLING_VSCODE -> STARTING_VSCODE -> READY
 *
 * Provides resilient CLI access: If VS Code server fails, Linux userspace remains
 * active for terminal troubleshooting.
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

    val paths = AppPaths.getInstance(context)
    val rootfsInstaller = RootfsInstaller(context)
    val linuxRuntime = PRootRuntime(context, rootfsInstaller)
    val vscodeServer = VsCodeServerManager(context, linuxRuntime)

    private val _appState = MutableStateFlow<AppState>(AppState.NeedsStorageAccess)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _terminalLogs = MutableSharedFlow<String>(replay = 500)
    val terminalLogs: SharedFlow<String> = _terminalLogs.asSharedFlow()

    init {
        updateInitialState()
    }

    fun updateInitialState() {
        if (!StoragePermissionHelper.isStorageConfigured(context)) {
            _appState.value = AppState.NeedsStorageAccess
        } else if (!rootfsInstaller.isInstalled()) {
            _appState.value = AppState.NotInstalled
        } else if (vscodeServer.isResponding()) {
            _appState.value = AppState.Ready(vscodeServer.getServerUrl())
        } else {
            _appState.value = AppState.RootfsReady
        }
    }

    private fun emitLog(line: String) {
        AvsLogger.d(TAG, line)
        scope.launch {
            _terminalLogs.emit(line)
        }
    }

    /**
     * Executes the complete runtime startup chain.
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

            // Step 0: Storage access check
            if (!StoragePermissionHelper.isStorageConfigured(context)) {
                _appState.value = AppState.NeedsStorageAccess
                emitLog("[Android] Storage access configuration required before continuing.")
                return@withContext Result.Failure(IllegalStateException("Storage access not configured"))
            }

            emitLog("[Android] Storage access verified.")

            // Step 1: Rootfs Download & Extraction (Android Host responsibility)
            if (!rootfsInstaller.isInstalled()) {
                emitLog("[Rootfs] Downloading and extracting Ubuntu 26.04 ARM64...")
                try {
                    rootfsInstaller.install { progress, status ->
                        if (progress < 0.50f) {
                            _appState.value = AppState.DownloadingRootfs(progress / 0.50f, status)
                        } else {
                            _appState.value = AppState.ExtractingRootfs((progress - 0.50f) / 0.50f, status)
                        }
                        emitLog("[Rootfs] $status")
                    }.getOrThrow()
                    _appState.value = AppState.RootfsReady
                    emitLog("[Rootfs] Rootfs verified and ready.")
                } catch (e: Throwable) {
                    _appState.value = AppState.RootfsFailed("Failed to install rootfs: ${e.message}", e)
                    emitLog("[Rootfs] ERROR: ${e.message}")
                    return@withContext Result.Failure(e)
                }
            } else {
                _appState.value = AppState.RootfsReady
                emitLog("[Rootfs] Existing Ubuntu rootfs verified.")
            }

            // Step 2: Start Linux PRoot Runtime
            _appState.value = AppState.StartingLinux
            emitLog("[Linux] Starting PRoot runtime...")
            try {
                linuxRuntime.start().getOrThrow()
            } catch (e: Throwable) {
                _appState.value = AppState.LinuxFailed("Failed to start PRoot: ${e.message}", e)
                emitLog("[Linux] ERROR: ${e.message}")
                return@withContext Result.Failure(e)
            }

            // Step 3: Verify Linux Userspace Diagnostic Probe
            _appState.value = AppState.VerifyingLinux
            emitLog("[Linux] Entering Ubuntu userspace and verifying diagnostics...")
            try {
                linuxRuntime.verifyGuestUserspace { line ->
                    emitLog(line)
                }.getOrThrow()
                _appState.value = AppState.LinuxReady
                emitLog("[Linux] Linux userspace ready. CLI is now accessible.")
            } catch (e: Throwable) {
                _appState.value = AppState.LinuxFailed("Linux userspace verification failed: ${e.message}", e)
                emitLog("[Linux] VERIFICATION FAILED: ${e.message}")
                return@withContext Result.Failure(e)
            }

            // Step 4: Linux Bootstrap (guest script: packages via apt)
            if (!paths.hostBootstrapMarker.exists()) {
                _appState.value = AppState.InstallingPackages("Configuring guest development tools...")
                emitLog("[Packages] Running Linux guest bootstrap script (/usr/local/lib/avscode/bootstrap.sh)...")
                try {
                    vscodeServer.ensureBootstrap { line ->
                        emitLog(line)
                    }.getOrThrow()
                    emitLog("[Packages] Development packages installed successfully.")
                } catch (e: Throwable) {
                    _appState.value = AppState.PackageInstallFailed("Package installation failed: ${e.message}", e)
                    emitLog("[Packages] ERROR: ${e.message}")
                    // Linux userspace remains running for CLI debugging
                    return@withContext Result.Failure(e)
                }
            } else {
                emitLog("[Packages] Development environment already bootstrapped.")
            }

            // Step 5: Install VS Code Server inside guest if needed
            if (!vscodeServer.isInstalled()) {
                _appState.value = AppState.InstallingVsCode(0f, "Installing VS Code Server (code-server)...")
                emitLog("[VS Code] Installing code-server inside Linux userspace...")
                try {
                    vscodeServer.install { progress, status ->
                        _appState.value = AppState.InstallingVsCode(progress, status)
                        emitLog(status)
                    }.getOrThrow()
                    emitLog("[VS Code] code-server installed and verified.")
                } catch (e: Throwable) {
                    _appState.value = AppState.VsCodeFailed("VS Code installation failed: ${e.message}", e)
                    emitLog("[VS Code] INSTALLATION FAILED: ${e.message}")
                    // Linux userspace remains running for CLI debugging
                    return@withContext Result.Failure(e)
                }
            } else {
                emitLog("[VS Code] code-server already installed.")
            }

            // Step 6: Start VS Code Server & await HTTP readiness
            _appState.value = AppState.StartingVsCode
            emitLog("[VS Code] Starting server process on 127.0.0.1:8080...")
            try {
                vscodeServer.start { line ->
                    emitLog(line)
                }.getOrThrow()
                val url = vscodeServer.getServerUrl()
                _appState.value = AppState.Ready(url)
                emitLog("[VS Code] Server ready at $url. Launching editor interface.")
                Result.Success(url)
            } catch (e: Throwable) {
                _appState.value = AppState.VsCodeFailed("VS Code failed to start: ${e.message}", e)
                emitLog("[VS Code] START FAILED: ${e.message}")
                // Linux userspace remains running for CLI debugging
                Result.Failure(e)
            }
        }
    }

    /**
     * Executes a command directly in the running Linux guest environment.
     * Useful for terminal diagnostics and CLI interactions.
     */
    suspend fun executeGuestCommand(command: String, onOutput: (String) -> Unit): Result<Int> {
        return linuxRuntime.executeStreaming(command, onOutput)
    }

    /**
     * Gracefully stops VS Code Server, Linux runtime, and foreground service.
     */
    suspend fun stopAll(): Result<Unit> = mutex.withLock {
        withContext(Dispatchers.IO) {
            _appState.value = AppState.Stopping
            emitLog("[Runtime] Stopping all services...")
            runCatchingResult {
                vscodeServer.stop()
                linuxRuntime.stop()
                LinuxRuntimeService.stop(context)
                updateInitialState()
                emitLog("[Runtime] All services stopped.")
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
