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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL

/**
 * Microsoft Visual Studio Code CLI and Local VS Code Server Manager.
 *
 * Implements a fully local, offline-capable VS Code architecture for Ubuntu/Debian ARM64:
 * - Official Microsoft standard Linux ARM64 standalone CLI archive (cli-linux-arm64, glibc)
 *   is downloaded and verified.
 * - Architecture, bitness, glibc (>= 2.28), libstdc++ (>= 3.4.25 / GLIBCXX_3.4.25), and system
 *   dependencies are explicitly verified before installation.
 * - System packages are resolved and installed idempotently via APT.
 * - Binary extraction and ELF AArch64 validation are performed inside Linux userspace.
 * - Stable persistent state layout under `/home/user/.avscode/` decouples application state
 *   (cli, server, user-data, extensions, logs) from the user workspace (`/home/user/projects`).
 * - Non-destructive migration preserves existing Alpine installations and user projects.
 * - Local VS Code Server (`code serve-web`) is supervised inside Ubuntu userspace on a stable loopback port.
 */
class VsCodeCliManager(
    private val context: Context,
    private val linuxRuntime: PRootRuntime
) {
    companion object {
        private const val TAG = "VsCodeCliManager"

        // Target platform identifiers
        const val TARGET_PLATFORM = "linux-arm64"
        const val CLI_TARGET = "cli-linux-arm64"

        // Official Microsoft VS Code CLI standalone Linux ARM64 download endpoints
        const val CLI_DOWNLOAD_URL = "https://update.code.visualstudio.com/latest/cli-linux-arm64/stable"
        const val CLI_FALLBACK_URL = "https://code.visualstudio.com/sha/download?build=stable&os=cli-linux-arm64"

        // Archive names
        const val ARCHIVE_NAME = "vscode_cli_linux_arm64.tar.gz"
        const val LEGACY_ARCHIVE_NAME = "vscode_cli_alpine_arm64.tar.gz"

        // Guest executable path inside rootfs
        const val GUEST_BIN_PATH = "/usr/local/bin/code"

        // Persistent AVSCode-managed state layout under /home/user/.avscode/
        const val GUEST_BASE_DIR = "/home/user/.avscode"
        const val GUEST_CLI_DIR = "/home/user/.avscode/cli"
        const val GUEST_SERVER_DIR = "/home/user/.avscode/server"
        const val GUEST_USER_DATA_DIR = "/home/user/.avscode/user-data"
        const val GUEST_EXTENSIONS_DIR = "/home/user/.avscode/extensions"
        const val GUEST_LOGS_DIR = "/home/user/.avscode/logs"

        // Legacy compatibility alias
        const val GUEST_DATA_DIR = GUEST_CLI_DIR
        const val LEGACY_DATA_DIR = "/home/user/.vscode-cli"

        // Workspace directory inside guest
        const val GUEST_PROJECTS_DIR = "/home/user/projects"

        // Minimum Microsoft VS Code Server requirements
        const val MIN_GLIBC_VERSION = "2.28"
        const val MIN_LIBSTDCXX_SYMBOL = "GLIBCXX_3.4.25"
        const val REQUIRED_ARCH = "aarch64"
        const val REQUIRED_BITNESS = 64

        // Default local host binding & persistent port (preserves origin localStorage/cookies)
        const val DEFAULT_SERVER_HOST = "127.0.0.1"
        const val DEFAULT_PREFERRED_PORT = 33000

        // Timeouts & intervals
        const val STARTUP_TIMEOUT_MS = 60_000L
        const val PROBE_INTERVAL_MS = 500L

        /**
         * Resolves the official VS Code CLI target platform.
         * Explicitly enforces standard Linux ARM64 (glibc) for Ubuntu/Debian ARM64.
         * Fails fast if Alpine/musl is passed, preventing silent fallback.
         */
        fun selectTargetPlatform(runtime: GuestRuntimeInfo): String {
            if (runtime.libc.equals("musl", ignoreCase = true) ||
                runtime.distro.contains("Alpine", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "Alpine/musl runtime detected (${runtime.distro}). " +
                            "AVSCode requires standard Linux ARM64 with glibc (Ubuntu/Debian). Alpine fallback is not supported."
                )
            }

            if (!runtime.arch.equals(REQUIRED_ARCH, ignoreCase = true) &&
                !runtime.arch.equals("arm64", ignoreCase = true)
            ) {
                throw IllegalStateException(
                    "Unsupported architecture: '${runtime.arch}'. Required: $REQUIRED_ARCH (64-bit ARM)."
                )
            }

            return CLI_TARGET
        }

        /**
         * Compares two semantic version strings (e.g. "2.43" vs "2.28").
         * Returns true if [installed] is greater than or equal to [required].
         */
        fun isGlibcVersionSupported(installed: String, required: String = MIN_GLIBC_VERSION): Boolean {
            val instParts = installed.split('.').mapNotNull { it.toIntOrNull() }
            val reqParts = required.split('.').mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(instParts.size, reqParts.size)
            for (i in 0 until maxLen) {
                val instVal = instParts.getOrElse(i) { 0 }
                val reqVal = reqParts.getOrElse(i) { 0 }
                if (instVal > reqVal) return true
                if (instVal < reqVal) return false
            }
            return true
        }

        /**
         * Finds an available TCP port on local loopback.
         * Prefers [preferredPort] to preserve web origin storage across restarts.
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
         * Decouples transport endpoint (port) from workspace identity and persists state
         * under the clean `/home/user/.avscode/` directory tree.
         */
        fun buildServerCommand(
            serverPort: Int,
            host: String = DEFAULT_SERVER_HOST,
            cliBinPath: String = GUEST_BIN_PATH,
            cliDataDir: String = GUEST_CLI_DIR,
            serverDataDir: String = GUEST_SERVER_DIR,
            workspaceDir: String = GUEST_PROJECTS_DIR
        ): String {
            return "$cliBinPath serve-web " +
                    "--host $host " +
                    "--port $serverPort " +
                    "--without-connection-token " +
                    "--accept-server-license-terms " +
                    "--cli-data-dir $cliDataDir " +
                    "--server-data-dir $serverDataDir " +
                    "--default-folder $workspaceDir"
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

        /**
         * Inspects binary header bytes to verify standard 64-bit ELF ARM aarch64 format.
         */
        fun verifyElfAarch64(file: File): Boolean {
            if (!file.exists() || file.length() < 52) return false
            try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(52)
                    val read = fis.read(header)
                    if (read < 52) return false

                    // Magic: 0x7F 'E' 'L' 'F'
                    if (header[0] != 0x7F.toByte() ||
                        header[1] != 'E'.code.toByte() ||
                        header[2] != 'L'.code.toByte() ||
                        header[3] != 'F'.code.toByte()
                    ) {
                        return false
                    }

                    // Class: 2 = 64-bit
                    if (header[4] != 2.toByte()) return false

                    // Data: 1 = 2's complement, little endian
                    if (header[5] != 1.toByte()) return false

                    // Machine: e_machine at offset 18-19 (0x00B7 = 183 = EM_AARCH64)
                    val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
                    return eMachine == 0x00B7
                }
            } catch (e: Exception) {
                return false
            }
        }

        /**
         * Determines the primary failure cause according to Phase 40 diagnostics.
         */
        fun determineFailureCause(
            runtime: GuestRuntimeInfo?,
            exitCode: Int,
            serverPort: Int,
            logOutput: String
        ): String {
            return when {
                runtime == null -> "invalid environment (failed to query Linux guest environment)"
                !runtime.arch.equals(REQUIRED_ARCH, ignoreCase = true) && !runtime.arch.equals("arm64", ignoreCase = true) ->
                    "wrong architecture: ${runtime.arch} (expected aarch64)"
                runtime.libc.isBlank() -> "missing libc"
                runtime.libc.equals("musl", ignoreCase = true) ->
                    "unsupported libc: musl (AVSCode requires glibc)"
                !isGlibcVersionSupported(runtime.libcVersion, MIN_GLIBC_VERSION) ->
                    "unsupported glibc version: ${runtime.libcVersion} (required >= $MIN_GLIBC_VERSION)"
                logOutput.contains("libstdc++.so.6", ignoreCase = true) &&
                        (logOutput.contains("cannot open shared object file", ignoreCase = true) || logOutput.contains("No such file", ignoreCase = true)) ->
                    "missing libstdc++"
                logOutput.contains("GLIBCXX_", ignoreCase = true) && logOutput.contains("not found", ignoreCase = true) ->
                    "missing libstdc++ (GLIBCXX symbol missing)"
                logOutput.contains("cannot execute binary file: Exec format error", ignoreCase = true) ->
                    "wrong artifact"
                logOutput.contains("command not found", ignoreCase = true) ||
                        (logOutput.contains("No such file or directory", ignoreCase = true) && !logOutput.contains("code")) ->
                    "missing dependency"
                logOutput.contains("Address already in use", ignoreCase = true) || logOutput.contains("EADDRINUSE", ignoreCase = true) ->
                    "port conflict (Port $serverPort is already bound)"
                logOutput.contains("Permission denied", ignoreCase = true) || logOutput.contains("EACCES", ignoreCase = true) ->
                    "permissions (Permissions error inside guest rootfs)"
                logOutput.contains("workspace", ignoreCase = true) && (logOutput.contains("invalid", ignoreCase = true) || logOutput.contains("does not exist", ignoreCase = true)) ->
                    "workspace (invalid or inaccessible workspace)"
                logOutput.contains("ExtensionHost", ignoreCase = true) && (logOutput.contains("crashed", ignoreCase = true) || logOutput.contains("terminated unexpectedly", ignoreCase = true)) ->
                    "extension failure"
                logOutput.contains("ERR_CONNECTION_REFUSED", ignoreCase = true) || logOutput.contains("WebView connection", ignoreCase = true) ->
                    "WebView connection"
                else -> "server state (exit code $exitCode)"
            }
        }
    }

    private val paths = AppPaths.getInstance(context)
    private var serverPid: Int? = null
    private var serverPort: Int? = null
    private var activeServerUrl: String? = null
    private var lastRuntimeInfo: GuestRuntimeInfo? = null

    /**
     * Check if Microsoft VS Code CLI binary is present in guest rootfs.
     */
    fun isInstalled(): Boolean {
        val bin = File(paths.rootfsDir, "usr/local/bin/code")
        val altBin = File(paths.rootfsDir, "home/user/.avscode/cli/code")
        return bin.exists() || altBin.exists()
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
     * Detects guest Linux runtime properties inside the PRoot environment.
     */
    suspend fun detectGuestRuntime(): Result<GuestRuntimeInfo> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val cmd = """
                OS="${'$'}(uname -s)"
                ARCH="${'$'}(uname -m)"
                BIT="${'$'}(getconf LONG_BIT 2>/dev/null || echo 64)"
                KERNEL="${'$'}(uname -r)"
                DISTRO="Unknown"
                DISTRO_VER="Unknown"
                if [ -f /etc/os-release ]; then
                    . /etc/os-release
                    DISTRO="${'$'}{NAME:-${'$'}{ID:-Unknown}}"
                    DISTRO_VER="${'$'}{VERSION_ID:-Unknown}"
                fi
                LIBC="glibc"
                LIBC_VER="0.0"
                if ldd --version 2>&1 | grep -qi 'musl'; then
                    LIBC="musl"
                    LIBC_VER="${'$'}(ldd --version 2>&1 | head -n 1 | grep -oE '[0-9]+\.[0-9]+' | head -n 1)"
                elif ldd --version 2>&1 | grep -qiE 'glibc|gnu'; then
                    LIBC="glibc"
                    LIBC_VER="${'$'}(getconf GNU_LIBC_VERSION 2>/dev/null | grep -oE '[0-9]+\.[0-9]+' | head -n 1 || true)"
                    if [ -z "${'$'}LIBC_VER" ]; then
                        LIBC_VER="${'$'}(ldd --version 2>&1 | head -n 1 | grep -oE '[0-9]+\.[0-9]+' | head -n 1)"
                    fi
                fi
                echo "OS=${'$'}OS;ARCH=${'$'}ARCH;BIT=${'$'}BIT;KERNEL=${'$'}KERNEL;DISTRO=${'$'}DISTRO;DISTRO_VER=${'$'}DISTRO_VER;LIBC=${'$'}LIBC;LIBC_VER=${'$'}LIBC_VER"
            """.trimIndent()

            val raw = linuxRuntime.execute(cmd).getOrThrow().trim()
            val map = raw.lines().lastOrNull { it.contains("OS=") }
                ?.split(';')
                ?.mapNotNull {
                    val p = it.split('=', limit = 2)
                    if (p.size == 2) p[0].trim() to p[1].trim() else null
                }?.toMap() ?: emptyMap()

            val info = GuestRuntimeInfo(
                os = map["OS"] ?: "Linux",
                distro = map["DISTRO"] ?: "Ubuntu",
                distroVersion = map["DISTRO_VER"] ?: "Unknown",
                arch = map["ARCH"] ?: "aarch64",
                bitness = map["BIT"]?.toIntOrNull() ?: 64,
                libc = map["LIBC"] ?: "glibc",
                libcVersion = map["LIBC_VER"]?.takeIf { it.isNotBlank() && it != "0.0" } ?: "0.0",
                kernelVersion = map["KERNEL"] ?: "Unknown"
            )
            lastRuntimeInfo = info
            info
        }
    }

    /**
     * Validates that the guest satisfies all Microsoft VS Code server requirements.
     * Generates a detailed diagnostic report conforming to Phase 13.
     */
    suspend fun validateGuestRuntime(): Result<DependencyValidationReport> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val runtime = detectGuestRuntime().getOrThrow()

            // 1. Target platform validation (must be glibc Linux ARM64)
            selectTargetPlatform(runtime)

            // 2. Validate glibc version
            val glibcValid = runtime.libc.equals("glibc", ignoreCase = true) &&
                    isGlibcVersionSupported(runtime.libcVersion, MIN_GLIBC_VERSION)

            // 3. Validate libstdc++ and GLIBCXX symbol
            val libstdcxxCmd = """
                LIB_PATH=""
                for p in /usr/lib/aarch64-linux-gnu/libstdc++.so.6 /usr/lib/libstdc++.so.6 /lib/aarch64-linux-gnu/libstdc++.so.6; do
                    if [ -f "${'$'}p" ]; then LIB_PATH="${'$'}p"; break; fi
                done
                if [ -z "${'$'}LIB_PATH" ]; then
                    LIB_PATH="${'$'}(ldconfig -p 2>/dev/null | grep libstdc++.so.6 | awk '{print ${'$'}NF}' | head -n 1 || true)"
                fi
                if [ -n "${'$'}LIB_PATH" ] && [ -f "${'$'}LIB_PATH" ]; then
                    if grep -a '$MIN_LIBSTDCXX_SYMBOL' "${'$'}LIB_PATH" >/dev/null 2>&1 || (strings "${'$'}LIB_PATH" 2>/dev/null | grep -q '$MIN_LIBSTDCXX_SYMBOL'); then
                        echo "OK"
                    else
                        echo "NOSYMBOL"
                    fi
                else
                    echo "MISSING"
                fi
            """.trimIndent()

            val symbolCheckRes = linuxRuntime.execute(libstdcxxCmd).getOrNull()?.trim() ?: "FAIL"
            val glibcxxSymbolValid = symbolCheckRes == "OK"
            val libstdcxxFile = File(paths.rootfsDir, "usr/lib/aarch64-linux-gnu/libstdc++.so.6")
            val libstdcxxValid = libstdcxxFile.exists() || glibcxxSymbolValid || symbolCheckRes != "MISSING"

            // 4. Validate required tools
            val requiredCommands = listOf(
                "bash",
                "tar",
                "gzip",
                "curl",
                "wget",
                "git",
            
                // procps
                "ps",
                "pgrep",
            
                // coreutils
                "mkdir",
                "cp",
                "mv",
                "rm",
                "ls",
            
                // findutils
                "find",
                "xargs",
            
                "grep",
                "sed",
                "unzip",
                "zip",
                "xz"
            )
            
            val missingTools = mutableListOf<String>()
            
            for (command in requiredCommands) {
                val check = linuxRuntime.execute(
                    "command -v $command >/dev/null 2>&1 && echo 0 || echo 1"
                )
            
                if (check.getOrNull()?.trim() != "0") {
                    missingTools.add(command)
                }
            }

            // 5. Query tool versions
            val toolVersions = mutableMapOf<String, String>()
            linuxRuntime.execute("git --version 2>/dev/null").getOrNull()?.trim()?.let {
                toolVersions["git"] = it
            }
            linuxRuntime.execute("node --version 2>/dev/null").getOrNull()?.trim()?.let {
                toolVersions["node"] = it
            }
            linuxRuntime.execute("npm --version 2>/dev/null").getOrNull()?.trim()?.let {
                toolVersions["npm"] = it
            }
            linuxRuntime.execute("code --version 2>/dev/null").getOrNull()?.lines()?.firstOrNull()?.let {
                toolVersions["code"] = it.trim()
            }

            val isSatisfied = glibcValid && libstdcxxValid && glibcxxSymbolValid && missingTools.isEmpty()

            val report = DependencyValidationReport(
                runtimeInfo = runtime,
                glibcValid = glibcValid,
                glibcInstalled = runtime.libcVersion,
                glibcRequired = MIN_GLIBC_VERSION,
                libstdcxxValid = libstdcxxValid,
                glibcxxSymbolValid = glibcxxSymbolValid,
                missingPackages = emptyList(),
                missingTools = missingTools,
                toolVersions = toolVersions,
                isSatisfied = isSatisfied
            )

            AvsLogger.i(TAG, "\n" + report.formatReport())

            if (!glibcValid) {
                throw IllegalStateException(
                    "glibc requirement not satisfied!\n" +
                            "Installed glibc: ${runtime.libcVersion}\n" +
                            "Required glibc: $MIN_GLIBC_VERSION"
                )
            }
            if (!libstdcxxValid) {
                throw IllegalStateException("libstdc++6 is missing or not installed in guest!")
            }
            if (!glibcxxSymbolValid) {
                throw IllegalStateException("libstdc++ does not satisfy required symbol $MIN_LIBSTDCXX_SYMBOL!")
            }
            if (missingTools.isNotEmpty()) {
                throw IllegalStateException(
                    "Missing required system dependencies:\n" +
                            missingTools.joinToString(", ")
                )
            }

            report
        }
    }

    /**
     * Idempotently ensures all required system dependencies are installed via APT.
     */
    suspend fun ensureSystemDependencies(
        progressCallback: ((String) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val requiredPackages = listOf(
                "libc6", "libstdc++6", "ca-certificates", "tar", "gzip",
                "bash", "curl", "wget", "git", "openssh-client",
                "unzip", "zip", "xz-utils", "procps", "coreutils", "findutils",
                "grep", "sed", "mawk"
            )

            // Check which packages are missing
            val missingQuery = requiredPackages.joinToString(" ")
            val checkCmd = "dpkg-query -W -f='\${Package} \${Status}\\n' $missingQuery 2>/dev/null || true"
            val checkRes = linuxRuntime.execute(checkCmd).getOrNull() ?: ""

            val installed = checkRes.lines()
                .filter { it.contains("install ok installed") }
                .map { it.substringBefore(' ').trim() }
                .toSet()

            val missing = requiredPackages.filter { it !in installed }

            if (missing.isEmpty()) {
                progressCallback?.invoke("[Dependencies] All base packages already installed.")
                return@runCatchingResult Unit
            }

            progressCallback?.invoke("[Dependencies] Missing packages: ${missing.joinToString(", ")}. Installing via APT...")
            val installCmd = "export DEBIAN_FRONTEND=noninteractive && " +
                    "apt-get update -qq && " +
                    "apt-get install -y --no-install-recommends ${missing.joinToString(" ")} && " +
                    "apt-get clean"

            val exit = linuxRuntime.executeStreaming(installCmd) { line ->
                progressCallback?.invoke("[APT] $line")
            }.getOrThrow()

            if (exit != 0) {
                throw RuntimeException("APT installation failed with exit code $exit")
            }

            progressCallback?.invoke("[Dependencies] Package installation completed.")
            Unit
        }
    }

    /**
     * Non-destructively migrates an existing Alpine installation to the standard Linux ARM64 layout.
     * Preserves projects, user-data, and extensions. Only cleans obsolete Alpine caches after verification.
     */
    suspend fun migrateFromAlpineIfNeeded(
        onLog: ((String) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingResult {
            val legacyCliDir = File(paths.rootfsDir, "home/user/.vscode-cli")
            val newCliDir = File(paths.rootfsDir, "home/user/.avscode/cli")
            val legacyArchive = File(paths.cacheDir, LEGACY_ARCHIVE_NAME)

            // Check if legacy Alpine markers exist
            val hasLegacyData = legacyCliDir.exists()
            val hasLegacyArchive = legacyArchive.exists()

            if (!hasLegacyData && !hasLegacyArchive) {
                return@runCatchingResult Unit
            }

            onLog?.invoke("[Migration] Existing Alpine runtime detected. Preparing non-destructive migration...")
            AvsLogger.i(TAG, "Migrating from legacy Alpine setup to Linux ARM64/glibc layout")

            // Ensure new directory hierarchy exists
            linuxRuntime.execute(
                "mkdir -p $GUEST_BASE_DIR $GUEST_CLI_DIR $GUEST_SERVER_DIR " +
                        "$GUEST_USER_DATA_DIR $GUEST_EXTENSIONS_DIR $GUEST_LOGS_DIR $GUEST_PROJECTS_DIR"
            )

            // Non-destructive copy of any legacy extensions if present
            val legacyExtDir = File(legacyCliDir, "data/extensions")
            if (legacyExtDir.exists() && legacyExtDir.isDirectory) {
                onLog?.invoke("[Migration] Preserving existing VS Code extensions...")
                linuxRuntime.execute(
                    "if [ -d $LEGACY_DATA_DIR/data/extensions ]; then " +
                            "cp -rn $LEGACY_DATA_DIR/data/extensions/* $GUEST_EXTENSIONS_DIR/ 2>/dev/null || true; " +
                            "fi"
                )
            }

            // Non-destructive copy of any legacy user-data if present
            val legacyUserDataDir = File(legacyCliDir, "data/user-data")
            if (legacyUserDataDir.exists() && legacyUserDataDir.isDirectory) {
                onLog?.invoke("[Migration] Preserving existing VS Code user data...")
                linuxRuntime.execute(
                    "if [ -d $LEGACY_DATA_DIR/data/user-data ]; then " +
                            "cp -rn $LEGACY_DATA_DIR/data/user-data/* $GUEST_USER_DATA_DIR/ 2>/dev/null || true; " +
                            "fi"
                )
            }

            onLog?.invoke("[Migration] Alpine migration prepared. Obsolete caches will be cleaned after new runtime validates.")
            Unit
        }
    }

    /**
     * Cleans obsolete Alpine artifacts only after new Linux ARM64 server has successfully started.
     */
    suspend fun cleanupObsoleteAlpineRuntime() = withContext(Dispatchers.IO) {
        try {
            val legacyArchive = File(paths.cacheDir, LEGACY_ARCHIVE_NAME)
            if (legacyArchive.exists()) {
                legacyArchive.delete()
            }
            val guestTmpArchive = File(paths.hostGuestTmpDir, "vscode_cli.tar.gz")
            if (guestTmpArchive.exists()) {
                guestTmpArchive.delete()
            }
            AvsLogger.i(TAG, "Cleaned up obsolete Alpine runtime artifacts.")
        } catch (e: Exception) {
            AvsLogger.d(TAG, "Non-critical error cleaning obsolete runtime: ${e.message}")
        }
    }

    /**
     * Installs the official Microsoft VS Code Linux ARM64 CLI inside Ubuntu userspace.
     */
    suspend fun install(progressCallback: ((Float, String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting Microsoft VS Code Linux ARM64 CLI installation")

        runCatchingResult {
            // 1. Detect guest environment and validate target platform prerequisites first
            progressCallback?.invoke(0.05f, "Validating Linux ARM64 / glibc runtime...")
            val runtime = detectGuestRuntime().getOrThrow()
            selectTargetPlatform(runtime)
            if (!isGlibcVersionSupported(runtime.libcVersion, MIN_GLIBC_VERSION)) {
                throw IllegalStateException(
                    "glibc requirement not satisfied!\n" +
                            "Installed glibc: ${runtime.libcVersion}\n" +
                            "Required glibc: $MIN_GLIBC_VERSION"
                )
            }

            // 2. Ensure system packages are present via APT (idempotent)
            progressCallback?.invoke(0.10f, "Ensuring Linux dependencies...")
            ensureSystemDependencies { status ->
                progressCallback?.invoke(0.15f, status)
            }.getOrThrow()

            // 3. Validate all guest dependencies and tools post-installation
            progressCallback?.invoke(0.20f, "Validating guest dependencies...")
            val validation = validateGuestRuntime().getOrThrow()
            if (!validation.isSatisfied) {
                throw IllegalStateException("Guest environment does not satisfy VS Code requirements:\n${validation.formatReport()}")
            }

            // 4. Prepare non-destructive migration from Alpine if applicable
            migrateFromAlpineIfNeeded { line ->
                progressCallback?.invoke(0.25f, line)
            }.getOrThrow()

            // Check if already installed and valid
            if (isInstalled()) {
                val verify = verifyCliInstallation()
                if (verify.isSuccess) {
                    val ver = verify.getOrNull()?.trim()
                    AvsLogger.i(TAG, "VS Code CLI already installed and verified: $ver")
                    progressCallback?.invoke(1.0f, "Microsoft VS Code Linux ARM64 CLI ready ($ver)")
                    return@runCatchingResult Unit
                }
                AvsLogger.w(TAG, "Existing CLI failed verification, reinstalling: ${verify.exceptionOrNull()?.message}")
            }

            // 5. Download Microsoft VS Code Linux ARM64 CLI tarball
            val downloadArchive = File(paths.cacheDir, ARCHIVE_NAME)
            if (!downloadArchive.exists() || downloadArchive.length() < 5 * 1024 * 1024) {
                progressCallback?.invoke(0.30f, "Downloading Microsoft VS Code CLI (Linux ARM64)...")
                downloadCliArchive(downloadArchive) { p ->
                    progressCallback?.invoke(0.30f + p * 0.35f, "Downloading VS Code CLI (${(p * 100).toInt()}%)...")
                }
            }

            // 6. Stage CLI archive in guest /tmp
            progressCallback?.invoke(0.65f, "Staging Linux ARM64 CLI archive in guest /tmp...")
            val guestTmpDir = paths.hostGuestTmpDir
            if (!guestTmpDir.exists()) {
                guestTmpDir.mkdirs()
            }
            val guestTmpArchive = File(guestTmpDir, "vscode_cli.tar.gz")
            downloadArchive.copyTo(guestTmpArchive, overwrite = true)

            // 7. Verify archive integrity inside guest
            progressCallback?.invoke(0.70f, "Verifying archive integrity...")
            val testExit = linuxRuntime.execute("tar -tzf /tmp/vscode_cli.tar.gz >/dev/null 2>&1 && echo 0 || echo 1")
            if (testExit.getOrNull()?.trim() != "0") {
                downloadArchive.delete()
                guestTmpArchive.delete()
                throw RuntimeException("Downloaded VS Code CLI archive is corrupt or invalid")
            }

            // 8. Extract into persistent AVSCode directory and set up /usr/local/bin/code
            progressCallback?.invoke(0.75f, "Extracting VS Code CLI inside Linux userspace...")
            val extractCmd = "mkdir -p /usr/local/bin $GUEST_BASE_DIR $GUEST_CLI_DIR $GUEST_SERVER_DIR " +
                    "$GUEST_USER_DATA_DIR $GUEST_EXTENSIONS_DIR $GUEST_LOGS_DIR $GUEST_PROJECTS_DIR && " +
                    "tar -xzf /tmp/vscode_cli.tar.gz -C $GUEST_CLI_DIR code && " +
                    "chmod 755 $GUEST_CLI_DIR/code && " +
                    "cp -f $GUEST_CLI_DIR/code $GUEST_BIN_PATH && " +
                    "chmod 755 $GUEST_BIN_PATH && " +
                    "rm -f /tmp/vscode_cli.tar.gz"

            val extractExit = linuxRuntime.executeStreaming(extractCmd) { line ->
                progressCallback?.invoke(0.80f, line)
            }.getOrThrow()

            if (extractExit != 0) {
                throw RuntimeException("Extraction of VS Code CLI failed with exit code $extractExit")
            }

            // 9. Verify binary architecture via ELF header inspection
            progressCallback?.invoke(0.85f, "Validating ELF AArch64 executable...")
            val installedBin = File(paths.rootfsDir, "usr/local/bin/code")
            if (!verifyElfAarch64(installedBin)) {
                throw RuntimeException("Extracted binary is not a valid 64-bit ELF ARM aarch64 executable!")
            }

            // 10. Validate CLI inside Linux userspace
            progressCallback?.invoke(0.90f, "Verifying VS Code CLI inside Linux...")
            val verify = verifyCliInstallation().getOrThrow()
            AvsLogger.i(TAG, "Microsoft VS Code Linux ARM64 CLI verified inside Linux:\n$verify")

            // 11. Cleanup downloaded host archive
            if (downloadArchive.exists()) downloadArchive.delete()
            if (guestTmpArchive.exists()) guestTmpArchive.delete()

            progressCallback?.invoke(1.0f, "Microsoft VS Code Linux ARM64 CLI installed successfully")
            AvsLogger.i(TAG, "Microsoft VS Code Linux ARM64 CLI installation completed successfully")
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
        val cmd = "code --install-extension $guestVsixPath " +
                "--cli-data-dir $GUEST_CLI_DIR " +
                "--extensions-dir $GUEST_EXTENSIONS_DIR"
        linuxRuntime.executeStreaming(cmd) { line ->
            AvsLogger.d(TAG, "[Extension Install] $line")
        }
    }

    /**
     * Lists currently installed extensions in the persistent extensions directory.
     */
    suspend fun listExtensions(): Result<List<String>> = withContext(Dispatchers.IO) {
        val cmd = "code --list-extensions --extensions-dir $GUEST_EXTENSIONS_DIR 2>/dev/null"
        val res = linuxRuntime.execute(cmd)
        if (res.isSuccess) {
            val list = res.getOrNull()?.lines()?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            Result.Success(list)
        } else {
            Result.Failure(res.exceptionOrNull() ?: RuntimeException("Failed to list extensions"))
        }
    }

    /**
     * Downloads the official Microsoft Linux ARM64 standalone CLI archive.
     * Tries primary endpoint, falling back if needed.
     */
    private suspend fun downloadCliArchive(target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(CLI_DOWNLOAD_URL, CLI_FALLBACK_URL)
        var lastException: Exception? = null

        for (downloadUrl in urlsToTry) {
            try {
                AvsLogger.i(TAG, "Downloading Linux ARM64 VS Code CLI from $downloadUrl")
                var currentUrl = downloadUrl
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
                    AvsLogger.i(TAG, "Linux ARM64 VS Code CLI download finished (${downloaded / (1024 * 1024)}MB)")
                    return@withContext
                }
                throw RuntimeException("Too many redirects downloading VS Code CLI from $downloadUrl")
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Download from $downloadUrl failed: ${e.message}")
                lastException = e
            }
        }

        throw RuntimeException("Failed to download VS Code CLI after trying endpoints: ${lastException?.message}", lastException)
    }

    /**
     * Starts the local Microsoft VS Code Server (`code serve-web`) inside Linux userspace.
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

            AvsLogger.i(TAG, "Starting local VS Code Server on 127.0.0.1:$selectedPort (Linux ARM64 / glibc)")
            onLog?.invoke("[VS Code] Starting local server on 127.0.0.1:$selectedPort (glibc ARM64)...")

            // Ensure workspace and persistent state directories exist
            linuxRuntime.execute(
                "mkdir -p $GUEST_PROJECTS_DIR $GUEST_BASE_DIR $GUEST_CLI_DIR " +
                        "$GUEST_SERVER_DIR $GUEST_USER_DATA_DIR $GUEST_EXTENSIONS_DIR $GUEST_LOGS_DIR"
            )

            // Resolve canonical realpath for workspace identity
            val realpathRes = linuxRuntime.execute("realpath $GUEST_PROJECTS_DIR 2>/dev/null || echo $GUEST_PROJECTS_DIR")
            val canonicalWorkspace = realpathRes.getOrNull()?.trim().takeUnless { it.isNullOrEmpty() } ?: GUEST_PROJECTS_DIR

            val logFile = paths.serverLogFile
            if (logFile.exists()) logFile.delete()
            logFile.parentFile?.mkdirs()

            val guestServerCmd = buildServerCommand(
                serverPort = selectedPort,
                host = DEFAULT_SERVER_HOST,
                cliBinPath = GUEST_BIN_PATH,
                cliDataDir = GUEST_CLI_DIR,
                serverDataDir = GUEST_SERVER_DIR,
                workspaceDir = canonicalWorkspace
            )

            val args = linuxRuntime.buildPRootArgs(guestServerCmd, canonicalWorkspace)
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

            val readyUrl = waitForServerReady(selectedPort, logFile, canonicalWorkspace, guestServerCmd, onLog)
            activeServerUrl = readyUrl
            onServerReady?.invoke(readyUrl)

            // After successful server start, safely cleanup obsolete Alpine runtime files
            cleanupObsoleteAlpineRuntime()

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
        workspacePath: String,
        serverCmd: String,
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

            // Check if process died prematurely
            if (exitStatus != -2) {
                val logs = if (logFile.exists()) logFile.readText() else "No logs"
                val diagnostic = buildFailureDiagnosticReport(
                    exitCode = exitStatus,
                    serverPort = serverPort,
                    serverCmd = serverCmd,
                    logOutput = logs
                )
                AvsLogger.e(TAG, "Server died prematurely:\n$diagnostic")
                throw RuntimeException("Local VS Code Server exited prematurely with code $exitStatus:\n$diagnostic")
            }

            // Probe HTTP endpoint
            if (checkHttpReachable(serverPort)) {
                return "http://$DEFAULT_SERVER_HOST:$serverPort/?folder=$workspacePath"
            }

            delay(PROBE_INTERVAL_MS)
        }

        val logs = if (logFile.exists()) logFile.readText().takeLast(2000) else "No logs"
        val diagnostic = buildFailureDiagnosticReport(
            exitCode = -1,
            serverPort = serverPort,
            serverCmd = serverCmd,
            logOutput = logs
        )
        throw RuntimeException("Timed out waiting for local VS Code Server on port $serverPort:\n$diagnostic")
    }

    /**
     * Builds comprehensive failure diagnostics conforming to Phase 40.
     */
    suspend fun buildFailureDiagnosticReport(
        exitCode: Int,
        serverPort: Int,
        serverCmd: String,
        logOutput: String
    ): String {
        val runtime = lastRuntimeInfo ?: detectGuestRuntime().getOrNull()
        val vsCodeVer = getCliVersion() ?: "Unknown"

        // Query libstdc++ version inside guest
        val libstdcxxVer = runCatching {
            val cmd = "readlink -f /usr/lib/aarch64-linux-gnu/libstdc++.so.6 2>/dev/null || true"
            val res = linuxRuntime.execute(cmd).getOrNull()?.trim()
            if (!res.isNullOrEmpty()) {
                File(res).name.removePrefix("libstdc++.so.")
            } else "Unknown"
        }.getOrDefault("Unknown")

        val pathEnv = linuxRuntime.execute("echo \"${'$'}PATH\" 2>/dev/null").getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        val homeEnv = linuxRuntime.execute("echo \"${'$'}HOME\" 2>/dev/null").getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "/home/user"

        val failureCause = determineFailureCause(runtime, exitCode, serverPort, logOutput)

        return """
            ==================================================
            AVSCode Server Failure Diagnostic Report
            ==================================================
            Primary Cause: $failureCause
            Exit Code: $exitCode
            VS Code Version: $vsCodeVer
            Selected Artifact: $CLI_TARGET ($ARCHIVE_NAME)
            Target Platform: $TARGET_PLATFORM
            Architecture: ${runtime?.arch ?: "Unknown"} (${runtime?.bitness ?: 64}-bit)
            Distribution: ${runtime?.distro ?: "Unknown"} ${runtime?.distroVersion ?: ""}
            Kernel Version: ${runtime?.kernelVersion ?: "Unknown"}
            libc Implementation: ${runtime?.libc ?: "Unknown"}
            libc Version: ${runtime?.libcVersion ?: "Unknown"} (Minimum required: $MIN_GLIBC_VERSION)
            libstdc++ Version: $libstdcxxVer
            PATH: $pathEnv
            HOME: $homeEnv
            CLI Directory: $GUEST_CLI_DIR
            Server Directory: $GUEST_SERVER_DIR
            User Data Directory: $GUEST_USER_DATA_DIR
            Extension Directory: $GUEST_EXTENSIONS_DIR
            Workspace Directory: $GUEST_PROJECTS_DIR
            Server Port: $serverPort
            Server Log Path: ${paths.serverLogFile.absolutePath}
            Complete serve-web Arguments: $serverCmd
            Relevant Stderr / Server Log Output:
            $logOutput
            ==================================================
        """.trimIndent()
    }

    /**
     * Collects comprehensive runtime diagnostics conforming to Phase 33.
     */
    suspend fun collectRuntimeDiagnostics(): RuntimeDiagnosticsReport = withContext(Dispatchers.IO) {
        val runtime = lastRuntimeInfo ?: detectGuestRuntime().getOrNull() ?: GuestRuntimeInfo(
            os = "Linux",
            distro = "Unknown",
            distroVersion = "Unknown",
            arch = "Unknown",
            bitness = 64,
            libc = "glibc",
            libcVersion = "Unknown",
            kernelVersion = "Unknown"
        )

        val libQueryCmd = """
            LIB_PATH=""
            for p in /usr/lib/aarch64-linux-gnu/libstdc++.so.6 /usr/lib/libstdc++.so.6 /lib/aarch64-linux-gnu/libstdc++.so.6; do
                if [ -f "${'$'}p" ]; then LIB_PATH="${'$'}p"; break; fi
            done
            REAL_LIB="${'$'}(readlink -f "${'$'}LIB_PATH" 2>/dev/null || echo "${'$'}LIB_PATH")"
            REAL_NAME="${'$'}(basename "${'$'}REAL_LIB")"
            VER="${'$'}(echo "${'$'}REAL_NAME" | sed 's/libstdc++\.so\.//')"
            MAX_GLIBCXX="${'$'}(grep -aoE 'GLIBCXX_3\.4\.[0-9]+' "${'$'}LIB_PATH" 2>/dev/null | sort -V | tail -n 1 || true)"
            echo "VER=${'$'}VER;GLIBCXX=${'$'}MAX_GLIBCXX"
        """.trimIndent()

        val libInfo = linuxRuntime.execute(libQueryCmd).getOrNull()?.trim() ?: ""
        val libVer = libInfo.substringAfter("VER=", "").substringBefore(";").takeIf { it.isNotBlank() } ?: "Unknown"
        val glibcxxVer = libInfo.substringAfter("GLIBCXX=", "").takeIf { it.isNotBlank() } ?: MIN_LIBSTDCXX_SYMBOL

        val nodeVer = linuxRuntime.execute("node --version 2>/dev/null").getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "not installed"
        val gitVer = linuxRuntime.execute("git --version 2>/dev/null").getOrNull()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "not installed"
        val vsCodeVer = getCliVersion() ?: "not installed"

        RuntimeDiagnosticsReport(
            distribution = runtime.distro,
            release = runtime.distroVersion,
            architecture = runtime.arch,
            bitness = runtime.bitness,
            kernel = runtime.kernelVersion,
            libc = runtime.libc,
            glibcVersion = runtime.libcVersion,
            libstdcxxVersion = libVer,
            glibcxxVersion = glibcxxVer,
            nodeVersion = nodeVer,
            gitVersion = gitVer,
            vsCodeVersion = vsCodeVer,
            vsCodeTarget = CLI_TARGET,
            vsCodeCliDirectory = GUEST_CLI_DIR,
            vsCodeServerDirectory = GUEST_SERVER_DIR,
            vsCodeUserDataDirectory = GUEST_USER_DATA_DIR,
            vsCodeExtensionDirectory = GUEST_EXTENSIONS_DIR,
            workspaceDirectory = GUEST_PROJECTS_DIR,
            serverPort = serverPort
        )
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
            serverUrl = activeServerUrl,
            runtimeInfo = lastRuntimeInfo
        )
    }
}

data class GuestRuntimeInfo(
    val os: String,
    val distro: String,
    val distroVersion: String,
    val arch: String,
    val bitness: Int,
    val libc: String,
    val libcVersion: String,
    val kernelVersion: String
)

data class DependencyValidationReport(
    val runtimeInfo: GuestRuntimeInfo,
    val glibcValid: Boolean,
    val glibcInstalled: String,
    val glibcRequired: String,
    val libstdcxxValid: Boolean,
    val glibcxxSymbolValid: Boolean,
    val missingPackages: List<String>,
    val missingTools: List<String>,
    val toolVersions: Map<String, String>,
    val isSatisfied: Boolean
) {
    fun formatReport(): String {
        val sb = StringBuilder()
        sb.appendLine("AVSCode Linux Runtime")
        sb.appendLine("---------------------")
        sb.appendLine("Distribution: ${runtimeInfo.distro}")
        sb.appendLine("Release: ${runtimeInfo.distroVersion}")
        sb.appendLine("Architecture: ${runtimeInfo.arch}")
        sb.appendLine("Bitness: ${runtimeInfo.bitness}")
        sb.appendLine("libc: ${runtimeInfo.libc}")
        sb.appendLine("glibc version: $glibcInstalled (required >= $glibcRequired, valid: $glibcValid)")
        sb.appendLine("libstdc++ availability: $libstdcxxValid")
        sb.appendLine("GLIBCXX availability: $glibcxxSymbolValid")
        sb.appendLine("Kernel version: ${runtimeInfo.kernelVersion}")
        sb.appendLine("tar: ${if ("tar" in missingTools) "MISSING" else "available"}")
        sb.appendLine("bash: ${if ("bash" in missingTools) "MISSING" else "available"}")
        sb.appendLine("curl/wget: ${if ("curl" in missingTools && "wget" in missingTools) "MISSING" else "available"}")
        sb.appendLine("ca-certificates: ${if ("ca-certificates" in missingPackages) "MISSING" else "available"}")
        sb.appendLine("git: ${toolVersions["git"] ?: if ("git" in missingTools) "MISSING" else "available"}")
        sb.appendLine("Node.js: ${toolVersions["node"] ?: "not installed"}")
        sb.appendLine("npm: ${toolVersions["npm"] ?: "not installed"}")
        if (missingTools.isNotEmpty()) {
            sb.appendLine("Missing dependencies: ${missingTools.joinToString(", ")}")
        }
        if (!glibcValid) {
            sb.appendLine("Installed glibc: $glibcInstalled")
            sb.appendLine("Required glibc: $glibcRequired")
        }
        if (!libstdcxxValid) {
            sb.appendLine("libstdc++: MISSING")
        }
        if (!glibcxxSymbolValid) {
            sb.appendLine("GLIBCXX symbol: MISSING")
        }
        sb.appendLine("Status: ${if (isSatisfied) "SATISFIED" else "INCOMPLETE"}")
        return sb.toString().trimEnd()
    }
}

data class RuntimeDiagnosticsReport(
    val distribution: String,
    val release: String,
    val architecture: String,
    val bitness: Int,
    val kernel: String,
    val libc: String,
    val glibcVersion: String,
    val libstdcxxVersion: String,
    val glibcxxVersion: String,
    val nodeVersion: String,
    val gitVersion: String,
    val vsCodeVersion: String,
    val vsCodeTarget: String = VsCodeCliManager.CLI_TARGET,
    val vsCodeCliDirectory: String = VsCodeCliManager.GUEST_CLI_DIR,
    val vsCodeServerDirectory: String = VsCodeCliManager.GUEST_SERVER_DIR,
    val vsCodeUserDataDirectory: String = VsCodeCliManager.GUEST_USER_DATA_DIR,
    val vsCodeExtensionDirectory: String = VsCodeCliManager.GUEST_EXTENSIONS_DIR,
    val workspaceDirectory: String = VsCodeCliManager.GUEST_PROJECTS_DIR,
    val serverPort: Int? = null
) {
    fun formatReport(): String {
        val sb = StringBuilder()
        sb.appendLine("AVSCode Runtime")
        sb.appendLine("---------------")
        sb.appendLine("Distribution: $distribution")
        sb.appendLine("Release: $release")
        sb.appendLine("Architecture: $architecture")
        sb.appendLine("Bitness: $bitness")
        sb.appendLine("Kernel: $kernel")
        sb.appendLine("libc: $libc")
        sb.appendLine("glibc version: $glibcVersion")
        sb.appendLine("libstdc++ version: $libstdcxxVersion")
        sb.appendLine("GLIBCXX version: $glibcxxVersion")
        sb.appendLine("Node version: $nodeVersion")
        sb.appendLine("Git version: $gitVersion")
        sb.appendLine("VS Code version: $vsCodeVersion")
        sb.appendLine("VS Code target: $vsCodeTarget")
        sb.appendLine("VS Code CLI directory: $vsCodeCliDirectory")
        sb.appendLine("VS Code server directory: $vsCodeServerDirectory")
        sb.appendLine("VS Code user-data directory: $vsCodeUserDataDirectory")
        sb.appendLine("VS Code extension directory: $vsCodeExtensionDirectory")
        sb.appendLine("Workspace directory: $workspaceDirectory")
        sb.appendLine("Server port: ${serverPort?.toString() ?: "not running"}")
        return sb.toString().trimEnd()
    }
}

data class VsCodeCliStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val serverPort: Int? = null,
    val serverUrl: String? = null,
    val runtimeInfo: GuestRuntimeInfo? = null
)
