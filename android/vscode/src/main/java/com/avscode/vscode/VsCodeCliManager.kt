package com.avscode.vscode

import android.content.Context
import com.avscode.core.AppPaths
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.runCatchingResult
import com.avscode.runtime.NativeSpawn
import com.avscode.runtime.PRootRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Microsoft Visual Studio Code CLI and Local VS Code Server Manager.
 *
 * Implements a fully local, offline-capable VS Code architecture:
 * - Official Microsoft ARM64 standalone CLI archive is downloaded on the Android host.
 * - Archive extraction and binary placement are performed strictly inside Linux userspace (`/usr/local/bin/code`).
 * - CLI presence is verified via `command -v code && code --version` inside PRoot.
 * - Local VS Code Server (`code serve-web`) is supervised inside Ubuntu userspace on a dynamic local port (`127.0.0.1:<port>`).
 * - Serves directly over local loopback HTTP to the Android WebView with 0 network/cloud dependencies.
 */
class VsCodeCliManager(
    private val context: Context,
    private val linuxRuntime: PRootRuntime
) {
    companion object {
        private const val TAG = "VsCodeCliManager"

        // Official Microsoft VS Code CLI standalone ARM64 download endpoints
        const val CLI_DOWNLOAD_URL = "https://code.visualstudio.com/sha/download?build=stable&os=cli-alpine-arm64"
        const val CLI_FALLBACK_URL = "https://update.code.visualstudio.com/latest/cli-alpine-arm64/stable"

        // Guest paths inside rootfs
        const val GUEST_BIN_PATH = "/usr/local/bin/code"
        const val GUEST_DATA_DIR = "/home/user/.vscode-cli"
        const val GUEST_PROJECTS_DIR = "/home/user/projects"

        // Default local host binding & persistent port (preserves origin localStorage/cookies)
        const val DEFAULT_SERVER_HOST = "127.0.0.1"
        const val DEFAULT_PREFERRED_PORT = 33000

        // Timeouts & intervals
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val PROBE_INTERVAL_MS = 500L

        /**
         * Finds an available TCP port on local loopback.
         * Prefers [preferredPort] to preserve web origin storage (cookies, localStorage, SecretStorage)
         * across restarts, only falling back to an ephemeral port if the preferred port is occupied.
         */
        fun findAvailablePort(preferredPort: Int = DEFAULT_PREFERRED_PORT): Int {
            return try {
                ServerSocket(preferredPort).use { it.localPort }
            } catch (e: Exception) {
                ServerSocket(0).use { it.localPort }
            }
        }

        /**
         * Builds the command to execute `code serve-web` locally inside PRoot userspace.
         */
        fun buildServerCommand(
            serverPort: Int,
            host: String = DEFAULT_SERVER_HOST,
            cliBinPath: String = GUEST_BIN_PATH,
            cliDataDir: String = GUEST_DATA_DIR
        ): String {
            return "$cliBinPath serve-web " +
                    "--host $host " +
                    "--port $serverPort " +
                    "--without-connection-token " +
                    "--accept-server-license-terms " +
                    "--cli-data-dir $cliDataDir " +
                    "--server-data-dir $cliDataDir/data"
        }

        /**
         * Checks if the local loopback server responds to HTTP requests.
         */
        fun checkHttpReachable(serverPort: Int, host: String = DEFAULT_SERVER_HOST): Boolean {
            return try {
                val url = URL("http://$host:$serverPort")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                conn.disconnect()
                code in 200..399
            } catch (e: Exception) {
                false
            }
        }
    }

    private val paths = AppPaths.getInstance(context)
    private var serverPid: Int? = null
    private var serverPort: Int? = null
    private var activeServerUrl: String? = null

    /**
     * Check if Microsoft VS Code CLI binary is present in guest rootfs.
     */
    fun isInstalled(): Boolean {
        val bin = File(paths.rootfsDir, "usr/local/bin/code")
        return bin.exists()
    }

    /**
     * Check if local server process is currently running.
     */
    fun isServerRunning(): Boolean {
        val pid = serverPid ?: return false
        val status = NativeSpawn.waitFor(pid, true)
        return status == -2
    }

    /**
     * Get the active local server URL (e.g. http://127.0.0.1:port/?folder=/home/user/projects).
     */
    fun getServerUrl(): String? = activeServerUrl

    /**
     * Get the active local listening port.
     */
    fun getServerPort(): Int? = serverPort

    /**
     * Installs the official Microsoft VS Code CLI inside Ubuntu userspace (/usr/local/bin/code).
     * Download is performed on Android host; extraction and execution are performed strictly inside Linux guest.
     */
    suspend fun install(progressCallback: ((Float, String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting Microsoft VS Code CLI installation")

        runCatchingResult {
            if (isInstalled()) {
                val verify = verifyCliInstallation()
                if (verify.isSuccess) {
                    AvsLogger.i(TAG, "VS Code CLI already installed: ${verify.getOrNull()?.trim()}")
                    progressCallback?.invoke(1.0f, "Microsoft VS Code CLI ready")
                    return@runCatchingResult Unit
                }
                AvsLogger.w(TAG, "Existing CLI failed verification, reinstalling: ${verify.exceptionOrNull()?.message}")
            }

            // Step 1: Download Microsoft VS Code CLI tarball on Android host
            val downloadArchive = File(paths.cacheDir, "vscode_cli_alpine_arm64.tar.gz")
            if (!downloadArchive.exists() || downloadArchive.length() < 5 * 1024 * 1024) {
                progressCallback?.invoke(0.1f, "Downloading Microsoft VS Code CLI (ARM64)...")
                downloadCliArchive(downloadArchive) { p ->
                    progressCallback?.invoke(0.1f + p * 0.45f, "Downloading VS Code CLI (${(p * 100).toInt()}%)...")
                }
            }

            // Step 2: Copy archive to guest /tmp for extraction inside guest
            progressCallback?.invoke(0.6f, "Staging CLI archive in Linux /tmp...")
            val guestTmpDir = paths.hostGuestTmpDir
            if (!guestTmpDir.exists()) {
                guestTmpDir.mkdirs()
            }
            val guestTmpArchive = File(guestTmpDir, "vscode_cli.tar.gz")
            downloadArchive.copyTo(guestTmpArchive, overwrite = true)

            // Step 3: Extract inside Linux userspace into /usr/local/bin/code
            progressCallback?.invoke(0.7f, "Extracting VS Code CLI inside Linux userspace...")
            val extractCmd = "mkdir -p /usr/local/bin $GUEST_DATA_DIR $GUEST_PROJECTS_DIR && " +
                    "tar -xzf /tmp/vscode_cli.tar.gz -C /usr/local/bin code && " +
                    "chmod 755 $GUEST_BIN_PATH && " +
                    "rm -f /tmp/vscode_cli.tar.gz"

            val extractExit = linuxRuntime.executeStreaming(extractCmd) { line ->
                progressCallback?.invoke(0.8f, line)
            }.getOrThrow()

            if (extractExit != 0) {
                throw RuntimeException("Extraction of VS Code CLI failed inside guest with exit code $extractExit")
            }

            // Step 4: Validate CLI inside Linux userspace
            progressCallback?.invoke(0.9f, "Verifying VS Code CLI inside Linux...")
            val verify = verifyCliInstallation().getOrThrow()
            AvsLogger.i(TAG, "Microsoft VS Code CLI verified inside Linux:\n$verify")

            // Step 5: Cleanup downloaded host archive
            if (downloadArchive.exists()) downloadArchive.delete()
            if (guestTmpArchive.exists()) guestTmpArchive.delete()

            progressCallback?.invoke(1.0f, "Microsoft VS Code CLI installed successfully")
            AvsLogger.i(TAG, "Microsoft VS Code CLI installation completed successfully")
        }
    }

    /**
     * Executes `command -v code && code --version` inside the Ubuntu userspace.
     */
    suspend fun verifyCliInstallation(): Result<String> = withContext(Dispatchers.IO) {
        val checkCmd = "command -v code && code --version"
        linuxRuntime.execute(checkCmd)
    }

    /**
     * Dynamically queries the installed VS Code version inside the guest.
     * Returns null if VS Code CLI is not installed or command fails.
     */
    suspend fun getCliVersion(): String? = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext null
        val res = linuxRuntime.execute("code --version 2>/dev/null")
        if (res.isSuccess) {
            val firstLine = res.getOrNull()?.lines()?.firstOrNull { it.isNotBlank() }?.trim()
            if (!firstLine.isNullOrEmpty()) {
                return@withContext firstLine
            }
        }
        null
    }

    /**
     * Installs a VS Code extension (.vsix) inside the Linux userspace using the official CLI.
     */
    suspend fun installExtension(guestVsixPath: String): Result<Int> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Installing VS Code extension: $guestVsixPath")
        val cmd = "code --install-extension $guestVsixPath --cli-data-dir $GUEST_DATA_DIR"
        linuxRuntime.executeStreaming(cmd) { line ->
            AvsLogger.d(TAG, "[Extension Install] $line")
        }
    }

    private suspend fun downloadCliArchive(target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Downloading VS Code CLI from $CLI_DOWNLOAD_URL")
        var currentUrl = CLI_DOWNLOAD_URL
        var redirects = 0
        val maxRedirects = 5

        while (redirects < maxRedirects) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = false

            val code = conn.responseCode
            if (code in 300..399) {
                val newUrl = conn.getHeaderField("Location")
                if (newUrl.isNullOrEmpty()) {
                    throw RuntimeException("Redirect received without Location header (HTTP $code)")
                }
                currentUrl = newUrl
                redirects++
                continue
            }

            if (code !in 200..299) {
                throw RuntimeException("HTTP error downloading VS Code CLI: $code ${conn.responseMessage}")
            }

            val total = conn.contentLengthLong
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var r: Int
                    while (input.read(buf).also { r = it } != -1) {
                        output.write(buf, 0, r)
                        downloaded += r
                        if (total > 0) {
                            onProgress(downloaded.toFloat() / total.toFloat())
                        }
                    }
                }
            }
            AvsLogger.i(TAG, "VS Code CLI download finished (${downloaded / (1024 * 1024)}MB)")
            return@withContext
        }

        throw RuntimeException("Too many redirects downloading VS Code CLI")
    }

    /**
     * Starts the local Microsoft VS Code Server (`code serve-web`) inside Linux userspace.
     *
     * - Cleans up any stale `code serve-web` processes inside the guest.
     * - Allocates an ephemeral dynamic port if not explicitly provided.
     * - Spawns the server bound strictly to loopback `127.0.0.1:<port>`.
     * - Monitors server readiness via active HTTP polling until reachable.
     * - Emits the local editor URL on success.
     * - On failure, terminates the spawned process and clears state.
     *
     * @param serverPort Optional specific port to bind to (defaults to dynamic port)
     * @param onLog Real-time output stream callback
     * @param onServerReady Invoked when local HTTP server is reachable
     */
    suspend fun startServer(
        serverPort: Int? = null,
        onLog: ((String) -> Unit)? = null,
        onServerReady: ((url: String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val result = runCatchingResult {
            if (isServerRunning() && activeServerUrl != null) {
                AvsLogger.i(TAG, "VS Code Server already running at $activeServerUrl")
                onLog?.invoke("[VS Code] Server already active at $activeServerUrl")
                return@runCatchingResult activeServerUrl!!
            }

            if (!isInstalled()) {
                install { _, status -> onLog?.invoke(status) }.getOrThrow()
            }

            // Cleanup stale processes before starting
            cleanStaleProcesses()

            val selectedPort = serverPort ?: findAvailablePort(DEFAULT_PREFERRED_PORT)
            this@VsCodeCliManager.serverPort = selectedPort

            AvsLogger.i(TAG, "Starting local VS Code Server on 127.0.0.1:$selectedPort")
            onLog?.invoke("[VS Code] Starting local server on 127.0.0.1:$selectedPort...")

            // Ensure workspace and CLI data directory exist
            linuxRuntime.execute("mkdir -p $GUEST_PROJECTS_DIR $GUEST_DATA_DIR $GUEST_DATA_DIR/data")

            val logFile = paths.serverLogFile
            if (logFile.exists()) logFile.delete()
            logFile.parentFile?.mkdirs()

            val guestServerCmd = buildServerCommand(selectedPort, DEFAULT_SERVER_HOST)
            val args = linuxRuntime.buildPRootArgs(guestServerCmd, GUEST_PROJECTS_DIR)
            val env = linuxRuntime.buildEnvironment("/home/user")

            val spawnResult = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                logFile.absolutePath
            ) ?: throw RuntimeException("Failed to spawn local VS Code Server process")

            serverPid = spawnResult[0]
            if (spawnResult.size > 1 && spawnResult[1] >= 0) {
                NativeSpawn.close(spawnResult[1])
            }
            AvsLogger.i(TAG, "Local VS Code Server spawned (PID $serverPid), polling readiness on port $selectedPort...")

            onLog?.invoke("[VS Code] Server process spawned (PID $serverPid), awaiting HTTP readiness...")

            val readyUrl = waitForServerReady(selectedPort, logFile, onLog)
            activeServerUrl = readyUrl
            onServerReady?.invoke(readyUrl)

            AvsLogger.i(TAG, "Local VS Code Server ready at $readyUrl")
            onLog?.invoke("[VS Code] Local server reachable at $readyUrl")

            readyUrl
        }

        if (result.isFailure) {
            try {
                stopServer()
            } catch (e: Exception) {
                AvsLogger.d(TAG, "Error cleaning up after failed server start: ${e.message}")
            }
        }

        result
    }

    /**
     * Polls the server endpoint until it responds to HTTP requests or fails fast if process terminates.
     */
    private suspend fun waitForServerReady(
        serverPort: Int,
        logFile: File,
        onLog: ((String) -> Unit)?
    ): String {
        val startTime = System.currentTimeMillis()
        var lastLogPos = 0L

        while (System.currentTimeMillis() - startTime < STARTUP_TIMEOUT_MS) {
            val pid = serverPid ?: throw RuntimeException("Server process reference lost")
            val exitStatus = NativeSpawn.waitFor(pid, true)

            // Read new log output
            if (logFile.exists() && logFile.length() > lastLogPos) {
                RandomAccessFile(logFile, "r").use { raf ->
                    raf.seek(lastLogPos)
                    var line = raf.readLine()
                    while (line != null) {
                        onLog?.invoke(line)
                        line = raf.readLine()
                    }
                    lastLogPos = raf.filePointer
                }
            }

            // Check if process died
            if (exitStatus != -2) {
                val logs = if (logFile.exists()) logFile.readText() else "No logs"
                throw RuntimeException("Local VS Code Server exited prematurely with code $exitStatus:\n$logs")
            }

            // Probe HTTP endpoint
            if (checkHttpReachable(serverPort)) {
                return "http://$DEFAULT_SERVER_HOST:$serverPort/?folder=$GUEST_PROJECTS_DIR"
            }

            delay(PROBE_INTERVAL_MS)
        }

        val logs = if (logFile.exists()) logFile.readText().takeLast(2000) else "No logs"
        throw RuntimeException("Timed out waiting for local VS Code Server on port $serverPort after ${STARTUP_TIMEOUT_MS / 1000}s:\n$logs")
    }

    private suspend fun cleanStaleProcesses() {
        try {
            linuxRuntime.execute("pkill -f 'code serve-web' 2>/dev/null || true")
            delay(150)
        } catch (e: Exception) {
            AvsLogger.d(TAG, "Error cleaning stale processes: ${e.message}")
        }
    }

    /**
     * Stops the local VS Code Server process group cleanly.
     */
    suspend fun stopServer(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping local VS Code Server")

        runCatchingResult {
            serverPid?.let { pid ->
                NativeSpawn.kill(pid, 15) // SIGTERM
                delay(200)
                NativeSpawn.kill(pid, 9)  // SIGKILL
                NativeSpawn.waitFor(pid, true)
            }
            serverPid = null
            serverPort = null
            activeServerUrl = null

            cleanStaleProcesses()
            AvsLogger.i(TAG, "Local VS Code Server stopped")
        }
    }

    suspend fun getStatus(): VsCodeCliStatus = withContext(Dispatchers.IO) {
        VsCodeCliStatus(
            isInstalled = isInstalled(),
            isRunning = isServerRunning(),
            serverPort = serverPort,
            serverUrl = activeServerUrl
        )
    }
}

data class VsCodeCliStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val serverPort: Int? = null,
    val serverUrl: String? = null
)
