package ai.openonion.oochat.util

import ai.openonion.oochat.util.SensitiveText.REDACTED
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W16AAPPLE-50: no credentials, private paths or raw provider payloads on screen. */
class SensitiveTextTest {

    // ── credentials ─────────────────────────────────────────────────────

    @Test
    fun `token shapes are redacted wherever they appear`() {
        listOf(
            "sk-ant-api03-abcdefghijklmnopqrstuv",
            "sk-proj-abcdefghijklmnopqrstuv",
            "ghp_abcdefghijklmnopqrstuvwxyz0123",
            "github_pat_abcdefghijklmnopqrstuvwxyz",
            "xoxb-1234567890-abcdefghij",
            "AKIAABCDEFGHIJKLMNOP",
            "AIzaSyA1234567890abcdefghijklmnopqrstuv",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijklmnop",
        ).forEach { secret ->
            val shown = SensitiveText.redact("key is $secret here")
            assertFalse("shown: $shown", shown.contains(secret))
            assertEquals("key is $REDACTED here", shown)
        }
    }

    @Test
    fun `a private key block is removed whole`() {
        val text = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXk\n-----END OPENSSH PRIVATE KEY-----"
        assertEquals("[private key removed]", SensitiveText.redact(text))
    }

    @Test
    fun `a url keeps its user and loses its password`() {
        assertEquals(
            "git clone https://priya:$REDACTED@github.com/x/y.git",
            SensitiveText.redact("git clone https://priya:hunter22@github.com/x/y.git")
        )
    }

    @Test
    fun `bearer headers, named secrets and env lines are redacted`() {
        assertEquals("Authorization: Bearer $REDACTED", SensitiveText.redact("Authorization: Bearer abc123def456ghi789"))
        assertEquals("password: $REDACTED", SensitiveText.redact("password: hunter22"))
        assertEquals("DB_PASSWORD=$REDACTED", SensitiveText.redact("DB_PASSWORD=hunter2"))
        assertEquals("CO_INVITE_CODE=$REDACTED", SensitiveText.redact("CO_INVITE_CODE=ABCD-1234"))
    }

