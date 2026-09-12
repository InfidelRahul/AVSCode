package com.avscode.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.webkit.*
import com.avscode.core.AvsLogger
import java.util.Locale

/**
 * WebView manager for displaying VS Code Web interface.
 * Optimized for VS Code editor rendering, keyboard inputs, and connection recovery.
 */
class VsCodeWebView(private val context: Context) {

    companion object {
        private const val TAG = "VsCodeWebView"
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        const val MIN_ZOOM_LEVEL = 40
        const val MAX_ZOOM_LEVEL = 300
        const val ZOOM_STEP = 10

        /**
         * Builds responsive CSS zoom JavaScript with inverse viewport scaling.
         * Uses Locale.US to ensure dot decimals in JS/CSS numbers across all device locales.
         */
        fun buildZoomJavaScript(factor: Double): String {
            val fStr = String.format(Locale.US, "%.4f", factor)
            val invW = String.format(Locale.US, "%.3fvw", 100.0 / factor)
            val invH = String.format(Locale.US, "%.3fvh", 100.0 / factor)

            return """
                (function() {
                    try {
                        var factor = $fStr;
                        window.__avsCurrentZoomFactor = factor;

                        var docEl = document.documentElement;
                        var body = document.body;
                        if (!docEl || !body) return;

                        // Ensure root document fills the exact viewport with no overflow or margins
                        docEl.style.width = '100vw';
                        docEl.style.height = '100vh';
                        docEl.style.maxWidth = '100vw';
                        docEl.style.maxHeight = '100vh';
                        docEl.style.margin = '0px';
                        docEl.style.padding = '0px';
                        docEl.style.overflow = 'hidden';
                        docEl.style.backgroundColor = '#181818';
                        docEl.style.zoom = '1';

                        // Set inverse dimensions so scaled body fills 100% of viewport
                        body.style.zoom = factor;
                        body.style.width = '$invW';
                        body.style.height = '$invH';
                        body.style.maxWidth = '$invW';
                        body.style.maxHeight = '$invH';
                        body.style.minWidth = '$invW';
                        body.style.minHeight = '$invH';
                        body.style.position = 'absolute';
                        body.style.top = '0px';
                        body.style.left = '0px';
                        body.style.margin = '0px';
                        body.style.padding = '0px';
                        body.style.overflow = 'hidden';
                        body.style.backgroundColor = '#181818';

                        // Ensure monaco-workbench fills the layout body
                        var workbench = document.querySelector('.monaco-workbench');
                        if (workbench) {
                            workbench.style.width = '100%';
                            workbench.style.height = '100%';
                        }

                        // Install responsive listener for screen rotation (portrait/landscape)
                        if (!window.__avsResizeListenerInstalled) {
                            window.__avsResizeListenerInstalled = true;
                            window.addEventListener('resize', function() {
                                if (window.__avsCurrentZoomFactor && window.__avsCurrentZoomFactor !== 1.0) {
                                    var f = window.__avsCurrentZoomFactor;
                                    var b = document.body;
                                    if (b) {
                                        var nw = (100.0 / f).toFixed(3) + 'vw';
                                        var nh = (100.0 / f).toFixed(3) + 'vh';
                                        b.style.width = nw;
                                        b.style.height = nh;
                                        b.style.maxWidth = nw;
                                        b.style.maxHeight = nh;
                                    }
                                }
                            });
                        }

                        // Install observer so when monaco-workbench mounts asynchronously, it layouts immediately
                        if (!window.__avsWorkbenchObserverInstalled) {
                            window.__avsWorkbenchObserverInstalled = true;
                            var obs = new MutationObserver(function() {
                                var wb = document.querySelector('.monaco-workbench');
                                if (wb && !wb.__avsZoomApplied) {
                                    wb.__avsZoomApplied = true;
                                    window.dispatchEvent(new Event('resize'));
                                }
                            });
                            obs.observe(docEl, { childList: true, subtree: true });
                        }

                    // Force VS Code workbench layout recalculation
                    window.dispatchEvent(new Event('resize'));
                } catch(e) {}
            })();
        """.trimIndent()
        }
    }

    private var webView: WebView? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var isReady = false
    private var lastLoadedUrl: String? = null

    var isDesktopMode: Boolean = true
        private set
    var currentZoomLevel: Int = 100
        private set

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
    var onZoomChanged: ((Int) -> Unit)? = null
    var onDesktopModeChanged: ((Boolean) -> Unit)? = null

