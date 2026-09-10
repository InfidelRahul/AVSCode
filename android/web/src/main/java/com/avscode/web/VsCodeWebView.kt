package com.avscode.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.avscode.core.AvsLogger

/**
 * WebView manager for displaying VS Code Web interface.
 */
class VsCodeWebView(private val context: Context) {
    
    companion object {
        private const val TAG = "VsCodeWebView"
    }
    
    private var webView: WebView? = null
    private var isReady = false
    
    /**
     * Create and configure the WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        AvsLogger.d(TAG, "Creating WebView")
        
        val view = WebView(context).apply {
            // Enable JavaScript - required for VS Code Web
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Enable geolocation (some extensions may need it)
                geolocationEnabled = true
                
                // Enable media playback
                mediaPlaybackRequiresUserGesture = false
            }
            
            // Handle page navigation within WebView
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AvsLogger.d(TAG, "Page finished loading: $url")
                    isReady = true
                }
                
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    url: String?
                ): Boolean {
                    // Keep all navigation within the WebView
                    return false
                }
            }
            
            // Handle JavaScript console messages
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                    consoleMessage?.let { msg ->
                        when (msg.messageLevel()) {
                            android.webkit.ConsoleMessage.MessageLevel.ERROR -> 
                                AvsLogger.e(TAG, "JS: ${msg.message()}")
                            android.webkit.ConsoleMessage.MessageLevel.WARNING -> 
                                AvsLogger.w(TAG, "JS: ${msg.message()}")
                            else -> 
                                AvsLogger.d(TAG, "JS: ${msg.message()}")
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
        AvsLogger.i(TAG, "Loading VS Code Web: $url")
        webView?.loadUrl(url)
    }
    
    /**
     * Check if WebView is ready.
     */
    fun isReady(): Boolean = isReady
    
    /**
     * Handle back button press.
     */
    fun handleBackPress(): Boolean {
        return webView?.canGoBack() == true && webView?.goBack().let { true }
    }
    
    /**
     * Handle keyboard events for special keys.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        // Forward key events to WebView
        webView?.dispatchKeyEvent(event)
        return true
    }
    
    /**
     * Resume WebView.
     */
    fun onResume() {
        webView?.onResume()
    }
    
    /**
     * Pause WebView.
     */
    fun onPause() {
        webView?.onPause()
    }
    
    /**
     * Destroy WebView and cleanup resources.
     */
    fun destroy() {
        AvsLogger.d(TAG, "Destroying WebView")
        webView?.destroy()
        webView = null
        isReady = false
    }
}
