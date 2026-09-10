package com.avscode.vscode

import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.runCatchingResult
import com.avscode.runtime.LinuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * VS Code Server manager for installing and running VS Code Server inside Linux.
 */
class VsCodeServerManager(
    private val linuxRuntime: LinuxRuntime
) {
    companion object {
        private const val TAG = "VsCodeServer"
        
        // code-server version (open-source VS Code server)
        const val SERVER_VERSION = "4.96.0"
        const val SERVER_DOWNLOAD_URL = "https://github.com/coder/code-server/releases/download/v$SERVER_VERSION/code-server_${SERVER_VERSION}_linux_arm64.tar.gz"
        
        // Server paths inside Linux
        const val SERVER_INSTALL_DIR = "/opt/code-server"
        const val SERVER_DATA_DIR = "/home/user/.local/share/code-server"
        const val SERVER_BIN_PATH = "$SERVER_INSTALL_DIR/bin/code-server"
        const val SERVER_LOG_FILE = "/tmp/code-server.log"
        
        // Server startup timeout in milliseconds
        const val SERVER_STARTUP_TIMEOUT = 120_000L
        
        // Port for the server
        const val SERVER_PORT = 8080
    }
    
    private var isInstalled = false
    private var serverPid: Int? = null
    
    /**
     * Check if VS Code Server is installed.
     */
    suspend fun checkInstallation(): Boolean = withContext(Dispatchers.IO) {
        val result = linuxRuntime.execute("test -d $SERVER_INSTALL_DIR && echo 'exists' || echo 'missing'")
        result.getOrNull()?.trim()?.contains("exists") == true
    }
    
    /**
     * Install code-server inside the Linux environment.
     */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Installing code-server v$SERVER_VERSION")
        
        runCatchingResult {
            // Check if already installed
            if (checkInstallation()) {
                AvsLogger.w(TAG, "code-server already installed")
                isInstalled = true
                return@runCatchingResult Result.Success(Unit)
            }
            
            // Create installation directory
            linuxRuntime.execute("mkdir -p $SERVER_INSTALL_DIR").getOrNull()
                ?: throw RuntimeException("Failed to create server directory")
            
            // Download code-server
            AvsLogger.d(TAG, "Downloading code-server from $SERVER_DOWNLOAD_URL...")
            val downloadResult = linuxRuntime.execute(
                "curl -L -o /tmp/code-server.tar.gz '$SERVER_DOWNLOAD_URL'"
            )
            
            if (downloadResult.isFailure) {
                throw RuntimeException("Failed to download code-server: ${downloadResult.exceptionOrNull()?.message}")
            }
            
            // Extract server
            AvsLogger.d(TAG, "Extracting code-server...")
            val extractResult = linuxRuntime.execute(
                "tar -xzf /tmp/code-server.tar.gz -C /tmp --strip-components=1"
            )
            
            if (extractResult.isFailure) {
                throw RuntimeException("Failed to extract code-server: ${extractResult.exceptionOrNull()?.message}")
            }
            
            // Move to installation directory
            linuxRuntime.execute("cp -r /tmp/* $SERVER_INSTALL_DIR/")
            
            // Set executable permissions
            linuxRuntime.execute("chmod +x $SERVER_BIN_PATH")
            
            // Cleanup download
            linuxRuntime.execute("rm -f /tmp/code-server.tar.gz")
            linuxRuntime.execute("rm -rf /tmp/code-server")
            
            // Create data directory
            linuxRuntime.execute("mkdir -p $SERVER_DATA_DIR")
            
            isInstalled = true
            AvsLogger.i(TAG, "code-server installation completed")
        }
    }
    
    /**
     * Start code-server.
     */
    suspend fun start(workspacePath: String = "/home/user/projects"): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting code-server on port $SERVER_PORT")
        
        runCatchingResult {
            if (!isInstalled && !checkInstallation()) {
                install().getOrNull() ?: throw RuntimeException("code-server not installed")
            }
            
            // Ensure workspace directory exists
            linuxRuntime.execute("mkdir -p $workspacePath")
            
            // Set up environment for code-server
            linuxRuntime.execute("export HOME=/home/user")
            
            // Start server in background using nohup
            val serverCommand = buildString {
                append("nohup $SERVER_BIN_PATH")
                append(" --port $SERVER_PORT")
                append(" --host 0.0.0.0")
                append(" --auth none")
                append(" --disable-telemetry")
                append(" --user-data-dir $SERVER_DATA_DIR")
                append(" > $SERVER_LOG_FILE 2>&1 &")
            }
            
            AvsLogger.d(TAG, "Starting server with command: $serverCommand")
            val result = linuxRuntime.execute(serverCommand)
            if (result.isFailure) {
                throw RuntimeException("Failed to start server: ${result.exceptionOrNull()?.message}")
            }
            
            // Give the process a moment to start
            delay(2000)
            
            // Wait for server to be ready
            waitForServerReady()
            
            AvsLogger.i(TAG, "code-server started successfully")
        }
    }
    
    /**
     * Stop code-server.
     */
    suspend fun stop(): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Stopping code-server")
        
        runCatchingResult {
            // Find and kill the server process
            linuxRuntime.execute("pkill -f 'code-server' || true")
            
            serverPid = null
            AvsLogger.i(TAG, "code-server stopped")
        }
    }
    
    /**
     * Get the URL to access VS Code Web.
     */
    fun getServerUrl(): String {
        return "http://127.0.0.1:$SERVER_PORT"
    }
    
    /**
     * Wait for the server to be ready.
     */
    private suspend fun waitForServerReady(): Result<Unit> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val maxWaitTime = SERVER_STARTUP_TIMEOUT
        
        AvsLogger.d(TAG, "Waiting for code-server to be ready...")
        
        while (System.currentTimeMillis() - startTime < maxWaitTime) {
            delay(1000)
            
            // Check if server is responding
            val result = linuxRuntime.execute(
                "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:$SERVER_PORT/"
            )
            
            val statusCode = result.getOrNull()?.trim()
            if (statusCode == "200" || statusCode == "302" || statusCode == "401") {
                AvsLogger.d(TAG, "Server is ready after ${System.currentTimeMillis() - startTime}ms (HTTP $statusCode)")
                return@withContext Result.Success(Unit)
            }
            
            if (startTime % 5000 < 1000) {
                AvsLogger.d(TAG, "Still waiting for server... (${System.currentTimeMillis() - startTime}ms)")
            }
        }
        
        // Try to get server logs for debugging
        val logResult = linuxRuntime.execute("cat $SERVER_LOG_FILE 2>/dev/null | tail -20")
        val logs = logResult.getOrNull() ?: "No logs available"
        AvsLogger.e(TAG, "Server failed to start. Logs:\n$logs")
        
        throw RuntimeException("code-server failed to start within ${maxWaitTime / 1000}s")
    }
    
    /**
     * Get server status.
     */
    suspend fun getStatus(): VsCodeServerStatus = withContext(Dispatchers.IO) {
        val isInstalled = checkInstallation()
        
        val isRunning = try {
            val result = linuxRuntime.execute("pgrep -f 'code-server'")
            result.getOrNull()?.isNotBlank() == true
        } catch (e: Exception) {
            false
        }
        
        VsCodeServerStatus(
            isInstalled = isInstalled,
            isRunning = isRunning,
            port = SERVER_PORT,
            url = getServerUrl()
        )
    }
}

/**
 * Status of the code-server.
 */
data class VsCodeServerStatus(
    val isInstalled: Boolean,
    val isRunning: Boolean,
    val port: Int,
    val url: String
)
