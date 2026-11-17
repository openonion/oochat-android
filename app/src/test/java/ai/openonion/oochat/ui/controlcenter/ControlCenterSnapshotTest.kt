package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.AgentSkill
import ai.openonion.oochat.domain.model.ConnectionState
import ai.openonion.oochat.domain.model.SessionActivity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterSnapshotTest {

    private fun of(
        activity: SessionActivity? = SessionActivity.IDLE,
        connection: ConnectionState = ConnectionState.Connected(address = "0xagent"),
        skills: List<AgentSkill> = emptyList(),
        chatItems: List<JsonObject> = emptyList()
    ) = ControlCenterSnapshot.of("conv-1", "0xagent", activity, connection, skills, ControlCenterChat.bounded(chatItems))

    private fun chatItem(id: Int, content: String = "m$id") = buildJsonObject {
        put("id", "i$id")
        put("type", "agent")
        put("content", content)
    }

    private fun bytes(item: JsonObject) = item.toString().toByteArray(Charsets.UTF_8).size

    private fun ids(snapshot: ControlCenterSnapshot) = snapshot.chatItems.map { it["id"].toString().trim('"') }

    @Test
    fun `the agent's status is the chat's own status line`() {
        assertEquals(ControlCenterAgentStatus.WORKING, of(SessionActivity.RUNNING).status)
        assertEquals(ControlCenterAgentStatus.WAITING, of(SessionActivity.AWAITING_USER).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(SessionActivity.IDLE).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(SessionActivity.NOT_FOUND).status)
        assertEquals(ControlCenterAgentStatus.IDLE, of(null).status)
    }

    @Test
    fun `only a live connection reads as connected, and only a reconnect as reconnecting`() {
        assertEquals(ControlCenterConnection.CONNECTED, of(connection = ConnectionState.Connected(address = "0xagent")).connectionState)
        assertEquals(ControlCenterConnection.RECONNECTING, of(connection = ConnectionState.Reconnecting).connectionState)
        listOf(ConnectionState.Idle, ConnectionState.Connecting, ConnectionState.Disconnected, ConnectionState.Error("x"))
            .forEach { assertEquals(it.toString(), ControlCenterConnection.DISCONNECTED, of(connection = it).connectionState) }
    }

    @Test
    fun `skills are cut to what the app's SDK accepts`() {
        val many = (0 until 2001).map { AgentSkill(name = "s$it") }
        val longName = AgentSkill(name = "n".repeat(65))
        val long = AgentSkill(name = "n".repeat(64), description = "d".repeat(300))

        val bounded = of(skills = many)
        val trimmed = of(skills = listOf(longName, long, AgentSkill(name = "plain", description = " "))).skills

        assertEquals(2000, bounded.skills.size)
        // As boundControlSnapshot: truncated is about chat items, not skills.
        assertFalse(bounded.truncated)
        // Dropped, not cut: the cut name would pass run_skill's check but name another skill.
        assertEquals(listOf("n".repeat(64), "plain"), trimmed.map { it.name })
        assertEquals(256, trimmed[0].description?.length)
        assertNull("a blank description is left out", trimmed[1].description)
    }

    @Test
    fun `skills that escape badly in JSON cannot outgrow the snapshot`() {
        // A control character encodes as six bytes, so 2000 such skills would be about 4 MB.
        val heavy = (0 until 2000).map { AgentSkill(name = "\u0001".repeat(60) + it, description = "\u0001".repeat(256)) }

        val skills = of(skills = heavy).toJson()["skills"].toString().toByteArray(Charsets.UTF_8).size

        assertTrue("skills JSON was $skills bytes", skills <= ControlCenterSnapshot.MAX_SKILL_BYTES)
    }

    @Test
    fun `a chat that fits is sent whole and not truncated`() {
        val items = (0 until 3).map { chatItem(it) }

        val snapshot = of(chatItems = items)

        assertEquals(items, snapshot.chatItems)
        assertFalse(snapshot.truncated)
        assertEquals(items, (snapshot.toJson()["chatItems"] as JsonArray).toList())
    }

    @Test
    fun `only the latest 2000 chat items are sent, and the snapshot says so`() {
        val snapshot = of(chatItems = (0 until 2001).map { chatItem(it) })

        assertEquals(ControlCenterChat.MAX_ITEMS, snapshot.chatItems.size)
        assertEquals("i1", ids(snapshot).first())
        assertEquals("i2000", ids(snapshot).last())
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `chat items are kept newest first within 1 MiB, skipping one that does not fit`() {
        val big = chatItem(1, "x".repeat(600 * 1024))
        val bigger = chatItem(2, "y".repeat(600 * 1024))
        val small = chatItem(0)
        val latest = chatItem(3)

        // As boundControlSnapshot: [bigger] no longer fits after [big], but the older [small] still does.
        val snapshot = of(chatItems = listOf(small, bigger, big, latest))

        assertEquals(listOf("i0", "i1", "i3"), ids(snapshot))
        assertTrue(snapshot.chatItems.sumOf(::bytes) <= ControlCenterChat.MAX_BYTES)
        assertTrue(snapshot.truncated)
    }

    @Test
    fun `a chat item measured exactly at the 1 MiB limit is kept`() {
        val empty = bytes(chatItem(0, ""))
        val exact = chatItem(0, "z".repeat(ControlCenterChat.MAX_BYTES - empty))
        assertEquals(ControlCenterChat.MAX_BYTES, bytes(exact))

        val snapshot = of(chatItems = listOf(exact))

        assertEquals(listOf("i0"), ids(snapshot))
        assertFalse(snapshot.truncated)
        assertTrue(of(chatItems = listOf(chatItem(9), exact)).truncated)
    }

    @Test
    fun `the chat is built from the app's own items`() {
        val chat = ControlCenterChat.of(
            listOf(
                ai.openonion.oochat.domain.model.ChatItem.User(id = "u1", content = "hi"),
                ai.openonion.oochat.domain.model.ChatItem.ModeChangedItem(id = "m1", mode = "auto")
            )
        )

        assertEquals(listOf("u1"), chat.items.map { it["id"].toString().trim('"') })
        assertFalse("an item with no SDK type is not one left out by the bounds", chat.truncated)
    }
}
