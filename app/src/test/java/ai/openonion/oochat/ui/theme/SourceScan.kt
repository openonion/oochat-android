package ai.openonion.oochat.ui.theme

import java.io.File

/**
 * One call found in the app's sources: where it is, everything between its parentheses ([text]), and
 * the lambda that follows it, if any ([block]), which is where a composable's content is.
 */
internal data class SourceCall(val where: String, val text: String, val block: String = "")

/** Reads `src/main/java` so a test can hold a rule over every screen, not just the ones it renders. */
internal object SourceScan {

    private val module: File = listOf(".", "app").map(::File).first { File(it, "src/main").isDirectory }

    val sources = File(module, "src/main/java")

    fun kotlinFiles(): Sequence<File> =
        sources.walkTopDown().filter { it.isFile && it.extension == "kt" }

    /** Every call of [name] outside `ui/theme`, with its whole argument list (nested parentheses included) and lambda. */
    fun calls(name: Regex, includeTheme: Boolean = false): List<SourceCall> =
        kotlinFiles()
            .filter { includeTheme || "/ui/theme/" !in it.invariantSeparatorsPath }
            .flatMap { file ->
                val text = file.readText()
                name.findAll(text).mapNotNull { match ->
                    val open = match.range.last
                    if (text.getOrNull(open) != '(') return@mapNotNull null
                    var depth = 1
                    var i = open + 1
                    while (depth > 0 && i < text.length) {
                        when (text[i]) {
                            '(' -> depth++
                            ')' -> depth--
                        }
                        i++
                    }
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    var start = i
                    while (start < text.length && text[start].isWhitespace()) start++
                    var end = start
                    if (text.getOrNull(start) == '{') {
                        var braces = 1
                        end = start + 1
                        while (braces > 0 && end < text.length) {
                            when (text[end]) {
                                '{' -> braces++
                                '}' -> braces--
                            }
                            end++
                        }
                    }
                    SourceCall("${file.name}:$line", text.substring(match.range.first, i), text.substring(start, end))
                }
            }
            .toList()
}
