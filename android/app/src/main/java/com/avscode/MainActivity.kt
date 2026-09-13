package com.avscode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.avscode.about.AboutInfoProvider
import com.avscode.core.AppState
import com.avscode.core.AvsLogger
import com.avscode.core.StoragePermissionHelper
import com.avscode.ports.PortScanner
import com.avscode.terminal.TerminalSession
import com.avscode.web.VsCodeWebView
import com.avscode.workspace.ArchiveFormat
import com.avscode.workspace.WorkspaceArchiveManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Main activity for AVSCode — VS Code for Android.
 *
 * Implements:
 * - Home Dashboard with Primary VS Code Card, Terminal Card, Open Ports, Workspace, and About.
 * - Dedicated clean rootfs & bootstrap installation UX (no raw terminal exposed during setup).
 * - Fullscreen VS Code WebView with persistent return-to-dashboard navigation.
 * - Interactive Ubuntu PRoot Terminal shell.
 * - In-app Android <-> Linux Authentication Bridge.
 * - Workspace project import and export supporting ZIP, TAR.GZ, and TAR.XZ.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    // Top-level View Containers
    private lateinit var mainContainer: FrameLayout
    private lateinit var dashboardContainer: ScrollView
    private lateinit var installationContainer: LinearLayout
    private lateinit var editorContainer: LinearLayout
    private lateinit var terminalContainer: LinearLayout

    // Dashboard UI components
    private lateinit var btnSettings: MaterialButton
    private lateinit var storageCard: MaterialCardView
    private lateinit var btnGrantStorage: MaterialButton

    // VS Code Card
    private lateinit var cardVsCode: MaterialCardView
    private lateinit var tvVsCodeStatusDesc: TextView
    private lateinit var ivVsCodeStatusDot: ImageView
    private lateinit var pbVsCodeStarting: ProgressBar
    private lateinit var btnVsCodeAction: MaterialButton
    private lateinit var btnVsCodeStop: MaterialButton

    // Terminal Card
    private lateinit var cardTerminal: MaterialCardView
    private lateinit var btnOpenTerminal: MaterialButton

    // Dedicated In-App Auth Dialog UI components
    private lateinit var authContainer: LinearLayout
    private lateinit var btnCloseAuth: MaterialButton
    private lateinit var btnCloseAuthAction: MaterialButton
    private lateinit var btnEditorCloseAuth: MaterialButton
    private lateinit var tvAuthTitle: TextView
    private lateinit var authSuccessBanner: LinearLayout
    private lateinit var authWebviewFrame: FrameLayout
    private var authWebView: WebView? = null

    // Open Ports Card
    private lateinit var cardPorts: MaterialCardView
    private lateinit var btnRefreshPorts: MaterialButton
    private lateinit var tvNoOpenPorts: TextView
    private lateinit var layoutPortsList: LinearLayout

    // Workspace Card
    private lateinit var cardWorkspace: MaterialCardView
    private lateinit var tvWorkspacePath: TextView
    private lateinit var tvProjectsSummary: TextView
    private lateinit var btnImportArchive: MaterialButton
    private lateinit var btnExportProject: MaterialButton

    // About Section
    private lateinit var tvAboutAppVer: TextView
    private lateinit var tvAboutDistro: TextView
    private lateinit var tvAboutVsCode: TextView
    private lateinit var tvAboutAndroid: TextView
    private lateinit var tvAboutDevice: TextView
    private lateinit var tvAboutCpu: TextView

    // Installation Progress UI
    private lateinit var tvInstallTitle: TextView
    private lateinit var tvInstallStep: TextView
    private lateinit var pbInstallProgress: ProgressBar
    private lateinit var tvInstallPercent: TextView
    private lateinit var btnInstallRetry: MaterialButton

    // Editor View
    private lateinit var btnEditorBackDashboard: MaterialButton
    private lateinit var tvEditorTitle: TextView
    private lateinit var btnZoomOut: MaterialButton
    private lateinit var tvZoomLevel: TextView
    private lateinit var btnZoomIn: MaterialButton
    private lateinit var btnToggleDesktop: MaterialButton
    private lateinit var btnEditorToTerminal: MaterialButton
    private lateinit var webviewContainer: FrameLayout

    // Terminal View
    private lateinit var btnTerminalBackDashboard: MaterialButton
    private lateinit var statusBadge: TextView
    private lateinit var btnTerminalToEditor: MaterialButton
    private lateinit var terminalScroll: ScrollView
    private lateinit var terminalOutput: TextView
    private lateinit var btnScrollToBottom: MaterialButton
    private lateinit var tvCliPrompt: TextView
    private lateinit var keyEsc: MaterialButton
    private lateinit var keyTab: MaterialButton
    private lateinit var keyCtrl: MaterialButton
    private lateinit var keyAlt: MaterialButton
    private lateinit var keyUp: MaterialButton
    private lateinit var keyDown: MaterialButton
    private lateinit var keyLeft: MaterialButton
    private lateinit var keyRight: MaterialButton
    private lateinit var keyCtrlC: MaterialButton
    private lateinit var keyCtrlX: MaterialButton
    private lateinit var keyCtrlO: MaterialButton
    private lateinit var keyClear: MaterialButton
    private lateinit var cliBar: LinearLayout
    private lateinit var commandInput: EditText
    private lateinit var btnRunCommand: MaterialButton

    // Managers & Controllers
    private lateinit var runtimeController: RuntimeController
    private lateinit var webViewManager: VsCodeWebView
    private lateinit var portScanner: PortScanner
    private lateinit var workspaceArchiveManager: WorkspaceArchiveManager
    private lateinit var aboutInfoProvider: AboutInfoProvider
    private lateinit var terminalSession: TerminalSession
    private var webViewAttached = false
    private var isCtrlActive = false
    private var isAltActive = false
    private var portsAutoRefreshJob: Job? = null

    // Pending export state
    private var pendingExportProject: File? = null
    private var pendingExportFormat: ArchiveFormat? = null

    // Activity Result Launchers for Archive Import and Export
    private val importArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleImportArchive(it) }
    }

    private val exportArchiveLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        uri?.let { destUri ->
            val project = pendingExportProject
            val format = pendingExportFormat
            if (project != null && format != null) {
                handleExportProject(project, format, destUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge layout
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_main)
        AvsLogger.i(TAG, "MainActivity created")

        initViews()
        setupWindowInsets()

        runtimeController = RuntimeController.getInstance(this)
        webViewManager = VsCodeWebView(this)
        portScanner = PortScanner()
        workspaceArchiveManager = WorkspaceArchiveManager(runtimeController.paths.hostProjectsDir)
        aboutInfoProvider = AboutInfoProvider(this)
        terminalSession = TerminalSession(runtimeController.linuxRuntime)

        updateAboutSection()
        updateWorkspaceSummary()

        // Initialize Terminal UI
        terminalOutput.text = terminalSession.buffer.render()
        tvCliPrompt.text = terminalSession.getPrompt()

        // Wire zoom and desktop mode callbacks
        tvZoomLevel.text = "${webViewManager.currentZoomLevel}%"
        webViewManager.onZoomChanged = { level ->
            runOnUiThread { tvZoomLevel.text = "$level%" }
        }
        webViewManager.onDesktopModeChanged = { isDesktop ->
            runOnUiThread { updateDesktopButtonState(isDesktop) }
        }
        updateDesktopButtonState(webViewManager.isDesktopMode)

        // Start live auto-refresh for open ports on dashboard
        startPortsAutoRefresh()

        // Wire up dedicated in-app Auth Dialog (pure in-app WebView, NO external browser)
        runtimeController.onAuthRequestTriggered = { requestId, authUrl, title ->
            runOnUiThread {
                try {
                    AvsLogger.i(TAG, "Opening dedicated in-app Auth Dialog for request $requestId: $authUrl")
                    val msg = title ?: "Authentication requested..."
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    showAuthContainer(authUrl, title)
                } catch (e: Exception) {
                    AvsLogger.w(TAG, "Failed to load auth URL: ${e.message}")
                }
            }
        }

        // Wire up AuthBridge callback interception from WebView
        webViewManager.onAuthCallbackReceived = { uri ->
            handleAuthCallbackUri(uri)
        }

        // Wire external URL routing to dedicated in-app auth container with close button
        webViewManager.onExternalUrlRequested = { url ->
            runOnUiThread {
                showAuthContainer(url, getString(R.string.auth_title))
            }
            true
        }

        webViewManager.onAuthFlowStateChanged = { inAuthFlow ->
            runOnUiThread {
                btnEditorCloseAuth.visibility = if (inAuthFlow) View.VISIBLE else View.GONE
            }
        }

        webViewManager.onConnectionError = { err ->
            AvsLogger.w(TAG, "[WebView] Connection error: $err")
        }

        observeRuntimeState()
        observeTerminalLogs()

        // Handle system back navigation
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authContainer.visibility == View.VISIBLE) {
                    if (authWebView?.canGoBack() == true) {
                        authWebView?.goBack()
                    } else {
                        hideAuthContainer()
                        if (runtimeController.appState.value is AppState.Ready) {
                            showEditorView()
                        }
                    }
                    return
                }

                if (editorContainer.visibility == View.VISIBLE) {
                    if (webViewManager.isInAuthFlow()) {
                        webViewManager.cancelAuthAndRestoreEditor()
                        Toast.makeText(this@MainActivity, "Authentication cancelled", Toast.LENGTH_SHORT).show()
                        return
                    }
                    if (webViewManager.handleBackPress()) {
                        return
                    }
                    showDashboardView()
                    return
                }

                if (terminalContainer.visibility == View.VISIBLE) {
                    showDashboardView()
                    return
                }

                // If on dashboard or installation, perform default back
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        handleIncomingAuthIntent(intent)

        // Check storage and start runtime
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
        AvsLogger.i(TAG, "Processing auth callback: $data")

        // 1. Unwrap vscode.dev redirect state if present
        var effectiveUri = data
        val stateParam = data.getQueryParameter("state")
        if (data.host?.contains("vscode.dev") == true && !stateParam.isNullOrBlank()) {
            try {
                val decodedState = Uri.parse(Uri.decode(stateParam))
                val codeParam = data.getQueryParameter("code")
                effectiveUri = if (codeParam != null && decodedState.getQueryParameter("code") == null) {
                    decodedState.buildUpon().appendQueryParameter("code", codeParam).build()
                } else {
                    decodedState
                }
                AvsLogger.i(TAG, "Unwrapped redirect state into: $effectiveUri")
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to unwrap state: ${e.message}")
            }
        }

        // 2. Extract VS Code Web callback parameters
        val reqId = effectiveUri.getQueryParameter("vscode-reqid")
        val scheme = effectiveUri.getQueryParameter("vscode-scheme") ?: effectiveUri.scheme ?: "vscode"
        val authority = effectiveUri.getQueryParameter("vscode-authority") ?: effectiveUri.authority ?: "vscode.github-authentication"
        val path = effectiveUri.getQueryParameter("vscode-path") ?: effectiveUri.path ?: "/did-authenticate"
        val query = effectiveUri.getQueryParameter("vscode-query")

        // Build query string of non-vscode parameters (e.g. code, state, nonce)
        val queryBuilder = StringBuilder()
        for (name in effectiveUri.queryParameterNames) {
            if (!name.startsWith("vscode-")) {
                val value = effectiveUri.getQueryParameter(name)
                if (value != null) {
                    if (queryBuilder.isNotEmpty()) queryBuilder.append("&")
                    queryBuilder.append(name).append("=").append(Uri.encode(value))
                }
            }
        }
        if (!query.isNullOrBlank()) {
            if (queryBuilder.isNotEmpty()) queryBuilder.append("&")
            queryBuilder.append(query)
        }

        // 3. Inject URL callback into active VS Code editor instance
        webViewManager.injectVsCodeUrlCallback(
            reqId = reqId,
            scheme = scheme,
            authority = authority,
            path = path,
            query = queryBuilder.toString()
        )

        // 4. Complete AuthBridgeServer session for any CLI / guest processes
        val code = effectiveUri.getQueryParameter("code")
        val token = effectiveUri.getQueryParameter("token")
        val requestId = effectiveUri.getQueryParameter("requestId") ?: effectiveUri.getQueryParameter("state") ?: reqId
        if (requestId != null) {
            runtimeController.authBridgeServer.completeSession(requestId, code, token)
        }

        // Notify loopback AuthBridgeServer in background
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bridgePort = runtimeController.authBridgePort
                if (bridgePort > 0) {
                    val q = effectiveUri.query.orEmpty()
                    val bridgeUrl = java.net.URL("http://127.0.0.1:$bridgePort/auth/callback?$q")
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

        CookieManager.getInstance().flush()

        // 5. Dismiss auth dialog and return to editor
        runOnUiThread {
            hideAuthContainer()
            showEditorView()
            Toast.makeText(this, R.string.auth_complete_msg, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    private fun handleIncomingAuthIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (webViewManager.isAuthCallback(data)) {
            handleAuthCallbackUri(data)
            showEditorView()
        }
    }

    private fun initViews() {
        mainContainer = findViewById(R.id.main_container)
        dashboardContainer = findViewById(R.id.dashboard_container)
        installationContainer = findViewById(R.id.installation_container)
        editorContainer = findViewById(R.id.editor_container)
        terminalContainer = findViewById(R.id.terminal_container)

        // Top App Bar & Storage
        btnSettings = findViewById(R.id.btn_settings)
        storageCard = findViewById(R.id.storage_card)
        btnGrantStorage = findViewById(R.id.btn_grant_storage)

        // VS Code Card
        cardVsCode = findViewById(R.id.card_vscode)
        tvVsCodeStatusDesc = findViewById(R.id.tv_vscode_status_desc)
        ivVsCodeStatusDot = findViewById(R.id.iv_vscode_status_dot)
        pbVsCodeStarting = findViewById(R.id.pb_vscode_starting)
        btnVsCodeAction = findViewById(R.id.btn_vscode_action)
        btnVsCodeStop = findViewById(R.id.btn_vscode_stop)

        // Terminal Card
        cardTerminal = findViewById(R.id.card_terminal)
        btnOpenTerminal = findViewById(R.id.btn_open_terminal)

        // Dedicated In-App Auth Dialog UI components
        authContainer = findViewById(R.id.auth_container)
        btnCloseAuth = findViewById(R.id.btn_close_auth)
        btnCloseAuthAction = findViewById(R.id.btn_close_auth_action)
        btnEditorCloseAuth = findViewById(R.id.btn_editor_close_auth)
        tvAuthTitle = findViewById(R.id.tv_auth_title)
        authSuccessBanner = findViewById(R.id.auth_success_banner)
        authWebviewFrame = findViewById(R.id.auth_webview_frame)

        // Open Ports Card
        cardPorts = findViewById(R.id.card_ports)
        btnRefreshPorts = findViewById(R.id.btn_refresh_ports)
        tvNoOpenPorts = findViewById(R.id.tv_no_open_ports)
        layoutPortsList = findViewById(R.id.layout_ports_list)

        // Workspace Card
        cardWorkspace = findViewById(R.id.card_workspace)
        tvWorkspacePath = findViewById(R.id.tv_workspace_path)
        tvProjectsSummary = findViewById(R.id.tv_projects_summary)
        btnImportArchive = findViewById(R.id.btn_import_archive)
        btnExportProject = findViewById(R.id.btn_export_project)

        // About Section
        tvAboutAppVer = findViewById(R.id.tv_about_app_ver)
        tvAboutDistro = findViewById(R.id.tv_about_distro)
        tvAboutVsCode = findViewById(R.id.tv_about_vscode)
        tvAboutAndroid = findViewById(R.id.tv_about_android)
        tvAboutDevice = findViewById(R.id.tv_about_device)
        tvAboutCpu = findViewById(R.id.tv_about_cpu)

        // Installation UI
        tvInstallTitle = findViewById(R.id.tv_install_title)
        tvInstallStep = findViewById(R.id.tv_install_step)
        pbInstallProgress = findViewById(R.id.pb_install_progress)
        tvInstallPercent = findViewById(R.id.tv_install_percent)
        btnInstallRetry = findViewById(R.id.btn_install_retry)

        // Editor View
        btnEditorBackDashboard = findViewById(R.id.btn_editor_back_dashboard)
        tvEditorTitle = findViewById(R.id.tv_editor_title)
        btnZoomOut = findViewById(R.id.btn_zoom_out)
        tvZoomLevel = findViewById(R.id.tv_zoom_level)
        btnZoomIn = findViewById(R.id.btn_zoom_in)
        btnToggleDesktop = findViewById(R.id.btn_toggle_desktop)
        btnEditorToTerminal = findViewById(R.id.btn_editor_to_terminal)
        webviewContainer = findViewById(R.id.webview_container)

        // Terminal View
        btnTerminalBackDashboard = findViewById(R.id.btn_terminal_back_dashboard)
        statusBadge = findViewById(R.id.status_badge)
        btnTerminalToEditor = findViewById(R.id.btn_terminal_to_editor)
        terminalScroll = findViewById(R.id.terminal_scroll)
        terminalOutput = findViewById(R.id.terminal_output)
        btnScrollToBottom = findViewById(R.id.btn_scroll_to_bottom)
        tvCliPrompt = findViewById(R.id.tv_cli_prompt)
        keyEsc = findViewById(R.id.key_esc)
        keyTab = findViewById(R.id.key_tab)
        keyCtrl = findViewById(R.id.key_ctrl)
        keyAlt = findViewById(R.id.key_alt)
        keyUp = findViewById(R.id.key_up)
        keyDown = findViewById(R.id.key_down)
        keyLeft = findViewById(R.id.key_left)
        keyRight = findViewById(R.id.key_right)
        keyCtrlC = findViewById(R.id.key_ctrl_c)
        keyCtrlX = findViewById(R.id.key_ctrl_x)
        keyCtrlO = findViewById(R.id.key_ctrl_o)
        keyClear = findViewById(R.id.key_clear)
        cliBar = findViewById(R.id.cli_bar)
        commandInput = findViewById(R.id.command_input)
        btnRunCommand = findViewById(R.id.btn_run_command)

        // Wire Click Listeners
        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        btnGrantStorage.setOnClickListener {
            handleGrantStorageAccess()
        }

        btnVsCodeAction.setOnClickListener {
            handleVsCodeActionClick()
        }

        btnVsCodeStop.setOnClickListener {
            handleVsCodeStopClick()
        }

        btnCloseAuth.setOnClickListener {
            hideAuthContainer()
            if (runtimeController.appState.value is AppState.Ready) {
                showEditorView()
            }
        }

        btnCloseAuthAction.setOnClickListener {
            hideAuthContainer()
            if (runtimeController.appState.value is AppState.Ready) {
                showEditorView()
            }
        }

        btnEditorCloseAuth.setOnClickListener {
            webViewManager.cancelAuthAndRestoreEditor()
            btnEditorCloseAuth.visibility = View.GONE
        }

        btnOpenTerminal.setOnClickListener {
            showTerminalView()
            lifecycleScope.launch {
                runtimeController.ensureLinuxStarted()
            }
        }

        btnRefreshPorts.setOnClickListener {
            updatePortsList()
        }

        btnImportArchive.setOnClickListener {
            importArchiveLauncher.launch(arrayOf("*/*"))
        }

        btnExportProject.setOnClickListener {
            showExportProjectDialog()
        }

        btnInstallRetry.setOnClickListener {
            btnInstallRetry.visibility = View.GONE
            lifecycleScope.launch {
                runtimeController.startAll(forceRestart = true)
            }
        }

        btnEditorBackDashboard.setOnClickListener {
            showDashboardView()
        }

        btnEditorToTerminal.setOnClickListener {
            showTerminalView()
        }

        btnTerminalBackDashboard.setOnClickListener {
            showDashboardView()
        }

        btnTerminalToEditor.setOnClickListener {
            showEditorView()
        }

        // Wire Zoom Controls
        btnZoomOut.setOnClickListener {
            val level = webViewManager.zoomOut()
            tvZoomLevel.text = "$level%"
        }

        btnZoomIn.setOnClickListener {
            val level = webViewManager.zoomIn()
            tvZoomLevel.text = "$level%"
        }

        tvZoomLevel.setOnClickListener {
            val level = webViewManager.resetZoom()
            tvZoomLevel.text = "$level%"
            Toast.makeText(this, R.string.reset_zoom, Toast.LENGTH_SHORT).show()
        }

        btnToggleDesktop.setOnClickListener {
            val isDesktop = webViewManager.toggleDesktopMode()
            updateDesktopButtonState(isDesktop)
            Toast.makeText(this, if (isDesktop) R.string.desktop_mode else R.string.mobile_mode, Toast.LENGTH_SHORT).show()
        }

        // Terminal Scroll Preservation & Bottom Chip
        btnScrollToBottom.setOnClickListener {
            btnScrollToBottom.visibility = View.GONE
            scrollTerminalToBottom()
        }

        terminalScroll.viewTreeObserver.addOnScrollChangedListener {
            if (isTerminalAtBottom()) {
                btnScrollToBottom.visibility = View.GONE
            }
        }

        // Wire Terminal Accessory Key Bar
        keyEsc.setOnClickListener {
            commandInput.setText("")
        }

        keyTab.setOnClickListener {
            val start = commandInput.selectionStart
            val end = commandInput.selectionEnd
            if (start >= 0 && end >= 0) {
                commandInput.text.replace(minOf(start, end), maxOf(start, end), "\t", 0, 1)
            } else {
                commandInput.append("\t")
            }
        }

        keyCtrl.setOnClickListener {
            isCtrlActive = !isCtrlActive
            keyCtrl.isSelected = isCtrlActive
            keyCtrl.setTextColor(if (isCtrlActive) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        }

        keyAlt.setOnClickListener {
            isAltActive = !isAltActive
            keyAlt.isSelected = isAltActive
            keyAlt.setTextColor(if (isAltActive) getColor(R.color.accent) else 0xFFFFFFFF.toInt())
        }

        keyUp.setOnClickListener {
            terminalSession.navigateHistory(up = true)?.let { prev ->
                commandInput.setText(prev)
                commandInput.setSelection(prev.length)
            }
        }

        keyDown.setOnClickListener {
            terminalSession.navigateHistory(up = false)?.let { next ->
                commandInput.setText(next)
                commandInput.setSelection(next.length)
            }
        }

        keyLeft.setOnClickListener {
            val pos = (commandInput.selectionStart - 1).coerceAtLeast(0)
            commandInput.setSelection(pos)
        }

        keyRight.setOnClickListener {
            val pos = (commandInput.selectionStart + 1).coerceAtMost(commandInput.text.length)
            commandInput.setSelection(pos)
        }

        keyCtrlC.setOnClickListener {
            if (commandInput.text.isNotEmpty()) {
                commandInput.setText("")
            } else {
                lifecycleScope.launch {
                    terminalSession.executeCommand("^C") { rendered ->
                        runOnUiThread { updateTerminalDisplay(rendered) }
                    }
                }
            }
        }

        keyCtrlX.setOnClickListener {
            commandInput.append("\u0018")
        }

        keyCtrlO.setOnClickListener {
            commandInput.append("\u000F")
        }

        keyClear.setOnClickListener {
            lifecycleScope.launch {
                terminalSession.executeCommand("clear") { rendered ->
                    runOnUiThread { updateTerminalDisplay(rendered) }
                }
            }
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

    private fun setupWindowInsets() {
        val density = resources.displayMetrics.density
        val basePad = (12 * density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(mainContainer) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())

            // Apply insets to dashboard
            dashboardContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                maxOf(insets.bottom, ime.bottom)
            )

            // Apply insets to installation view
            installationContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                maxOf(insets.bottom, ime.bottom)
            )

            // Apply insets to terminal container
            terminalContainer.setPadding(
                basePad + insets.left,
                basePad + insets.top,
                basePad + insets.right,
                basePad + maxOf(insets.bottom, ime.bottom)
            )

            // Apply insets to editor container
            editorContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                insets.bottom
            )

            // Apply insets to auth container
            authContainer.setPadding(
                insets.left,
                insets.top,
                insets.right,
                maxOf(insets.bottom, ime.bottom)
            )

            windowInsets
        }
    }

    private fun showDashboardView() {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.VISIBLE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.GONE
        updatePortsList()
        updateWorkspaceSummary()
    }

    private fun showEditorView() {
        authContainer.visibility = View.GONE
        val state = runtimeController.appState.value
        val url = if (state is AppState.Ready) state.url else runtimeController.activeServerUrl
        if (url != null) {
            attachAndLoadWebView(url)
            tvEditorTitle.text = url
        }
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.VISIBLE
        terminalContainer.visibility = View.GONE
    }

    private fun showTerminalView() {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.GONE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.VISIBLE
        tvCliPrompt.text = terminalSession.getPrompt()
        scrollTerminalToBottom()
    }

    private fun showInstallationView(title: String, step: String, progress: Int, isIndeterminate: Boolean = false) {
        authContainer.visibility = View.GONE
        dashboardContainer.visibility = View.GONE
        installationContainer.visibility = View.VISIBLE
        editorContainer.visibility = View.GONE
        terminalContainer.visibility = View.GONE

        tvInstallTitle.text = title
        tvInstallStep.text = step
        pbInstallProgress.isIndeterminate = isIndeterminate
        if (!isIndeterminate) {
            pbInstallProgress.progress = progress
            tvInstallPercent.text = "$progress%"
        } else {
            tvInstallPercent.text = ""
        }
        btnInstallRetry.visibility = View.GONE
    }

    private fun showAuthContainer(url: String, title: String?) {
        tvAuthTitle.text = title ?: getString(R.string.auth_title)
        authSuccessBanner.visibility = View.GONE
        authContainer.visibility = View.VISIBLE

        if (authWebView == null) {
            val webView = WebView(this).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = webViewManager.getDesktopUserAgent()
                }
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val uri = request?.url ?: return false
                        AvsLogger.d(TAG, "Auth WebView shouldOverrideUrlLoading: $uri")
                        if (webViewManager.isAuthCallback(uri)) {
                            handleAuthCallbackUri(uri)
                            return true
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        val uri = url?.let { Uri.parse(it) }
                        if (uri != null && webViewManager.isAuthCallback(uri)) {
                            handleAuthCallbackUri(uri)
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        super.onReceivedError(view, request, error)
                        val reqUrl = request?.url
                        if (reqUrl != null && webViewManager.isAuthCallback(reqUrl)) {
                            handleAuthCallbackUri(reqUrl)
                        }
                    }
                }
            }
            authWebView = webView
            authWebviewFrame.removeAllViews()
            authWebviewFrame.addView(webView)
        }

        authWebView?.loadUrl(url)
    }

    private fun hideAuthContainer() {
        authContainer.visibility = View.GONE
        authSuccessBanner.visibility = View.GONE
        authWebView?.stopLoading()
        authWebView?.loadUrl("about:blank")
    }

    private fun handleGrantStorageAccess() {
        val rootfsDir = runtimeController.paths.rootfsDir
        StoragePermissionHelper.verifyStorageAccessible(rootfsDir)
        StoragePermissionHelper.markStorageConfigured(this, rootfsDir.absolutePath)
        storageCard.visibility = View.GONE

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
    }

    private fun handleVsCodeActionClick() {
        val state = runtimeController.appState.value
        when {
            state is AppState.Ready -> {
                showEditorView()
            }
            state.isFailed -> {
                lifecycleScope.launch {
                    runtimeController.startAll(forceRestart = true)
                }
            }
            else -> {
                lifecycleScope.launch {
                    runtimeController.startVsCodeServer()
                }
            }
        }
    }

    private fun handleVsCodeStopClick() {
        lifecycleScope.launch {
            btnVsCodeStop.isEnabled = false
            btnVsCodeAction.isEnabled = false
            runtimeController.stopVsCodeServer()
        }
    }

    private fun observeRuntimeState() {
        lifecycleScope.launch {
            runtimeController.appState.collectLatest { state ->
                AvsLogger.d(TAG, "Observed AppState: $state")

                when (state) {
                    is AppState.NeedsStorageAccess -> {
                        showStoragePermissionCard()
                        showDashboardView()
                    }
                    is AppState.NotInstalled -> {
                        showDashboardView()
                        tvVsCodeStatusDesc.text = getString(R.string.vscode_status_stopped)
                        btnVsCodeAction.text = getString(R.string.start_vscode)
                        btnVsCodeAction.setIconResource(R.drawable.ic_play_arrow)
                        btnVsCodeAction.isEnabled = true
                        btnVsCodeStop.visibility = View.GONE
                        pbVsCodeStarting.visibility = View.GONE
                        ivVsCodeStatusDot.visibility = View.GONE
                    }
                    is AppState.DownloadingRootfs -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = getString(R.string.downloading_rootfs),
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.ExtractingRootfs -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = getString(R.string.extracting_rootfs),
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.RootfsReady -> {
                        showInstallationView(
                            title = getString(R.string.installing_linux),
                            step = "Ubuntu rootfs ready.",
                            progress = 100
                        )
                    }
                    is AppState.StartingLinux -> {
                        btnVsCodeStop.visibility = View.GONE
                        if (installationContainer.visibility == View.VISIBLE) {
                            showInstallationView(
                                title = getString(R.string.starting_linux),
                                step = "Starting Linux PRoot runtime...",
                                progress = 100,
                                isIndeterminate = true
                            )
                        } else {
                            tvVsCodeStatusDesc.text = getString(R.string.vscode_status_starting)
                            pbVsCodeStarting.visibility = View.VISIBLE
                            btnVsCodeAction.text = "Starting..."
                            btnVsCodeAction.isEnabled = false
                        }
                    }
                    is AppState.VerifyingLinux -> {
                        if (installationContainer.visibility == View.VISIBLE) {
                            showInstallationView(
                                title = getString(R.string.installing_linux),
                                step = "Verifying Linux environment...",
                                progress = 100,
                                isIndeterminate = true
                            )
                        }
                    }
                    is AppState.LinuxReady -> {
                        // Userspace ready
                        if (installationContainer.visibility == View.VISIBLE) {
                            showDashboardView()
                        }
                        statusBadge.text = "ONLINE"
                        tvVsCodeStatusDesc.text = getString(R.string.vscode_status_stopped)
                        btnVsCodeAction.text = getString(R.string.start_vscode)
                        btnVsCodeAction.setIconResource(R.drawable.ic_play_arrow)
                        btnVsCodeAction.isEnabled = true
                        btnVsCodeStop.visibility = View.GONE
                        pbVsCodeStarting.visibility = View.GONE
                        ivVsCodeStatusDot.visibility = View.GONE
                    }
                    is AppState.InstallingPackages -> {
                        showInstallationView(
                            title = getString(R.string.configuring_environment),
                            step = state.status,
                            progress = 50,
                            isIndeterminate = true
                        )
                    }
                    is AppState.InstallingVsCode -> {
                        val pct = (state.progress * 100).toInt()
                        showInstallationView(
                            title = "Installing VS Code CLI...",
                            step = state.status,
                            progress = pct
                        )
                    }
                    is AppState.VsCodeReady -> {
                        if (installationContainer.visibility == View.VISIBLE) {
                            showDashboardView()
                        }
                        tvVsCodeStatusDesc.text = getString(R.string.vscode_status_stopped)
                        btnVsCodeAction.text = getString(R.string.start_vscode)
                        btnVsCodeAction.setIconResource(R.drawable.ic_play_arrow)
                        btnVsCodeAction.isEnabled = true
                        btnVsCodeStop.visibility = View.GONE
                        pbVsCodeStarting.visibility = View.GONE
                        ivVsCodeStatusDot.visibility = View.GONE
                    }
                    is AppState.StartingAuthBridge -> {
                        tvVsCodeStatusDesc.text = "Starting Auth Bridge..."
                        pbVsCodeStarting.visibility = View.VISIBLE
                        btnVsCodeAction.text = "Starting..."
                        btnVsCodeAction.isEnabled = false
                        btnVsCodeStop.visibility = View.GONE
                    }
                    is AppState.StartingVsCodeServer -> {
                        tvVsCodeStatusDesc.text = getString(R.string.starting_vscode)
                        pbVsCodeStarting.visibility = View.VISIBLE
                        btnVsCodeAction.text = "Starting..."
                        btnVsCodeAction.isEnabled = false
                        btnVsCodeStop.visibility = View.GONE
                    }
                    is AppState.Ready -> {
                        // Ensure WebView is prepared
                        val sPort = runtimeController.serverPort ?: 0
                        val bPort = runtimeController.authBridgePort
                        webViewManager.setEndpoints(sPort, bPort, state.url)
                        attachAndLoadWebView(state.url)

                        // Update Dashboard Card
                        tvVsCodeStatusDesc.text = getString(R.string.vscode_status_running, state.url)
                        ivVsCodeStatusDot.visibility = View.VISIBLE
                        pbVsCodeStarting.visibility = View.GONE
                        btnVsCodeAction.text = getString(R.string.return_to_code)
                        btnVsCodeAction.setIconResource(R.drawable.ic_launch)
                        btnVsCodeAction.isEnabled = true
                        btnVsCodeStop.visibility = View.VISIBLE
                        btnVsCodeStop.isEnabled = true
                        statusBadge.text = "READY"

                        updatePortsList()

                        // If user was waiting during bootstrap, switch directly into the Editor
                        if (installationContainer.visibility == View.VISIBLE) {
                            showEditorView()
                        }
                    }
                    is AppState.Stopping -> {
                        tvVsCodeStatusDesc.text = getString(R.string.stopping_vscode)
                        ivVsCodeStatusDot.visibility = View.GONE
                        pbVsCodeStarting.visibility = View.VISIBLE
                        btnVsCodeAction.isEnabled = false
                        btnVsCodeStop.visibility = View.VISIBLE
                        btnVsCodeStop.isEnabled = false
                    }
                    is AppState.RootfsFailed -> {
                        showInstallationView(
                            title = "Installation Failed",
                            step = state.message,
                            progress = 0
                        )
                        btnInstallRetry.visibility = View.VISIBLE
                        btnVsCodeStop.visibility = View.GONE
                    }
                    is AppState.PackageInstallFailed -> {
                        showInstallationView(
                            title = "Package Setup Failed",
                            step = state.message,
                            progress = 0
                        )
                        btnInstallRetry.visibility = View.VISIBLE
                        btnVsCodeStop.visibility = View.GONE
                    }
                    is AppState.VsCodeFailed, is AppState.LinuxFailed, is AppState.Failed -> {
                        showDashboardView()
                        val msg = when (state) {
                            is AppState.VsCodeFailed -> state.message
                            is AppState.LinuxFailed -> state.message
                            is AppState.Failed -> state.message
                        }
                        tvVsCodeStatusDesc.text = msg
                        ivVsCodeStatusDot.visibility = View.GONE
                        pbVsCodeStarting.visibility = View.GONE
                        btnVsCodeAction.text = getString(R.string.retry)
                        btnVsCodeAction.setIconResource(R.drawable.ic_refresh)
                        btnVsCodeAction.isEnabled = true
                        btnVsCodeStop.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeTerminalLogs() {
        // Internal supervisor/runtime logs are monitored for diagnostics without polluting user terminal
        lifecycleScope.launch {
            runtimeController.terminalLogs.collect { line ->
                AvsLogger.d(TAG, "[SupervisorLog] $line")
            }
        }
    }

    private fun appendTerminalLine(line: String) {
        terminalSession.buffer.appendLine(line)
        updateTerminalDisplay(terminalSession.buffer.render())
    }

    private fun isTerminalAtBottom(): Boolean {
        val diff = (terminalOutput.bottom - (terminalScroll.height + terminalScroll.scrollY))
        return diff <= 120
    }

    private fun updateTerminalDisplay(rendered: String) {
        val isAtBottom = isTerminalAtBottom()
        terminalOutput.text = rendered
        tvCliPrompt.text = terminalSession.getPrompt()
        if (isAtBottom) {
            scrollTerminalToBottom()
        } else {
            btnScrollToBottom.visibility = View.VISIBLE
        }
    }

    private fun scrollTerminalToBottom() {
        terminalScroll.post {
            terminalScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun updateDesktopButtonState(isDesktop: Boolean) {
        btnToggleDesktop.setIconTintResource(if (isDesktop) R.color.accent else R.color.text_secondary)
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

    private fun submitCommand() {
        val cmd = commandInput.text.toString()
        if (cmd.trim().isEmpty()) return

        commandInput.setText("")
        lifecycleScope.launch {
            terminalSession.executeCommand(cmd) { rendered ->
                runOnUiThread {
                    updateTerminalDisplay(rendered)
                }
            }
        }
    }

    private fun startPortsAutoRefresh() {
        portsAutoRefreshJob?.cancel()
        portsAutoRefreshJob = lifecycleScope.launch {
            while (true) {
                if (dashboardContainer.visibility == View.VISIBLE) {
                    updatePortsList()
                }
                delay(4000)
            }
        }
    }

    private fun updatePortsList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val primaryPort = runtimeController.serverPort
            val bridgePort = runtimeController.authBridgePort

            // Query guest processes if Linux is running
            val guestProcessMap = try {
                if (runtimeController.linuxRuntime.state.value.isRunning) {
                    val ssOutput = runtimeController.linuxRuntime.execute("ss -tlnp 2>/dev/null || netstat -tlnp 2>/dev/null").getOrDefault("")
                    portScanner.parseGuestSockets(ssOutput)
                } else emptyMap()
            } catch (e: Exception) {
                emptyMap()
            }

            val ports = portScanner.scanPorts(
                primaryVsCodePort = primaryPort,
                authBridgePort = bridgePort,
                guestProcessMap = guestProcessMap
            )

            withContext(Dispatchers.Main) {
                layoutPortsList.removeAllViews()
                if (ports.isEmpty()) {
                    tvNoOpenPorts.visibility = View.VISIBLE
                    layoutPortsList.visibility = View.GONE
                } else {
                    tvNoOpenPorts.visibility = View.GONE
                    layoutPortsList.visibility = View.VISIBLE

                    for (port in ports) {
                        val itemView = layoutInflater.inflate(R.layout.item_open_port, layoutPortsList, false)
                        val badge = itemView.findViewById<TextView>(R.id.port_badge)
                        val serviceName = itemView.findViewById<TextView>(R.id.port_service_name)
                        val urlView = itemView.findViewById<TextView>(R.id.port_url)
                        val btnOpen = itemView.findViewById<MaterialButton>(R.id.btn_open_port)
                        val btnCopy = itemView.findViewById<MaterialButton>(R.id.btn_copy_port_url)

                        badge.text = port.port.toString()
                        serviceName.text = port.serviceName
                        urlView.text = port.url

                        btnOpen.setOnClickListener {
                            if (port.isPrimaryVsCode) {
                                showEditorView()
                            } else {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(port.url))
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    attachAndLoadWebView(port.url)
                                    showEditorView()
                                }
                            }
                        }

                        btnCopy.setOnClickListener {
                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("URL", port.url)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(this@MainActivity, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                        }

                        layoutPortsList.addView(itemView)
                    }
                }
            }
        }
    }

    private fun updateWorkspaceSummary() {
        val projects = workspaceArchiveManager.listProjects()
        val count = projects.size
        tvProjectsSummary.text = if (count == 1) "1 project in workspace" else "$count projects in workspace"
    }

    private fun updateAboutSection() {
        val info = aboutInfoProvider.getAboutInfo()
        tvAboutAppVer.text = info.appVersion
        tvAboutDistro.text = info.linuxDistro
        tvAboutVsCode.text = info.vsCodeVersion
        tvAboutAndroid.text = "${info.androidVersion} (API ${info.sdkInt})"
        tvAboutDevice.text = info.deviceModel
        tvAboutCpu.text = "${info.cpuArch} • ${info.kernelVersion}"

        // Asynchronously query live VS Code CLI version
        lifecycleScope.launch {
            val dynamicVer = withContext(Dispatchers.IO) {
                aboutInfoProvider.resolveDynamicVsCodeVersion(runtimeController.vscodeCli)
            }
            tvAboutVsCode.text = dynamicVer
        }
    }

    private fun showSettingsDialog() {
        val paths = runtimeController.paths
        val msg = StringBuilder()
            .append("Projects Directory:\n${paths.guestProjectsPath}\n\n")
            .append("Rootfs Location:\n${paths.rootfsDir.absolutePath}\n\n")
            .append("VS Code CLI Data:\n${paths.guestVsCodeDataDir}\n\n")
            .append("Server Port:\n${runtimeController.serverPort ?: "Inactive"}")
            .toString()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_title)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .setNeutralButton(R.string.clear_logs) { _, _ ->
                paths.serverLogFile.delete()
                paths.runtimeLogFile.delete()
                Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showExportProjectDialog() {
        val projects = workspaceArchiveManager.listProjects()
        if (projects.isEmpty()) {
            Toast.makeText(this, R.string.no_projects_found, Toast.LENGTH_SHORT).show()
            return
        }

        val projectNames = projects.map { it.name }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_project_to_export)
            .setItems(projectNames) { _, which ->
                val selectedProject = projects[which]
                showExportFormatDialog(selectedProject)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showExportFormatDialog(project: File) {
        val formats = ArchiveFormat.values()
        val formatNames = formats.map { it.displayName }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_export_format)
            .setItems(formatNames) { _, which ->
                val chosenFormat = formats[which]
                pendingExportProject = project
                pendingExportFormat = chosenFormat
                val suggestedFileName = "${project.name}${chosenFormat.extension}"
                exportArchiveLauncher.launch(suggestedFileName)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun handleImportArchive(uri: Uri) {
        val fileName = queryFileName(uri) ?: "imported_archive.zip"
        val format = ArchiveFormat.fromFileName(fileName)
        val baseName = fileName.substringBeforeLast(".").removeSuffix(".tar")
        val targetDir = File(runtimeController.paths.hostProjectsDir, baseName)

        Toast.makeText(this, "Importing $fileName...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Cannot open input stream for $uri")
                val count = stream.use { inStream ->
                    workspaceArchiveManager.importArchive(inStream, targetDir, format)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.import_success, count),
                        Toast.LENGTH_LONG
                    ).show()
                    updateWorkspaceSummary()
                }
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to import archive: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.operation_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun handleExportProject(project: File, format: ArchiveFormat, destUri: Uri) {
        Toast.makeText(this, "Exporting ${project.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val stream = contentResolver.openOutputStream(destUri)
                    ?: throw IllegalStateException("Cannot open output stream for $destUri")
                stream.use { outStream ->
                    workspaceArchiveManager.exportProject(project, outStream, format)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.export_success, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                AvsLogger.e(TAG, "Failed to export project: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.operation_failed, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                pendingExportProject = null
                pendingExportFormat = null
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    override fun onResume() {
        super.onResume()
        webViewManager.onResume()
        updatePortsList()
        updateWorkspaceSummary()
    }

    override fun onPause() {
        super.onPause()
        webViewManager.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && webViewAttached && editorContainer.visibility == View.VISIBLE) {
            if (webViewManager.handleKeyEvent(event)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        AvsLogger.i(TAG, "MainActivity destroyed")
        portsAutoRefreshJob?.cancel()
        authWebView?.stopLoading()
        authWebView?.destroy()
        authWebView = null
        webViewManager.destroy()
        webViewAttached = false
        super.onDestroy()
    }
}