    @Test
    fun `a secret in a JSON argument is redacted and the JSON still parses`() {
        val shown = SensitiveText.redact("""{"url": "https://x.dev", "api_key": "hunter2hunter2", "env": "TOKEN=abcdef"}""")
        val obj = Json.parseToJsonElement(shown).jsonObject
        assertEquals(REDACTED, obj["api_key"]!!.jsonPrimitive.content)
        assertEquals("TOKEN=$REDACTED", obj["env"]!!.jsonPrimitive.content)
        assertEquals("https://x.dev", obj["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a token after a named secret is masked once, with nothing left over`() {
        assertEquals("api key = $REDACTED", SensitiveText.redact("api key = sk-ant-api03-abcdefghijklmnopqrstuv"))
        assertEquals("token: $REDACTED", SensitiveText.redact("token: ghp_abcdefghijklmnopqrstuvwxyz0123"))
        assertEquals("OPENAI_API_KEY=$REDACTED", SensitiveText.redact("OPENAI_API_KEY=sk-proj-abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `code that reads a secret is left as written`() {
        listOf(
            "const token = await getToken();",
            "token = get_token()",
            """password = request.form["password"]""",
            "api_key = os.environ[\"OPENAI_API_KEY\"]",
            "token = process.env.GITHUB_TOKEN",
            "password: string;",
            "token = None",
            "export TOKEN=\$GITHUB_TOKEN",
            "BYPASS=1 make",
            "SORT_KEY=name",
        ).forEach { assertEquals(it, SensitiveText.redact(it)) }
    }

    @Test
    fun `an env name ending in a secret word after an underscore is still a secret`() {
        assertEquals("DB_PASS=$REDACTED", SensitiveText.redact("DB_PASS=hunter2"))
        assertEquals("AWS_SECRET_ACCESS_KEY=$REDACTED", SensitiveText.redact("AWS_SECRET_ACCESS_KEY=abc/def+ghi"))
        assertEquals("GITHUB_TOKEN=$REDACTED", SensitiveText.redact("GITHUB_TOKEN=abcdef123456"))
    }

    @Test
    fun `ordinary prose and command output are left alone`() {
        listOf(
            "The token expired, so log in again.",
            "Process exited with EXIT_CODE=1",
            "\"max_tokens\": 4096",
            "See https://docs.example.com/guide/setup for details.",
        ).forEach { assertEquals(it, SensitiveText.redact(it)) }
    }

    // ── private paths ───────────────────────────────────────────────────

    @Test
    fun `home folders become tilde on every platform`() {
        assertEquals("cat ~/.ssh/config", SensitiveText.redact("cat /Users/priya/.ssh/config"))
        assertEquals("cd ~/code/app", SensitiveText.redact("cd /home/priya/code/app"))
        assertEquals("""~\project\app""", SensitiveText.redact("""C:\Users\priya\project\app"""))
        assertEquals("~/project", SensitiveText.redact("/mnt/c/Users/priya/project"))
    }

    @Test
    fun `a home folder escaped in JSON is still found`() {
        assertEquals("""{"path": "~\\a.txt"}""", SensitiveText.redact("""{"path": "C:\\Users\\priya\\a.txt"}"""))
        assertEquals("""{"path": "~/a.txt"}""", SensitiveText.redact("""{"path": "/Users/priya/a.txt"}"""))
    }

    @Test
    fun `a web address with home or Users in its path is left whole`() {
        listOf(
            "[the guide](https://docs.example.com/home/getting-started) first.",
            "https://github.com/Users/repo",
            "see src/home/index.ts",
        ).forEach { assertEquals(it, SensitiveText.redact(it)) }
    }

    @Test
    fun `a home folder in a file url or a PATH list is still found`() {
        assertEquals("file://~/a.txt", SensitiveText.redact("file:///Users/priya/a.txt"))
        assertEquals("PATH=/usr/bin:~/bin", SensitiveText.redact("PATH=/usr/bin:/home/priya/bin"))
    }

    @Test
    fun `paths outside a home folder stay readable in chat`() {
        // What a tool card shows is what the user is approving (ToolArgsSummary).
        assertEquals("cat /etc/hosts", SensitiveText.redact("cat /etc/hosts"))
    }

    @Test
    fun `without paths, any other absolute path goes too`() {
        assertEquals(
            "saved to ${SensitiveText.PATH_REMOVED}, notes in ~/notes.txt",
            SensitiveText.redactWithoutPaths("saved to /var/data/priya.db, notes in /Users/priya/notes.txt")
        )
    }

    // ── raw provider payloads ───────────────────────────────────────────

    @Test
    fun `a provider's object in an error is replaced whole`() {
        listOf(
            """Error code: 400 - {'type': 'error', 'error': {'type': 'invalid_request_error'}}""",
            """{"type":"error","error":{"message":"overloaded"}}""",
            "Traceback (most recent call last):\n  File \"/Users/priya/agent.py\", line 3",
        ).forEach { assertEquals(SensitiveText.UNREADABLE_ERROR, SensitiveText.errorText(it)) }
    }

    @Test
    fun `a readable error is kept, redacted`() {
        assertEquals("Couldn't open ~/app/x.txt", SensitiveText.errorText("Couldn't open /home/priya/app/x.txt"))
        assertEquals("This agent is offline.", SensitiveText.errorText("This agent is offline."))
    }

    @Test
    fun `an error keeps no path outside the home folder, where chat would`() {
        val raw = "Permission denied: /var/www/client-site/config.php"
        assertEquals("Permission denied: ${SensitiveText.PATH_REMOVED}", SensitiveText.errorText(raw))
        assertEquals(raw, SensitiveText.redact(raw))
    }

    @Test
    fun `other payload shapes are replaced too`() {
        listOf(
            """[{"type": "error", "message": "overloaded"}]""",
            """['rate_limit', 'retry_after']""",
            "Error: {type: overloaded_error, status: 529}",
            "{400: 'Bad Request'}",
            "APIStatusError(message='prompt too long', status_code=400)",
            "<anthropic.types.Message object at 0x7f3a2b1c>",
            "java.io.IOException: closed\n\tat okhttp3.internal.Http2.read(Http2.kt:42)",
            "TypeError: x is undefined\n    at Object.<anonymous> (/srv/agent.js:3:9)",
            "<!DOCTYPE html><html><body>502 Bad Gateway</body></html>",
        ).forEach { assertEquals("for: $it", SensitiveText.UNREADABLE_ERROR, SensitiveText.errorText(it)) }
    }

    @Test
    fun `a Work Room summary that is a payload is replaced without calling it an error`() {
        assertEquals(SensitiveText.UNREADABLE_SUMMARY, SensitiveText.summaryText("""{"status": "ok", "files": 3}"""))
        assertEquals("Edited ~/app and /srv/site", SensitiveText.summaryText("Edited /Users/priya/app and /srv/site"))
    }

    @Test
    fun `redacting HTML leaves its tags intact`() {
        assertEquals("<td>~</td><td>~/x</td>", SensitiveText.redact("<td>/Users/priya</td><td>/home/priya/x</td>"))
        assertEquals("<td>password: $REDACTED</td>", SensitiveText.redact("<td>password: hunter22</td>"))
        assertEquals("<code>DB_PASS=$REDACTED</code>", SensitiveText.redact("<code>DB_PASS=hunter2</code>"))
    }

    @Test
    fun `braces in a sentence are not a payload`() {
        listOf(
            "Use {name} as the placeholder",
            "Couldn't parse the config {timeout: 30s}",
            "Expected one of ['fast', 'safe'], got 'slow'",
            "Error at line 3: {x: 1}",
        ).forEach {
            assertFalse("for: $it", SensitiveText.isRawPayload(it))
            assertEquals(it, SensitiveText.errorText(it))
        }
        assertTrue(SensitiveText.isRawPayload("""failed: {"code": 500}"""))
    }

    @Test
    fun `a successful run's summary that quotes an object is kept`() {
        assertEquals("Edited config to {retries: 3}", SensitiveText.summaryText("Edited config to {retries: 3}"))
    }

    @Test
    fun `a password that only looks like code is still masked`() {
        listOf(
            "password: correct.horse.battery",
            "my password: new.Hunter2.pass",
            "password: \$ecretPass99",
            "password=Passw0rd[1]",
        ).forEach { assertFalse("shown: ${SensitiveText.redact(it)}", SensitiveText.redact(it) == it) }
        assertEquals("password: $REDACTED", SensitiveText.redact("password: correct.horse.battery"))
        assertEquals("TOKEN=\$(cat ~/.token)", SensitiveText.redact("TOKEN=\$(cat /home/priya/.token)"))
    }
}
