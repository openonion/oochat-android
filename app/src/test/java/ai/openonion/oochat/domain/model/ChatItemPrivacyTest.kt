package ai.openonion.oochat.domain.model

import ai.openonion.oochat.util.SensitiveText
import ai.openonion.oochat.util.SensitiveText.REDACTED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** W16AAPPLE-50: every chat item the screen draws, redacted; nothing else changed. */
class ChatItemPrivacyTest {

    private val key = "sk-ant-api03-abcdefghijklmnopqrstuv"
    private val home = "/Users/priya"

    @Test
    fun `a tool call's arguments and output are redacted, its keys kept`() {
        val item = ChatItem.ToolCall(
            id = "t1",
            name = "bash",
            args = mapOf("command" to "cat $home/.env", "description" to "Show env"),
            result = "ANTHROPIC_API_KEY=$key"
        )

        val shown = ChatItemPrivacy.redact(item) as ChatItem.ToolCall

        assertEquals(mapOf("command" to "cat ~/.env", "description" to "Show env"), shown.args)
        assertEquals("ANTHROPIC_API_KEY=$REDACTED", shown.result)
    }

    @Test
    fun `an approval shows what it asks, redacted, and keeps its request id`() {
        val item = ChatItem.ApprovalNeeded(
            id = "a1",
            tool = "write",
            arguments = mapOf("file_path" to "$home/app/config.json"),
            description = "Write the key $key",
            batchRemaining = listOf(BatchToolPreview("bash", """{"command": "rm $home/tmp"}""")),
            requestId = "req-1"
        )

        val shown = ChatItemPrivacy.redact(item) as ChatItem.ApprovalNeeded

        assertEquals(mapOf("file_path" to "~/app/config.json"), shown.arguments)
        assertEquals("Write the key $REDACTED", shown.description)
        assertEquals("""{"command": "rm ~/tmp"}""", shown.batchRemaining!!.single().arguments)
        assertEquals("req-1", shown.requestId)
    }

    @Test
    fun `a turn's thinking and reply are both redacted`() {
        val item = ChatItem.Turn(
            id = "turn",
            thinking = ChatItem.Thinking(id = "turn", status = ThinkingStatus.DONE, content = "Reading $home/a"),
            agent = ChatItem.Agent(id = "turn-r", content = "Your key is $key")
        )

        val shown = ChatItemPrivacy.redact(item) as ChatItem.Turn

        assertEquals("Reading ~/a", shown.thinking!!.content)
        assertEquals("Your key is $REDACTED", shown.agent!!.content)
    }

    @Test
    fun `error text that is a raw payload is replaced, content that looks like one is not`() {
        val payload = """{"type": "error", "error": {"message": "overloaded"}}"""

        val failed = ChatItemPrivacy.redact(ChatItem.Thinking(id = "t", status = ThinkingStatus.ERROR, content = payload))
        val compact = ChatItemPrivacy.redact(ChatItem.CompactItem(id = "c", status = CompactStatus.ERROR, error = payload))
        val blocked = ChatItemPrivacy.redact(ChatItem.ToolBlockedItem(id = "b", tool = "bash", reason = payload, message = payload))
        // A tool that returns JSON: the user asked for it.
        val output = ChatItemPrivacy.redact(ChatItem.ToolCall(id = "o", name = "fetch", result = payload))

        assertEquals(SensitiveText.UNREADABLE_ERROR, (failed as ChatItem.Thinking).content)
        assertEquals(SensitiveText.UNREADABLE_ERROR, (compact as ChatItem.CompactItem).error)
        assertEquals(SensitiveText.UNREADABLE_ERROR, (blocked as ChatItem.ToolBlockedItem).reason)
        assertEquals(payload, (output as ChatItem.ToolCall).result)
    }

    @Test
    fun `a path stays in an approval but leaves the error that refused it`() {
        val path = "/var/www/client-site"
        val approval = ChatItemPrivacy.redact(
            ChatItem.ApprovalNeeded(id = "a", tool = "bash", arguments = mapOf("command" to "rm -rf $path"))
        ) as ChatItem.ApprovalNeeded
        val blocked = ChatItemPrivacy.redact(
            ChatItem.ToolBlockedItem(id = "b", tool = "bash", reason = "outside workspace", message = "Blocked: $path", command = "rm -rf $path")
        ) as ChatItem.ToolBlockedItem

        assertEquals("rm -rf $path", approval.arguments["command"])
        assertEquals("Blocked: ${SensitiveText.PATH_REMOVED}", blocked.message)
        // The command is what was refused; the user reads it as they would in an approval.
        assertEquals("rm -rf $path", blocked.command)
    }

