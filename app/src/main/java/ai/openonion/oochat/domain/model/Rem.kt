package ai.openonion.oochat.domain.model

import ai.openonion.oochat.util.SensitiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The agent owner's Rem notebook (W16AAPPLE-69): Markdown pages about the
 * owner's people, projects and tools. Rem was called Wiki until connectonion
 * 1.9.0; the wire frames still say `WIKI_READ` / `WIKI_RESULT`.
 *
 * Only what the app shows is kept. The Host sends its whole reader page, whose
 * embedded snapshot also carries the notebook's folder on the owner's machine,
 * source files, run logs and mail metadata. None of that leaves [parse].
 */
data class RemNotebook(val pages: List<RemPage>) {

    /**
     * The contents: the [RemPage.listed] pages under each category, in the
     * Host's category order. Empty categories are left out. Unlisted pages
     * stay in [pages], so a link can still open them.
     */
    val sections: List<Pair<String, List<RemPage>>>
        get() = pages.filter { it.listed }.groupBy { it.category }.toList()

    /**
     * The page a link on [fromPageId] points at, or null for anything that is
     * not another page of this notebook. Rem links pages relative to the page
     * they are on (`[Harbour](../orgs/harbour.md)` from `people/sam.md`), as
     * rem/page_review.py and rem/files.py write them.
     */
    fun pageLinkedFrom(fromPageId: String, target: String): RemPage? {
        val path = target.substringBefore('#').substringBefore('?').trim()
        if (path.isEmpty() || path.contains("://") || path.startsWith("mailto:")) return null
        val segments = ArrayDeque<String>()
        val base = if (path.startsWith("/")) "" else fromPageId.substringBeforeLast('/', "")
        "$base/$path".split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> segments.removeLastOrNull() ?: return null
                else -> segments.addLast(segment)
            }
        }
        val id = segments.joinToString("/")
        return pages.firstOrNull { it.id == id }
    }

    companion object {
        private const val DATA_PREFIX = "const REM = "

        /**
         * The notebook inside the Host's reader page, or null when the page
         * holds none (an unknown template). rem/reader.py `render` puts the
         * snapshot on one line, `const REM = {…};`, with `<` escaped, so
         * the line is the whole value.
         */
        fun parse(html: String): RemNotebook? {
            val line = html.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(DATA_PREFIX) } ?: return null
            val snapshot = runCatching {
                Json.parseToJsonElement(line.removePrefix(DATA_PREFIX).removeSuffix(";"))
            }.getOrNull() as? JsonObject ?: return null
            // From connectonion 1.9.3, `const REM` is only the reader's "Today" shell: page
            // texts cut to a few sections, and no sessions, notes or logs. The full notebook
            // comes in the page's rem-detail block (W16AAPPLE-110). An older page has none.
            val detail = detailBlock(html)

            val order = ((detail?.get("categories") ?: snapshot["categories"]) as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content }
            val pages = mergedRecords(snapshot, detail).mapNotNull { it.toPage() }
            return RemNotebook(
                pages.sortedWith(
                    compareBy<RemPage> { order.indexOf(it.category).let { i -> if (i < 0) order.size else i } }
                        .thenBy { it.title.lowercase() }
                )
            )
        }

        private const val DETAIL_OPEN = """<script id="rem-detail" type="application/json">"""

        /**
         * The page's full notebook (rem/reader.py `_detail_data`), or null when it has none: a
         * page from before connectonion 1.9.3, or one whose block is empty or unreadable, which
         * then reads as its shell alone. Inert JSON, with `<` escaped like the shell's, so the
         * first `</script>` after it is its end.
         */
        private fun detailBlock(html: String): JsonObject? {
            val start = html.indexOf(DETAIL_OPEN).takeIf { it >= 0 }?.plus(DETAIL_OPEN.length) ?: return null
            val end = html.indexOf("</script>", start).takeIf { it >= 0 } ?: return null
            val body = html.substring(start, end).trim().takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        }

        /**
         * The shell's records with the detail laid over them, as rem/reader.html `loadDetails`
         * merges them: a detail record replaces the fields it carries on the shell record with
         * its path (always its full `text`), and one the shell does not have is added.
         */
        private fun mergedRecords(shell: JsonObject, detail: JsonObject?): List<JsonObject> {
            val records = LinkedHashMap<String, JsonObject>()
            val unkeyed = mutableListOf<JsonObject>()
            fun add(record: JsonObject) {
                val path = record.string("path") ?: return run { unkeyed += record }
                records[path] = records[path]?.let { JsonObject(it + record) } ?: record
            }
            (shell["records"] as? JsonArray).orEmpty().forEach { (it as? JsonObject)?.let(::add) }
            (detail?.get("records") as? JsonArray).orEmpty().forEach { (it as? JsonObject)?.let(::add) }
            return records.values.toList() + unkeyed
        }

        /** Categories the reader's contents leave off: the AI's own notes and run logs. */
        private val UNLISTED_CATEGORIES = setOf("notes", "logs")
        private const val CATALOG_INDEX = "skills/catalog/index.md"

        private fun JsonObject.toPage(): RemPage? {
            val text = string("text") ?: return null
            val category = string("category")?.takeIf { it.isNotBlank() } ?: return null
            val id = string("path") ?: return null
            // A topic Rem labels [personal] or [sensitive] is marked `private`; the Host leaves
            // it out of any page but its owner's local one, and the app never shows one either.
            if (flag("private")) return null
            // Rem's own state (run memory, the index) lives in hidden folders such as .state,
            // never in a category; a record there is not a page of the notebook (W16AAPPLE-110).
            if (id.split('/').any { it.startsWith(".") }) return null
            return RemPage(
                id = id,
                category = category,
                title = string("title")?.takeIf { it.isNotBlank() }
                    ?.let { RemPrivacy.sanitize(RemPassages.hideInTitle(it)) } ?: return null,
                markdown = RemPrivacy.sanitize(RemPassages.hide(text)),
                updated = string("updated"),
                // rem/reader.html `listedRecords`: held for review, services
                // still on a people page, duplicate catalog entries and the
                // catalog's own index are reachable by link but not listed.
                listed = category !in UNLISTED_CATEGORIES &&
                    !flag("needs_review") && !flag("service") &&
                    string("catalog_parent") == null && id != CATALOG_INDEX
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.flag(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content == "true"
    }
}

/**
 * What a Rem page may not show on a phone (W16AAPPLE-69: no credentials or
 * private paths). Pages are written from the owner's mail, chats and files,
 * so a pasted key or a path on their machine can end up in one. The app-wide
 * rules ([SensitiveText], W16AAPPLE-50), plus no absolute path at all: a page
 * has no reason to name a location on the owner's computer.
 */
internal object RemPrivacy {
    const val REDACTED = SensitiveText.REDACTED
    const val PATH_REMOVED = SensitiveText.PATH_REMOVED

    fun sanitize(text: String): String = SensitiveText.redactWithoutPaths(text)
}

/**
 * Rem's own privacy labels. Rem ends a private sentence or clause with
 * `[personal]` (family, home, trips) or `[sensitive]` (health, private money,
 * legal, ID numbers), before its citations: "Recovering from knee surgery.
 * [sensitive] [3]". O Chat starts with these passages hidden, and this app
 * has no way to show them, so they are dropped here, sentence by sentence,
 * the way the Host reader's hidden view drops its `.private` spans
 * (rem/reader.html `privatePassages`, connectonion 1.9.3rc30).
 */
internal object RemPassages {
    const val HIDDEN = "*Hidden labelled passage*"
    const val HIDDEN_TITLE = "Untitled page"

    // Either writing system's brackets: Rem labels Chinese text ［sensitive］［2］.
    private const val LABEL = """[\[［](?:personal|sensitive)[\]］]"""
    private const val MARK = """[\[［](?:personal|sensitive|W?\d+)[\]］]"""
    private const val STOP = """[.!?;:,。！？；：，]"""
    private val ANY_LABEL = Regex(LABEL, RegexOption.IGNORE_CASE)

    /**
     * reader.html `privatePassages` (connectonion 1.9.3rc30): from a line
     * start or a clause end (and any labels or citations after it) to the
     * label, with the labels, citations and stop that follow. Chinese needs
     * no space after its full stop. The reader's boundary is a look-behind,
     * which the JVM cannot size, so here it is group 1, put back by the
     * replacement. A labelled sentence's stop is the next one's boundary, so
     * [hideInline] repeats until nothing more matches.
     */
    private val LABELLED = Regex(
        """(^\s*|[.!?;:,。！？；：，](?:\s*$MARK)*\s*)(?!\s|$MARK)[^\n.!?。！？]*?$STOP?\s*$LABEL(?:\s*$MARK)*$STOP?""",
        RegexOption.IGNORE_CASE
    )

    // What the reader sets aside first, so its stops do not end a sentence:
    // code and links whole, the stops inside quotes, HTML entities, and a dot
    // between letters or digits (harbour.com, 120.5k).
    private val CODE_OR_LINK = Regex("""`[^`\n]*`|\[[^\]\n]+]\((?:[^()\s]|\([^()\s]*\))+\)""")
    private val QUOTED = Regex("“[^”\\n]*”|「[^」\\n]*」|\"[^\"\\n]*\"")
    private val STOP_CHAR = Regex(STOP)
    private val ENTITY = Regex("""&(?:#\d+|#x[\da-fA-F]+|\w+);""")
    private val INNER_DOT = Regex("""(?<=[\p{L}\p{N}])\.(?=[\p{L}\p{N}])""")
    private val HELD = Regex("""\u0005(\d+)\u0005""")

    /** A clause's leftover comma: "Lives with Alex [personal] , works at Harbour." */
    private val DANGLING = Regex("""(^|[.!?。！？]\s*)\s*[,;，；]\s*""")

    private val MARKER = Regex("""^\s*(?:[-*+]\s+|\d+[.)]\s+|#{1,6}\s+|>\s*)?""")
    private val LIST_ITEM = Regex("""^\s*(?:[-*+]|\d+[.)])\s+$""")

    fun hide(markdown: String): String {
        if (!ANY_LABEL.containsMatchIn(markdown)) return markdown
        var fenced = false
        return markdown.lines().mapNotNull { line ->
            if (line.trimStart().startsWith("```")) fenced = !fenced
            if (fenced || line.trimStart().startsWith("```") || !ANY_LABEL.containsMatchIn(line)) return@mapNotNull line
            val marker = MARKER.find(line)?.value.orEmpty()
            val kept = hideInline(line.substring(marker.length))
            when {
                kept != null && kept.any { it.isLetterOrDigit() } -> marker + kept
                // A list item that was all private goes, as the reader hides its `li.private-item`.
                LIST_ITEM.matches(marker) -> null
                else -> marker + HIDDEN
            }
        }.joinToString("\n")
    }

    fun hideInTitle(title: String): String {
        if (!ANY_LABEL.containsMatchIn(title)) return title
        return hideInline(title)?.takeIf { kept -> kept.any { it.isLetterOrDigit() } } ?: HIDDEN_TITLE
    }

    /** [text] without its labelled passages, or null when a label is left that no sentence could be cut around. */
    private fun hideInline(text: String): String? {
        val held = mutableListOf<String>()
        val hold = { part: String -> held += part; "\u0005${held.size - 1}\u0005" }
        var out = CODE_OR_LINK.replace(text) { hold(it.value) }
        out = QUOTED.replace(out) { quote -> STOP_CHAR.replace(quote.value) { hold(it.value) } }
        out = ENTITY.replace(out) { hold(it.value) }
        out = INNER_DOT.replace(out) { hold(it.value) }
        while (true) {
            val cut = LABELLED.replace(out) { it.groupValues[1] }
            if (cut == out) break
            out = cut
        }
        out = DANGLING.replace(out) { it.groupValues[1] }
        out = out.replace(Regex("""[ \t]{2,}"""), " ").trim()
        // Fail closed: a label still standing (outside code) means its passage was not found.
        if (ANY_LABEL.containsMatchIn(out)) return null
        return HELD.replace(out) { held[it.groupValues[1].toInt()] }
    }
}

/**
 * One notebook page. [id] is its notebook-relative path (`people/jo.md`),
 * used only to tell pages apart; it is never shown. [listed] is false for a
 * page the contents leave off, which is still opened by a link to it.
 */
data class RemPage(
    val id: String,
    val category: String,
    val title: String,
    val markdown: String,
    val updated: String? = null,
    val listed: Boolean = true
)

/** Every way a Rem read can end, each with its own message and next step on screen. */
sealed interface RemOutcome {
    data class Loaded(val notebook: RemNotebook) : RemOutcome

    /** No live, admitted connection to send the request on. */
    data object NotConnected : RemOutcome

    /** The Host only shows Rem to its owner (an admin in its trust list). */
    data object NotOwner : RemOutcome

    /** This Host has no Rem root configured. */
    data object NoNotebook : RemOutcome

    /** Over the Host's 16 MiB limit. */
    data object TooLarge : RemOutcome

    /**
     * The Host has no authenticated session for this socket any more: it
     * restarted, or the session crashed, while the relay kept the app's socket
     * open. Only a fresh CONNECT fixes it, so reading again cannot.
     * AgentConnection.readRem reconnects and reads once more by itself
     * (W16AAPPLE-108), so this is only seen when that read was refused too.
     */
    data object SessionLost : RemOutcome

    /** The Host does not know `WIKI_READ` at all. */
    data object HostTooOld : RemOutcome

    /** Nothing came back in time. */
    data object NoAnswer : RemOutcome

    /** Anything else. [reason] is for the log, never the screen. */
    data class Failed(val reason: String) : RemOutcome

    companion object {
        /**
         * A `WIKI_RESULT` as an outcome. The error strings are ws_router/rem.py
         * `handle_rem_read`'s, matched loosely so a reworded one still lands in
         * the right state rather than the generic one where it can.
         */
        fun fromResult(ok: Boolean, html: String?, error: String?): RemOutcome {
            if (ok) {
                val notebook = html?.let(RemNotebook::parse) ?: return Failed("WIKI_RESULT held no notebook")
                return Loaded(notebook)
            }
            val reason = error.orEmpty()
            return when {
                reason.contains("only to the Host owner", ignoreCase = true) -> NotOwner
                reason.contains("not available on this Host", ignoreCase = true) -> NoNotebook
                reason.contains("exceeds", ignoreCase = true) -> TooLarge
                reason.contains("Authentication required", ignoreCase = true) -> SessionLost
                else -> Failed(reason.ifBlank { "WIKI_RESULT refused with no reason" })
            }
        }
    }
}
