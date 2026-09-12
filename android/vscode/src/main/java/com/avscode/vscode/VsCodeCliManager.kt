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
import java.net.URL
import java.util.regex.Pattern

/**
 * Microsoft Visual Studio Code CLI and VS Code Tunnel manager.
 *
 * Implements strict host/guest execution boundaries:
 * - Official Microsoft ARM64 standalone CLI archive is downloaded on the Android host.
 * - Archive extraction and binary placement are performed strictly inside Linux userspace (`/usr/local/bin/code`).
 * - Guest bootstrap is performed via `/usr/local/lib/avscode/bootstrap.sh`.
 * - CLI presence is verified via `command -v code && code --version` inside PRoot.
 * - `code tunnel` is supervised inside Ubuntu userspace with real-time log streaming.
 * - Device code authentication prompts (`https://github.com/login/device`) and
 *   the resulting `https://vscode.dev/tunnel/...` connection endpoint are detected dynamically.
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

        // Timeouts
        const val STARTUP_TIMEOUT_MS = 90_000L
        const val AUTH_TIMEOUT_MS = 300_000L // 5 minutes for device login

        // Regex patterns for parsing tunnel stdout
        val TUNNEL_URL_REGEX = Regex("https://(?:insiders\\.)?vscode\\.dev/tunnel/[a-zA-Z0-9._-]+(?:/[^\\s]*)?")
        val AUTH_URL_REGEX = Regex("https://(?:github\\.com/login/device|login\\.microsoft\\.com/device)")
        val AUTH_CODE_REGEX = Regex("(?:code|enter the code|use code)[:\\s]+([A-Z0-9]{4,9}-[A-Z0-9]{4,9}|[A-Z0-9]{8,12})", RegexOption.IGNORE_CASE)
    }

    private val paths = AppPaths.getInstance(context)
    private var tunnelPid: Int? = null
    private var activeTunnelUrl: String? = null

    /**
     * Check if Microsoft VS Code CLI binary is present in guest rootfs.
     */
    fun isInstalled(): Boolean {
        val bin = File(paths.rootfsDir, "usr/local/bin/code")
        return bin.exists()
    }

    /**
     * Check if tunnel process is currently running.
     */
    fun isTunnelRunning(): Boolean {
        val pid = tunnelPid ?: return false
        val status = NativeSpawn.waitFor(pid, true)
        return status == -2
    }

    /**
     * Get the active vscode.dev tunnel URL if established.
     */
    fun getTunnelUrl(): String? = activeTunnelUrl

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
     * Starts the Microsoft VS Code Tunnel inside Linux userspace.
     *
     * - Checks if already logged in via `code tunnel user show`.
     * - If not logged in, initiates login via `code tunnel user login --provider github`
     *   and emits [onAuthRequired] with the verification URL and device code.
     * - Spawns `code tunnel` daemon inside PRoot.
     * - Monitors output in real time until `vscode.dev/tunnel/...` endpoint is established.
     *
     * @param tunnelName Optional machine name for the tunnel (default: "avscode")
     * @param onLog Real-time output stream callback
     * @param onAuthRequired Invoked when user authentication is required
     * @param onTunnelReady Invoked when tunnel URL is obtained
     */
    suspend fun startTunnel(
        tunnelName: String = "avscode",
        onLog: ((String) -> Unit)? = null,
        onAuthRequired: ((authUrl: String, code: String?) -> Unit)? = null,
        onTunnelReady: ((url: String) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting Microsoft VS Code Tunnel (name: $tunnelName)")

        runCatchingResult {
            if (isTunnelRunning() && activeTunnelUrl != null) {
                AvsLogger.i(TAG, "VS Code Tunnel already running at $activeTunnelUrl")
                onLog?.invoke("[VS Code] Tunnel already active at $activeTunnelUrl")
                return@runCatchingResult activeTunnelUrl!!
            }

            if (!isInstalled()) {
                install { _, status -> onLog?.invoke(status) }.getOrThrow()
            }

            // Ensure workspace and CLI data directory exist
            linuxRuntime.execute("mkdir -p $GUEST_PROJECTS_DIR $GUEST_DATA_DIR")

            // Check existing login status
            val checkLoginResult = linuxRuntime.execute("$GUEST_BIN_PATH tunnel user show --cli-data-dir $GUEST_DATA_DIR")
            val isUserLoggedIn = checkLoginResult.isSuccess && !checkLoginResult.getOrNull().orEmpty().contains("not logged in")

            if (!isUserLoggedIn) {
                AvsLogger.i(TAG, "VS Code Tunnel not logged in. Initiating device-code authentication...")
                onLog?.invoke("[VS Code] Microsoft Tunnel authentication required.")
                onLog?.invoke("[VS Code] Requesting device authorization code...")

                val loginCmd = "$GUEST_BIN_PATH tunnel user login --provider github --cli-data-dir $GUEST_DATA_DIR"
                val authCompleted = handleDeviceLogin(loginCmd, onLog, onAuthRequired)
                if (!authCompleted) {
                    throw RuntimeException("VS Code Tunnel authentication timed out or was cancelled")
                }
                onLog?.invoke("[VS Code] Authentication successful. Initializing tunnel...")
            } else {
                AvsLogger.i(TAG, "VS Code Tunnel already authenticated: ${checkLoginResult.getOrNull()?.trim()}")
                onLog?.invoke("[VS Code] Logged in to tunnel: ${checkLoginResult.getOrNull()?.trim()}")
            }

            // Launch tunnel daemon inside Linux userspace
            val logFile = paths.tunnelLogFile
            if (logFile.exists()) logFile.delete()
            logFile.parentFile?.mkdirs()

            val guestTunnelCmd = "$GUEST_BIN_PATH tunnel --accept-server-license-terms " +
                    "--cli-data-dir $GUEST_DATA_DIR " +
                    "--user-data-dir $GUEST_DATA_DIR/data " +
                    "--name $tunnelName"

            val args = linuxRuntime.buildPRootArgs(guestTunnelCmd, GUEST_PROJECTS_DIR)
            val env = linuxRuntime.buildEnvironment("/home/user")

            val spawnResult = NativeSpawn.spawn(
                args.toTypedArray(),
                env,
                paths.rootfsDir.absolutePath,
                logFile.absolutePath
            ) ?: throw RuntimeException("Failed to spawn VS Code Tunnel process")

            tunnelPid = spawnResult[0]
            AvsLogger.i(TAG, "VS Code Tunnel spawned with PID $tunnelPid, monitoring for vscode.dev endpoint...")
            onLog?.invoke("[VS Code] Tunnel process spawned (PID $tunnelPid), awaiting connection URL...")

            val detectedUrl = waitForTunnelUrl(logFile, onLog, onAuthRequired)
            activeTunnelUrl = detectedUrl
            onTunnelReady?.invoke(detectedUrl)
            AvsLogger.i(TAG, "VS Code Tunnel ready at $detectedUrl")
            onLog?.invoke("[VS Code] Tunnel ready at $detectedUrl")

            detectedUrl
        }
    }

    /**
     * Executes device login command, parses authorization URL and code, and waits for completion.
     */
    private suspend fun handleDeviceLogin(
        loginCmd: String,
        onLog: ((String) -> Unit)?,
        onAuthRequired: ((authUrl: String, code: String?) -> Unit)?
    ): Boolean {
        val logFile = File(paths.cacheDir, "login_${System.currentTimeMillis()}.log")
        val args = linuxRuntime.buildPRootArgs(loginCmd, "/home/user")
        val env = linuxRuntime.buildEnvironment("/home/user")

        val spawnResult = NativeSpawn.spawn(
            args.toTypedArray(),
            env,
            paths.rootfsDir.absolutePath,
            logFile.absolutePath
        ) ?: return false

        val pid = spawnResult[0]
        var lastPos = 0L
        val startTime = System.currentTimeMillis()
        var authNotified = false

        try {
            while (System.currentTimeMillis() - startTime < AUTH_TIMEOUT_MS) {
                val status = NativeSpawn.waitFor(pid, true)

                if (logFile.exists() && logFile.length() > lastPos) {
                    RandomAccessFile(logFile, "r").use { raf ->
                        raf.seek(lastPos)
                        var line = raf.readLine()
                        while (line != null) {
                            onLog?.invoke(line)

                            if (!authNotified) {
                                val authUrlMatch = AUTH_URL_REGEX.find(line)
                                val authCodeMatch = AUTH_CODE_REGEX.find(line)
                                if (authUrlMatch != null) {
                                    val authUrl = authUrlMatch.value
                                    val authCode = authCodeMatch?.groupValues?.getOrNull(1)
                                    AvsLogger.i(TAG, "Device code authentication required: url=$authUrl code=$authCode")
                                    onAuthRequired?.invoke(authUrl, authCode)
                                    authNotified = true
                                }
                            }
                            line = raf.readLine()
                        }
                        lastPos = raf.filePointer
                    }
                }

                if (status != -2) {
                    return status == 0
                }

                delay(500)
            }
        } finally {
            if (logFile.exists()) logFile.delete()
        }

        return false
    }

    /**
     * Monitors tunnel log file until the vscode.dev URL is output.
     */
    private suspend fun waitForTunnelUrl(
        logFile: File,
        onLog: ((String) -> Unit)?,
        onAuthRequired: ((authUrl: String, code: String?) -> Unit)?
    ): String {
        val start = System.currentTimeMillis()
        var lastPos = 0L

        while (System.currentTimeMillis() - start < STARTUP_TIMEOUT_MS) {
            val pid = tunnelPid ?: throw RuntimeException("Tunnel process terminated")
            val status = NativeSpawn.waitFor(pid, true)

            if (logFile.exists() && logFile.length() > lastPos) {
                RandomAccessFile(logFile, "r").use { raf ->
                    raf.seek(lastPos)
                    var line = raf.readLine()
                    while (line != null) {
                        onLog?.invoke(line)

                        // Check for device code authentication if required
                        val authUrlMatch = AUTH_URL_REGEX.find(line)
                        if (authUrlMatch != null) {
                            val authUrl = authUrlMatch.value
                            val authCode = AUTH_CODE_REGEX.find(line)?.groupValues?.getOrNull(1)
                            onAuthRequired?.invoke(authUrl, authCode)
                        }

                        // Check for tunnel connection URL
                        val urlMatch = TUNNEL_URL_REGEX.find(line)
                        if (urlMatch != null) {
                            return urlMatch.value
                        }

                        line = raf.readLine()
                    }
                    lastPos = raf.filePointer
                }
            }

            if (status != -2) {
                val logs = if (logFile.exists()) logFile.readText() else "No logs"
                throw RuntimeException("VS Code Tunnel exited unexpectedly with status $status:\n$logs")
            }

            delay(500)
        }

        val logs = if (logFile.exists()) logFile.readText().takeLast(2000) else "No logs"
        throw RuntimeException("Timed out waiting for VS Code Tunnel URL after ${STARTUP_TIMEOUT_MS / 1000}s:\n$logs")
    }

    /**
     * Stops the running VS Code Tunnel process group.
     */
    suspend fun stopTunnel(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping VS Code Tunnel")

        runCatchingResult {
            tunnelPid?.let { pid ->
                NativeSpawn.kill(pid, 15) // SIGTERM
                delay(200)
                NativeSpawn.kill(pid, 9)  // SIGKILL
                NativeSpawn.waitFor(pid, true)
            }
            tunnelPid = null
            activeTunnelUrl = null

            linuxRuntime.execute("pkill -f 'code tunnel' 2>/dev/null || true")
            AvsLogger.i(TAG, "VS Code Tunnel stopped")
        }
    }

    suspend fun getStatus(): VsCodeCliStatus = withContext(Dispatchers.IO) {
        VsCodeCliStatus(
            isInstalled = isInstalled(),
            isRunning = isTunnelRunning(),
            tunnelUrl = activeTunnelUrl
        )
    }
}

data class VsCodeCliStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val tunnelUrl: String?
)

