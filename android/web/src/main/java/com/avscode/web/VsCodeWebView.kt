package com.avscode.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.View
import android.webkit.*
import com.avscode.core.AvsLogger

/**
 * WebView manager for displaying VS Code Web interface.
 * Optimized for VS Code editor rendering, keyboard inputs, and connection recovery.
 */
class VsCodeWebView(private val context: Context) {

    companion object {
        private const val TAG = "VsCodeWebView"
    }

    private var webView: WebView? = null
    private var isReady = false
    private var lastLoadedUrl: String? = null

    var serverPort: Int? = null
        private set
    var authBridgePort: Int? = null
        private set
    var editorUrl: String? = null
        private set
    private var inAuthFlow = false

    var onLoadingStateChanged: ((Boolean) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onAuthCallbackReceived: ((Uri) -> Boolean)? = null

    /**
     * Create and configure the WebView for VS Code Web.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        if (webView != null) {
            return webView!!
        }

        AvsLogger.d(TAG, "Creating and configuring WebView for VS Code")

        val view = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AvsLogger.d(TAG, "Page started loading: $url")
                    onLoadingStateChanged?.invoke(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AvsLogger.i(TAG, "VS Code Web page finished loading: $url")
                    isReady = true
                    onLoadingStateChanged?.invoke(false)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    val reqUrl = request?.url
                    if (request?.isForMainFrame == true) {
                        // Suppress connection error if caused by an auth callback redirect
                        if (reqUrl != null && isAuthBridgeCallback(reqUrl)) {
                            AvsLogger.w(TAG, "Auth callback reached onReceivedError; intercepting directly: $reqUrl")
                            onAuthCallbackReceived?.invoke(reqUrl)
                            restoreEditor()
                            return
                        }
                        val description = error?.description?.toString() ?: "Connection error"
                        AvsLogger.w(TAG, "Main frame error ($description): $reqUrl")
                        onConnectionError?.invoke(description)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val uri = request?.url ?: return false
                    val uriStr = uri.toString()
                    AvsLogger.d(TAG, "shouldOverrideUrlLoading: $uriStr")

                    // 1. Intercept Android <-> Linux AuthBridge callbacks
                    if (isAuthBridgeCallback(uri)) {
                        AvsLogger.i(TAG, "Intercepted AuthBridge callback URL: $uriStr")
                        val handled = onAuthCallbackReceived?.invoke(uri) ?: true
                        if (handled) {
                            restoreEditor()
                            return true
                        }
                    }

                    // 2. Pass-through VS Code Server application URLs
                    if (isVsCodeServerUrl(uri)) {
                        inAuthFlow = false
                        return false
                    }

                    // 3. External OAuth authentication provider URLs (Google, GitHub, Microsoft)
                    // Must remain strictly within this Android WebView (no Chrome/external browser)
                    val host = uri.host.orEmpty()
                    if (!host.equals("127.0.0.1", ignoreCase = true) && !host.equals("localhost", ignoreCase = true)) {
                        AvsLogger.i(TAG, "Executing in-app OAuth authentication inside WebView: $uriStr")
                        inAuthFlow = true
                        return false
                    }

                    return false
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let { msg ->
                        when (msg.messageLevel()) {
                            ConsoleMessage.MessageLevel.ERROR ->
                                AvsLogger.e(TAG, "VSCode Web JS: ${msg.message()}")
                            ConsoleMessage.MessageLevel.WARNING ->
                                AvsLogger.w(TAG, "VSCode Web JS: ${msg.message()}")
                            else ->
                                AvsLogger.d(TAG, "VSCode Web JS: ${msg.message()}")
                        }
                    }
                    return true
                }
            }
        }

        webView = view
        return view
    }

    /**
     * Load VS Code Web URL.
     */
    fun loadUrl(url: String) {
        lastLoadedUrl = url
        AvsLogger.i(TAG, "Loading URL in WebView: $url")
        webView?.loadUrl(url)
    }

    /**
     * Reload current page.
     */
    fun reload() {
        lastLoadedUrl?.let { loadUrl(it) } ?: webView?.reload()
    }

    fun isReady(): Boolean = isReady

    /**
     * Forward back button press to WebView history if available.
     */
    fun handleBackPress(): Boolean {
        return if (webView?.canGoBack() == true) {
            webView?.goBack()
            true
        } else {
            false
        }
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        return webView?.dispatchKeyEvent(event) ?: false
    }

    fun onResume() {
        webView?.onResume()
    }

    fun onPause() {
        webView?.onPause()
    }

    fun destroy() {
        AvsLogger.d(TAG, "Destroying WebView")
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        isReady = false
    }

    /**
     * Configure runtime endpoints to distinguish VS Code Server from AuthBridge.
     */
    fun setEndpoints(serverPort: Int, authBridgePort: Int, editorUrl: String? = null) {
        this.serverPort = serverPort
        this.authBridgePort = authBridgePort
        if (editorUrl != null) {
            this.editorUrl = editorUrl
        }
        AvsLogger.i(TAG, "Configured endpoints: serverPort=$serverPort, authBridgePort=$authBridgePort, editorUrl=$editorUrl")
    }

    /**
     * Restores the VS Code editor UI inside the WebView.
     */
    fun restoreEditor() {
        inAuthFlow = false
        val target = editorUrl ?: serverPort?.let { "http://127.0.0.1:$it/?folder=/home/user/projects" }
        if (target != null) {
            AvsLogger.i(TAG, "Restoring VS Code editor in WebView: $target")
            loadUrl(target)
        }
    }

    fun isInAuthFlow(): Boolean = inAuthFlow

    fun cancelAuthAndRestoreEditor() {
        AvsLogger.i(TAG, "Cancelling auth flow and restoring editor")
        restoreEditor()
    }

    /**
     * Checks if a given URI is an OAuth callback intended for Android AuthBridge.
     */
    fun isAuthBridgeCallback(uri: Uri): Boolean {
        if (uri.scheme == "avscode") return true
        val path = uri.path.orEmpty()
        if (path.contains("auth/callback") || path.contains("/auth/callback")) return true
        val port = if (uri.port != -1) uri.port else null
        if (authBridgePort != null && port == authBridgePort && path.contains("callback")) return true
        return false
    }

    /**
     * Checks if a given URI belongs to the local Linux VS Code Server.
     */
    fun isVsCodeServerUrl(uri: Uri): Boolean {
        val port = if (uri.port != -1) uri.port else null
        if (serverPort != null && port == serverPort) return true
        val host = uri.host.orEmpty()
        if ((host.equals("127.0.0.1", ignoreCase = true) || host.equals("localhost", ignoreCase = true)) &&
            serverPort != null && port == serverPort
        ) {
            return true
        }
        if (editorUrl != null && uri.toString().startsWith(editorUrl!!)) return true
        return false
    }
}
