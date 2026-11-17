package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W16AAPPLE-69: the Rem notebook out of the Host's reader page. The page shape
 * is connectonion 1.9.1's rem/reader.py `render`: the template, with the
 * snapshot dropped onto its `const REM = …;` line and `<` escaped.
 */
class RemNotebookTest {

    private val snapshot = """
        {"as_of":"2026-11-02T01:00:00+00:00","root":"/Users/jo/.co/rem",
         "categories":["people","orgs","projects","skills","notes"],
         "records":[
           {"path":"projects/apollo.md","category":"projects","title":"Apollo",
            "text":"# Apollo\n\nLaunch on **Friday**.\n\n- File: /Users/jo/code/apollo/README.md",
            "updated":"2026-10-30T09:15:00+00:00","project_coverage":{"cwd":"/Users/jo/code/apollo"}},
           {"path":"people/sam.md","category":"people","title":"Sam Lee",
            "text":"# Sam Lee\n\nWorks on \u003cscript\u003e tags.","updated":"2026-11-01T22:00:00+00:00"},
           {"path":"people/ana.md","category":"people","title":"Ana","text":"Plain note, no heading."},
           {"path":"skills/catalog/deploy.md","category":"skills","title":"deploy",
            "text":"- File: C:\\Users\\jo\\.claude\\skills\\deploy\\SKILL.md"},
           {"path":"broken.md","category":"notes","text":"no title"}
         ],
         "logs":[{"run":"mail","error":"token expired for jo@example.com"}],
         "subscriptions":{"gmail":"jo@example.com"}}
    """.trimIndent().replace("\n", "")

    private val readerPage = """
        <!doctype html>
        <html><head><title>co rem</title></head><body>
        <script>
        const REM = $snapshot;
        render(REM);
        </script>
        </body></html>
    """.trimIndent()

    private val notebook = RemNotebook.parse(readerPage)!!

    @Test
    fun `pages come out in the Host's category order, then by title`() {
        assertEquals(
            listOf("people" to listOf("Ana", "Sam Lee"), "projects" to listOf("Apollo"), "skills" to listOf("deploy")),
            notebook.sections.map { (category, pages) -> category to pages.map { it.title } }
        )
    }

    @Test
    fun `a page keeps its Markdown, with the reader's escaping undone`() {
        val sam = notebook.pages.single { it.title == "Sam Lee" }
        assertEquals("# Sam Lee\n\nWorks on <script> tags.", sam.markdown)
        assertEquals("2026-11-01T22:00:00+00:00", sam.updated)
    }

    @Test
    fun `home folders in page text are not shown`() {
        val texts = notebook.pages.joinToString("\n") { it.markdown }

        assertFalse(texts, texts.contains("/Users/jo"))
        assertFalse(texts, texts.contains("Users\\jo"))
        assertTrue(texts, texts.contains("- File: ~/code/apollo/README.md"))
        assertTrue(texts, texts.contains("- File: ~\\.claude\\skills\\deploy\\SKILL.md"))
    }

    @Test
    fun `nothing but the pages leaves the snapshot`() {
        // The notebook's folder, the run logs, mail accounts and per-page
        // working folders are all in the snapshot; none of it is kept.
        val kept = notebook.toString()
        listOf("/Users/jo/.co/rem", "token expired", "jo@example.com", "cwd").forEach { leak ->
            assertFalse("kept $leak", kept.contains(leak))
        }
    }

    @Test
    fun `a page with no title is left out rather than shown blank`() {
        assertTrue(notebook.pages.none { it.id == "broken.md" })
    }

    @Test
    fun `a page that is not the reader yields no notebook`() {
        assertNull(RemNotebook.parse("<html><body>Not found</body></html>"))
        assertNull(RemNotebook.parse("const REM = {not json};"))
    }

    // ── WIKI_RESULT to outcome: ws_router/rem.py's error strings, verbatim ──