    @Test
    fun `a Work Room run's steps and files read like tool cards, its error like an error`() {
        val run = ChatItem.ProviderInvocation(
            id = "inv-1",
            provider = "codex",
            taskTitle = "Rotate key $key",
            status = ProviderInvocationStatus.FAILED,
            errorSummary = "Could not write /var/www/site/.env",
            activities = listOf(
                ProviderActivity(
                    id = "s1",
                    sequence = 1,
                    kind = "file_change",
                    status = ProviderActivityStatus.values().first(),
                    title = "Edited $home/app/main.kt",
                    summary = "Set TOKEN=abcdef",
                    files = listOf("$home/app/main.kt", "/var/www/site/index.php")
                )
            )
        )

        val shown = ChatItemPrivacy.redact(run) as ChatItem.ProviderInvocation
        val step = shown.activities.single()

        assertEquals("Rotate key $REDACTED", shown.taskTitle)
        assertEquals("Could not write ${SensitiveText.PATH_REMOVED}", shown.errorSummary)
        assertEquals("Edited ~/app/main.kt", step.title)
        assertEquals("Set TOKEN=$REDACTED", step.summary)
        assertEquals(listOf("~/app/main.kt", "/var/www/site/index.php"), step.files)
        assertEquals("inv-1", shown.id)
    }

    @Test
    fun `a Work Room summary that is a raw payload is replaced`() {
        val run = ChatItem.ProviderInvocation(
            id = "inv-2",
            currentSummary = "Running tests in $home/app",
            resultSummary = """{"type": "result", "subtype": "success", "session_id": "abc"}"""
        )

        val shown = ChatItemPrivacy.redact(run) as ChatItem.ProviderInvocation

        assertEquals("Running tests in ~/app", shown.currentSummary)
        assertEquals(SensitiveText.UNREADABLE_SUMMARY, shown.resultSummary)
    }

    @Test
    fun `a Work Room's messages are redacted, a raw payload in one is replaced, and code is kept`() {
        val run = ChatItem.ProviderInvocation(
            id = "inv-3",
            messages = listOf(
                ProviderMessage("m1", ProviderMessageRole.USER, "Use key $key in $home/app"),
                ProviderMessage("m2", ProviderMessageRole.ASSISTANT, """{"type": "result", "session_id": "abc"}"""),
                ProviderMessage("m3", ProviderMessageRole.ASSISTANT, "Run:\n```\nText(text = \"hi\")\n```")
            )
        )

        val shown = (ChatItemPrivacy.redact(run) as ChatItem.ProviderInvocation).messages

        assertEquals("Use key $REDACTED in ~/app", shown[0].text)
        assertEquals(SensitiveText.UNREADABLE_SUMMARY, shown[1].text)
        assertEquals("Run:\n```\nText(text = \"hi\")\n```", shown[2].text)
        assertEquals(listOf("m1", "m2", "m3"), shown.map { it.id })
        assertEquals(
            listOf(ProviderMessageRole.USER, ProviderMessageRole.ASSISTANT, ProviderMessageRole.ASSISTANT),
            shown.map { it.role }
        )
    }

    @Test
    fun `the user's own attachments keep their local paths`() {
        val item = ChatItem.User(
            id = "u",
            content = "see $home/a.png",
            images = listOf("$home/a.png"),
            files = listOf(ChatFileAttachment("a.pdf", "$home/a.pdf"))
        )

        val shown = ChatItemPrivacy.redact(item) as ChatItem.User

        assertEquals("see ~/a.png", shown.content)
        assertEquals(item.images, shown.images)
        assertEquals(item.files, shown.files)
    }

    @Test
    fun `an item with nothing to hide comes back as the same instance`() {
        val item = ChatItem.Agent(id = "a", content = "All done.")
        assertSame(item, ChatItemPrivacy.redact(item))
    }

    // ── Cache ────────────────────────────────────────────────────────────

    @Test
    fun `the cache hands back the caller's list when nothing is masked`() {
        val items = listOf(ChatItem.Agent(id = "a", content = "hi"), ChatItem.User(id = "u", content = "hello"))
        assertSame(items, ChatItemPrivacy.Cache().redact(items))
    }

    @Test
    fun `the same list twice gives the same result`() {
        val cache = ChatItemPrivacy.Cache()
        val items = listOf(ChatItem.Agent(id = "a", content = "key $key"))
        assertSame(cache.redact(items), cache.redact(items))
    }

    @Test
    fun `an unchanged item keeps its redacted instance while another one streams`() {
        val cache = ChatItemPrivacy.Cache()
        val settled = ChatItem.Agent(id = "a", content = "key $key")
        val first = cache.redact(listOf(settled, ChatItem.Agent(id = "b", content = "Wri")))
        val second = cache.redact(listOf(settled, ChatItem.Agent(id = "b", content = "Writing")))

        assertNotSame(settled, first[0])
        assertSame(first[0], second[0])
        assertEquals("Writing", (second[1] as ChatItem.Agent).content)
    }

    @Test
    fun `an item changed under the same id is redacted again`() {
        val cache = ChatItemPrivacy.Cache()
        cache.redact(listOf(ChatItem.Agent(id = "a", content = "safe")))
        val shown = cache.redact(listOf(ChatItem.Agent(id = "a", content = "now $key")))
        assertEquals("now $REDACTED", (shown[0] as ChatItem.Agent).content)
    }
}
