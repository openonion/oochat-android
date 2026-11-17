package ai.openonion.oochat

import ai.openonion.oochat.crypto.BouncyCastleEd25519
import ai.openonion.oochat.crypto.KeyManager
import ai.openonion.oochat.data.local.ConnectionConfig
import ai.openonion.oochat.di.AppContainer
import ai.openonion.oochat.di.FakeAppContainer
import ai.openonion.oochat.di.FakeConnectToAgentUseCase
import ai.openonion.oochat.di.FakeConnectionConfigRepository
import ai.openonion.oochat.domain.model.ChatEvent
import ai.openonion.oochat.domain.usecase.ConnectToAgentUseCaseContract
import ai.openonion.oochat.ui.theme.ConnectOnionTheme
import android.os.Looper
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The app on a test container. Static because AndroidViewModelFactory keeps the
 * first Application for the whole JVM, so later tests reach it through that one.
 */
class StartScreenTestApplication : ConnectOnionApplication() {
    override val container: AppContainer get() = fake ?: error("set the container before the app starts")

    companion object {
        var fake: AppContainer? = null
    }
}

/** Setup with no saved connection, Loading with one. The whole app runs on [FakeAppContainer]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = StartScreenTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Never connects, so Loading stays on screen. */
    private class NeverConnects : ConnectToAgentUseCaseContract by FakeConnectToAgentUseCase() {
        @Volatile var attempts = 0
        override suspend fun connect(
            agentAddress: String,
            conversationId: String?,
            directUrl: String?,
            relayUrl: String?
        ): Boolean {
            attempts++
            awaitCancellation()
        }

        // The shared fake's empty flow completes, which Loading treats as a failure.
        override fun observeEvents(): Flow<ChatEvent> = flow { awaitCancellation() }
    }

    private val connection = NeverConnects()

    private fun launch(saved: ConnectionConfig?) {
        val app = ApplicationProvider.getApplicationContext<StartScreenTestApplication>()
        StartScreenTestApplication.fake = FakeAppContainer(
            context = app,
            // libsodium cannot load on the JVM.
            keyManager = KeyManager(app, BouncyCastleEd25519()),
            configRepository = FakeConnectionConfigRepository(initialConfig = saved),
            connectToAgentUseCase = connection
        )
        composeRule.setContent { ConnectOnionTheme { ConnectOnionApp() } }
    }

    /** Polled, not waitUntil: the screens animate forever, and the main looper needs idling by hand. */
    private fun await(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            composeRule.mainClock.advanceTimeBy(50L)
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(50L)
        }
        throw AssertionError("timed out waiting for $what:\n" + composeRule.onRoot(useUnmergedTree = true).printToString())
    }

    private fun awaitScreen(text: String) = await("\"$text\" on screen") {
        composeRule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun `with no saved connection the app opens on Setup`() {
        launch(saved = null)

        awaitScreen("Setup")
        composeRule.onNodeWithText("Connect").assertExists()
        composeRule.onNodeWithText("Connecting to", substring = true).assertDoesNotExist()
        assertTrue("nothing to connect to, so nothing is dialled", connection.attempts == 0)
    }

    @Test
    fun `with a saved connection the app opens on Loading and reconnects`() {
        launch(
            saved = ConnectionConfig(
                serverUrl = "https://oo.openonion.ai",
                agentAddress = "0x" + "ab".repeat(32)
            )
        )

        awaitScreen("Connecting to oo.openonion.ai")
        composeRule.onNodeWithText("Setup").assertDoesNotExist()
        await("Loading to dial the saved agent") { connection.attempts > 0 }
        assertEquals("once", 1, connection.attempts)
    }
}