    @Test
    fun `a successful result is the notebook`() {
        val outcome = RemOutcome.fromResult(ok = true, html = readerPage, error = null)
        assertEquals(notebook, (outcome as RemOutcome.Loaded).notebook)
    }

    @Test
    fun `the Host's refusals each have their own state`() {
        assertEquals(RemOutcome.NotOwner, RemOutcome.fromResult(false, null, "co rem is available only to the Host owner"))
        assertEquals(RemOutcome.NoNotebook, RemOutcome.fromResult(false, null, "co rem is not available on this Host"))
        assertEquals(RemOutcome.TooLarge, RemOutcome.fromResult(false, null, "co rem reader exceeds the 16 MiB limit"))
        // Seen live: the Host restarted, or its session crashed, under a socket the relay kept open.
        assertEquals(RemOutcome.SessionLost, RemOutcome.fromResult(false, null, "Authentication required"))
    }

    @Test
    fun `anything else fails without showing the Host's words`() {
        assertEquals(
            RemOutcome.Failed("Signed co rem request required"),
            RemOutcome.fromResult(false, null, "Signed co rem request required")
        )
        assertTrue(RemOutcome.fromResult(ok = true, html = "<html></html>", error = null) is RemOutcome.Failed)
    }

    // ── what a page may not show (review of MR !54) ──
    //
    // Written the way Rem writes pages: facts with relative links and source
    // citations (rem/facts.py), a skills catalog entry quoting its file
    // (rem/files.py), and notes pasted in from mail and terminals.

    private val personPage = """
        # Priya Shah

        - Company: [Harbour Analytics](../orgs/harbour-analytics.md) [1]
        - Works on: [Apollo](../projects/apollo.md)
        - Staging login: password: hunter2-staging
        - Shared the deploy key: OPENAI_API_KEY=sk-proj-4fJk29dLqP0aXz7mVb81RtYw
        - CI token ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8 lives in the repo settings
        - Dashboard: https://priya:Tr0ub4dor@grafana.harbour.example/d/abc
        - Invite code: 7Hx9-Qm2L
        - The token expired last week, she will send a new one.

        Calls the API with `Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJwcml5YSJ9.c2lnbmF0dXJlLWhlcmU`.

        ```
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        -----END OPENSSH PRIVATE KEY-----
        ```

        [1]: mail 2026-10-28, also saved to /var/lib/rem/archive/2026-10-28.eml and D:\Backups\rem\priya.md
        Notes live in /Users/jo/notes/priya.txt; the runbook is https://docs.harbour.example/runbooks/deploy.
    """.trimIndent()

    private val sanitized = RemPrivacy.sanitize(personPage)

    @Test
    fun `credentials in a real page are not shown`() {
        listOf(
            "hunter2-staging",
            "sk-proj-4fJk29dLqP0aXz7mVb81RtYw",
            "ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8",
            "Tr0ub4dor",
            "7Hx9-Qm2L",
            "eyJhbGciOiJIUzI1NiJ9",
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ"
        ).forEach { secret -> assertFalse("shown: $secret\n$sanitized", sanitized.contains(secret)) }
    }

    @Test
    fun `private paths in a real page are not shown`() {
        listOf("/var/lib/rem", "D:\\Backups", "/Users/jo").forEach { path ->
            assertFalse("shown: $path\n$sanitized", sanitized.contains(path))
        }
        assertTrue(sanitized, sanitized.contains("also saved to [path removed] and [path removed]"))
        assertTrue("a home folder still reads as one: $sanitized", sanitized.contains("~/notes/priya.txt"))
    }

    @Test
    fun `the rest of the page survives sanitising`() {
        // Links between pages, web addresses and ordinary prose stay readable.
        listOf(
            "[Harbour Analytics](../orgs/harbour-analytics.md) [1]",
            "[Apollo](../projects/apollo.md)",
            "https://docs.harbour.example/runbooks/deploy",
            "https://priya:[redacted]@grafana.harbour.example/d/abc",
            "The token expired last week, she will send a new one.",
            "OPENAI_API_KEY=[redacted]"
        ).forEach { kept -> assertTrue("lost: $kept\n$sanitized", sanitized.contains(kept)) }
    }

