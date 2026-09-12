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
 * Implements strict host/guest execution boundaries:
 * - Archive download is performed on the Android host.
 * - Archive extraction and execution are performed strictly inside Linux userspace.
 * - Guest bootstrap is performed via `/usr/local/lib/avscode/bootstrap.sh`.
 * - Server process lifecycle is supervised with HTTP readiness checks.
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
     * Executes the guest-side bootstrap script /usr/local/lib/avscode/bootstrap.sh inside PRoot.
     * Skips immediately if already bootstrapped.
     */
    suspend fun ensureBootstrap(onOutput: ((String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val marker = paths.hostBootstrapMarker
            if (marker.exists()) {
                AvsLogger.d(TAG, "Bootstrap already completed, skipping")
                onOutput?.invoke("[Bootstrap] Linux environment already bootstrapped.")
                return@runCatchingResult Unit
            }

            AvsLogger.i(TAG, "Running guest bootstrap script: ${paths.guestBootstrapScript}")
            onOutput?.invoke("[Bootstrap] Running guest bootstrap script (/usr/local/lib/avscode/bootstrap.sh)...")

            val exitCode = linuxRuntime.executeStreaming(paths.guestBootstrapScript) { line ->
                onOutput?.invoke(line)
            }.getOrThrow()

            if (exitCode != 0) {
                throw RuntimeException("Guest bootstrap script failed with exit code $exitCode")
            }

            // Verify marker
            if (!marker.exists()) {
                marker.parentFile?.mkdirs()
                marker.createNewFile()
            }

            AvsLogger.i(TAG, "Linux bootstrap completed successfully")
            onOutput?.invoke("[Bootstrap] Linux bootstrap complete.")
            Unit
        }
    }

    /**
     * Installs code-server into guest /opt/code-server.
     * Download is an Android host operation; extraction occurs strictly inside the Linux guest.
     */
    suspend fun install(progressCallback: ((Float, String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting code-server installation")

        runCatchingResult {
            if (isInstalled()) {
                AvsLogger.i(TAG, "code-server is already installed")
                progressCallback?.invoke(1.0f, "VS Code Server ready")
                return@runCatchingResult Unit
            }

            // Step 1: Download code-server tarball on Android host
            val downloadArchive = File(paths.cacheDir, "code-server-$SERVER_VERSION-linux-arm64.tar.gz")
            if (!downloadArchive.exists() || downloadArchive.length() < 50 * 1024 * 1024) {
                progressCallback?.invoke(0.1f, "Downloading VS Code Server (code-server v$SERVER_VERSION)...")
                downloadServerArchive(downloadArchive) { p ->
                    progressCallback?.invoke(0.1f + p * 0.4f, "Downloading VS Code Server (${(p * 100).toInt()}%)...")
                }
            }

            // Step 2: Copy archive to guest /tmp for extraction inside guest
            progressCallback?.invoke(0.55f, "Staging archive in guest /tmp...")
            val guestTmpDir = paths.hostGuestTmpDir
            if (!guestTmpDir.exists()) {
                guestTmpDir.mkdirs()
            }
            val guestTmpArchive = File(guestTmpDir, "code-server.tar.gz")
            downloadArchive.copyTo(guestTmpArchive, overwrite = true)

            // Step 3: Extract inside Linux userspace using Ubuntu guest tar
            progressCallback?.invoke(0.65f, "Extracting VS Code Server inside Linux userspace...")
            val extractCmd = "mkdir -p $GUEST_INSTALL_DIR && tar -xzf /tmp/code-server.tar.gz -C $GUEST_INSTALL_DIR --strip-components=1 && rm -f /tmp/code-server.tar.gz && chmod +x $GUEST_BIN_PATH"
            val extractExit = linuxRuntime.executeStreaming(extractCmd) { line ->
                progressCallback?.invoke(0.75f, line)
            }.getOrThrow()

            if (extractExit != 0) {
                throw RuntimeException("Extraction of code-server failed inside guest with code $extractExit")
            }

            // Step 4: Symlink bundled node & npm so they are globally accessible in guest
            progressCallback?.invoke(0.85f, "Configuring Node runtime symlinks...")
            linuxRuntime.execute("mkdir -p /usr/local/bin && ln -sf $GUEST_INSTALL_DIR/lib/node /usr/local/bin/node")
            linuxRuntime.execute("mkdir -p $GUEST_PROJECTS_DIR $GUEST_DATA_DIR")

            // Step 5: Validate code-server installation
            progressCallback?.invoke(0.95f, "Validating VS Code Server installation...")
            val verifyResult = linuxRuntime.execute("$GUEST_BIN_PATH --version")
            if (verifyResult.isFailure) {
                throw RuntimeException("code-server validation failed: ${verifyResult.exceptionOrNull()?.message}")
            }
            AvsLogger.i(TAG, "code-server validated: ${verifyResult.getOrNull()?.trim()}")

            // Step 6: Cleanup downloaded archive from host cache
            if (downloadArchive.exists()) downloadArchive.delete()
            if (guestTmpArchive.exists()) guestTmpArchive.delete()

            progressCallback?.invoke(1.0f, "VS Code Server installed successfully")
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
     * Starts VS Code Server inside PRoot and waits until HTTP endpoint is ready.
     */
    suspend fun start(workspacePath: String = GUEST_PROJECTS_DIR, onLog: ((String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting VS Code Server on port $SERVER_PORT")

        runCatchingResult {
            if (isResponding()) {
                AvsLogger.i(TAG, "VS Code Server already running and responding on port $SERVER_PORT")
                onLog?.invoke("[VS Code] Server already active on port $SERVER_PORT")
                return@runCatchingResult Unit
            }

            if (!isInstalled()) {
                install { _, status -> onLog?.invoke(status) }.getOrThrow()
            }

            linuxRuntime.execute("mkdir -p $workspacePath $GUEST_DATA_DIR")

            val logFile = paths.serverLogFile
            logFile.parentFile?.mkdirs()

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
            onLog?.invoke("[VS Code] Process spawned (PID $serverPid), awaiting HTTP readiness...")

            waitForServerReady(onLog)
            AvsLogger.i(TAG, "VS Code Server is ready at ${getServerUrl()}")
            onLog?.invoke("[VS Code] Server ready at ${getServerUrl()}")
            Unit
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

    fun getServerUrl(): String = "http://127.0.0.1:$SERVER_PORT"

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

    private suspend fun waitForServerReady(onLog: ((String) -> Unit)? = null) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < STARTUP_TIMEOUT_MS) {
            if (isResponding()) {
                AvsLogger.d(TAG, "Server responded after ${System.currentTimeMillis() - start}ms")
                return
            }

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
        VsCodeServerStatus(
            isInstalled = isInstalled(),
            isRunning = isResponding(),
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