    /**
     * Create and configure the WebView for VS Code Web.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        if (webView != null) {
            return webView!!
        }

        AvsLogger.d(TAG, "Creating and configuring WebView for VS Code (DesktopMode=$isDesktopMode)")

        scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var lastScaleTime = 0L

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastScaleTime < 100) return false
                val factor = detector.scaleFactor
                if (factor > 1.05f) {
                    zoomIn()
                    lastScaleTime = now
                    return true
                } else if (factor < 0.95f) {
                    zoomOut()
                    lastScaleTime = now
                    return true
                }
                return false
            }
        })

        val view = WebView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            setBackgroundColor(Color.parseColor("#181818"))

            setOnTouchListener { _, event ->
                if (event.pointerCount > 1) {
                    scaleGestureDetector?.onTouchEvent(event) ?: false
                } else {
                    false
                }
            }

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                if (isDesktopMode) {
                    userAgentString = DESKTOP_USER_AGENT
                }
            }

            // Ensure CookieManager preserves cookies and session credentials across restarts
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    AvsLogger.d(TAG, "Page started loading: $url")
                    injectViewportOverride(view)
                    onLoadingStateChanged?.invoke(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    AvsLogger.i(TAG, "VS Code Web page finished loading: $url")
                    isReady = true
                    injectViewportOverride(view)
                    applyZoom()
                    view?.postDelayed({ applyZoom() }, 500)
                    view?.postDelayed({ applyZoom() }, 1500)
                    CookieManager.getInstance().flush()
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
        CookieManager.getInstance().flush()
        webView?.onPause()
    }

    fun destroy() {
        AvsLogger.d(TAG, "Destroying WebView")
        CookieManager.getInstance().flush()
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
        CookieManager.getInstance().flush()
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

    /**
     * Injects JavaScript to lock the viewport meta tag to screen bounds,
     * preventing blurry camera-level scaling and horizontal viewport clipping.
     */
    fun injectViewportOverride(view: WebView?) {
        val js = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        document.head.appendChild(meta);
                    }
                    meta.setAttribute('content', 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no');
                } catch(e) {}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    /**
     * Zooms in editor UI responsively without viewport overflow or horizontal scroll clipping.
     */
    fun zoomIn(): Int {
        if (currentZoomLevel < MAX_ZOOM_LEVEL) {
            currentZoomLevel = (currentZoomLevel + ZOOM_STEP).coerceAtMost(MAX_ZOOM_LEVEL)
            applyZoom()
            onZoomChanged?.invoke(currentZoomLevel)
        }
        return currentZoomLevel
    }

    /**
     * Zooms out editor UI responsively without leaving empty white margins.
     */
    fun zoomOut(): Int {
        if (currentZoomLevel > MIN_ZOOM_LEVEL) {
            currentZoomLevel = (currentZoomLevel - ZOOM_STEP).coerceAtLeast(MIN_ZOOM_LEVEL)
            applyZoom()
            onZoomChanged?.invoke(currentZoomLevel)
        }
        return currentZoomLevel
    }

    /**
     * Resets zoom to default 100%.
     */
    fun resetZoom(): Int {
        currentZoomLevel = 100
        applyZoom()
        onZoomChanged?.invoke(currentZoomLevel)
        return currentZoomLevel
    }

    /**
     * Directly sets zoom level percentage.
     */
    fun setZoomLevel(level: Int) {
        currentZoomLevel = level.coerceIn(MIN_ZOOM_LEVEL, MAX_ZOOM_LEVEL)
        applyZoom()
        onZoomChanged?.invoke(currentZoomLevel)
    }

    /**
     * Applies responsive CSS zoom to scale the VS Code editor UI cleanly.
     * Inverse width and height calculations ensure the scaled workspace always occupies
     * exactly 100% of the viewport with no white margins on zoom-out and no clipping on zoom-in.
     */
    fun applyZoom() {
        val factor = currentZoomLevel / 100.0
        val js = buildZoomJavaScript(factor)
        webView?.evaluateJavascript(js, null)
    }

    /**
     * Toggles between Desktop Mode and Mobile Mode and reloads WebView.
     */
    fun toggleDesktopMode(): Boolean {
        isDesktopMode = !isDesktopMode
        val newUa = if (isDesktopMode) DESKTOP_USER_AGENT else null
        webView?.settings?.userAgentString = newUa
        AvsLogger.i(TAG, "Toggled desktop mode to $isDesktopMode (UA: $newUa)")
        onDesktopModeChanged?.invoke(isDesktopMode)
        reload()
        return isDesktopMode
    }
}
