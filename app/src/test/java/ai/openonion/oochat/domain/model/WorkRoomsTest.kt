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

    // ── provider_message and provider_artifact (W16AAPPLE-34) ─────────

    private fun said(id: String, text: String = "Done.", role: ProviderMessageRole = ProviderMessageRole.ASSISTANT) =
        ChatItem.ProviderMessageUpdate(
            invocationId = "codex:call-1",
            parentToolCallId = "call-1",
            provider = "codex",
            message = ProviderMessage(id = id, role = role, text = text)
        )

    private fun shot(id: String = "shot-1", revision: Long = 1, image: String = "data:image/png;base64,AAAA") =
        ChatItem.ProviderArtifactUpdate(
            invocationId = "codex:call-1",
            parentToolCallId = "call-1",
            provider = "codex",
            artifact = ProviderArtifact(id, image, "Latest provider workspace view", revision)
        )

    private fun cardOf(result: ReduceResult) =
        result.chatItems.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation

    @Test
    fun `messages join their run's conversation in the order they arrive`() {
        val onScreen = reduce(listOf(user), run()).chatItems

        val first = reduce(onScreen, said("m1", "Add a login page", ProviderMessageRole.USER))
        val second = reduce(first.chatItems, said("m2", "Added it."))

        assertEquals(listOf("u-1", "codex:call-1"), second.chatItems.map { it.id })
        assertEquals(listOf("m1", "m2"), cardOf(second).messages.map { it.id })
        assertEquals(
            listOf(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT),
            cardOf(second).messages.map { it.role }
        )
        assertEquals(cardOf(second), second.itemToPersist)
    }

    @Test
    fun `a message sent twice, or replayed, is shown once where it first arrived`() {
        val onScreen = reduce(reduce(listOf(user), run()).chatItems, said("m1", "Working on it")).chatItems
        val withSecond = reduce(onScreen, said("m2", "Added it.")).chatItems

        val again = reduce(withSecond, said("m1", "Working on it"))
        val rewritten = reduce(withSecond, said("m1", "Working on it, nearly done"))

        assertEquals(withSecond, again.chatItems)
        assertNull(again.itemToPersist)
        assertEquals(listOf("m1", "m2"), cardOf(rewritten).messages.map { it.id })
        assertEquals("Working on it, nearly done", cardOf(rewritten).messages.first().text)
    }

    @Test
    fun `a message that arrives before its run starts the card, in the place of the tool card`() {
        val result = reduce(listOf(user, parentCard), said("m1"))

        assertEquals(listOf("u-1", "codex:call-1"), result.chatItems.map { it.id })
        assertEquals(listOf("m1"), cardOf(result).messages.map { it.id })
    }

    @Test
    fun `a later run frame keeps the conversation and the picture`() {
        val withBoth = reduce(reduce(reduce(listOf(user), run()).chatItems, said("m1")).chatItems, shot()).chatItems

        val finished = reduce(withBoth, run(revision = 4, status = ProviderInvocationStatus.COMPLETED, summary = "Done"))

        assertEquals(listOf("m1"), cardOf(finished).messages.map { it.id })
        assertEquals("shot-1", cardOf(finished).artifact?.id)
        assertEquals(ProviderInvocationStatus.COMPLETED, cardOf(finished).status)
    }

    @Test
    fun `the run's picture is its latest, and an older one never replaces a newer`() {
        val onScreen = reduce(listOf(user), run()).chatItems

        val first = reduce(onScreen, shot("shot-1", revision = 2, image = "data:image/png;base64,ONE="))
        val newer = reduce(first.chatItems, shot("shot-2", revision = 3, image = "data:image/png;base64,TWO="))
        val older = reduce(newer.chatItems, shot("shot-0", revision = 1, image = "data:image/png;base64,OLD="))
        val replay = reduce(newer.chatItems, shot("shot-2", revision = 3, image = "data:image/png;base64,TWO="))

        assertEquals("shot-1", cardOf(first).artifact?.id)
        assertEquals("shot-2", cardOf(newer).artifact?.id)
        assertEquals(newer.chatItems, older.chatItems)
        assertNull(older.itemToPersist)
        assertEquals(newer.chatItems, replay.chatItems)
    }

    @Test
    fun `a picture of the same revision replaces the one shown`() {
        val first = reduce(reduce(listOf(user), run()).chatItems, shot("shot-1", revision = 2)).chatItems

        val again = reduce(first, shot("shot-2", revision = 2))

        assertEquals("shot-2", cardOf(again).artifact?.id)
    }

    @Test
    fun `a message or picture is never a list item of its own`() {
        val onScreen = reduce(listOf(user), run()).chatItems

        val result = reduce(reduce(onScreen, said("m1")).chatItems, shot())

        assertEquals(listOf("u-1", "codex:call-1"), result.chatItems.map { it.id })
    }

    @Test
    fun `a cleared run stays cleared when a late message or picture arrives`() {
        listOf(said("m1"), shot()).forEach { frame ->
            val result = ChatEventReducer.reduce(listOf(user), ChatEvent.ChatItemUpdated(frame), ignoredIds = setOf("codex:call-1"))

            assertEquals(listOf(user), result.chatItems)
        }
    }

    @Test
    fun `a saved card is brought up to date with the messages and picture of a card folded without it`() {
        val stored = run().copy(
            messages = listOf(ProviderMessage("m1", ProviderMessageRole.USER, "Add a login page")),
            artifact = ProviderArtifact("shot-2", "data:image/png;base64,TWO=", "Latest provider workspace view", 3)
        )
        val folded = ChatItem.ProviderInvocation(
            id = "codex:call-1",
            parentToolCallId = "call-1",
            provider = "codex",
            messages = listOf(ProviderMessage("m2", ProviderMessageRole.ASSISTANT, "Added it.")),
            artifact = ProviderArtifact("shot-1", "data:image/png;base64,ONE=", "Latest provider workspace view", 2)
        )

        val merged = WorkRooms.mergeStored(stored, folded)

        assertEquals(listOf("m1", "m2"), merged.messages.map { it.id })
        assertEquals("shot-2", merged.artifact?.id)
    }

    @Test
    fun `a conversation that outgrows the budget keeps its newest messages, in order`() {
        val chunk = WorkRooms.MAX_CONVERSATION_CHARS / 4
        var chat = reduce(listOf(user), run()).chatItems
        (1..6).forEach { n -> chat = reduce(chat, said("m$n", "x".repeat(chunk))).chatItems }

        val kept = (chat.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation).messages

        assertEquals(listOf("m3", "m4", "m5", "m6"), kept.map { it.id })
        assertTrue(kept.sumOf { it.text.length } <= WorkRooms.MAX_CONVERSATION_CHARS)
    }

    @Test
    fun `a conversation within the budget loses nothing`() {
        val chunk = WorkRooms.MAX_CONVERSATION_CHARS / 4
        var chat = reduce(listOf(user), run()).chatItems
        (1..4).forEach { n -> chat = reduce(chat, said("m$n", "x".repeat(chunk))).chatItems }

        val kept = (chat.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation).messages

        assertEquals(listOf("m1", "m2", "m3", "m4"), kept.map { it.id })
    }

    @Test
    fun `the oldest message goes when it alone tips the conversation over the budget`() {
        val half = WorkRooms.MAX_CONVERSATION_CHARS / 2
        var chat = reduce(listOf(user), run()).chatItems
        chat = reduce(chat, said("m1", "x".repeat(half))).chatItems
        chat = reduce(chat, said("m2", "y".repeat(half))).chatItems

        val kept = (reduce(chat, said("m3", "z")).chatItems.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation).messages

        assertEquals(listOf("m2", "m3"), kept.map { it.id })
    }

    @Test
    fun `the newest message is kept however long it is`() {
        val onScreen = reduce(listOf(user), run()).chatItems

        val kept = cardOf(reduce(onScreen, said("m1", "x".repeat(WorkRooms.MAX_CONVERSATION_CHARS + 1)))).messages

        assertEquals(listOf("m1"), kept.map { it.id })
    }

    @Test
    fun `a saved card and the frames folded since stay within the budget together`() {
        val chunk = WorkRooms.MAX_CONVERSATION_CHARS / 2
        val stored = run().copy(messages = listOf(
            ProviderMessage("m1", ProviderMessageRole.USER, "x".repeat(chunk)),
            ProviderMessage("m2", ProviderMessageRole.ASSISTANT, "y".repeat(chunk))
        ))
        val folded = run().copy(messages = listOf(ProviderMessage("m3", ProviderMessageRole.ASSISTANT, "z".repeat(10))))

        assertEquals(listOf("m2", "m3"), WorkRooms.mergeStored(stored, folded).messages.map { it.id })
    }


    @Test
    fun `a run's permission is kept by frames that leave it out, and replaced by one that names it (W16AAPPLE-43)`() {
        val ask = ProviderPermissionState(
            provider = "codex",
            activeOptionId = "codex:workspace-ask",
            options = listOf(
                ProviderPermissionOption("codex:workspace-ask", "Ask for approval", selectable = true),
                ProviderPermissionOption("codex:read-only", "Read Only", selectable = true)
            )
        )
        val onScreen = reduce(listOf(user), run(revision = 1).copy(providerPermission = ask)).chatItems

        val stepOnly = reduce(onScreen, run(revision = 2, title = null)).chatItems
        val readOnly = ask.copy(activeOptionId = "codex:read-only")
        val changed = reduce(stepOnly, run(revision = 3, title = null).copy(providerPermission = readOnly))

        assertEquals(ask, (stepOnly.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation).providerPermission)
        assertEquals(
            "codex:read-only",
            (changed.chatItems.single { it is ChatItem.ProviderInvocation } as ChatItem.ProviderInvocation).providerPermission?.activeOptionId
        )
    }
}
