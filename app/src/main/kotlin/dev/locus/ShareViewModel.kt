package dev.locus

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import dev.locus.core.data.shortcuts.ChatShortcutPlan
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Turns something shared to Locus from another app (text, a link, files) into a new chat with a
 * prefilled draft. Files are copied into the workspace's `inbox/` folder, under the rules in
 * SharedImport; a file that can't be imported is named in [failures] instead of dropped. Nothing is
 * sent until the user taps Send.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val workspace: WorkspaceRepository,
) : ViewModel() {

    private val _failures = MutableStateFlow<List<String>>(emptyList())

    /** "name: reason" for each shared file that wasn't imported, until dismissed. */
    val failures: StateFlow<List<String>> = _failures.asStateFlow()

    private companion object {
        /** Selected text beyond this is cut; a selection is a quote, not a document. */
        const val MAX_SELECTED_CHARS = 20_000
    }

    /** A new, empty chat (the New chat shortcut). */
    fun newChat(open: (conversationId: String) -> Unit) {
        viewModelScope.launch { open(conversations.create(settings.current().defaultProviderId).id) }
    }

    /** Tells the launcher a static shortcut was used, so it ranks it (ShortcutManagerCompat.reportShortcutUsed). */
    fun reportShortcutUsed(action: String?) {
        val id = when (action) {
            LaunchActions.NEW_CHAT -> "new_chat"
            LaunchActions.VOICE -> "voice"
            LaunchActions.WORKSPACE -> "workspace"
            else -> return
        }
        runCatching { ShortcutManagerCompat.reportShortcutUsed(context, id) }
    }

    fun dismissFailures() {
        _failures.value = emptyList()
    }

    fun handle(intent: Intent, open: (conversationId: String, draft: String) -> Unit) {
        // "Ask Locus" in another app's text selection menu: the selected text as a new chat's draft.
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            val selected = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim()?.take(MAX_SELECTED_CHARS)
            if (!selected.isNullOrEmpty()) viewModelScope.launch { open(conversations.create(settings.current().defaultProviderId).id, selected) }
            return
        }
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
        // A Direct Share target: the chat the user shared to (dynamic shortcuts, docs/design/android-bridge.md).
        val sharedTo = ChatShortcutPlan.chatOf(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT)).joinToString("\n").trim()
        val uris: List<Uri> = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        viewModelScope.launch {
            val files = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (uri in uris) {
                try {
                    files += workspace.importShared(uri, "inbox")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += "${uri.lastPathSegment ?: "A file"}: ${e.message ?: "it couldn't be read"}"
                }
            }
            if (failed.isNotEmpty()) _failures.value = failed
            val draft = buildString {
                if (text.isNotEmpty()) append(text)
                if (files.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Shared files (in the workspace): ").append(files.joinToString())
                }
            }
            if (draft.isBlank()) return@launch
            // Into that chat when it still exists (the draft waits for Send there too), else a new one.
            val target = sharedTo?.let { conversations.get(it) }?.takeIf { !it.hidden }?.id
            open(target ?: conversations.create(settings.current().defaultProviderId).id, draft)
        }
    }
}
