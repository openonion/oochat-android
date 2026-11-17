package ai.openonion.oochat.ui.workroom

import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.ProviderInvocationStatus
import ai.openonion.oochat.domain.model.ProviderPermissionAck
import ai.openonion.oochat.domain.model.ProviderPermissionChange
import ai.openonion.oochat.domain.model.ProviderPermissionControlState
import ai.openonion.oochat.domain.model.ProviderPermissionOption
import ai.openonion.oochat.domain.model.ProviderPermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-43: a Work Room's provider permission requests, and what becomes of them. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkRoomPermissionsTest {

    private val run = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        provider = "codex",
        status = ProviderInvocationStatus.RUNNING,
        stateRevision = 4,
        providerPermission = ProviderPermissionState(
            provider = "codex",
            activeOptionId = "codex:workspace-ask",
            options = listOf(
                ProviderPermissionOption("codex:read-only", "Read Only", selectable = true),
                ProviderPermissionOption("codex:workspace-ask", "Ask for approval", selectable = true),
                ProviderPermissionOption("codex:full-access", "Full Access", elevated = true, selectable = true)
            )
        )
    )

    private val sent = mutableListOf<ProviderPermissionChange>()
    private var wentOut = true
    private var ids = 0

    private fun TestScope.permissions() = WorkRoomPermissions(
        scope = backgroundScope,
        send = { sent += it; wentOut },
        ackTimeoutMs = 1_000,
        newRequestId = { "req-${++ids}" }
    )

    private fun WorkRoomPermissions.ui() = rooms.value[run.id] ?: WorkRoomPermissionUi()

    @Test
    fun `a choice goes out at the revision on screen and settles on acceptance`() = runTest {
        val permissions = permissions()

        permissions.choose(run, "codex:read-only")
        runCurrent()

        assertEquals(listOf(ProviderPermissionChange("codex:call-1", 4, "codex:read-only", false, "req-1")), sent)
        assertEquals(ProviderPermissionControlState.Pending("codex:read-only"), permissions.ui().control)

        permissions.onAck(ProviderPermissionAck("req-1", run.id, accepted = true, stateRevision = 5))

        assertEquals("nothing left to show once the Host took it", WorkRoomPermissionUi(), permissions.ui())
    }

    @Test
    fun `an elevated choice is sent only once confirmed, with the risk confirmed`() = runTest {
        val permissions = permissions()

        permissions.choose(run, "codex:full-access")
        runCurrent()
        assertTrue("nothing sent before confirming", sent.isEmpty())
        assertEquals(ProviderPermissionControlState.ConfirmingElevated("codex:full-access"), permissions.ui().control)

        permissions.confirm(run)
        runCurrent()

        assertEquals(listOf(ProviderPermissionChange("codex:call-1", 4, "codex:full-access", true, "req-1")), sent)
    }

    @Test
    fun `cancelling the confirmation sends nothing`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:full-access")

        permissions.cancel(run.id)
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomPermissionUi(), permissions.ui())
    }

    @Test
    fun `a refusal says why and leaves the control free`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:read-only")
        runCurrent()

        permissions.onAck(ProviderPermissionAck("req-1", run.id, accepted = false, reason = "stale_revision"))

        assertEquals(ProviderPermissionControlState.Idle, permissions.ui().control)
        assertEquals(WorkRoomText.permissionRefused("stale_revision"), permissions.ui().error)
    }

    @Test
    fun `no answer in time frees the control and says so`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:read-only")
        runCurrent()

        advanceTimeBy(1_001)

        assertEquals(ProviderPermissionControlState.Idle, permissions.ui().control)
        assertEquals(WorkRoomText.PERMISSION_NO_ANSWER, permissions.ui().error)
        // A late answer then settles nothing it should not.
        permissions.onAck(ProviderPermissionAck("req-1", run.id, accepted = true))
        assertEquals(WorkRoomText.PERMISSION_NO_ANSWER, permissions.ui().error)
    }

    @Test
    fun `a change that could not go out says so`() = runTest {
        wentOut = false
        val permissions = permissions()

        permissions.choose(run, "codex:read-only")
        runCurrent()

        assertEquals(ProviderPermissionControlState.Idle, permissions.ui().control)
        assertEquals(WorkRoomText.PERMISSION_NOT_CONNECTED, permissions.ui().error)
    }

    @Test
    fun `nothing else goes out while a request waits`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:read-only")
        runCurrent()

        permissions.choose(run, "codex:full-access")
        runCurrent()

        assertEquals(1, sent.size)
    }

    @Test
    fun `a run with no revision yet sends nothing`() = runTest {
        val permissions = permissions()

        permissions.choose(run.copy(stateRevision = null), "codex:read-only")
        runCurrent()

        assertTrue(sent.isEmpty())
        assertEquals(WorkRoomText.PERMISSION_NOT_READY, permissions.ui().error)
    }

    @Test
    fun `an answer to another request is ignored, and a new pick clears the last message`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:read-only")
        runCurrent()
        permissions.onAck(ProviderPermissionAck("someone-else", run.id, accepted = true))
        assertEquals(ProviderPermissionControlState.Pending("codex:read-only"), permissions.ui().control)

        permissions.onAck(ProviderPermissionAck("req-1", run.id, accepted = false, reason = "ceiling_denied"))
        permissions.choose(run, "codex:full-access")

        assertNull(permissions.ui().error)
    }

    @Test
    fun `a cleared conversation's rooms are forgotten`() = runTest {
        val permissions = permissions()
        permissions.choose(run, "codex:read-only", roomKey = "s1\u0000codex:call-1")
        runCurrent()

        permissions.discardMatching { it.startsWith("s1\u0000") }
        advanceTimeBy(1_001)

        assertTrue(permissions.rooms.value.isEmpty())
    }
}
