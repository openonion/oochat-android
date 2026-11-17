package ai.openonion.oochat.ui.components

import ai.openonion.oochat.R
import android.webkit.WebSettings
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * The theme a WebView is made under, for an app that shows web pages it does not write
 * (W16AAPPLE-49). WebView tells the page inside it `prefers-color-scheme` from its theme's
 * `isLightTheme`, and the activity's theme follows the system's dark setting rather than the app's
 * own Light/Dark/System choice. So a WebView is created under one of these, chosen by the app's
 * choice, with `ContextThemeWrapper(context, webViewTheme(dark))`, and the page goes dark when
 * the rest of the app does. See `themes.xml`.
 */
internal fun webViewTheme(dark: Boolean): Int =
    if (dark) R.style.ConnectOnionWebViewDark else R.style.ConnectOnionWebViewLight

/**
 * Lets a page with no dark theme of its own be darkened when [webViewTheme] is dark. A page that has
 * one already follows `prefers-color-scheme`. A WebView that claims the feature can still refuse it
 * (Robolectric's does), and darkening is only a nicety, so it never stops a page from loading.
 */
internal fun WebSettings.allowAlgorithmicDarkening() {
    runCatching {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, true)
        }
    }
}
