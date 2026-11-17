package ai.openonion.oochat.util

/**
 * What may never be drawn on this device's screen (W16AAPPLE-50): credentials,
 * the owner's private paths, and raw provider payloads. The app is used in
 * public and its screenshots get shared, and everything an agent sends (its
 * replies, tool arguments and output, error text) can carry a pasted key or a
 * path on the owner's machine.
 *
 * A different job from [LogSanitizer], which keeps content out of the on-disk
 * log at all. This keeps the content and masks only the parts that must not
 * be seen, so a tool card still says what the tool does: `cat ~/.env` stays
 * readable, which is what [summarizeToolCall] exists for.
 *
 * Rules run in order, most specific first, so a token inside a path is caught
 * as a token. Written for Rem first (W16AAPPLE-69, see RemPrivacy), which adds
 * [removeAbsolutePaths] on top, since a Rem page has no reason to name any
 * location on the owner's computer.
 */
object SensitiveText {
    const val REDACTED = "[redacted]"
    const val PATH_REMOVED = "[path removed]"

    /** What an error reads as when all the Host sent was a raw payload. */
    const val UNREADABLE_ERROR = "The agent reported an error. The details are in its logs on its computer."

    /** What a Work Room summary reads as when all the provider sent was a raw payload. */
    const val UNREADABLE_SUMMARY = "The details are in the agent's logs on its computer."