    @Test
    fun `parsing applies the same filter to every page and its title`() {
        val page = """{"categories":["people"],"records":[{"path":"people/priya.md","category":"people",""" +
            """"title":"Priya (token: abcd1234efgh)","text":"password: hunter2-staging"}]}"""
        val parsed = RemNotebook.parse("const REM = $page;")!!.pages.single()

        assertEquals("Priya (token: [redacted])", parsed.title)
        assertEquals("password: [redacted]", parsed.markdown)
    }

    @Test
    fun `a Bearer token on its own is not shown`() {
        // Not JWT-shaped and no `token:` before it, so only the Bearer rule can catch it.
        assertEquals("curl -H 'Authorization: Bearer [redacted]'", RemPrivacy.sanitize("curl -H 'Authorization: Bearer abc123def456ghi789'"))
    }

    @Test
    fun `an env-file secret on its own is not shown`() {
        // DB_PASSWORD has no word break before PASSWORD, so only the env-file rule can catch it.
        assertEquals("DB_PASSWORD=[redacted]", RemPrivacy.sanitize("DB_PASSWORD=hunter2"))
    }

    // ── passages Rem labels private (second review of MR !54) ──
    //
    // Rem ends a private sentence or clause with [personal] or [sensitive],
    // before its citations (rem/facts.py). O Chat starts with them hidden.

    private val labelledPage = """
        # Sam Lee

        Sam runs the Harbour data team. Recovering from knee surgery. [sensitive] [3]
        Back in the office on Monday. [2]

        - Partner: Alex, lives in Newtown [personal] [4]
        - Works on: [Apollo](../projects/apollo.md) [1]
        - Off to Lisbon for the wedding [personal] [5], back on the 14th.

        Owes the bank for the flat. [sensitive] Is going through a divorce. [sensitive] [6]

        Recovering from knee surgery. [sensitive] [3]

        ```
        echo "[personal] in code is left alone"
        ```
    """.trimIndent()

    private val hidden = RemPassages.hide(labelledPage)

    @Test
    fun `a labelled sentence is not shown, with its label and citations`() {
        listOf("knee surgery", "Alex", "Newtown", "Lisbon", "wedding", "bank", "divorce", "[3]", "[4]", "[5]", "[6]")
            .forEach { private -> assertFalse("shown: $private\n$hidden", hidden.contains(private)) }
        assertFalse(hidden, hidden.lines().any { "[sensitive]" in it || ("[personal]" in it && "echo" !in it) })
    }

    @Test
    fun `the sentences around a labelled one stay`() {
        listOf(
            "Sam runs the Harbour data team.",
            "Back in the office on Monday. [2]",
            "- Works on: [Apollo](../projects/apollo.md) [1]",
            "- back on the 14th."
        ).forEach { kept -> assertTrue("lost: $kept\n$hidden", hidden.contains(kept)) }
    }

    @Test
    fun `a line that was all private says so, and a list item that was goes`() {
        assertEquals(
            """
            # Sam Lee

            Sam runs the Harbour data team.
            Back in the office on Monday. [2]

            - Works on: [Apollo](../projects/apollo.md) [1]
            - back on the 14th.

            ${RemPassages.HIDDEN}

            ${RemPassages.HIDDEN}

            ```
            echo "[personal] in code is left alone"
            ```
            """.trimIndent(),
            hidden
        )
    }

