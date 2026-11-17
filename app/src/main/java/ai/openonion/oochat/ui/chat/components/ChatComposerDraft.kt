package ai.openonion.oochat.ui.chat.components

/** One staged file in the chat composer. */
data class ChatComposerFile(
    val uri: String,
    val name: String
)

/** Chat text and attachments that should survive leaving the chat screen. */
data class ChatComposerDraft(
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val files: List<ChatComposerFile> = emptyList()
)
