package ai.openonion.oochat.ui.chat

import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionActivity
import ai.openonion.oochat.network.AgentConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The user comes back to the app mid-turn (W16AAPPLE-46). The socket may have died while the app
 * was in the background with no close frame: the process was frozen, or the network changed under
 * it. It still reads as connected, so nothing would notice until OkHttp's ping failed. The chat
 * screen's return checks it instead, and a dead one is replaced at once, resuming from the last
 * event the app handled.
 *
 * The harness is in [RelayResumeTestBase].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ForegroundReturnTest : RelayResumeTestBase() {

    @Test
    fun `a socket that died in the background is replaced on return and what was missed is replayed once`() = runBlocking {
        val (app, conversation) = connectedMidTurn()
        // Silent, not closed: the network is fine, but nothing on this socket arrives any more.
        val dead = liveSocket
        dead.unreachable = true
        missWhileDown()

        val returnedAt = scheduler.currentTime
        app.vm.onReturnedToForeground()
        pumpUntil("the return to reconnect") { relay.connects.size == 2 }

        val waited = scheduler.currentTime - returnedAt
        assertTrue(
            "the reconnect must follow the check's ${AgentConnection.FOREGROUND_PROBE_TIMEOUT_MS}ms wait, not a ping timeout: waited ${waited}ms",
            waited <= AgentConnection.FOREGROUND_PROBE_TIMEOUT_MS + SLACK_MS
        )
        assertTrue(
            "the check asked about the conversation on screen, on the socket that had gone silent",
            dead.sent.any { it.contains("\"SESSION_STATUS\"") && it.contains(conversation) }
        )
        assertResumedThroughRelay(conversation)
        finishAndAssertArrivedOnce(app, conversation)
    }

    @Test
    fun `a socket that still works is kept on return`() = runBlocking {
        val (app, _) = connectedMidTurn()
        val socket = liveSocket

        app.vm.onReturnedToForeground()
        pumpFor(AgentConnection.FOREGROUND_PROBE_TIMEOUT_MS * 2)

        assertEquals("the Host was asked once", 1, relay.statusQueries.size)
        assertEquals("nothing to replace, so no second CONNECT", 1, relay.connects.size)
        assertTrue("the same socket is still the live one", liveSocket === socket && !socket.dead)
        assertTrue("and still connected: ${app.state}", app.state is ConnectionState.Connected)
    }

    @Test
    fun `returning to a chat that has run no turn leaves the status line alone`() = runBlocking {
        val app = connectedIdle()
        val before = app.vm.sessionActivity.value

        app.vm.onReturnedToForeground()
        pumpFor(AgentConnection.FOREGROUND_PROBE_TIMEOUT_MS * 2)

        // The real Host answers not_found here: the session is only in its memory once a turn runs.
        assertEquals("the Host answered the check", 1, relay.statusQueries.size)
        assertEquals("an answer is an answer, so no reconnect", 1, relay.connects.size)
        assertEquals("the status line is what it was", before, app.vm.sessionActivity.value)
        assertNotEquals(SessionActivity.NOT_FOUND, app.vm.sessionActivity.value)
    }

    @Test
    fun `a socket gone silent while the network changed is replaced when the network is back`() = runBlocking {
        val (_, conversation) = connectedMidTurn()
        val dead = liveSocket
        online.emit(false)
        dead.unreachable = true
        missWhileDown()

        val backAt = scheduler.currentTime
        online.emit(true)
        pumpUntil("the network coming back to reconnect") { relay.connects.size == 2 }

        assertTrue(
            "the reconnect follows the check, not a ping timeout: waited ${scheduler.currentTime - backAt}ms",
            scheduler.currentTime - backAt <= AgentConnection.FOREGROUND_PROBE_TIMEOUT_MS + SLACK_MS
        )
        assertResumedThroughRelay(conversation)
    }
}
