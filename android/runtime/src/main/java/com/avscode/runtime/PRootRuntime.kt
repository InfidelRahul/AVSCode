package com.avscode.runtime

import android.content.Context
import com.avscode.core.AppPaths
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
import java.io.RandomAccessFile

/**
 * Production PRoot runtime integrating with LinuxDroid PRoot and NativeSpawn.
 *
 * Provides userspace Linux execution, process group isolation,
 * environment configuration, and process management.
 */
class PRootRuntime(
    private val context: Context,
    private val rootfsInstaller: RootfsInstaller
) : LinuxRuntime {

    companion object {
        private const val TAG = "PRootRuntime"
    }

    private val paths = AppPaths.getInstance(context)
    private val _state = MutableStateFlow(RuntimeState.NOT_INSTALLED)
    override val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private var supervisorPid: Int? = null
    private var startTime: Long? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Resolved executable files
    private var cachedProotBin: File? = null
    private var cachedLoaderBin: File? = null

    init {
        updateState()
    }

    private fun updateState() {
        if (!rootfsInstaller.isInstalled()) {
            _state.value = RuntimeState.NOT_INSTALLED
        } else if (_state.value == RuntimeState.NOT_INSTALLED) {
            _state.value = RuntimeState.READY
        }
    }

    override fun isInstalled(): Boolean {
        return rootfsInstaller.isInstalled()
    }

    /**
     * Resolves the executable PRoot carrier binary, copying to app binary dir if needed.
     */
    fun getProotBinary(): File {
        cachedProotBin?.let { if (it.exists() && it.canExecute()) return it }

        val candidates = listOf(
            File(paths.nativeLibDir, "libproot.so"),
            File(paths.nativeBinDir, "proot")
        )

        for (candidate in candidates) {
            if (candidate.exists()) {
                candidate.setExecutable(true, false)
                if (candidate.canExecute()) {
                    cachedProotBin = candidate
                    return candidate
                }
            }
        }

        // Copy carrier to nativeBinDir if direct execution is blocked
        val libProot = File(paths.nativeLibDir, "libproot.so")
        val target = File(paths.nativeBinDir, "proot")
        if (libProot.exists()) {
            libProot.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            cachedProotBin = target
            return target
        }

        throw IllegalStateException("PRoot binary (libproot.so) not found in ${paths.nativeLibDir.absolutePath}")
    }

    /**
     * Resolves the freestanding static loader binary.
     */
    fun getLoaderBinary(): File {
        cachedLoaderBin?.let { if (it.exists() && it.canExecute()) return it }

        val candidates = listOf(
            File(paths.nativeLibDir, "libproot_loader.so"),
            File(paths.nativeLibDir, "libprootloader.so"),
            File(paths.nativeBinDir, "proot_loader")
        )

        for (candidate in candidates) {
            if (candidate.exists()) {
                candidate.setExecutable(true, false)
                if (candidate.canExecute()) {
                    cachedLoaderBin = candidate
                    return candidate
                }
            }
        }

        // Copy loader to nativeBinDir if needed
        val libLoader = candidates.firstOrNull { it.exists() }
        val target = File(paths.nativeBinDir, "proot_loader")
        if (libLoader != null && libLoader.exists()) {
            libLoader.copyTo(target, overwrite = true)
            target.setExecutable(true, false)
            cachedLoaderBin = target
            return target
        }

        throw IllegalStateException("PRoot loader (libproot_loader.so) not found in ${paths.nativeLibDir.absolutePath}")
    }

    /**
     * Builds standard PRoot CLI invocation arguments.
     */
    fun buildPRootArgs(guestCommand: String, workingDir: String = "/home/user"): List<String> {
        val proot = getProotBinary()
        val rootfsPath = paths.rootfsDir.absolutePath

        val args = mutableListOf(
            proot.absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfsPath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys"
        )

        // Bind standard host directories if they exist
        listOf("/system", "/apex", "/vendor", "/product").forEach { sysPath ->
            if (File(sysPath).exists()) {
                args.add("-b")
                args.add(sysPath)
            }
        }

        // Bind host cache tmp directory to /tmp
        val tmpDir = paths.prootTmpDir
        args.add("-b")
        args.add("${tmpDir.absolutePath}:/tmp")

        // Working directory inside rootfs
        args.add("-w")
        args.add(workingDir)

        // Guest shell and command
        args.add("/bin/bash")
        args.add("-c")
        args.add(guestCommand)

        return args
    }

    /**
     * Builds standard environment variables for PRoot execution.
     */
    fun buildEnvironment(homeDir: String = "/home/user"): Array<String> {
        val loader = getLoaderBinary()
        return arrayOf(
            "PROOT_LOADER=${loader.absolutePath}",
            "PROOT_TMP_DIR=${paths.prootTmpDir.absolutePath}",
            "LD_LIBRARY_PATH=${paths.nativeLibDir.absolutePath}",
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
            "PROOT_NO_SECCOMP=1",
            "HOME=$homeDir",
            "USER=user",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "DEBIAN_FRONTEND=noninteractive"
        )
    }

    override suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Initiating rootfs install from PRootRuntime")
        _state.value = RuntimeState.INSTALLING

        val result = rootfsInstaller.install()
        if (result.isSuccess) {
            _state.value = RuntimeState.READY
        } else {
            _state.value = RuntimeState.FAILED
        }
        result
    }

    override suspend fun start(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting Linux runtime supervisor")

        if (_state.value == RuntimeState.RUNNING && supervisorPid != null) {
            val check = NativeSpawn.waitFor(supervisorPid!!, true)
            if (check == -2) {
                AvsLogger.d(TAG, "Runtime already active (PID $supervisorPid)")
                return@withContext Result.Success(Unit)
            }
        }

        _state.value = RuntimeState.STARTING

        runCatchingResult {
            if (!rootfsInstaller.isInstalled()) {
                AvsLogger.i(TAG, "Rootfs not installed, installing now...")
                install().getOrThrow()
            }

            // Verify binaries exist
            getProotBinary()
            getLoaderBinary()

            // Verify a basic echo command executes inside PRoot
            val testResult = execute("echo 'AVSCode Linux Initialized'")
            if (testResult.isFailure) {
                throw RuntimeException("PRoot self-test failed: ${testResult.exceptionOrNull()?.message}")
            }
            AvsLogger.i(TAG, "PRoot self-test passed: ${testResult.getOrNull()?.trim()}")

            // Launch persistent background supervisor session
            val logFile = paths.runtimeLogFile
            val args = buildPRootArgs("while true; do sleep 3600; done", "/root")
            val env = buildEnvironment("/root")

            val spawnResult = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                logFile.absolutePath
            ) ?: throw RuntimeException("Failed to spawn PRoot supervisor process")

            supervisorPid = spawnResult[0]
            startTime = System.currentTimeMillis()
            _state.value = RuntimeState.RUNNING

            AvsLogger.i(TAG, "Linux runtime running with supervisor PID $supervisorPid")
        }
    }

    override suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping Linux runtime")

        _state.value = RuntimeState.STOPPING

        runCatchingResult {
            supervisorPid?.let { pid ->
                AvsLogger.d(TAG, "Terminating process group for PID $pid")
                NativeSpawn.kill(pid, 15) // SIGTERM
                delay(200)
                NativeSpawn.kill(pid, 9)  // SIGKILL
                NativeSpawn.waitFor(pid, true)
            }

            supervisorPid = null
            startTime = null
            _state.value = if (isInstalled()) RuntimeState.READY else RuntimeState.NOT_INSTALLED
            AvsLogger.i(TAG, "Linux runtime stopped")
        }
    }

    override suspend fun execute(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatchingResult {
            if (!isInstalled()) {
                throw IllegalStateException("Cannot execute command: Rootfs is not installed")
            }

            val outputFile = File(paths.cacheDir, "exec_${System.currentTimeMillis()}_${(0..9999).random()}.log")
            val args = buildPRootArgs(command, "/home/user")
            val env = buildEnvironment("/home/user")

            try {
                val spawnResult = NativeSpawn.spawn(
                    args.toTypedArray(),
                    env,
                    paths.rootfsDir.absolutePath,
                    outputFile.absolutePath
                ) ?: throw RuntimeException("NativeSpawn failed to spawn process for command: $command")

                val pid = spawnResult[0]
                val exitCode = NativeSpawn.waitFor(pid, false)
                val output = if (outputFile.exists()) outputFile.readText() else ""

                if (exitCode != 0) {
                    throw RuntimeException("Command '$command' failed with code $exitCode:\n$output")
                }

                output
            } finally {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            }
        }
    }

    override suspend fun executeStreaming(
        command: String,
        onOutput: (String) -> Unit
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatchingResult {
            if (!isInstalled()) {
                throw IllegalStateException("Cannot execute command: Rootfs is not installed")
            }

            val outputFile = File(paths.cacheDir, "stream_${System.currentTimeMillis()}_${(0..9999).random()}.log")
            val args = buildPRootArgs(command, "/home/user")
            val env = buildEnvironment("/home/user")

            try {
                val spawnResult = NativeSpawn.spawn(
                    args.toTypedArray(),
                    env,
                    paths.rootfsDir.absolutePath,
                    outputFile.absolutePath
                ) ?: throw RuntimeException("NativeSpawn failed for streaming command: $command")

                val pid = spawnResult[0]
                var lastPos = 0L

                while (true) {
                    val status = NativeSpawn.waitFor(pid, true)
                    if (outputFile.exists() && outputFile.length() > lastPos) {
                        RandomAccessFile(outputFile, "r").use { raf ->
                            raf.seek(lastPos)
                            var line = raf.readLine()
                            while (line != null) {
                                onOutput(line)
                                line = raf.readLine()
                            }
                            lastPos = raf.filePointer
                        }
                    }

                    if (status != -2) { // Process finished
                        return@runCatchingResult status
                    }
                    delay(150)
                }

                @Suppress("UNREACHABLE_CODE")
                0
            } finally {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
            }
        }
    }

    override fun getDiagnostics(): RuntimeDiagnostics {
        return RuntimeDiagnostics(
            state = _state.value,
            isInstalled = isInstalled(),
            pid = supervisorPid,
            uptimeMs = startTime?.let { System.currentTimeMillis() - it },
            memoryUsageBytes = null,
            lastError = null
        )
    }

    fun destroy() {
        scope.cancel()
        supervisorPid?.let { pid ->
            NativeSpawn.kill(pid, 9)
        }
        supervisorPid = null
    }
}
