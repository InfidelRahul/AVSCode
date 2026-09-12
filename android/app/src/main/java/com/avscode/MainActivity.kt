package com.avscode

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.avscode.core.AppState
import com.avscode.core.AvsLogger
import com.avscode.web.VsCodeWebView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Main activity for AVSCode — VS Code for Android.
 * Communicates with the single authoritative RuntimeController.
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

    private lateinit var runtimeController: RuntimeController
    private lateinit var webViewManager: VsCodeWebView
    private var webViewAttached = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AvsLogger.i(TAG, "MainActivity created")

        initViews()

        runtimeController = RuntimeController.getInstance(this)
        webViewManager = VsCodeWebView(this)

        webViewManager.onConnectionError = { err ->
            showError("Failed to connect to VS Code: $err")
        }

        observeRuntimeState()

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!webViewManager.handleBackPress()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // Trigger startup if needed
        lifecycleScope.launch {
            if (runtimeController.appState.value !is AppState.Ready) {
                runtimeController.startAll()
            }
        }
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
            showLoading("Retrying startup...")
            lifecycleScope.launch {
                runtimeController.startAll(forceRestart = true)
            }
        }
    }

    private fun observeRuntimeState() {
        lifecycleScope.launch {
            runtimeController.appState.collectLatest { state ->
                AvsLogger.d(TAG, "Observed AppState: $state")
                when (state) {
                    is AppState.NotInstalled -> {
                        showLoading("Preparing installation...")
                    }
                    is AppState.InstallingRootfs -> {
                        showLoading("Installing Ubuntu Linux...")
                        updateStatus(state.status)
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = (state.progress * 100).toInt()
                    }
                    is AppState.StartingLinux -> {
                        progressBar.visibility = View.GONE
                        showLoading("Starting Linux userspace...")
                        updateStatus("Initializing PRoot supervisor...")
                    }
                    is AppState.Bootstrapping -> {
                        progressBar.visibility = View.GONE
                        showLoading("Bootstrapping development tools...")
                        updateStatus(state.status)
                    }
                    is AppState.StartingVsCode -> {
                        progressBar.visibility = View.GONE
                        showLoading("Starting VS Code Server...")
                        updateStatus("Launching editor on port 8080...")
                    }
                    is AppState.Ready -> {
                        progressBar.visibility = View.GONE
                        loadingOverlay.visibility = View.GONE
                        errorOverlay.visibility = View.GONE

                        attachAndLoadWebView(state.url)
                    }
                    is AppState.Stopping -> {
                        showLoading("Stopping runtime...")
                    }
                    is AppState.Failed -> {
                        progressBar.visibility = View.GONE
                        showError(state.message)
                    }
                }
            }
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

    private fun showLoading(message: String) {
        loadingText.text = message
        loadingOverlay.visibility = View.VISIBLE
        errorOverlay.visibility = View.GONE
    }

    private fun updateStatus(status: String) {
        statusText.text = status
        statusText.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        errorMessage.text = message
        loadingOverlay.visibility = View.GONE
        errorOverlay.visibility = View.VISIBLE
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
        if (event != null && webViewManager.handleKeyEvent(event)) {
            return true
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
