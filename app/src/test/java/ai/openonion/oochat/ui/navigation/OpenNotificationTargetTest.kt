package ai.openonion.oochat.ui.navigation

import ai.openonion.oochat.domain.model.NotificationTarget
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * W16AAPPLE-44: where a tapped approval notification takes the user, over the
 * app's own chat routes. A tap acts once a chat is reached, and is dropped when
 * the app lands somewhere else, rather than held for whenever a chat next shows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenNotificationTargetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val address = "0x" + "ab".repeat(32)
    private val workRoomTarget = NotificationTarget(address, sessionId = "s-1", invocationId = "codex:inv-1")

    private lateinit var nav: NavHostController
    private var target by mutableStateOf<NotificationTarget?>(null)
    private var handled = 0

    private fun show(start: String, saved: Boolean = true) {
        composeRule.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable(Routes.LOADING) { Text("loading") }
                composable(Routes.ONBOARDING) { Text("setup") }
                composable(CHAT_ROUTES[0]) { Text("chat") }
                composable(
                    CHAT_ROUTES[1],
                    arguments = listOf(
                        navArgument("agentId") { type = NavType.StringType },
                        navArgument("sessionId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("startNew") {
                            type = NavType.BoolType
                            defaultValue = false
                        }
                    )
                ) { Text("agent chat") }
                composable("${Routes.WORK_ROOM}/{invocationId}") { Text("work room") }
            }
            OpenNotificationTarget(nav, target, isSavedAgent = { saved }, onHandled = {
                handled++
                target = null
            })
        }
        composeRule.waitForIdle()
    }

    private fun route() = nav.currentBackStackEntry?.destination?.route

    private fun arg(name: String) = nav.currentBackStackEntry?.arguments?.getString(name)

    @Test
    fun `a tap while in a chat opens the conversation, then its Work Room over it`() {
        show(start = CHAT_ROUTES[0])

        composeRule.runOnIdle { target = workRoomTarget }
        composeRule.waitForIdle()

        assertEquals("${Routes.WORK_ROOM}/{invocationId}", route())
        assertEquals("codex:inv-1", arg("invocationId"))
        composeRule.runOnIdle { nav.popBackStack() }
        assertEquals(CHAT_ROUTES[1], route())
        assertEquals(address, arg("agentId"))
        assertEquals("s-1", arg("sessionId"))
        assertEquals(1, handled)
    }

    @Test
    fun `a tap that starts the app waits out Loading, then opens the conversation`() {
        show(start = Routes.LOADING)
        composeRule.runOnIdle { target = workRoomTarget.copy(invocationId = null) }
        composeRule.waitForIdle()
        assertEquals("still loading", Routes.LOADING, route())

        composeRule.runOnIdle {
            nav.navigate(CHAT_ROUTES[0]) { popUpTo(Routes.LOADING) { inclusive = true } }
        }
        composeRule.waitForIdle()

        assertEquals(CHAT_ROUTES[1], route())
        assertEquals("s-1", arg("sessionId"))
    }

    @Test
    fun `a tap is dropped when the app lands on Setup or the error screen, not held for later`() {
        show(start = Routes.LOADING)
        composeRule.runOnIdle { target = workRoomTarget }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.LOADING) { inclusive = true } }
        }
        composeRule.waitForIdle()
        assertEquals(1, handled)

        // Reaching a chat later does not bring the old tap back.
        composeRule.runOnIdle { nav.navigate(CHAT_ROUTES[0]) }
        composeRule.waitForIdle()
        assertEquals(CHAT_ROUTES[0], route())
    }

    @Test
    fun `a tap for an agent the user has not saved is ignored`() {
        show(start = CHAT_ROUTES[0], saved = false)

        composeRule.runOnIdle { target = workRoomTarget }
        composeRule.waitForIdle()

        assertEquals(CHAT_ROUTES[0], route())
        assertNull(arg("agentId"))
        assertEquals(1, handled)
    }
}