    private val PRIVATE_KEY_BLOCK = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----""")

    /** `https://user:secret@host` keeps the user and loses the secret. */
    private val URL_CREDENTIALS = Regex("""(\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s/:@]+:)[^\s/@]+@""")

    /** Shapes that are a credential wherever they appear. */
    private val TOKENS = listOf(
        Regex("""\bsk-(?:ant-|proj-)?[A-Za-z0-9_-]{16,}"""), // Anthropic, OpenAI
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}|\bgithub_pat_[A-Za-z0-9_]{20,}"""), // GitHub
        Regex("""\bxox[abposr]-[A-Za-z0-9-]{10,}"""), // Slack
        Regex("""\bAKIA[0-9A-Z]{16}\b"""), // AWS access key
        Regex("""\bAIza[0-9A-Za-z_-]{35}"""), // Google API key
        Regex("""\beyJ[A-Za-z0-9_-]{8,}\.eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""), // JWT
    )

    private val BEARER = Regex("""(?i)\b(Bearer)\s+[A-Za-z0-9._~+/-]{12,}=*""")

    /**
     * `password: …`, `api key = …`, and the same as a JSON or Python key
     * (`"password": "…"`). Only with `:` or `=`, so prose like "the token
     * expired" is left alone. A quoted value keeps its quotes, so JSON stays
     * JSON for the cards that parse it.
     *
     * The value stops at `(` and `[`, so one already masked (`token: [redacted]`)
     * is not masked again, and code reading a secret is told from the secret
     * itself (see [isCode]).
     */
    private val NAMED_SECRET = Regex(
        """(?i)\b((?:client[ _-]?)?secret|password|passwd|pwd|(?:access[ _-]?|refresh[ _-]?|auth[ _-]?)?token|api[ _-]?key|private[ _-]?key|invite[ _-]?code)(["']?\s*[:=]\s*)(["'`]?)([^\s"'`,;()\[\]{}<>]{4,})\3"""
    )

    /**
     * `OPENAI_API_KEY=…`, `CO_INVITE_CODE=…` as written in an env file. The
     * name is one of these words, or ends in `_` and one: `DB_PASS` is a
     * secret, `BYPASS` is not. A bare `KEY` is not either (`SORT_KEY=name`),
     * nor any `*_CODE` (`EXIT_CODE=1`).
     */
    private val ENV_SECRET = Regex(
        """\b((?:[A-Z][A-Z0-9]*_)*(?:(?:API|SECRET|SECRET_ACCESS|ACCESS|PRIVATE|AUTH|MASTER|ENCRYPTION|SIGNING|LICENSE)_?KEY|SECRET|TOKEN|PASSWORD|PASSWD|PASS|INVITE_CODE))(\s*=\s*)(["'`]?)([^\s"'`()\[\]<>]+)\3"""
    )

    /**
     * Words a value written in code can be, which no secret is: `const token =
     * await getToken()`, `password: string`, `token = None`.
     */
    private val CODE_WORDS = setOf(
        "await", "new", "null", "nil", "None", "undefined", "true", "false", "True", "False",
        "this", "self", "string", "String", "number", "boolean", "Optional", "function", "lambda"
    )

    /**
     * `process.env.TOKEN`, `self.password`, `config.api_key`: a name, not a
     * value. Only under the objects code reads settings from, since a
     * passphrase can be dotted too (`correct.horse.battery`).
     */
    private val DOTTED_NAME = Regex(
        """(?:self|this|cls|config|settings|env|args|options|opts|params|request|req|ctx|process\.env|os\.environ)(?:\.[A-Za-z_][A-Za-z0-9_]*)+"""
    )

    /**
     * What is called or indexed in `getToken()` or `request.form["…"]`: a
     * name with no digits, so `Passw0rd[1]` is still taken as a password.
     */
    private val CALLEE = Regex("""[A-Za-z_]+(?:\.[A-Za-z_]+)*""")

    /** A shell variable: `$GITHUB_TOKEN`, `${TOKEN}`. In capitals, so `$ecretPass99` is still a password. */
    private val SHELL_VARIABLE = Regex("""\$\{.*|\$[A-Z_][A-Z0-9_]*""")

    /**
     * A home folder, whose name is the owner's. `/mnt/c/Users/…` (WSL) as one,
     * and a Windows path with doubled backslashes, as JSON escapes it.
     *
     * Not inside a web address or a relative path: not after a word character,
     * `.`, `~` or `]`, nor after a `/` that follows one, so
     * `docs.example.com/home/…` and `src/home/…` stay as they are, while
     * `file:///Users/…` and `PATH=…:/home/…` are still found.
     */
    private val HOME_FOLDER = Regex(
        """(?<![\w.~\]])(?<![\w.~-]/)(?:(?:/mnt/[a-z])?(?:/Users|/home)/[^/\s"'`<>]+|[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s"'`<>]+)"""
    )

    /**
     * Any other absolute path of two segments or more. Not after a word
     * character, `:`, `/`, `.` or `~`, so a URL's path, a relative link such
     * as `../orgs/x.md` and a path already shortened to `~/…` stay as they are.
     */
    private val UNIX_PATH = Regex("""(?<![\w:/.~\]])/(?:[^\s/()\[\]<>"'`]+/)+[^\s/()\[\]<>"'`,;]*""")
    private val WINDOWS_PATH = Regex("""\b[A-Za-z]:[\\/](?:[^\s\\/()<>"'`]+[\\/])*[^\s\\/()<>"'`,;]*""")

    /**
     * What a provider's raw output looks like, as against a sentence. Each is
     * written for a log, not a person.
     */
    private val RAW_PAYLOAD = listOf(
        // An object as JSON or a Python dict writes it: `{"type": …`, `{'error': …`.
        Regex("""\{\s*["'][^"'\n]{1,64}["']\s*:"""),
        // An object with bare keys (JavaScript, YAML flow, a dict keyed by number),
        // when it is the whole message or has two fields or more: `{400: …}`,
        // `Error: {type: overloaded, status: 529}`. One field inside a sentence is
        // the sentence quoting it ("Couldn't parse the config {timeout: 30s}").
        Regex("""^\s*\{\s*\w{1,64}\s*:\s*\S"""),
        Regex("""\{\s*\w{1,64}\s*:[^{}\n]*?,\s*\w{1,64}\s*:"""),
        // An array, when it is the whole message: `[{"…`, `['a', 'b']`. Inside a
        // sentence it is the sentence's own list ("Expected one of ['fast', 'safe']").
        Regex("""^\s*\[\s*[{"']"""),
        // A Python or Kotlin object's repr: `APIStatusError(message=…`, `Message(id=…`.
        Regex("""\b[A-Z][A-Za-z0-9_]*\(\s*[a-z_]\w*\s*="""),
        // A repr with no fields: `<anthropic.types.Message object at 0x7f…>`.
        Regex("""<[\w.]+ object at 0x[0-9a-fA-F]+>"""),
        // A stack trace: Python's header, a JVM or JavaScript frame.
        Regex("""Traceback \(most recent call last\)"""),
        Regex("""(?m)^\s+at [\w$.<>]+\s*\("""),
        // A gateway's error page.
        Regex("""(?i)<!doctype html|<html[\s>]"""),
    )

    /** The few of those that mark a message as a payload when it begins with one: a snippet quoted in prose does not. */
    private val WHOLE_RAW_PAYLOAD = listOf(
        Regex("""^\s*\{\s*["']?\w{1,64}["']?\s*:"""),
        Regex("""^\s*\[\s*[{"']"""),
        Regex("""^\s*Traceback \(most recent call last\)"""),
        Regex("""(?i)^\s*(<!doctype html|<html[\s>])""")
    )

    /**
     * [text] with every credential masked and home folders shortened to `~`.
     * Other paths are kept: in a tool card or an approval they are what the
     * user is deciding on (W16AAPPLE-36), as O Chat shows them, and are not
     * the owner's once the home folder is gone.
     *
     * For where the user acts on what they read: the transcript, tool and
     * approval cards, diff previews, the Control Center feed. Anywhere else
     * takes [redactWithoutPaths].
     */
    fun redact(text: String): String {
        var out = text.replace(PRIVATE_KEY_BLOCK, "[private key removed]")
        out = out.replace(URL_CREDENTIALS) { "${it.groupValues[1]}$REDACTED@" }
        TOKENS.forEach { out = out.replace(it, REDACTED) }
        out = out.replace(BEARER) { "${it.groupValues[1]} $REDACTED" }
        out = maskValues(out, NAMED_SECRET)
        out = maskValues(out, ENV_SECRET)
        // Home folders read better as `~`.
        return out.replace(HOME_FOLDER, "~")
    }

    /** Each match of [rule] (name, separator, quote, value) with its value masked, unless it is code. */
    private fun maskValues(text: String, rule: Regex): String = text.replace(rule) { m ->
        val (name, separator, quote, value) = m.destructured
        val next = text.getOrNull(m.range.last + 1)
        if (quote.isEmpty() && isCode(value, next)) m.value else "$name$separator$quote$REDACTED$quote"
    }

    /**
     * Whether an unquoted [value] after `token =` or `password:` is code rather
     * than a secret: a call or an index (`getToken()`, `request.form["…"]`,
     * where [next] is the `(` or `[`, see [CALLEE]), a shell variable or
     * command (`$TOKEN`, `$(cat key)`), a setting read from an object (see
     * [DOTTED_NAME]), or one of [CODE_WORDS]. A coding agent's replies are
     * full of such lines, and masking them would garble the code the user is
     * reading. Each check is narrow, so a password that only looks like code
     * is masked: over-masking is the safer mistake.
     */
    private fun isCode(value: String, next: Char?): Boolean =
        ((next == '(' || next == '[') && CALLEE.matches(value)) ||
            (value == "$" && next == '(') ||
            SHELL_VARIABLE.matches(value) ||
            value in CODE_WORDS ||
            DOTTED_NAME.matches(value)

    /**
     * [redact], then every absolute path still left becomes [PATH_REMOVED].
     *
     * For text nobody decides anything from, which bystanders see or which
     * gets pasted elsewhere: the lock-screen notification, error text, the
     * Logs and Recovery screens, conversation titles, and Rem (W16AAPPLE-69),
     * whose acceptance criterion this rule was first written for.
     */
    fun redactWithoutPaths(text: String): String = removeAbsolutePaths(redact(text))

    private fun removeAbsolutePaths(text: String): String =
        text.replace(UNIX_PATH, PATH_REMOVED).replace(WINDOWS_PATH, PATH_REMOVED)

    /**
     * An error worth showing, from what the Host or provider sent. A raw
     * payload (a JSON or Python object, a stack trace) is written for a log,
     * not a person, and can hold anything the provider echoed back, so it is
     * replaced whole. Anything else goes through [redactWithoutPaths]: an
     * error asks for no decision, so a path in it is only a detail to leak.
     */
    fun errorText(raw: String): String =
        if (isRawPayload(raw)) UNREADABLE_ERROR else redactWithoutPaths(raw)

    /**
     * A Work Room run's progress or result line, from what the provider sent.
     * Meant as a sentence; a raw payload in its place (see [isRawPayload]) is
     * replaced whole, as in [errorText], but by words that do not call it an
     * error, since the run may well have succeeded. Otherwise only [redact]:
     * the summary sits with the run's steps, which keep their paths.
     */
    fun summaryText(raw: String): String =
        if (isRawPayload(raw)) UNREADABLE_SUMMARY else redact(raw)

    /**
     * A line of the conversation inside a Work Room (W16AAPPLE-34): prose and code the
     * user is meant to read, so a snippet in it keeps its braces and its calls and is
     * only [redact]ed. Replaced whole, as in [summaryText], only when the message itself
     * is a raw payload, that is when it begins with one (see [isWholeRawPayload]).
     */
    fun messageText(raw: String): String =
        if (isWholeRawPayload(raw)) UNREADABLE_SUMMARY else redact(raw)

    /** Whether [text] is a provider's object, array, stack trace or error page from its first character, not one quoted in a sentence. */
    private fun isWholeRawPayload(text: String): Boolean = WHOLE_RAW_PAYLOAD.any { it.containsMatchIn(text) }

    /** Whether [text] carries a provider's object, a stack trace or an error page rather than a sentence. */
    fun isRawPayload(text: String): Boolean = RAW_PAYLOAD.any { it.containsMatchIn(text) }
}
