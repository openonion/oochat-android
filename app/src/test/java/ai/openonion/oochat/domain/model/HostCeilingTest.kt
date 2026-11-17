package ai.openonion.oochat.domain.model

import ai.openonion.oochat.domain.model.ApprovalMode.AUTO
import ai.openonion.oochat.domain.model.ApprovalMode.FULL_ACCESS
import ai.openonion.oochat.domain.model.ApprovalMode.READ_ONLY
import ai.openonion.oochat.domain.model.HostCeilingState.ConfirmingFullAccess
import ai.openonion.oochat.domain.model.HostCeilingState.Idle
import org.junit.Assert.assertEquals
import org.junit.Test

/** W16AAPPLE-42: full access is only ever sent after an explicit confirmation. */
class HostCeilingTest {

    private fun HostCeilingState.choose(mode: ApprovalMode, current: ApprovalMode) =
        on(HostCeilingEvent.Choose(mode, current))

    @Test
    fun `choosing full access asks first and sends nothing`() {
        assertEquals(HostCeilingTransition(ConfirmingFullAccess), Idle.choose(FULL_ACCESS, current = AUTO))
    }

    @Test
    fun `confirming sends full access`() {
        assertEquals(
            HostCeilingTransition(Idle, send = FULL_ACCESS),
            ConfirmingFullAccess.on(HostCeilingEvent.Confirm)
        )
    }

    @Test
    fun `cancelling sends nothing`() {
        assertEquals(HostCeilingTransition(Idle), ConfirmingFullAccess.on(HostCeilingEvent.Cancel))
    }

    @Test
    fun `a confirm with no question showing sends nothing`() {
        // A stale or doubled tap must never be the thing that grants full access.
        assertEquals(HostCeilingTransition(Idle), Idle.on(HostCeilingEvent.Confirm))
    }

    @Test
    fun `every other mode goes straight out, including leaving full access`() {
        assertEquals(HostCeilingTransition(Idle, send = READ_ONLY), Idle.choose(READ_ONLY, current = AUTO))
        assertEquals(HostCeilingTransition(Idle, send = AUTO), Idle.choose(AUTO, current = FULL_ACCESS))
    }

    @Test
    fun `picking another mode while asked replaces the question`() {
        assertEquals(
            HostCeilingTransition(Idle, send = READ_ONLY),
            ConfirmingFullAccess.choose(READ_ONLY, current = AUTO)
        )
    }

    @Test
    fun `a full access the Host already granted is not asked about again`() {
        assertEquals(HostCeilingTransition(Idle, send = FULL_ACCESS), Idle.choose(FULL_ACCESS, current = FULL_ACCESS))
    }

    @Test
    fun `legacy ultra work is not the Host ceiling and is not held`() {
        // ULW keeps its own turn-budget step in the sheet.
        assertEquals(
            HostCeilingTransition(Idle, send = ApprovalMode.ULW),
            Idle.choose(ApprovalMode.ULW, current = ApprovalMode.SAFE)
        )
    }
}
