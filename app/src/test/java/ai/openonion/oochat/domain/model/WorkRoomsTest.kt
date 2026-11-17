package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-33: Work Room frames folded into the chat the way the Host's own
 * replay nests them (network/host/session/ui.py `_nest_provider_invocations`).
 */
class WorkRoomsTest {

    private fun reduce(current: List<ChatItem>, item: ChatItem, updated: Boolean = true) =
        ChatEventReducer.reduce(
            current,
            if (updated) ChatEvent.ChatItemUpdated(item) else ChatEvent.ChatItemReceived(item),
            ignoredIds = emptySet()
        )

    private fun run(
        revision: Long? = 1,
        status: ProviderInvocationStatus = ProviderInvocationStatus.RUNNING,
        title: String? = "Add a login page",
        summary: String? = null
    ) = ChatItem.ProviderInvocation(
        id = "codex:call-1",
        parentToolCallId = "call-1",
        provider = "codex",
        providerDisplayName = "Codex",
        taskTitle = title,
        status = status,
        currentSummary = summary,
        stateRevision = revision
    )

    private fun step(id: String, sequence: Int, status: ProviderActivityStatus = ProviderActivityStatus.RUNNING, legacy: Boolean = false) =
        ChatItem.ProviderActivityUpdate(
            invocationId = "codex:call-1",
            parentToolCallId = "call-1",
            provider = "codex",
            activity = ProviderActivity(id, sequence, "command", status, "Run the requested tests", "", legacy = legacy)
        )

    private val user = ChatItem.User(id = "u-1", content = "build me a login page")
    private val parentCard = ChatItem.ToolCall(id = "call-1", name = "codex", status = ToolStatus.RUNNING)

    @Test
    fun `the run takes the place of the agent's own codex tool card`() {
        val result = reduce(listOf(user, parentCard), run())

        assertEquals(listOf("u-1", "codex:call-1"), result.chatItems.map { it.id })
        assertTrue(result.chatItems[1] is ChatItem.ProviderInvocation)
        assertEquals(result.chatItems[1], result.itemToPersist)
    }

    @Test
    fun `the codex tool card arriving after the run is not shown again`() {
        val onScreen = reduce(listOf(user), run()).chatItems

        val result = reduce(onScreen, parentCard.copy(status = ToolStatus.DONE, result = "done"))
        val restarted = reduce(onScreen, parentCard, updated = false)

        assertEquals(onScreen, result.chatItems)
        assertEquals(onScreen, restarted.chatItems)
        assertNull(result.itemToPersist)
    }

    @Test
    fun `a newer revision updates the card in place, keeping what it leaves out`() {
        val onScreen = reduce(listOf(user, parentCard), run()).chatItems

        val done = reduce(onScreen, run(revision = 3, status = ProviderInvocationStatus.COMPLETED, title = null, summary = "Done"))
        val card = done.chatItems.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation

        assertEquals(ProviderInvocationStatus.COMPLETED, card.status)
        assertEquals("Add a login page", card.taskTitle)
        assertEquals("Done", card.currentSummary)
        assertEquals(2, done.chatItems.size)
    }

    @Test
    fun `an older revision, as a reconnect replays it, changes nothing`() {
        val onScreen = reduce(listOf(user), run(revision = 4, status = ProviderInvocationStatus.COMPLETED)).chatItems

        val result = reduce(onScreen, run(revision = 2, status = ProviderInvocationStatus.RUNNING))

        assertSame(onScreen, result.chatItems)
        assertNull(result.itemToPersist)
    }

    @Test
    fun `the same frame twice, live then committed, is one card`() {
        val once = reduce(listOf(user), run()).chatItems
        val twice = reduce(once, run())

        assertEquals(once, twice.chatItems)
        assertNull(twice.itemToPersist)
    }

    @Test
    fun `steps are upserted by id and kept in sequence order`() {
        var items = reduce(listOf(user), run()).chatItems
        items = reduce(items, step("b", 2)).chatItems
        items = reduce(items, step("c", 3)).chatItems
        items = reduce(items, step("a", 1)).chatItems
        val arrivedLate = items.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation
        assertEquals(listOf("a", "b", "c"), arrivedLate.activities.map { it.id })

        items = reduce(items, step("b", 2, ProviderActivityStatus.COMPLETED)).chatItems
        val card = items.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation
        assertEquals(listOf("a", "b", "c"), card.activities.map { it.id })
        assertEquals(ProviderActivityStatus.COMPLETED, card.activities[1].status)
    }

