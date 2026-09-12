package com.avscode.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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

    var onLoadingStateChanged: ((Boolean) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null

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
                    if (request?.isForMainFrame == true) {
                        val description = error?.description?.toString() ?: "Connection error"
                        AvsLogger.w(TAG, "Main frame error ($description): ${request.url}")
                        onConnectionError?.invoke(description)
                    }
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    // Keep all navigation within this WebView
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
}
