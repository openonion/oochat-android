package ai.openonion.oochat.ui.controlcenter

import ai.openonion.oochat.domain.model.ApprovalDecision
import ai.openonion.oochat.domain.model.BatchToolPreview
import ai.openonion.oochat.domain.model.ChatFileAttachment
import ai.openonion.oochat.domain.model.ChatItem
import ai.openonion.oochat.domain.model.CompactStatus
import ai.openonion.oochat.domain.model.EvalStatus
import ai.openonion.oochat.domain.model.IntentStatus
import ai.openonion.oochat.domain.model.ReceivedFile
import ai.openonion.oochat.domain.model.ThinkingStatus
import ai.openonion.oochat.domain.model.ToolStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlCenterChatItemsTest {

    private fun one(item: ChatItem): JsonObject = ControlCenterChatItems.of(listOf(item)).single()

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `messages are the SDK's user and agent items, without the user's local attachments`() {
        val user = ChatItem.User(
            id = "u1",
            content = "hi",
            images = listOf("file:///data/user/0/app/img.jpg"),
            files = listOf(ChatFileAttachment("a.pdf", "file:///data/user/0/app/a.pdf"))
        )
        assertEquals(json("""{"id":"u1","type":"user","content":"hi"}"""), one(user))
        assertEquals(
            json("""{"id":"a1","type":"agent","content":"hello","images":["https://x/i.png"]}"""),
            one(ChatItem.Agent(id = "a1", content = "hello", images = listOf("https://x/i.png")))
        )
        assertEquals(json("""{"id":"a2","type":"agent","content":"no images"}"""), one(ChatItem.Agent(id = "a2", content = "no images", images = emptyList())))
    }

    @Test
    fun `thinking carries its status, timing, context and usage`() {
        val thinking = ChatItem.Thinking(
            id = "th1",
            status = ThinkingStatus.DONE,
            model = "m",
            durationMs = 120.0,
            tokensTotal = 42,
            costUsd = 0.01,
            contextPercent = 33.5,
            content = "pondering",
            kind = "reasoning"
        )
        assertEquals(
            json(
                """{"id":"th1","type":"thinking","status":"done","model":"m","duration_ms":120.0,"content":"pondering",""" +
                    """"kind":"reasoning","context_percent":33.5,"usage":{"total_tokens":42,"cost":0.01}}"""
            ),
            one(thinking)
        )
        assertEquals(
            json("""{"id":"th2","type":"thinking","status":"running"}"""),
            one(ChatItem.Thinking(id = "th2", status = ThinkingStatus.RUNNING))
        )
    }

    @Test
    fun `a turn is its thinking and then its reply, with distinct ids`() {
        val turn = ChatItem.Turn(
            id = "t1",
            thinking = ChatItem.Thinking(id = "t1", status = ThinkingStatus.DONE),
            agent = ChatItem.Agent(id = "t1", content = "answer")
        )
        assertEquals(
            listOf(
                json("""{"id":"t1","type":"thinking","status":"done"}"""),
                json("""{"id":"t1:reply","type":"agent","content":"answer"}""")
            ),
            ControlCenterChatItems.of(listOf(turn))
        )
        assertEquals(
            listOf(json("""{"id":"r1","type":"agent","content":"only"}""")),
            ControlCenterChatItems.of(listOf(ChatItem.Turn(id = "t2", agent = ChatItem.Agent(id = "r1", content = "only"))))
        )
        assertEquals(emptyList<JsonObject>(), ControlCenterChatItems.of(listOf(ChatItem.Turn(id = "t3"))))
    }

    @Test
    fun `tool calls follow their status`() {
        assertEquals(
            json("""{"id":"c1","type":"tool_call","name":"search","args":{"q":"x"},"status":"running"}"""),
            one(ChatItem.ToolCall(id = "c1", name = "search", args = mapOf("q" to "x")))
        )
        assertEquals(
            json("""{"id":"c1","type":"tool_call","name":"search","status":"done","result":"ok","timing_ms":12}"""),
            one(ChatItem.ToolCall(id = "c1", name = "search", status = ToolStatus.DONE, result = "ok", timingMs = 12))
        )
        assertEquals("error", one(ChatItem.ToolCall(id = "c1", name = "search", status = ToolStatus.ERROR))["status"].toString().trim('"'))
    }

    @Test
    fun `questions and approvals say whether they are still open`() {
        assertEquals(
            json("""{"id":"q1","type":"ask_user","text":"Which?","options":["a","b"],"multi_select":true}"""),
            one(ChatItem.AskUser(id = "q1", text = "Which?", options = listOf("a", "b"), multiSelect = true))
        )
        val open = ChatItem.ApprovalNeeded(
            id = "p1",
            tool = "bash",
            arguments = mapOf("command" to "ls"),
            description = "List",
            batchRemaining = listOf(BatchToolPreview("bash", "pwd"))
        )
        assertEquals(
            json(
                """{"id":"p1","type":"approval_needed","tool":"bash","arguments":{"command":"ls"},"description":"List",""" +
                    """"batch_remaining":[{"tool":"bash","arguments":"pwd"}],"answered":false}"""
            ),
            one(open)
        )
        assertEquals("true", one(open.copy(decision = ApprovalDecision(true, "once", null)))["answered"].toString())
    }

    @Test
    fun `status items map their status to the SDK's words`() {
        assertEquals(
            json("""{"id":"i1","type":"intent","status":"understood","ack":"ok","is_build":false}"""),
            one(ChatItem.IntentItem(id = "i1", status = IntentStatus.UNDERSTOOD, ack = "ok", isBuild = false))
        )
        assertEquals(json("""{"id":"i2","type":"intent","status":"analyzing"}"""), one(ChatItem.IntentItem(id = "i2", status = IntentStatus.ANALYZING)))
        assertEquals(
            json("""{"id":"e1","type":"eval","status":"done","passed":true,"summary":"s","expected":"e","eval_path":"p"}"""),
            one(ChatItem.EvalItem(id = "e1", status = EvalStatus.DONE, passed = true, summary = "s", expected = "e", evalPath = "p"))
        )
        assertEquals(json("""{"id":"e2","type":"eval","status":"evaluating"}"""), one(ChatItem.EvalItem(id = "e2", status = EvalStatus.EVALUATING)))
        assertEquals(
            json("""{"id":"k1","type":"compact","status":"done","context_before":80.5,"context_after":20.0,"context_percent":20.0,"message":"m"}"""),
            one(ChatItem.CompactItem(id = "k1", status = CompactStatus.DONE, contextBefore = 80.5, contextAfter = 20.0, contextPercent = 20.0, message = "m"))
        )
        assertEquals(
            json("""{"id":"k2","type":"compact","status":"error","error":"x"}"""),
            one(ChatItem.CompactItem(id = "k2", status = CompactStatus.ERROR, error = "x"))
        )
        assertEquals(json("""{"id":"k3","type":"compact","status":"compacting"}"""), one(ChatItem.CompactItem(id = "k3", status = CompactStatus.COMPACTING)))
    }

    @Test
    fun `onboarding, blocked tools and received files keep their fields`() {
        assertEquals(
            json("""{"id":"o1","type":"onboard_required","methods":["invite_code","payment"],"paymentAmount":1.5,"paymentAddress":"0xpay"}"""),
            one(ChatItem.OnboardRequired(id = "o1", methods = listOf("invite_code", "payment"), paymentAmount = 1.5, paymentAddress = "0xpay"))
        )
        assertEquals(
            json("""{"id":"o2","type":"onboard_success","level":"contact","message":"Welcome"}"""),
            one(ChatItem.OnboardSuccess(id = "o2", level = "contact", message = "Welcome"))
        )
        assertEquals(
            json("""{"id":"b1","type":"tool_blocked","tool":"bash","reason":"policy","message":"no","command":"rm"}"""),
            one(ChatItem.ToolBlockedItem(id = "b1", tool = "bash", reason = "policy", message = "no", command = "rm"))
        )
        assertEquals(
            json("""{"id":"f1","type":"files_received","files":[{"name":"r.txt","path":"/out/r.txt"}]}"""),
            one(ChatItem.FilesReceivedItem(id = "f1", files = listOf(ReceivedFile("r.txt", "/out/r.txt"))))
        )
    }

    @Test
    fun `items the SDK has no type for are left out, and the order is kept`() {
        val items = listOf(
            ChatItem.User(id = "u1", content = "a"),
            ChatItem.OnboardingFailed(id = "x1", reason = "bad code"),
            ChatItem.PlanReviewItem(id = "x2", planContent = "plan"),
            ChatItem.UlwTurnsReachedItem(id = "x3", turnsUsed = 5, maxTurns = 5),
            ChatItem.DiffPreviewItem(id = "x4", path = "f", preview = "+"),
            ChatItem.ModeChangedItem(id = "x5", mode = "auto"),
            ChatItem.Agent(id = "a1", content = "b")
        )
        assertEquals(listOf("u1", "a1"), ControlCenterChatItems.of(items).map { it["id"].toString().trim('"') })
    }
}
