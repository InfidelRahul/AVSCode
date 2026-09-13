package com.avscode.web

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class VsCodeWebViewTest {

    @Test
    fun testZoomConstants() {
        assertEquals(75, VsCodeWebView.DEFAULT_ZOOM_LEVEL)
        assertEquals(40, VsCodeWebView.MIN_ZOOM_LEVEL)
        assertEquals(300, VsCodeWebView.MAX_ZOOM_LEVEL)
        assertEquals(10, VsCodeWebView.ZOOM_STEP)
    }

    @Test
    fun testDesktopUserAgentIsArmSpecific() {
        val ua = VsCodeWebView.DESKTOP_USER_AGENT
        assertNotNull(ua)
        assertTrue("Desktop UA must be ARM-specific (aarch64): $ua", ua.contains("Linux aarch64"))
        assertFalse("Desktop UA must NOT contain x86_64: $ua", ua.contains("x86_64"))
        assertFalse("Desktop UA must NOT contain x86: $ua", ua.contains("x86"))
        assertTrue("Desktop UA must contain Chrome token: $ua", ua.contains("Chrome/"))
    }

    @Test
    fun testResolveLinuxArchitecture() {
        assertEquals("aarch64", VsCodeWebView.resolveLinuxArchitecture("arm64-v8a"))
        assertEquals("aarch64", VsCodeWebView.resolveLinuxArchitecture("aarch64"))
        assertEquals("armv7l", VsCodeWebView.resolveLinuxArchitecture("armeabi-v7a"))
        assertEquals("armv7l", VsCodeWebView.resolveLinuxArchitecture("armv7l"))
        assertEquals("x86_64", VsCodeWebView.resolveLinuxArchitecture("x86_64"))
        assertEquals("i686", VsCodeWebView.resolveLinuxArchitecture("x86"))
        assertEquals("aarch64", VsCodeWebView.resolveLinuxArchitecture("unknown_arch"))
    }

    @Test
    fun testBuildDesktopUserAgentArm64() {
        val ua = VsCodeWebView.buildDesktopUserAgent(null, "arm64-v8a")
        assertTrue("Must contain Linux aarch64: $ua", ua.contains("Linux aarch64"))
        assertFalse("Must NOT contain x86_64: $ua", ua.contains("x86_64"))
        assertTrue("Must contain Chrome token: $ua", ua.contains("Chrome/"))
        assertTrue("Must start with Mozilla/5.0: $ua", ua.startsWith("Mozilla/5.0"))
    }

    @Test
    fun testBuildDesktopUserAgentArm32() {
        val ua = VsCodeWebView.buildDesktopUserAgent(null, "armeabi-v7a")
        assertTrue("Must contain Linux armv7l: $ua", ua.contains("Linux armv7l"))
        assertFalse("Must NOT contain x86_64: $ua", ua.contains("x86_64"))
    }

    @Test
    fun testBuildZoomJavaScriptDefault75() {
        val js = VsCodeWebView.buildZoomJavaScript(0.75)
        assertNotNull(js)
        assertTrue(js.contains("var factor = 0.7500;"))
        assertTrue("Root documentElement must receive zoom factor", js.contains("docEl.style.zoom = factor;"))
        assertTrue("Root documentElement must have 100% width", js.contains("docEl.style.width = '100%';"))
        assertTrue("Root documentElement must have 100% height", js.contains("docEl.style.height = '100%';"))
        assertTrue("Body zoom must be 1 to prevent double-scaling", js.contains("body.style.zoom = '1';"))
        assertTrue("Body must fill 100% width", js.contains("body.style.width = '100%';"))
        assertTrue("Body must fill 100% height", js.contains("body.style.height = '100%';"))
        assertTrue(js.contains(".monaco-workbench"))
        assertTrue(js.contains("window.dispatchEvent(new Event('resize'))"))
        assertTrue(js.contains("#181818"))
    }

    @Test
    fun testBuildZoomJavaScriptScalingFactors() {
        // Zoom-out: 0.5 (50%)
        val js50 = VsCodeWebView.buildZoomJavaScript(0.5)
        assertTrue(js50.contains("var factor = 0.5000;"))
        assertTrue(js50.contains("docEl.style.zoom = factor;"))
        assertTrue(js50.contains("body.style.zoom = '1';"))

        // Default 1.0 (100%)
        val js100 = VsCodeWebView.buildZoomJavaScript(1.0)
        assertTrue(js100.contains("var factor = 1.0000;"))
        assertTrue(js100.contains("docEl.style.zoom = factor;"))

        // Zoom-in: 1.5 (150%)
        val js150 = VsCodeWebView.buildZoomJavaScript(1.5)
        assertTrue(js150.contains("var factor = 1.5000;"))
        assertTrue(js150.contains("docEl.style.zoom = factor;"))
    }

    @Test
    fun testBuildZoomJavaScriptLocaleIndependence() {
        val originalLocale = Locale.getDefault()
        try {
            // Set locale that uses comma for decimals (e.g. Germany)
            Locale.setDefault(Locale.GERMANY)

            val js = VsCodeWebView.buildZoomJavaScript(0.75)
            // Must contain dot decimals, NEVER comma
            assertTrue("JS must use dot decimal: $js", js.contains("0.7500"))
            assertFalse("JS must not contain comma decimals in numbers", js.contains("0,7500"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun testIsAuthCallbackUrl() {
        // VS Code protocol callbacks
        assertTrue(VsCodeWebView.isAuthCallbackUrl("vscode://vscode.github-authentication/did-authenticate?code=123"))
        assertTrue(VsCodeWebView.isAuthCallbackUrl("vscode-insiders://vscode.github-authentication/did-authenticate?code=123"))
        assertTrue(VsCodeWebView.isAuthCallbackUrl("avscode://auth/callback?code=123"))

        // Web callback route
        assertTrue(VsCodeWebView.isAuthCallbackUrl("http://127.0.0.1:33000/callback?vscode-reqid=1&code=123"))
        assertTrue(VsCodeWebView.isAuthCallbackUrl("http://localhost:33000/callback?vscode-reqid=1&code=123"))
        assertTrue(VsCodeWebView.isAuthCallbackUrl("https://vscode.dev/redirect?code=123&state=abc"))

        // AuthBridge port callback
        assertTrue(VsCodeWebView.isAuthCallbackUrl("http://127.0.0.1:40000/auth/callback?code=123", 40000))

        // Normal editor/auth URLs should NOT be treated as callbacks
        assertFalse(VsCodeWebView.isAuthCallbackUrl("http://127.0.0.1:33000/"))
        assertFalse(VsCodeWebView.isAuthCallbackUrl("http://127.0.0.1:33000/?folder=/home/user/projects"))
        assertFalse(VsCodeWebView.isAuthCallbackUrl("https://github.com/login/oauth/authorize?client_id=123"))
    }
}
