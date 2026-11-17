package ai.openonion.oochat.ui.navigation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.navigation.navDeepLink
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which links open the app on an agent: what the nav graph understands, and what Android offers
 * the app for, asked of the package manager rather than read out of the manifest (W16AAPPLE-78).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepLinksTest {

    private val address = "0x" + "3d40".repeat(16)

    private val matchers = DeepLinks.patterns.map { navDeepLink { uriPattern = it } }

    /** The address the nav graph would hand the chat screen for [link], or null if no pattern takes it. */
    private fun addressIn(link: String): String? =
        matchers.firstNotNullOfOrNull { matcher ->
            matcher.getMatchingArguments(Uri.parse(link), emptyMap())?.getString(DeepLinks.ADDRESS)
        }

    @Test
    fun `the app's own link carries its address`() {
        assertEquals(address, addressIn("connectonion://agent/$address"))
    }

    @Test
    fun `the website's link and Share QR carry its address`() {
        assertEquals(address, addressIn("https://chat.openonion.ai/$address"))
    }

    @Test
    fun `the website's old domain carries its address`() {
        assertEquals(address, addressIn("https://oo-chat.com/$address"))
    }

    @Test
    fun `a host that is not listed is left to the browser`() {
        assertNull(addressIn("https://example.com/$address"))
    }

    @Test
    fun `a plain http link is left to the browser`() {
        assertNull(addressIn("http://oo-chat.com/$address"))
    }

    @Test
    fun `the manifest offers the app for every host the nav graph understands`() {
        DeepLinks.WEB_HOSTS.forEach { host ->
            assertTrue(host, opensApp("https://$host/$address"))
        }
    }

    @Test
    fun `the manifest offers the app for the connectonion scheme`() {
        assertTrue(opensApp("connectonion://agent/$address"))
    }

    @Test
    fun `the website's own pages are left to the browser`() {
        // The app claims an agent's page, not the web client around it: once links are
        // verified, or on an older Android, a claimed /settings would open the app
        // on its start screen instead of that page.
        DeepLinks.WEB_HOSTS.forEach { host ->
            listOf("", "/", "/settings", "/login?next=/x", "/explore").forEach { path ->
                assertFalse("https://$host$path", opensApp("https://$host$path"))
            }
        }
    }

    @Test
    fun `a plain http link to an agent is left to the browser`() {
        DeepLinks.WEB_HOSTS.forEach { host -> assertFalse(host, opensApp("http://$host/$address")) }
    }

    /** True if Android would offer this app for [link], by the manifest's own rules. */
    private fun opensApp(link: String): Boolean {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(view, 0).isNotEmpty()
    }
}