    /**
     * Each line, and what the Host reader's own `privatePassages` (connectonion
     * 1.9.3rc30, run under Node) leaves of it, spaces collapsed. A line it
     * leaves empty shows [RemPassages.HIDDEN] here.
     */
    @Test
    fun `labelled passages are cut where the Host reader cuts them`() {
        listOf(
            // Full-width labels and citations, as Rem writes them in Chinese text.
            "Works at Harbour. Has diabetes. ［sensitive］ [2]" to "Works at Harbour.",
            "- 在Harbour工作。患有糖尿病。［sensitive］［2］" to "- 在Harbour工作。",
            "在Harbour工作；和Alex住在一起［personal］。喜欢爵士乐。" to "喜欢爵士乐。",
            // Chinese has no space after its full stop.
            "- 在Harbour工作。患有糖尿病。[sensitive]" to "- 在Harbour工作。",
            // Labels are matched whatever their case.
            "Works at Harbour. [Sensitive] Has diabetes." to "Has diabetes.",
            "Notes [Personal] mixed case" to "mixed case",
            // A dot between letters or digits does not end a sentence.
            "Runs harbour.com with 120.5k users. Recovering from surgery. [sensitive] [3]" to "Runs harbour.com with 120.5k users.",
            "Email sam@harbour.com about it. Pregnant. [personal]" to "Email sam@harbour.com about it.",
            "Weighs 72.5 kg after surgery. [sensitive]" to RemPassages.HIDDEN,
            "Sam leads data. Books her therapy on calm.com weekly. [sensitive] [4]" to "Sam leads data.",
            // Two labelled sentences in a row, with unlabelled ones either side.
            "Sam is kind. Owes the bank. [sensitive] Is divorcing. [sensitive] Likes jazz." to "Sam is kind. Likes jazz.",
            // The reader's leftmost match starts at the line, so these go whole.
            "Works at Harbour; lives with Alex [personal]." to RemPassages.HIDDEN,
            "Said \"I'm fine. Really.\" Has a heart condition. [sensitive]" to RemPassages.HIDDEN
        ).forEach { (line, kept) -> assertEquals(line, kept, RemPassages.hide(line)) }
    }

    @Test
    fun `labels inside a code block are left alone`() {
        assertTrue(hidden, hidden.contains("echo \"[personal] in code is left alone\""))
    }

    @Test
    fun `a dot inside a link does not split a labelled sentence`() {
        assertEquals(
            "Met at the offsite.",
            RemPassages.hide("Met at the offsite. Stayed with [Jo](../people/jo.md) in Bondi. [personal] [7]")
        )
    }

    @Test
    fun `a label no sentence can be cut around hides the whole line`() {
        // No sentence comes before the label. The reader shows this line whole, label and all.
        assertEquals(RemPassages.HIDDEN, RemPassages.hide("［sensitive］ Has diabetes."))
    }

    @Test
    fun `parsing hides labelled passages in the text and title`() {
        val page = """{"categories":["people"],"records":[{"path":"people/sam.md","category":"people",""" +
            """"title":"Sam's surgery [sensitive]","text":"Sam leads data. Recovering from knee surgery. [sensitive] [3]"}]}"""
        val parsed = RemNotebook.parse("const REM = $page;")!!.pages.single()

        assertEquals(RemPassages.HIDDEN_TITLE, parsed.title)
        assertEquals("Sam leads data.", parsed.markdown)
    }

    // ── what the contents list (rem/reader.html listedRecords) ──

