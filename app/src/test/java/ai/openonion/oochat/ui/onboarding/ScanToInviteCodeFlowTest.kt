package ai.openonion.oochat.ui.onboarding

import ai.openonion.oochat.ui.agent.components.AgentFormState
import ai.openonion.oochat.ui.loading.LoadingScreen
import ai.openonion.oochat.ui.loading.LoadingViewModel
import ai.openonion.oochat.ui.loading.LoadingViewModelTest.FakeConnectToAgentUseCase
import ai.openonion.oochat.ui.loading.LoadingViewModelTest.FakeConnectionConfigRepository
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole W16AAPPLE-111 path in one: what the scanner read, through Setup's
 * view model to the saved configuration, then the real Loading screen reading
 * that configuration and handing off to the chat with the invite code
 * pending, with nothing tapped on the way. The chat's side of the hand-off
 * (markOnboardPending, then the invite-code card) is covered by
 * ChatViewModelTest and OnboardCardsComposeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScanToInviteCodeFlowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val address = "0x" + "ab".repeat(32)
    private val server = "https://agent.example.com:8000"

    @Test
    fun `a scanned agent that wants an invite code is handed to the chat with it pending, with nothing tapped`() {
        // Nothing saved yet: a first run.
        val configRepository = FakeConnectionConfigRepository().apply { config = null }
        val setup = OnboardingViewModel(application, configRepository)
        var wentToLoading = false

        val scanned = setup.onScanned("$server/$address") ?: error("the QR code was refused")
        setup.connectScanned(scanned, AgentFormState(), onSaved = { wentToLoading = true })
        composeRule.waitUntil(timeoutMillis = 5_000) { wentToLoading }

        val saved = configRepository.config
        assertNotNull("the scan saved the agent to connect to", saved)
        assertEquals(address, saved?.agentAddress)
        assertEquals(server, saved?.serverUrl)
        assertEquals("direct", saved?.connectionMode)

        // The agent answers the connect with ONBOARD_REQUIRED, as an
        // invite-gated agent does.
        val connectUseCase = FakeConnectToAgentUseCase().apply {
            suspendConnectForever = true
            onConnect = {
                yield()
                emitOnboardRequired()
            }
        }
        val loading = LoadingViewModel(
            application = application,
            configRepository = configRepository,
            connectUseCase = connectUseCase,
            resumableConversation = { null }
        )
        val navigated = mutableListOf<Boolean>()
        composeRule.setContent {
            LoadingScreen(
                onConnectionSuccess = { onboardPending -> navigated += onboardPending },
                onConnectionFailed = { error("not expected to fail: $it") },
                viewModel = loading
            )
        }
        composeRule.waitUntil(timeoutMillis = 5_000) { navigated.isNotEmpty() }

        assertEquals("Loading connected to the scanned agent", address, connectUseCase.lastAddress)
        assertEquals("directly, at the server the code named", server, connectUseCase.lastDirectUrl)
        assertEquals("on to the chat with the invite code pending", listOf(true), navigated)
    }
}
