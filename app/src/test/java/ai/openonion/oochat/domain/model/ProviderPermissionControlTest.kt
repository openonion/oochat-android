package ai.openonion.oochat.domain.model

import ai.openonion.oochat.domain.model.ProviderPermissionControlState.ConfirmingElevated
import ai.openonion.oochat.domain.model.ProviderPermissionControlState.Idle
import ai.openonion.oochat.domain.model.ProviderPermissionControlState.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-43: the provider permission control's own state machine, kept
 * apart from the Host ceiling's (HostCeilingStateTest).
 */
class ProviderPermissionControlTest {

    /** Codex under a Full Access Host ceiling, as connectonion's provider_permission_state builds it. */
    private val codex = ProviderPermissionState(
        provider = "codex",
        activeOptionId = "codex:workspace-ask",
        options = listOf(
            ProviderPermissionOption("codex:read-only", "Read Only", selectable = true),
            ProviderPermissionOption("codex:workspace-ask", "Ask for approval", selectable = true),
            ProviderPermissionOption("codex:workspace-auto", "Approve for me", selectable = true),
            ProviderPermissionOption("codex:full-access", "Full Access", elevated = true, selectable = true)
        )
    )

    /** The same under an Auto ceiling: Full Access is shown but not selectable. */
    private val codexUnderAuto = codex.copy(
        options = codex.options.map {
            if (it.elevated) it.copy(selectable = false, disabledReason = "Host permission ceiling is Auto.") else it
        }
    )

    private fun choose(id: String, permission: ProviderPermissionState = codex) =
        ProviderPermissionEvent.Choose(id, permission)

    @Test
    fun `a standard choice goes straight out and waits for its answer`() {
        val transition = Idle.on(choose("codex:workspace-auto"))

        assertEquals(Pending("codex:workspace-auto"), transition.state)
        assertEquals("codex:workspace-auto", transition.send)
        assertFalse(transition.confirmRisk)
    }

    @Test
    fun `an elevated choice waits for confirmation and sends nothing yet`() {
        val transition = Idle.on(choose("codex:full-access"))

        assertEquals(ConfirmingElevated("codex:full-access"), transition.state)
        assertNull(transition.send)
    }

    @Test
    fun `confirming sends the elevated choice with the risk confirmed`() {
        val transition = ConfirmingElevated("codex:full-access").on(ProviderPermissionEvent.Confirm)

        assertEquals(Pending("codex:full-access"), transition.state)
        assertEquals("codex:full-access", transition.send)
        assertTrue("the Host refuses an elevated choice without it", transition.confirmRisk)
    }

    @Test
    fun `cancelling a confirmation sends nothing`() {
        val transition = ConfirmingElevated("codex:full-access").on(ProviderPermissionEvent.Cancel)

        assertEquals(Idle, transition.state)
        assertNull(transition.send)
    }

    @Test
    fun `another pick while confirming replaces the confirmation`() {
        val transition = ConfirmingElevated("codex:full-access").on(choose("codex:read-only"))

        assertEquals(Pending("codex:read-only"), transition.state)
        assertEquals("codex:read-only", transition.send)
    }

    @Test
    fun `a choice above the Host ceiling is never sent`() {
        val transition = Idle.on(choose("codex:full-access", codexUnderAuto))

        assertEquals(Idle, transition.state)
        assertNull(transition.send)
    }

    @Test
    fun `a choice the Host did not offer, or the one already active, sends nothing`() {
        assertNull(Idle.on(choose("codex:something-new")).send)
        assertNull(Idle.on(choose("codex:workspace-ask")).send)
        assertEquals(Idle, Idle.on(choose("codex:workspace-ask")).state)
    }

    @Test
    fun `nothing else goes out while a request waits, and it cannot be cancelled`() {
        val waiting = Pending("codex:workspace-auto")

        assertEquals(ProviderPermissionTransition(waiting), waiting.on(choose("codex:read-only")))
        assertEquals(ProviderPermissionTransition(waiting), waiting.on(ProviderPermissionEvent.Cancel))
        assertEquals(ProviderPermissionTransition(waiting), waiting.on(ProviderPermissionEvent.Confirm))
    }

    @Test
    fun `an answer, a refusal or a timeout settles it`() {
        assertEquals(Idle, Pending("codex:workspace-auto").on(ProviderPermissionEvent.Settled).state)
    }

    @Test
    fun `confirm with nothing to confirm does nothing`() {
        assertEquals(ProviderPermissionTransition(Idle), Idle.on(ProviderPermissionEvent.Confirm))
    }
}
