package com.avscode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.avscode.core.AppState
import com.avscode.core.AvsLogger
import com.avscode.core.StoragePermissionHelper
import com.avscode.web.VsCodeWebView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity for AVSCode — VS Code for Android.
 *
 * Implements:
 * - Edge-to-edge support with dynamic safe area insets (status bars, cutouts, gesture bars, IME).
 * - Direct transition to the local VS Code editor WebView once server is ready.
 * - Persistent/toggleable Linux terminal and diagnostics console.
 * - Android <-> Linux Authentication Bridge coordination.
 * - Offline-first operation against local PRoot userspace.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // UI components
    private lateinit var mainContainer: FrameLayout
    private lateinit var webviewContainer: FrameLayout
    private lateinit var terminalContainer: LinearLayout
    private lateinit var statusBadge: TextView
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnHideTerminal: MaterialButton
    private lateinit var progressBar: ProgressBar
    private lateinit var statusHeadline: TextView
    private lateinit var storageCard: LinearLayout
    private lateinit var btnGrantStorage: MaterialButton
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalOutput: TextView
    private lateinit var cliBar: LinearLayout
    private lateinit var commandInput: EditText
    private lateinit var btnRunCommand: MaterialButton
    private lateinit var fabShowTerminal: FloatingActionButton

    private lateinit var runtimeController: RuntimeController
    private lateinit var webViewManager: VsCodeWebView
    private var webViewAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable modern edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        AvsLogger.i(TAG, "MainActivity created")

        initViews()
        setupWindowInsets()

        runtimeController = RuntimeController.getInstance(this)
        webViewManager = VsCodeWebView(this)

        // Wire up in-app Auth Bridge handler (pure in-app WebView, NO Chrome/external browser)
        runtimeController.onAuthRequestTriggered = { requestId, authUrl, title ->
            runOnUiThread {
                try {
                    AvsLogger.i(TAG, "Navigating WebView to auth URL for request $requestId: $authUrl")
                    val msg = title ?: "Authentication requested..."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    webViewManager.loadUrl(authUrl)
                } catch (e: Exception) {
                    AvsLogger.w(TAG, "Failed to load auth URL in WebView for request $requestId: ${e.message}")
                }
            }
        }

        // Wire up AuthBridge callback interception from WebView
        webViewManager.onAuthCallbackReceived = { uri ->
            handleAuthCallbackUri(uri)
        }

        webViewManager.onConnectionError = { err ->
            appendTerminalLine("[WebView] Connection error: $err")
        }

        observeRuntimeState()
        observeTerminalLogs()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (terminalContainer.visibility == View.VISIBLE && webViewAttached) {
                    // Switch back to editor
                    terminalContainer.visibility = View.GONE
                    fabShowTerminal.visibility = View.VISIBLE
                    return
                }
                if (webViewManager.isInAuthFlow()) {
                    webViewManager.cancelAuthAndRestoreEditor()
                    Toast.makeText(this@MainActivity, "Authentication cancelled", Toast.LENGTH_SHORT).show()
                    return
                }
                if (!webViewManager.handleBackPress()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        handleIncomingAuthIntent(intent)

        // Check and trigger startup
        lifecycleScope.launch {
            if (StoragePermissionHelper.isStorageConfigured(this@MainActivity)) {
                if (runtimeController.appState.value !is AppState.Ready) {
                    runtimeController.startAll()
                }
            } else {
                showStoragePermissionCard()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingAuthIntent(intent)
    }

    private fun handleAuthCallbackUri(data: Uri): Boolean {
        val requestId = data.getQueryParameter("requestId") ?: data.getQueryParameter("state")
        val code = data.getQueryParameter("code")
        val token = data.getQueryParameter("token")
        AvsLogger.i(TAG, "Processing auth callback: requestId=$requestId, hasCode=${code != null}")

        if (requestId != null) {
            runtimeController.authBridgeServer.completeSession(requestId, code, token)
        }

        // Notify loopback AuthBridgeServer in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bridgePort = runtimeController.authBridgePort
                if (bridgePort > 0) {
                    val query = data.query.orEmpty()
                    val bridgeUrl = java.net.URL("http://127.0.0.1:$bridgePort/auth/callback?$query")
                    val conn = bridgeUrl.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.responseCode
                    conn.disconnect()
                }
            } catch (e: Exception) {
                AvsLogger.d(TAG, "Background notify to AuthBridgeServer: ${e.message}")
            }
        }

        runOnUiThread {
            Toast.makeText(this, "AVSCode: Authentication complete!", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun handleIncomingAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "avscode" || data.path?.contains("callback") == true) {
            handleAuthCallbackUri(data)
            webViewManager.restoreEditor()
        }
    }

    private fun initViews() {
        mainContainer = findViewById(R.id.main_container)
        webviewContainer = findViewById(R.id.webview_container)
        terminalContainer = findViewById(R.id.terminal_container)
        statusBadge = findViewById(R.id.status_badge)
        btnRetry = findViewById(R.id.btn_retry)
        btnHideTerminal = findViewById(R.id.btn_hide_terminal)
        progressBar = findViewById(R.id.progress_bar)
        statusHeadline = findViewById(R.id.status_headline)
        storageCard = findViewById(R.id.storage_card)
        btnGrantStorage = findViewById(R.id.btn_grant_storage)
        terminalScroll = findViewById(R.id.terminal_scroll)
        terminalOutput = findViewById(R.id.terminal_output)
        cliBar = findViewById(R.id.cli_bar)
        commandInput = findViewById(R.id.command_input)
        btnRunCommand = findViewById(R.id.btn_run_command)
        fabShowTerminal = findViewById(R.id.fab_show_terminal)

        btnGrantStorage.setOnClickListener {
            handleGrantStorageAccess()
        }

        btnRetry.setOnClickListener {
            btnRetry.visibility = View.GONE
            appendTerminalLine("[UI] Retrying startup sequence...")
            lifecycleScope.launch {
                runtimeController.startAll(forceRestart = true)
            }
        }

        btnHideTerminal.setOnClickListener {
            if (webViewAttached) {
                terminalContainer.visibility = View.GONE
                fabShowTerminal.visibility = View.VISIBLE
            }
        }

        fabShowTerminal.setOnClickListener {
            terminalContainer.visibility = View.VISIBLE
            fabShowTerminal.visibility = View.GONE
            scrollTerminalToBottom()
        }

        btnRunCommand.setOnClickListener {
            submitCommand()
        }

        commandInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitCommand()
                true
            } else {
                false
            }
        }
    }

    /**
     * Applies dynamic system bar, display cutout, and IME window insets.
     * Prevents content clipping without hardcoded pixel/dp dimensions.
     */
    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val basePad = (12 * density).toInt()
        val baseFabMargin = (16 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply safe insets to terminal container
            terminalContainer.setPadding(
                basePad + insets.left,
                basePad + insets.top,
                basePad + insets.right,
                basePad + maxOf(insets.bottom, ime.bottom)
            )

            // Apply safe insets to webview container so editor tabs and status bar are accessible
            webviewContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom
            )

            // Apply insets to Floating Action Button
            val fabParams = fabShowTerminal.layoutParams as? FrameLayout.LayoutParams
            fabParams?.let { params ->
                params.bottomMargin = baseFabMargin + maxOf(insets.bottom, ime.bottom)
                params.rightMargin = baseFabMargin + insets.right
                fabShowTerminal.layoutParams = params
            }

            windowInsets
        }
    }

    private fun handleGrantStorageAccess() {
        val rootfsDir = runtimeController.paths.rootfsDir
        StoragePermissionHelper.verifyStorageAccessible(rootfsDir)
        StoragePermissionHelper.markStorageConfigured(this, rootfsDir.absolutePath)
        storageCard.visibility = View.GONE

        // Check if external storage manager can be optionally requested
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!StoragePermissionHelper.hasManageExternalStoragePermission()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    AvsLogger.d(TAG, "External storage settings intent not available: ${e.message}")
                }
            }
        }

        appendTerminalLine("[Android] Storage access configured for: ${rootfsDir.absolutePath}")
        lifecycleScope.launch {
            runtimeController.startAll()
        }
    }

    private fun showStoragePermissionCard() {
        storageCard.visibility = View.VISIBLE
        statusBadge.text = "NEEDS_STORAGE_ACCESS"
        statusHeadline.text = getString(R.string.storage_access_title)
    }

    private fun submitCommand() {
        val cmd = commandInput.text.toString().trim()
        if (cmd.isEmpty()) return

        commandInput.setText("")
        appendTerminalLine("guest:$ $cmd")

        lifecycleScope.launch {
            runtimeController.executeGuestCommand(cmd) { line ->
                appendTerminalLine(line)
            }
        }
    }

    private fun observeRuntimeState() {
        lifecycleScope.launch {
            runtimeController.appState.collectLatest { state ->
                AvsLogger.d(TAG, "Observed AppState: $state")

                // Update CLI bar visibility
                cliBar.visibility = if (state.canAccessCli) View.VISIBLE else View.GONE

                // Update Retry button visibility
                btnRetry.visibility = if (state.isFailed) View.VISIBLE else View.GONE

                when (state) {
                    is AppState.NeedsStorageAccess -> {
                        showStoragePermissionCard()
                        progressBar.visibility = View.GONE
                    }
                    is AppState.NotInstalled -> {
                        statusBadge.text = "NOT_INSTALLED"
                        statusHeadline.text = "Ready to download Ubuntu rootfs..."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.DownloadingRootfs -> {
                        storageCard.visibility = View.GONE
                        statusBadge.text = "DOWNLOADING_ROOTFS"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = (state.progress * 100).toInt()
                    }
                    is AppState.ExtractingRootfs -> {
                        storageCard.visibility = View.GONE
                        statusBadge.text = "EXTRACTING_ROOTFS"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = (state.progress * 100).toInt()
                    }
                    is AppState.RootfsReady -> {
                        storageCard.visibility = View.GONE
                        statusBadge.text = "ROOTFS_READY"
                        statusHeadline.text = "Ubuntu rootfs verified."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.StartingLinux -> {
                        statusBadge.text = "STARTING_LINUX"
                        statusHeadline.text = "Starting Linux PRoot runtime..."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.VerifyingLinux -> {
                        statusBadge.text = "VERIFYING_LINUX"
                        statusHeadline.text = "Running userspace diagnostics (/usr/bin/mkdir probe)..."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.LinuxReady -> {
                        statusBadge.text = "LINUX_READY"
                        statusHeadline.text = "Linux userspace active. CLI available."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.InstallingPackages -> {
                        statusBadge.text = "INSTALLING_PACKAGES"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.GONE
                    }
                    is AppState.InstallingVsCode -> {
                        statusBadge.text = "INSTALLING_VSCODE"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = (state.progress * 100).toInt()
                    }
                    is AppState.VsCodeReady -> {
                        statusBadge.text = "VSCODE_READY"
                        statusHeadline.text = "Microsoft VS Code CLI ready."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.StartingAuthBridge -> {
                        statusBadge.text = "STARTING_AUTH_BRIDGE"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.GONE
                    }
                    is AppState.StartingVsCodeServer -> {
                        statusBadge.text = "STARTING_SERVER"
                        statusHeadline.text = state.status
                        progressBar.visibility = View.GONE
                    }
                    is AppState.Ready -> {
                        statusBadge.text = "READY"
                        statusHeadline.text = "VS Code is ready at ${state.url}"
                        progressBar.visibility = View.GONE
                        btnHideTerminal.visibility = View.VISIBLE

                        val sPort = runtimeController.serverPort ?: 0
                        val bPort = runtimeController.authBridgePort
                        webViewManager.setEndpoints(sPort, bPort, state.url)

                        attachAndLoadWebView(state.url)
                        // Transition to editor
                        terminalContainer.visibility = View.GONE
                        fabShowTerminal.visibility = View.VISIBLE
                    }
                    is AppState.Stopping -> {
                        statusBadge.text = "STOPPING"
                        statusHeadline.text = "Stopping Linux runtime..."
                        progressBar.visibility = View.GONE
                    }
                    is AppState.RootfsFailed -> {
                        statusBadge.text = "ROOTFS_FAILED"
                        statusHeadline.text = state.message
                        progressBar.visibility = View.GONE
                        appendTerminalLine("[ERROR] ${state.message}")
                    }
                    is AppState.LinuxFailed -> {
                        statusBadge.text = "LINUX_FAILED"
                        statusHeadline.text = state.message
                        progressBar.visibility = View.GONE
                        appendTerminalLine("[ERROR] ${state.message}")
                    }
                    is AppState.PackageInstallFailed -> {
                        statusBadge.text = "PACKAGE_INSTALL_FAILED"
                        statusHeadline.text = state.message
                        progressBar.visibility = View.GONE
                        appendTerminalLine("[ERROR] ${state.message}")
                    }
                    is AppState.VsCodeFailed -> {
                        statusBadge.text = "VSCODE_FAILED"
                        statusHeadline.text = state.message
                        progressBar.visibility = View.GONE
                        appendTerminalLine("[ERROR] ${state.message}")
                        // CLI remains active in terminalContainer for debugging
                        terminalContainer.visibility = View.VISIBLE
                    }
                    is AppState.Failed -> {
                        statusBadge.text = "FAILED"
                        statusHeadline.text = state.message
                        progressBar.visibility = View.GONE
                        appendTerminalLine("[ERROR] ${state.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeTerminalLogs() {
        lifecycleScope.launch {
            runtimeController.terminalLogs.collect { line ->
                appendTerminalLine(line)
            }
        }
    }

    private fun appendTerminalLine(line: String) {
        terminalOutput.append(line + "\n")
        scrollTerminalToBottom()
    }

    private fun scrollTerminalToBottom() {
        terminalScroll.post {
            terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun attachAndLoadWebView(url: String) {
        if (!webViewAttached) {
            val webView = webViewManager.createWebView()
            webviewContainer.removeAllViews()
            webviewContainer.addView(webView)
            webViewAttached = true
        }
        webViewManager.loadUrl(url)
    }

    override fun onResume() {
        super.onResume()
        webViewManager.onResume()
    }

    override fun onPause() {
        super.onPause()
        webViewManager.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && webViewAttached && terminalContainer.visibility != View.VISIBLE) {
            if (webViewManager.handleKeyEvent(event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "MainActivity destroyed")
        webViewManager.destroy()
        webViewAttached = false
        super.onDestroy()
    }
}
