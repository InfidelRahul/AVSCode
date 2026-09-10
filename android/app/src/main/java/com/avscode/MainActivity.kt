package com.avscode

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.avscode.core.AvsLogger
import com.avscode.core.RuntimeState
import com.avscode.rootfs.RootfsInstaller
import com.avscode.runtime.PRootRuntime
import com.avscode.vscode.VsCodeServerManager
import com.avscode.web.VsCodeWebView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity for AVscode - VS Code for Android.
 * 
 * This activity manages the entire application lifecycle:
 * 1. Install Ubuntu rootfs on first launch
 * 2. Start Linux runtime
 * 3. Start VS Code Server
 * 4. Display VS Code Web in WebView
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // UI components
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var errorOverlay: LinearLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorMessage: TextView
    private lateinit var retryButton: MaterialButton

    // Core components
    private lateinit var rootfsInstaller: RootfsInstaller
    private lateinit var linuxRuntime: PRootRuntime
    private lateinit var vscodeServer: VsCodeServerManager
    private lateinit var webViewManager: VsCodeWebView

    private var webViewInitialized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AvsLogger.i(TAG, "MainActivity created")

        // Initialize UI
        initViews()

        // Initialize components
        rootfsInstaller = RootfsInstaller(this)
        linuxRuntime = PRootRuntime(this, rootfsInstaller)
        vscodeServer = VsCodeServerManager(linuxRuntime)
        webViewManager = VsCodeWebView(this)

        // Start the application flow
        startApplication()
    }

    private fun initViews() {
        loadingOverlay = findViewById(R.id.loading_overlay)
        errorOverlay = findViewById(R.id.error_overlay)
        webviewContainer = findViewById(R.id.webview_container)
        loadingText = findViewById(R.id.loading_text)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)
        errorMessage = findViewById(R.id.error_message)
        retryButton = findViewById(R.id.retry_button)

        retryButton.setOnClickListener {
            showError(null)
            startApplication()
        }
    }

    private fun startApplication() {
        showLoading("Initializing...")

        lifecycleScope.launch {
            try {
                // Step 1: Check/install rootfs
                if (!rootfsInstaller.isInstalled()) {
                    updateStatus("Installing Linux environment...")
                    installRootfs()
                }

                // Step 2: Start Linux runtime
                updateStatus("Starting Linux...")
                startLinuxRuntime()

                // Step 3: Setup development environment
                updateStatus("Configuring development environment...")
                setupDevelopmentEnvironment()

                // Step 4: Start VS Code Server
                updateStatus("Starting VS Code Server...")
                startVsCodeServer()

                // Step 5: Load VS Code Web
                updateStatus("Connecting to VS Code...")
                loadVsCodeWeb()

            } catch (e: Exception) {
                AvsLogger.e(TAG, "Application startup failed", e)
                showError(e.message ?: "Unknown error occurred")
            }
        }
    }

    private suspend fun installRootfs() {
        val result = rootfsInstaller.install { progress ->
            runOnUiThread {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = (progress * 100).toInt()
            }
        }

        result.getOrNull() ?: throw RuntimeException("Rootfs installation failed")
        
        runOnUiThread {
            progressBar.visibility = View.GONE
        }
    }

    private suspend fun startLinuxRuntime() {
        val result = linuxRuntime.start()
        result.getOrNull() ?: throw RuntimeException("Failed to start Linux runtime")

        // Monitor runtime state
        lifecycleScope.launch {
            linuxRuntime.state.collectLatest { state ->
                AvsLogger.d(TAG, "Runtime state: $state")
                when (state) {
                    RuntimeState.FAILED -> {
                        showError("Linux runtime failed")
                    }
                    else -> {}
                }
            }
        }
    }

    private suspend fun setupDevelopmentEnvironment() {
        AvsLogger.i(TAG, "Setting up development environment")
        
        // Install essential development tools and VS Code Server dependencies
        val commands = listOf(
            "export DEBIAN_FRONTEND=noninteractive",
            "export HOME=/home/user",
            "apt-get update -qq",
            "apt-get install -y -qq git curl wget nodejs npm python3 python3-pip ca-certificates apt-transport-https libgbm1 libnss3 libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 || true",
            "mkdir -p /home/user/projects",
            "chown -R user:user /home/user 2>/dev/null || true"
        )

        for (command in commands) {
            val result = linuxRuntime.execute(command)
            if (result.isFailure) {
                AvsLogger.w(TAG, "Command failed: $command - ${result.exceptionOrNull()?.message}")
            }
        }
        
        AvsLogger.i(TAG, "Development environment setup completed")
    }

    private suspend fun startVsCodeServer() {
        val result = vscodeServer.start()
        result.getOrNull() ?: throw RuntimeException("Failed to start VS Code Server")
    }

    private fun loadVsCodeWeb() {
        runOnUiThread {
            try {
                // Initialize WebView if not already done
                if (!webViewInitialized) {
                    val webView = webViewManager.createWebView()
                    webviewContainer.addView(webView)
                    webViewInitialized = true
                }

                // Load VS Code Web
                val serverUrl = vscodeServer.getServerUrl()
                webViewManager.loadUrl(serverUrl)

                // Hide loading overlay
                loadingOverlay.visibility = View.GONE
                errorOverlay.visibility = View.GONE

                AvsLogger.i(TAG, "VS Code Web loaded from $serverUrl")

            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to load VS Code Web", e)
                showError("Failed to connect to VS Code: ${e.message}")
            }
        }
    }

    private fun showLoading(message: String) {
        runOnUiThread {
            loadingText.text = message
            loadingOverlay.visibility = View.VISIBLE
            errorOverlay.visibility = View.GONE
        }
    }

    private fun updateStatus(status: String) {
        runOnUiThread {
            statusText.text = status
            statusText.visibility = View.VISIBLE
            loadingText.text = getString(com.avscode.R.string.loading)
        }
    }

    private fun showError(message: String?) {
        runOnUiThread {
            loadingOverlay.visibility = View.GONE
            errorOverlay.visibility = View.VISIBLE
            
            if (message != null) {
                errorMessage.text = message
            }
        }
    }

    override fun onResume() {
        super.onResume()
        webViewManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        webViewManager.onPause()
    }

    override fun onBackPressed() {
        if (!webViewManager.handleBackPress()) {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "MainActivity destroyed")
        webViewManager.destroy()
        super.onDestroy()
    }
}
