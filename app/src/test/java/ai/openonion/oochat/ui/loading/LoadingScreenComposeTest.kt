package ai.openonion.oochat.ui.loading

import ai.openonion.oochat.ui.loading.LoadingViewModelTest.FakeConnectToAgentUseCase
import ai.openonion.oochat.ui.loading.LoadingViewModelTest.FakeConnectionConfigRepository
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Loading screen hands an agent that asks for an invite code straight on
 * to the chat's invite-code card, as it does a plain connect: there is no
 * "Continue to Onboarding" to tap (W16AAPPLE-111).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoadingScreenComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    // connect() yields once before answering, as a real one waits on the
    // network: an answer within start() itself would be emitted before the
    // screen has begun listening for it.
    private val connectUseCase = FakeConnectToAgentUseCase().apply { onConnect = { yield() } }

    private fun viewModel() = LoadingViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        configRepository = FakeConnectionConfigRepository(),
        connectUseCase = connectUseCase,
        resumableConversation = { null }
    )

    private fun show(): MutableList<Boolean> {
        val navigated = mutableListOf<Boolean>()
        val vm = viewModel()
        composeRule.setContent {
            LoadingScreen(
                onConnectionSuccess = { onboardPending -> navigated += onboardPending },
                onConnectionFailed = { error("not expected to fail: $it") },
                viewModel = vm
            )
        }
        return navigated
    }

    @Test
    fun `an agent that asks for an invite code goes on to the chat by itself`() {
        connectUseCase.suspendConnectForever = true
        connectUseCase.onConnect = {
            yield()
            connectUseCase.emitOnboardRequired()
        }

        val navigated = show()
        composeRule.waitUntil(timeoutMillis = 5_000) { navigated.isNotEmpty() }

        assertEquals("on to the chat, told the invite code is pending", listOf(true), navigated)
        composeRule.onNodeWithText("Continue to Onboarding").assertDoesNotExist()
    }

    @Test
    fun `an invite code is still pending when connect itself returns on the agent's request for one`() {
        // ConnectToAgentUseCase races the same ONBOARD_REQUIRED and returns
        // true when it wins, so connect() can finish before this screen's own
        // listener has run. The chat must still be told a code is pending.
        connectUseCase.connectResult = true
        connectUseCase.onConnect = {
            yield()
            connectUseCase.emitOnboardRequired()
            connectUseCase.lastConnectNeedsOnboarding = true
        }

        val navigated = show()
        composeRule.waitUntil(timeoutMillis = 5_000) { navigated.isNotEmpty() }

        assertEquals(listOf(true), navigated)
    }

    @Test
    fun `a plain connect still goes on to the chat with nothing pending`() {
        val navigated = show()
        composeRule.waitUntil(timeoutMillis = 5_000) { navigated.isNotEmpty() }

        assertEquals(listOf(false), navigated)
    }
}
