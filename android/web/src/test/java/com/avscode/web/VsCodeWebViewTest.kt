package com.avscode.web

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class VsCodeWebViewTest {

    @Test
    fun testZoomConstants() {
        assertEquals(40, VsCodeWebView.MIN_ZOOM_LEVEL)
        assertEquals(300, VsCodeWebView.MAX_ZOOM_LEVEL)
        assertEquals(10, VsCodeWebView.ZOOM_STEP)
    }

    @Test
    fun testBuildZoomJavaScriptDefault100() {
        val js = VsCodeWebView.buildZoomJavaScript(1.0)
        assertNotNull(js)
        assertTrue(js.contains("var factor = 1.0000;"))
        assertTrue(js.contains("100.000vw"))
        assertTrue(js.contains("100.000vh"))
        assertTrue(js.contains("body.style.zoom = factor;"))
        assertTrue(js.contains(".monaco-workbench"))
        assertTrue(js.contains("window.dispatchEvent(new Event('resize'))"))
        assertTrue(js.contains("#181818"))
    }

    @Test
    fun testBuildZoomJavaScriptZoomOutInverseCalculation() {
        // 80% zoom out: body needs to be (100 / 0.8) = 125vw by 125vh so visual rendering is 100vw x 100vh
        val js80 = VsCodeWebView.buildZoomJavaScript(0.8)
        assertTrue(js80.contains("var factor = 0.8000;"))
        assertTrue(js80.contains("125.000vw"))
        assertTrue(js80.contains("125.000vh"))

        // 50% zoom out: body needs to be 200vw by 200vh
        val js50 = VsCodeWebView.buildZoomJavaScript(0.5)
        assertTrue(js50.contains("var factor = 0.5000;"))
        assertTrue(js50.contains("200.000vw"))
        assertTrue(js50.contains("200.000vh"))
    }

    @Test
    fun testBuildZoomJavaScriptZoomInInverseCalculation() {
        // 125% zoom in: body needs to be (100 / 1.25) = 80vw by 80vh so visual rendering is 100vw x 100vh
        val js125 = VsCodeWebView.buildZoomJavaScript(1.25)
        assertTrue(js125.contains("var factor = 1.2500;"))
        assertTrue(js125.contains("80.000vw"))
        assertTrue(js125.contains("80.000vh"))

        // 200% zoom in: body needs to be (100 / 2.0) = 50vw by 50vh
        val js200 = VsCodeWebView.buildZoomJavaScript(2.0)
        assertTrue(js200.contains("var factor = 2.0000;"))
        assertTrue(js200.contains("50.000vw"))
        assertTrue(js200.contains("50.000vh"))
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
            assertTrue("JS must use dot decimal in dimensions: $js", js.contains("133.333vw"))
            assertTrue("JS must use dot decimal in dimensions: $js", js.contains("133.333vh"))
            assertFalse("JS must not contain comma decimals in numbers", js.contains("0,7500"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
