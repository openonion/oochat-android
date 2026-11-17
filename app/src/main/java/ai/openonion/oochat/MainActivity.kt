package ai.openonion.oochat

import ai.openonion.oochat.data.local.ThemePreferences
import ai.openonion.oochat.domain.model.NotificationTarget
import ai.openonion.oochat.domain.model.ThemeMode
import ai.openonion.oochat.notification.NotificationIntents
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import ai.openonion.oochat.ui.theme.LocalThemeController
import ai.openonion.oochat.ui.theme.ThemeController
import ai.openonion.oochat.util.FileLogger
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /**
     * Where a tapped notification asked to go (W16AAPPLE-44), until the app has
     * taken the user there. From the intent that started the activity, or from
     * [onNewIntent] when it was already running.
     */
    private val notificationTarget = MutableStateFlow<NotificationTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate() so the system
        // can show the windowSplashScreen* attributes defined on
        // Theme.ConnectOnion.Starting until the first frame is ready.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        FileLogger.init(this)
        // We could call splashScreen.setKeepOnScreenCondition { ... } here
        // if we needed to gate on async work. Our Compose SplashScreen
        // already handles the brief delay before startDestination is known,
        // so we don't need an additional gate.
        splashScreen.setKeepOnScreenCondition { false }
        // Draw behind the (now transparent) system bars. The window no longer
        // resizes for the IME once decorFitsSystemWindows is false, so every
        // screen takes its own insets — see ChatScreen's windowInsetsPadding.
        enableEdgeToEdge()
        // Not on a recreation: the activity restores its own back stack, and the
        // tap that started it was already acted on.
        if (savedInstanceState == null) notificationTarget.value = NotificationIntents.targetOf(intent)
        setContent {
            // Persisted theme mode drives the palette; SYSTEM until DataStore emits.
            val themePrefs = remember { ThemePreferences(applicationContext) }
            val scope = rememberCoroutineScope()
            val themeMode by themePrefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val tappedNotification by notificationTarget.collectAsState()

            CompositionLocalProvider(
                LocalThemeController provides ThemeController(
                    mode = themeMode,
                    setMode = { mode -> scope.launch { themePrefs.setThemeMode(mode) } }
                )
            ) {
                ConnectOnionTheme(themeMode = themeMode) {
                    ConnectOnionApp(
                        notificationTarget = tappedNotification,
                        onNotificationTargetHandled = { notificationTarget.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        NotificationIntents.targetOf(intent)?.let { notificationTarget.value = it }
    }
}
