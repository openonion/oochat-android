package ai.openonion.oochat.domain.model

/**
 * A skill the connected agent publishes — the candidate set for the
 * composer's `/` palette, which shows [description] under the name. It comes
 * from the relay's directory, or from an `AGENT_PROFILE` frame; both go
 * through [cleaned], so the palette sees the same shape from either.
 *
 * [description] is null when the agent has nothing useful to say about the
 * skill: absent, empty, or the `"No description"` placeholder the host emits
 * for a skill whose file carries no summary. All three normalize to null at
 * the boundary so the UI has one case to render, not four.
 */
data class AgentSkill(
    val name: String,
    val description: String? = null
) {
    companion object {
        /** What the host emits for a skill whose file has no summary. */
        private const val NO_DESCRIPTION_PLACEHOLDER = "No description"

        /**
         * Raw skills, from whichever source, as the palette's candidates.
         * Nameless entries are dropped: there is no command to complete
         * without one. A description that says nothing becomes null.
         *
         * One entry per name, the first winning: the palette keys its list on
         * the name, and a `LazyColumn` throws on a repeated key. Nothing on
         * the wire or in the directory promises names are unique, so an agent
         * that registers the same skill twice would otherwise reach the UI
         * with two (W16AAPPLE-65).
         */
        fun <T> cleaned(
            raw: List<T>,
            name: (T) -> String,
            description: (T) -> String?
        ): List<AgentSkill> =
            raw.filter { name(it).isNotBlank() }
                .distinctBy(name)
                .map { skill ->
                    AgentSkill(
                        name = name(skill),
                        description = description(skill)?.trim()
                            ?.takeIf { it.isNotEmpty() && !it.equals(NO_DESCRIPTION_PLACEHOLDER, ignoreCase = true) }
                    )
                }
    }
}
