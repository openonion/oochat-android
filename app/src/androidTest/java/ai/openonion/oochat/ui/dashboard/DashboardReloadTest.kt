package ai.openonion.oochat.ui.dashboard

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * A newer snapshot replacing an older one on a real WebView, which is the one
 * thing the Robolectric tests cannot see: Robolectric never draws. Here every
 * frame on screen is sampled across the reload.
 *
 * Each snapshot paints the whole page one solid colour, and the WebView's own
 * background (what a blank frame shows) is a third. Any sample that is not
 * the old or the new snapshot's colour is a blank (or foreign) frame. The
 * reload is in place, and this is what shows Chromium's paint holding keeps
 * it from flashing; it fails if the WebView is made to show a page early.
 *
 * Deliberately free of Espresso (and so of the Compose test rule, which idles
 * through it): espresso-core 3.5.1 cannot start on API 37, and this test is
 * most useful on the newest WebView.
 */
class DashboardReloadTest {

    private var document by mutableStateOf("")

    private lateinit var scenario: ActivityScenario<ComponentActivity>

    /** Centre-pixel colour of every sampled frame, in the order the frames were drawn. */
    private val samples = ConcurrentLinkedQueue<Int>()

    @Volatile private var sampling = false

    // For the failure message: a run with no samples says why.
    @Volatile private var frames = 0
    @Volatile private var failedCopies = 0

    @After
    fun tearDown() {
        sampling = false
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun a_newer_snapshot_replaces_the_old_one_with_no_blank_frame() {
        showFirst(RED)

        swapTo(BLUE)

        assertOnlyColours(listOf(RED, BLUE))
    }

    @Test
    fun a_burst_of_snapshots_ends_on_the_latest_with_no_blank_frame() {
        showFirst(RED)

        startSampling()
        // A frame apart, so each is still loading when the next arrives.
        listOf(BLUE, MAGENTA, CYAN).forEach { colour ->
            onMain { document = snapshot(colour) }
            SystemClock.sleep(16)
        }
        waitForColour(CYAN)

        assertOnlyColours(listOf(RED, BLUE, MAGENTA, CYAN))
    }

    /** Shows [colour] as the first snapshot; that load may show the background, as nothing came before it. */
    private fun showFirst(colour: Int) {
        document = snapshot(colour)
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        scenario.onActivity { activity ->
            activity.setContent {
                MaterialTheme(colorScheme = lightColorScheme(surface = ComposeColor(BACKGROUND))) {
                    DashboardWebView(
                        document = document,
                        onRunSkill = { _, _ -> },
                        onNavigationBlocked = {},
                        // As DashboardScreen does; unconstrained, a WebView wraps to zero height.
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        startSampling()
        waitForColour(colour)
    }

    private fun swapTo(colour: Int) {
        samples.clear()
        onMain { document = snapshot(colour) }
        waitForColour(colour)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Waits until [colour] has held for a run of frames, then stops sampling. */
    private fun waitForColour(colour: Int) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val recent = samples.toList().takeLast(STABLE_FRAMES)
            if (recent.size == STABLE_FRAMES && recent.all { it == colour }) return
            SystemClock.sleep(16)
        }
        throw AssertionError(
            "${hex(colour)} never held on screen ($frames frames sampled, $failedCopies copies failed; " +
                "is the screen on?); last frames ${samples.toList().takeLast(STABLE_FRAMES).map(::hex)}"
        )
    }

    /** Every sample is one of [allowed], and once a later colour shows, an earlier one never comes back. */
    private fun assertOnlyColours(allowed: List<Int>) {
        sampling = false
        val seen = samples.toList()
        assertTrue("no frames were sampled", seen.isNotEmpty())
        val foreign = seen.filter { it !in allowed }.distinct()
        assertEquals("frames that were neither snapshot: ${foreign.map(::hex)}", emptyList<Int>(), foreign)
        val order = seen.map(allowed::indexOf)
        assertEquals("a newer snapshot was replaced by an older one: ${seen.map(::hex)}", order.sorted(), order)
    }

    /** Copies the dashboard's centre pixel from the window on every frame. */
    private fun startSampling() {
        samples.clear()
        sampling = true
        scenario.onActivity { activity ->
            val window = activity.window
            val handler = Handler(Looper.getMainLooper())
            // Copies complete out of order; a slot per frame keeps them in draw order.
            var frame = 0
            val pending = sortedMapOf<Int, Int>()
            var next = 0
            val tick = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!sampling) return
                    // Not there until the first composition has run.
                    val page = findWebView(window.decorView)
                    if (page == null || page.width == 0 || page.height == 0) {
                        Choreographer.getInstance().postFrameCallback(this)
                        return
                    }
                    val location = IntArray(2).also(page::getLocationInWindow)
                    val x = location[0] + page.width / 2
                    val y = location[1] + page.height / 2
                    val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    val index = frame++
                    frames++
                    PixelCopy.request(window, Rect(x, y, x + 1, y + 1), bitmap, { result ->
                        if (result == PixelCopy.SUCCESS) pending[index] = bitmap.getPixel(0, 0) or OPAQUE
                        while (pending.containsKey(next)) samples += pending.remove(next)!!.also { next++ }
                        // A failed copy is skipped rather than recorded as a colour.
                        if (result != PixelCopy.SUCCESS) {
                            failedCopies++
                            next = maxOf(next, index + 1)
                            while (pending.containsKey(next)) samples += pending.remove(next)!!.also { next++ }
                        }
                    }, handler)
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(tick)
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private companion object {
        val RED = Color.rgb(0xff, 0, 0)
        val BLUE = Color.rgb(0, 0, 0xff)
        val MAGENTA = Color.rgb(0xff, 0, 0xff)
        val CYAN = Color.rgb(0, 0xff, 0xff)
        val BACKGROUND = Color.rgb(0, 0xff, 0)
        const val OPAQUE = 0xff shl 24
        const val STABLE_FRAMES = 10

        /**
         * About 1.5 MB of laid-out elements, not hidden text: a page that is
         * quick to lay out is covered by Chromium's paint holding even when
         * loaded in place, and would not show the gap this test looks for.
         */
        val PADDING = "<i></i>".repeat(200_000)

        /** A fresh String each call, as a new DASHBOARD_SNAPSHOT is a new document. */
        fun snapshot(colour: Int) = DashboardDocument.build(
            """<style>html, body { margin: 0; height: 100%; background: ${hex(colour)} !important; }</style>
              |<div style="position:absolute;top:0;left:0;opacity:0">$PADDING</div>""".trimMargin(),
            DashboardDocument.generateNonce()
        )

        fun hex(colour: Int) = "#%06x".format(colour and 0xffffff)
    }
}