    @Test
    fun `pages the reader leaves off its contents are unlisted but still open by link`() {
        val page = """{"categories":["people","skills","notes","logs"],"records":[""" +
            """{"path":"people/sam.md","category":"people","title":"Sam","text":"x"},""" +
            """{"path":"people/held.md","category":"people","title":"Held","text":"x","needs_review":true},""" +
            """{"path":"people/github.md","category":"people","title":"GitHub","text":"x","service":true},""" +
            """{"path":"skills/catalog/deploy.md","category":"skills","title":"deploy","text":"x"},""" +
            """{"path":"skills/catalog/deploy-2.md","category":"skills","title":"deploy","text":"x","catalog_parent":"skills/catalog/deploy.md"},""" +
            """{"path":"skills/catalog/index.md","category":"skills","title":"Catalog","text":"x"},""" +
            """{"path":"notes/reflection.md","category":"notes","title":"Reflection","text":"x"},""" +
            """{"path":"logs/run.md","category":"logs","title":"Run","text":"x"}]}"""
        val parsed = RemNotebook.parse("const REM = $page;")!!

        assertEquals(
            listOf("people" to listOf("Sam"), "skills" to listOf("deploy")),
            parsed.sections.map { (category, pages) -> category to pages.map { it.title } }
        )
        assertEquals(8, parsed.pages.size)
        assertEquals("people/held.md", parsed.pageLinkedFrom("people/sam.md", "held.md")?.id)
    }

    // ── links between pages ──

    private val linked = RemNotebook(
        listOf(
            RemPage("people/priya.md", "people", "Priya Shah", ""),
            RemPage("orgs/harbour-analytics.md", "orgs", "Harbour Analytics", ""),
            RemPage("projects/apollo.md", "projects", "Apollo", "")
        )
    )

    @Test
    fun `a relative link resolves from the page it is on`() {
        assertEquals("orgs/harbour-analytics.md", linked.pageLinkedFrom("people/priya.md", "../orgs/harbour-analytics.md")?.id)
        assertEquals("projects/apollo.md", linked.pageLinkedFrom("people/priya.md", "../projects/apollo.md#status")?.id)
        assertEquals("people/priya.md", linked.pageLinkedFrom("orgs/harbour-analytics.md", "/people/priya.md")?.id)
    }

    @Test
    fun `a link to anything but another page resolves to nothing`() {
        assertNull(linked.pageLinkedFrom("people/priya.md", "https://harbour.example/team"))
        assertNull(linked.pageLinkedFrom("people/priya.md", "../people/someone-else.md"))
        assertNull("climbs out of the notebook", linked.pageLinkedFrom("people/priya.md", "../../etc/passwd"))
        assertNull(linked.pageLinkedFrom("people/priya.md", "mailto:priya@harbour.example"))
    }

    // ── connectonion 1.9.3+: a Today shell plus the full notebook in rem-detail (W16AAPPLE-110) ──

    /**
     * A reader page in connectonion 1.9.5's shape (rem/reader.py `_page`, the WIKI_READ case:
     * not live). `const REM` is the Today shell, whose page texts keep only Facts, Contact,
     * Open threads and Insight and which leaves out sessions, notes and logs; the
     * `rem-detail` block holds what the shell does not (`_detail_data`): every record's full
     * `text`, any field that differs, and the records the shell left out.
     */
    private fun todayPage(shell: String, detail: String) = """
        <!doctype html>
        <html><body>
        <script>
        "use strict";
        const REM = $shell;
        window.REM = REM;
        </script>
        <script id="rem-detail" type="application/json">$detail</script>
        </body></html>
    """.trimIndent()

    private val todayShell = """{"categories":["people","projects","sessions","notes"],"deferred_details":true,"detail_url":"",""" +
        """"records":[{"path":"people/sam.md","category":"people","title":"Sam Lee",""" +
        """"text":"# Sam Lee\n\n## Facts\n\n- Works at Harbour [1]","updated":"2026-11-10T09:00:00+00:00"},""" +
        """{"path":"projects/apollo.md","category":"projects","title":"Apollo","text":"# Apollo\n\n## Open threads\n\n- Ship it"}]}"""

    private val todayDetail = """{"records":[""" +
        """{"path":"people/sam.md","text":"# Sam Lee\n\n## Facts\n\n- Works at Harbour [1]\n\n## Background\n\nMet at \u003cb\u003eHarbour\u003c/b\u003e in 2024."},""" +
        """{"path":"projects/apollo.md","text":"# Apollo\n\nThe whole project page.\n\n## Open threads\n\n- Ship it","needs_review":true},""" +
        """{"path":"sessions/codex-1.md","category":"sessions","title":"Codex session","text":"# Codex session\n\nWhat you typed."},""" +
        """{"path":"notes/reflection.md","category":"notes","title":"Reflection","text":"x"}]}"""

