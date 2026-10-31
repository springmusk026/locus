package dev.locus

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Turns something shared to Locus from another app (text, a link, files) into a new chat with a
 * prefilled draft. Files are copied into the workspace's `inbox/` folder. Nothing is sent until the
 * user taps Send.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val workspace: WorkspaceRepository,
) : ViewModel() {

    fun handle(intent: Intent, open: (conversationId: String, draft: String) -> Unit) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n").trim()
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        viewModelScope.launch {
            val files = uris.mapNotNull { runCatching { workspace.import(it, "inbox") }.getOrNull() }
            val draft = buildString {
                if (text.isNotEmpty()) append(text)
                if (files.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Shared files (in the workspace): ").append(files.joinToString())
                }
            }
            if (draft.isBlank()) return@launch
            open(conversations.create(settings.current().defaultProviderId).id, draft)
        }
    }
}