    @Test
    fun `a native step is not replaced by its compatibility tool_call`() {
        var items = reduce(listOf(user), run()).chatItems
        items = reduce(items, step("x", 1, ProviderActivityStatus.COMPLETED)).chatItems

        val result = reduce(items, step("x", Int.MAX_VALUE, ProviderActivityStatus.RUNNING, legacy = true))

        assertEquals(items, result.chatItems)
    }

    @Test
    fun `a step that arrives before its run starts the card`() {
        val result = reduce(listOf(user, parentCard), step("a", 1))
        val card = result.chatItems[1] as ChatItem.ProviderInvocation

        assertEquals("codex:call-1", card.id)
        assertEquals(listOf("a"), card.activities.map { it.id })

        val titled = reduce(result.chatItems, run()).chatItems[1] as ChatItem.ProviderInvocation
        assertEquals("Add a login page", titled.taskTitle)
        assertEquals(listOf("a"), titled.activities.map { it.id })
    }

    @Test
    fun `a revision between two seen ones is still stale`() {
        // rev 1, then rev 3, then a replayed rev 2: the card must remember it is on 3.
        var items = reduce(listOf(user), run(revision = 1)).chatItems
        items = reduce(items, run(revision = 3, status = ProviderInvocationStatus.COMPLETED)).chatItems

        val result = reduce(items, run(revision = 2, status = ProviderInvocationStatus.RUNNING))

        assertSame(items, result.chatItems)
    }

    @Test
    fun `a compatibility step keeps its place when it finishes`() {
        var items = reduce(listOf(user), run()).chatItems
        items = reduce(items, step("x", Int.MAX_VALUE, legacy = true)).chatItems
        items = reduce(items, step("y", Int.MAX_VALUE, legacy = true)).chatItems

        items = reduce(items, step("x", Int.MAX_VALUE, ProviderActivityStatus.COMPLETED, legacy = true)).chatItems

        val card = items.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation
        assertEquals(listOf("x", "y"), card.activities.map { it.id })
    }

    @Test
    fun `a saved card takes an off-screen fold without losing what it had`() {
        val saved = run(revision = 2, status = ProviderInvocationStatus.COMPLETED)
            .copy(activities = listOf(ProviderActivity("a", 1, "command", ProviderActivityStatus.COMPLETED, "t", "")))
        // A step folded against an empty list starts a bare card: no title, a guessed status, no revision.
        val partial = ChatItem.ProviderInvocation(
            id = saved.id,
            provider = "codex",
            activities = listOf(ProviderActivity("b", 2, "command", ProviderActivityStatus.RUNNING, "t", ""))
        )

        val merged = WorkRooms.mergeStored(saved, partial)

        assertEquals("Add a login page", merged.taskTitle)
        assertEquals(ProviderInvocationStatus.COMPLETED, merged.status)
        assertEquals(listOf("a", "b"), merged.activities.map { it.id })
    }

    @Test
    fun `a saved conversation never shows a run's own tool card`() {
        val saved = listOf(user, parentCard, run(), parentCard.copy(status = ToolStatus.DONE))

        assertEquals(listOf("u-1", "codex:call-1"), WorkRooms.withoutParentToolCards(saved).map { it.id })
    }

    @Test
    fun `only a run still working is ended by an idle Host`() {
        assertEquals(ProviderInvocationStatus.ENDED, WorkRooms.endedUnseen(run())?.status)
        assertEquals(ProviderInvocationStatus.ENDED, WorkRooms.endedUnseen(run(status = ProviderInvocationStatus.AWAITING_APPROVAL))?.status)
        assertNull(WorkRooms.endedUnseen(run(status = ProviderInvocationStatus.COMPLETED)))
        assertNull(WorkRooms.endedUnseen(parentCard))
    }

    @Test
    fun `a cleared run stays cleared`() {
        val result = ChatEventReducer.reduce(listOf(user), ChatEvent.ChatItemUpdated(run()), ignoredIds = setOf("codex:call-1"))

        assertEquals(listOf(user), result.chatItems)
    }
}
