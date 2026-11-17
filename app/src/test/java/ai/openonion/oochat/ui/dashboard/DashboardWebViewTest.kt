package ai.openonion.oochat.ui.dashboard

import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowWebView
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/** Verifies the Android half of the untrusted dashboard sandbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardWebViewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var blocked = 0

    private var document by mutableStateOf("<h1>Dashboard</h1>")

    private fun showWebView(first: String = "<h1>Dashboard</h1>") {
        document = first
        composeRule.setContent {
            DashboardWebView(
                document = document,
                onRunSkill = { _, _ -> },
                onNavigationBlocked = { blocked++ }
            )
        }
        composeRule.waitForIdle()
    }

    private fun loaded(view: WebView): String? = shadowOf(view).lastLoadDataWithBaseURL?.data

    private fun webView(): WebView {
        val found = mutableListOf<WebView>()
        fun walk(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(composeRule.activity.window.decorView)
        return found.single()
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test
    fun `untrusted content cannot use WebView file content storage or window capabilities`() {
        showWebView()

        val view = webView()
        val settings = view.settings
        assertTrue("the nonced bridge requires JavaScript", settings.javaScriptEnabled)
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        assertFalse(settings.allowFileAccessFromFileURLs)
        assertFalse(settings.allowUniversalAccessFromFileURLs)
        assertFalse(settings.domStorageEnabled)
        assertFalse(settings.javaScriptCanOpenWindowsAutomatically)
        assertFalse(settings.supportMultipleWindows())
        assertNull("permission and dialog requests must not be surfaced", shadowOf(view).webChromeClient)
    }

    @Test
    fun `the snapshot is loaded with an opaque origin`() {
        val document = "<h1>Dashboard</h1>"
        showWebView(document)

        val load = shadowOf(webView()).lastLoadDataWithBaseURL
        assertNotNull(load)
        assertNull(load.baseUrl)
        assertEquals(document, load.data)
        assertEquals("text/html", load.mimeType)
        assertEquals("utf-8", load.encoding)
        assertNull(load.historyUrl)
    }

    @Test
    fun `only the defined skill bridge is exposed to JavaScript`() {
        showWebView()

        val shadow = shadowOf(webView())
        val interfaces = registeredInterfaces(shadow)
        assertEquals(setOf(DashboardDocument.BRIDGE_NAME), interfaces.keys)

        val bridge = interfaces.getValue(DashboardDocument.BRIDGE_NAME)
        val callable = bridge.javaClass.declaredMethods.filter {
            it.isAnnotationPresent(JavascriptInterface::class.java)
        }
        assertEquals(listOf("runSkill"), callable.map { it.name })
        assertEquals(listOf(String::class.java, String::class.java), callable.single().parameterTypes.toList())
    }

    @Test
    fun `every attempted navigation is refused and reported`() {
        showWebView()

        val view = webView()
        val client = shadowOf(view).webViewClient
        val destinations = listOf(
            "https://example.test/collect",
            "file:///data/local/tmp/secret",
            "content://ai.openonion.oochat/secret",
            "intent://dashboard#Intent;scheme=https;end"
        )

        destinations.forEach { destination ->
            assertTrue(destination, client.shouldOverrideUrlLoading(view, request(destination)))
        }
        assertEquals(destinations.size, blocked)
    }

    @Test
    fun `a newer snapshot reloads the same WebView in place`() {
        showWebView("<h1>v1</h1>")
        val view = webView()

        document = "<h1>v2</h1>"
        composeRule.waitForIdle()

        assertSame(view, webView())
        assertEquals("<h1>v2</h1>", loaded(view))
    }

    @Test
    fun `a theme change repaints the background without reloading the page`() {
        var surface by mutableStateOf(Color(0xFF101F14))
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = surface)) {
                DashboardWebView(document = "<h1>v1</h1>", onRunSkill = { _, _ -> }, onNavigationBlocked = {})
            }
        }
        composeRule.waitForIdle()
        val before = shadowOf(webView()).lastLoadDataWithBaseURL

        surface = Color(0xFFF4F1E8)
        composeRule.waitForIdle()

        // The new colour shows the update ran; a reload there would throw away
        // the user's scroll and filter text.
        assertEquals(Color(0xFFF4F1E8).toArgb(), shadowOf(webView()).backgroundColor)
        assertSame(before, shadowOf(webView()).lastLoadDataWithBaseURL)
    }

    @Test
    fun `the WebView paints the theme surface until a page draws`() {
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme(surface = Color(0xFF101F14))) {
                DashboardWebView(document = "<h1>v1</h1>", onRunSkill = { _, _ -> }, onNavigationBlocked = {})
            }
        }
        composeRule.waitForIdle()

        assertEquals(Color(0xFF101F14).toArgb(), shadowOf(webView()).backgroundColor)
    }

    @Test
    fun `the screen wraps the snapshot off the main thread and loads it`() {
        composeRule.setContent { screen(html = "<h1>v1</h1>") }

        composeRule.waitUntil(5_000) { loadedOrNull() != null }
        val document = loadedOrNull()!!
        assertTrue(document.contains("<h1>v1</h1>"))
        assertTrue("the wrapper, not the raw snapshot", document.contains("Content-Security-Policy"))
    }

    @Test
    fun `the screen draws while a held-back build has not finished, so the build is off the main thread`() {
        val held = mutableListOf<Runnable>()
        val gate = Executor { held += it }.asCoroutineDispatcher()
        composeRule.setContent { screen(html = "<h1>v1</h1>", buildDispatcher = gate) }
        composeRule.waitForIdle()

        assertTrue("the wrap was handed to the build dispatcher", held.isNotEmpty())
        composeRule.onNodeWithText("Agent home").assertIsDisplayed()
        composeRule.onNodeWithText("No Home page yet").assertDoesNotExist()
        assertNull("nothing is loaded while the build is held", loadedOrNull())

        thread { held.toList().forEach(Runnable::run) }.join()
        composeRule.waitUntil(5_000) { loadedOrNull() != null }
        assertTrue(loadedOrNull()!!.contains("<h1>v1</h1>"))
    }

    @Test
    fun `the screen never claims there is no page while a snapshot is being wrapped`() {
        composeRule.setContent { screen(html = "<h1>v1</h1>") }

        composeRule.onNodeWithText("No Home page yet").assertDoesNotExist()
    }

    @Test
    fun `a newer snapshot keeps the old page up while it is wrapped`() {
        val held = mutableListOf<Runnable>()
        val gate = Executor { held += it }.asCoroutineDispatcher()
        var html by mutableStateOf("<h1>v1</h1>")
        composeRule.setContent { screen(html = html, buildDispatcher = gate) }
        composeRule.waitForIdle()
        thread { held.toList().forEach(Runnable::run) }.join()
        held.clear()
        composeRule.waitUntil(5_000) { loadedOrNull() != null }

        html = "<h1>v2</h1>"
        composeRule.waitForIdle()

        assertTrue("v2 is still being wrapped", held.isNotEmpty())
        assertTrue("v1 stays loaded meanwhile", loadedOrNull()!!.contains("<h1>v1</h1>"))
        composeRule.onNodeWithText("No Home page yet").assertDoesNotExist()

        thread { held.toList().forEach(Runnable::run) }.join()
        composeRule.waitUntil(5_000) { loadedOrNull()!!.contains("<h1>v2</h1>") }
    }

    @Test
    fun `a cleared snapshot takes the page down in the same frame`() {
        var html by mutableStateOf<String?>("<h1>v1</h1>")
        composeRule.setContent { screen(html = html) }
        composeRule.waitUntil(5_000) { loadedOrNull() != null }

        // One frame only: the build state catches up a frame later, and until
        // then it still holds the old page.
        composeRule.mainClock.autoAdvance = false
        html = null
        Snapshot.sendApplyNotifications()
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithText("No Home page yet").assertIsDisplayed()
        assertNull("the old page is gone", loadedOrNull())
    }

    @Test
    fun `a 2 MiB snapshot is wrapped and loaded whole`() {
        val big = "<p>" + "x".repeat(2 * 1024 * 1024 - 7) + "</p>"
        composeRule.setContent { screen(html = big) }

        composeRule.waitUntil(10_000) { loadedOrNull() != null }
        assertTrue(loadedOrNull()!!.contains(big))
    }

    @Test
    fun `the default build dispatcher is not the main thread`() = runBlocking {
        assertNotSame(Looper.getMainLooper(), withContext(DashboardBuildDispatcher) { Looper.myLooper() })
    }

    /** What the screen's WebView has loaded, or null before it exists or loads. */
    private fun loadedOrNull(): String? {
        val found = mutableListOf<WebView>()
        fun walk(view: View) {
            if (view is WebView) found += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
        }
        walk(composeRule.activity.window.decorView)
        return found.singleOrNull()?.let(::loaded)
    }

    @Composable
    private fun screen(html: String?, buildDispatcher: CoroutineDispatcher = DashboardBuildDispatcher) = ConnectOnionTheme {
        DashboardScreen(
            html = html,
            allowedSkills = emptyList(),
            onRunSkill = {},
            onNavigateBack = {},
            buildDispatcher = buildDispatcher
        )
    }

    /** Robolectric records every addJavascriptInterface call but exposes lookups only by name. */
    @Suppress("UNCHECKED_CAST")
    private fun registeredInterfaces(shadow: ShadowWebView): Map<String, Any> {
        val field = ShadowWebView::class.java.getDeclaredField("javascriptInterfaces")
        field.isAccessible = true
        return field.get(shadow) as Map<String, Any>
    }
}