    @Test
    fun `a page in the Today shape shows each page's full text, not the shell's summary`() {
        val parsed = RemNotebook.parse(todayPage(todayShell, todayDetail))!!

        val sam = parsed.pages.single { it.id == "people/sam.md" }
        assertTrue("the section the shell cut is back", sam.markdown.contains("## Background"))
        assertTrue("escaping undone in the detail too", sam.markdown.contains("Met at <b>Harbour</b> in 2024."))
        assertEquals("fields only the shell carries are kept", "Sam Lee", sam.title)
        assertEquals("2026-11-10T09:00:00+00:00", sam.updated)
    }

    @Test
    fun `records only in the detail are added, and its fields win`() {
        val parsed = RemNotebook.parse(todayPage(todayShell, todayDetail))!!

        assertEquals(
            listOf("people" to listOf("Sam Lee"), "sessions" to listOf("Codex session")),
            parsed.sections.map { (category, pages) -> category to pages.map { it.title } }
        )
        val apollo = parsed.pages.single { it.id == "projects/apollo.md" }
        assertFalse("needs_review from the detail holds it back from the contents", apollo.listed)
        assertTrue(apollo.markdown.startsWith("# Apollo\n\nThe whole project page."))
        assertEquals("notes stay unlisted but open by link", false, parsed.pages.single { it.id == "notes/reflection.md" }.listed)
    }

    @Test
    fun `a page whose detail is empty or unreadable reads as its shell alone`() {
        listOf("", "{not json", "[]").forEach { detail ->
            val parsed = RemNotebook.parse(todayPage(todayShell, detail))!!
            assertEquals(detail, listOf("people/sam.md", "projects/apollo.md"), parsed.pages.map { it.id }.sorted())
            assertFalse(detail, parsed.pages.single { it.id == "people/sam.md" }.markdown.contains("## Background"))
        }
    }

    @Test
    fun `a detail block's category order is the one followed`() {
        // _detail_data carries `categories` only when it differs from the shell's; then it wins,
        // as loadDetails lets every detail field win.
        val detail = """{"categories":["projects","people","sessions","notes"],"records":[]}"""

        val parsed = RemNotebook.parse(todayPage(todayShell, detail))!!

        assertEquals(listOf("projects", "people"), parsed.sections.map { it.first })
    }

    @Test
    fun `a topic marked private is never shown`() {
        val shell = """{"categories":["knowledge"],"records":[""" +
            """{"path":"knowledge/health.md","category":"knowledge","title":"Health [sensitive]","text":"x","private":true},""" +
            """{"path":"knowledge/kotlin.md","category":"knowledge","title":"Kotlin","text":"x"}]}"""

        val parsed = RemNotebook.parse("const REM = $shell;")!!

        assertEquals(listOf("knowledge/kotlin.md"), parsed.pages.map { it.id })
    }

    @Test
    fun `Rem's own state, such as the run memory, is never a page`() {
        // connectonion 1.9.3rc55 keeps the native round's run memory in .state/memory.md (#2828).
        val shell = """{"categories":["notes"],"records":[""" +
            """{"path":".state/memory.md","category":"notes","title":"Run memory","text":"x"},""" +
            """{"path":"notes/.hidden/x.md","category":"notes","title":"Hidden","text":"x"},""" +
            """{"path":"notes/reflection.md","category":"notes","title":"Reflection","text":"x"}]}"""

        val parsed = RemNotebook.parse(todayPage(shell, ""))!!

        assertEquals(listOf("notes/reflection.md"), parsed.pages.map { it.id })
    }
}
