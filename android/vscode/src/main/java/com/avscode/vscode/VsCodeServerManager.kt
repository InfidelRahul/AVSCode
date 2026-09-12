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
import java.net.HttpURLConnection
import java.net.URL

/**
 * VS Code Server (code-server) manager.
 *
 * Handles installation, bootstrap of development environment, server lifecycle,
 * and readiness detection.
 */
class VsCodeServerManager(
    private val context: Context,
    private val linuxRuntime: PRootRuntime
) {
    companion object {
        private const val TAG = "VsCodeServerManager"

        // code-server ARM64 stable release
        const val SERVER_VERSION = "4.96.4"
        const val SERVER_DOWNLOAD_URL = "https://github.com/coder/code-server/releases/download/v$SERVER_VERSION/code-server-$SERVER_VERSION-linux-arm64.tar.gz"

        // Server paths inside guest
        const val GUEST_INSTALL_DIR = "/opt/code-server"
        const val GUEST_BIN_PATH = "$GUEST_INSTALL_DIR/bin/code-server"
        const val GUEST_DATA_DIR = "/home/user/.local/share/code-server"
        const val GUEST_PROJECTS_DIR = "/home/user/projects"

        // Default local port
        const val SERVER_PORT = 8080

        // Timeout for server startup
        const val STARTUP_TIMEOUT_MS = 60_000L
    }

    private val paths = AppPaths.getInstance(context)
    private var serverPid: Int? = null

    /**
     * Check if code-server binary is present and executable in rootfs.
     */
    fun isInstalled(): Boolean {
        val bin = File(paths.rootfsDir, "opt/code-server/bin/code-server")
        return bin.exists()
    }

    /**
     * Installs code-server into guest /opt/code-server.
     */
    suspend fun install(progressCallback: ((Float, String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting code-server installation")

        runCatchingResult {
            if (isInstalled()) {
                AvsLogger.i(TAG, "code-server is already installed")
                progressCallback?.invoke(1.0f, "VS Code Server ready")
                return@runCatchingResult Unit
            }

            // Step 1: Download code-server tarball
            val downloadArchive = File(paths.cacheDir, "code-server-$SERVER_VERSION-linux-arm64.tar.gz")
            if (!downloadArchive.exists() || downloadArchive.length() < 50 * 1024 * 1024) {
                progressCallback?.invoke(0.1f, "Downloading VS Code Server (code-server v$SERVER_VERSION)...")
                downloadServerArchive(downloadArchive) { p ->
                    progressCallback?.invoke(0.1f + p * 0.4f, "Downloading VS Code Server (${(p * 100).toInt()}%)...")
                }
            }

            // Step 2: Extract directly to /opt/code-server inside rootfs
            progressCallback?.invoke(0.6f, "Extracting VS Code Server...")
            val hostInstallDir = File(paths.rootfsDir, "opt/code-server")
            hostInstallDir.mkdirs()

            // Copy archive into rootfs /tmp for guest extraction
            val guestTmpArchive = File(paths.rootfsDir, "tmp/code-server.tar.gz")
            downloadArchive.copyTo(guestTmpArchive, overwrite = true)

            val extractCmd = "mkdir -p $GUEST_INSTALL_DIR && tar -xzf /tmp/code-server.tar.gz -C $GUEST_INSTALL_DIR --strip-components=1 && rm -f /tmp/code-server.tar.gz"
            val extractResult = linuxRuntime.execute(extractCmd)
            if (extractResult.isFailure) {
                throw RuntimeException("Extraction of code-server failed: ${extractResult.exceptionOrNull()?.message}")
            }

            // Ensure permissions
            linuxRuntime.execute("chmod +x $GUEST_BIN_PATH")

            // Symlink bundled node & npm so they are globally available in Linux
            linuxRuntime.execute("mkdir -p /usr/local/bin")
            linuxRuntime.execute("ln -sf $GUEST_INSTALL_DIR/lib/node /usr/local/bin/node")
            linuxRuntime.execute("ln -sf $GUEST_INSTALL_DIR/lib/node /usr/bin/node 2>/dev/null || true")

            // Create projects & data directories
            linuxRuntime.execute("mkdir -p $GUEST_PROJECTS_DIR $GUEST_DATA_DIR")

            // Cleanup downloaded archive from cache
            if (downloadArchive.exists()) downloadArchive.delete()
            if (guestTmpArchive.exists()) guestTmpArchive.delete()

            progressCallback?.invoke(1.0f, "VS Code Server installed")
            AvsLogger.i(TAG, "code-server installation completed successfully")
        }
    }

    private suspend fun downloadServerArchive(target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Downloading code-server from $SERVER_DOWNLOAD_URL")
        val url = URL(SERVER_DOWNLOAD_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true

        val code = conn.responseCode
        if (code !in 200..299) {
            throw RuntimeException("HTTP error downloading code-server: $code ${conn.responseMessage}")
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
        AvsLogger.i(TAG, "code-server download completed (${downloaded / (1024 * 1024)}MB)")
    }

    /**
     * Ensures initial Linux environment tools are bootstrapped (Phase 5).
     * Skips immediately if already bootstrapped.
     */
    suspend fun ensureBootstrap(onStatus: ((String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val marker = paths.hostBootstrapMarker
            if (marker.exists()) {
                AvsLogger.d(TAG, "Bootstrap already completed, skipping")
                return@runCatchingResult Unit
            }

            AvsLogger.i(TAG, "Performing one-time Linux bootstrap...")
            onStatus?.invoke("Configuring Linux development environment...")

            // Make sure projects directory exists
            linuxRuntime.execute("mkdir -p /home/user/projects /home/user/.local/share")

            // Install essential dev tools via apt
            onStatus?.invoke("Updating Linux packages (apt-get update)...")
            linuxRuntime.execute("export DEBIAN_FRONTEND=noninteractive && apt-get update -qq")

            onStatus?.invoke("Installing core development tools (git, python3, ca-certificates)...")
            val installCmd = "export DEBIAN_FRONTEND=noninteractive && apt-get install -y --no-install-recommends ca-certificates curl wget git python3 python3-pip"
            val aptResult = linuxRuntime.execute(installCmd)
            if (aptResult.isFailure) {
                AvsLogger.w(TAG, "apt install finished with warnings: ${aptResult.exceptionOrNull()?.message}")
            }

            // Mark bootstrap as complete
            marker.parentFile?.mkdirs()
            marker.createNewFile()
            AvsLogger.i(TAG, "Linux bootstrap completed successfully")
        }
    }

    /**
     * Starts VS Code Server inside PRoot and waits until HTTP endpoint is ready.
     */
    suspend fun start(workspacePath: String = GUEST_PROJECTS_DIR): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting VS Code Server on port $SERVER_PORT")

        runCatchingResult {
            // Check if already running and responding
            if (isResponding()) {
                AvsLogger.i(TAG, "VS Code Server already running and responding on port $SERVER_PORT")
                return@runCatchingResult Unit
            }

            if (!isInstalled()) {
                install().getOrThrow()
            }

            // Ensure workspace directory exists
            linuxRuntime.execute("mkdir -p $workspacePath $GUEST_DATA_DIR")

            val logFile = paths.serverLogFile
            logFile.parentFile?.mkdirs()

            // Construct server start command
            val guestCmd = buildString {
                append(GUEST_BIN_PATH)
                append(" --bind-addr 127.0.0.1:$SERVER_PORT")
                append(" --auth none")
                append(" --disable-telemetry")
                append(" --disable-update-check")
                append(" --user-data-dir $GUEST_DATA_DIR")
                append(" --extensions-dir $GUEST_DATA_DIR/extensions")
                append(" $workspacePath")
            }

            val args = linuxRuntime.buildPRootArgs(guestCmd, "/home/user")
            val env = linuxRuntime.buildEnvironment("/home/user")

            val spawnResult = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                logFile.absolutePath
            ) ?: throw RuntimeException("Failed to spawn code-server process")

            serverPid = spawnResult[0]
            AvsLogger.i(TAG, "code-server spawned with PID $serverPid, waiting for HTTP readiness...")

            // Wait for HTTP endpoint to become responsive
            waitForServerReady()
            AvsLogger.i(TAG, "VS Code Server is ready at ${getServerUrl()}")
        }
    }

    /**
     * Stop code-server process group.
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping VS Code Server")

        runCatchingResult {
            serverPid?.let { pid ->
                NativeSpawn.kill(pid, 15) // SIGTERM
                delay(200)
                NativeSpawn.kill(pid, 9)  // SIGKILL
                NativeSpawn.waitFor(pid, true)
            }
            serverPid = null
            linuxRuntime.execute("pkill -f 'code-server' 2>/dev/null || true")
            AvsLogger.i(TAG, "VS Code Server stopped")
        }
    }

    /**
     * Get the local HTTP URL to access VS Code Web.
     */
    fun getServerUrl(): String = "http://127.0.0.1:$SERVER_PORT"

    /**
     * Check if the HTTP server is currently responding on 127.0.0.1:SERVER_PORT.
     */
    fun isResponding(): Boolean {
        return try {
            val url = URL("http://127.0.0.1:$SERVER_PORT/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.readTimeout = 1000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            code in 200..399 || code == 401
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Poll until the server responds or timeout expires.
     */
    private suspend fun waitForServerReady() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STARTUP_TIMEOUT_MS) {
            if (isResponding()) {
                AvsLogger.d(TAG, "Server responded after ${System.currentTimeMillis() - start}ms")
                return
            }

            // Check if process crashed
            serverPid?.let { pid ->
                val status = NativeSpawn.waitFor(pid, true)
                if (status != -2) {
                    val logs = if (paths.serverLogFile.exists()) paths.serverLogFile.readText() else "No logs"
                    throw RuntimeException("code-server exited unexpectedly with status $status:\n$logs")
                }
            }

            delay(500)
        }

        val logs = if (paths.serverLogFile.exists()) paths.serverLogFile.readText().takeLast(2000) else "No logs"
        throw RuntimeException("Timed out waiting for VS Code Server on port $SERVER_PORT after ${STARTUP_TIMEOUT_MS / 1000}s:\n$logs")
    }

    suspend fun getStatus(): VsCodeServerStatus = withContext(Dispatchers.IO) {
        val installed = isInstalled()
        val running = isResponding()

        VsCodeServerStatus(
            isInstalled = installed,
            isRunning = running,
            port = SERVER_PORT,
            url = getServerUrl()
        )
    }
}

data class VsCodeServerStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val port: Int,
    val url: String
)
