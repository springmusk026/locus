package dev.locus.core.data.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.data.R
import dev.locus.core.data.agent.OpenChatLink
import dev.locus.core.data.di.ApplicationScope
import dev.locus.core.data.repository.ConversationRepository
import dev.locus.core.model.Conversation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One chat as a launcher and Direct Share shortcut. */
data class ChatShortcut(val id: String, val conversationId: String, val title: String, val pinned: Boolean, val rank: Int)

/**
 * Which chats become shortcuts (docs/design/android-bridge.md): pinned chats first, then the most
 * recent, at most [max]; never helpers, hidden chats or scheduled runs. Pure, so it is tested on the JVM.
 */
object ChatShortcutPlan {
    /** Most launchers show four shortcuts in an app's menu, and the static ones take three. */
    const val DEFAULT_MAX = 4

    const val PREFIX = "chat:"

    /** The share-target category in res/xml/shortcuts.xml (app). */
    const val SHARE_CATEGORY = "dev.locus.category.SHARE_TO_CHAT"

    fun idOf(conversationId: String) = PREFIX + conversationId

    /** The chat a shortcut id (from a share's EXTRA_SHORTCUT_ID) names, or null for any other id. */
    fun chatOf(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }

    fun plan(chats: List<Conversation>, max: Int = DEFAULT_MAX): List<ChatShortcut> {
        if (max <= 0) return emptyList()
        val eligible = chats.filter { c -> !c.hidden && c.parentConversationId == null && !c.title.startsWith("Scheduled: ") }
        val (pinned, rest) = eligible.partition { it.pinned }
        return (pinned.sortedByDescending { it.updatedAt } + rest.sortedByDescending { it.updatedAt })
            .take(max)
            .mapIndexed { rank, c -> ChatShortcut(idOf(c.id), c.id, c.title.ifBlank { "Chat" }.take(MAX_LABEL), c.pinned, rank) }
    }

    /** Ids published before and no longer in [now]: removed, long-lived copies included, so a deleted chat leaves no shortcut. */
    fun removed(before: Set<String>, now: List<ChatShortcut>): Set<String> = before.filter { it.startsWith(PREFIX) }.toSet() - now.map { it.id }.toSet()

    private const val MAX_LABEL = 40
}

/**
 * Keeps the launcher's chat shortcuts in step with the chat list: dynamic shortcuts for pinned and
 * recent chats, each also a Direct Share target (sharing to one lands in that chat), long-lived so the
 * share sheet can rank them. Started once from the app's startup.
 */
@Singleton
class ChatShortcuts @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val conversations: ConversationRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    @Volatile private var published: Set<String> = emptySet()

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun start() {
        scope.launch {
            val max = runCatching { ShortcutManagerCompat.getMaxShortcutCountPerActivity(context) }.getOrDefault(ChatShortcutPlan.DEFAULT_MAX)
                .let { (it - STATIC_COUNT).coerceIn(0, ChatShortcutPlan.DEFAULT_MAX) }
            conversations.all
                .map { ChatShortcutPlan.plan(it, max) }
                .distinctUntilChanged()
                .debounce(1_000)
                .collect { plan ->
                    try {
                        publish(plan)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Rate-limited or refused by the launcher: the next change tries again.
                    }
                }
        }
    }

    private fun publish(plan: List<ChatShortcut>) {
        val gone = ChatShortcutPlan.removed(published + ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }, plan)
        if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone.toList())
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val shortcuts = plan.map { s ->
            val intent = OpenChatLink.attach(context, Intent(launch).setAction(Intent.ACTION_VIEW).setFlags(0), s.conversationId)
            ShortcutInfoCompat.Builder(context, s.id)
                .setShortLabel(s.title)
                .setLongLabel(s.title)
                .setIcon(IconCompat.createWithResource(context, if (s.pinned) R.drawable.ic_shortcut_pinned_chat else R.drawable.ic_shortcut_chat))
                .setIntent(intent)
                .setRank(s.rank)
                .setLongLived(true)
                .setCategories(setOf(ChatShortcutPlan.SHARE_CATEGORY))
                .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        published = plan.map { it.id }.toSet()
    }

    private companion object {
        /** New chat, Voice and Workspace (res/xml/shortcuts.xml). */
        const val STATIC_COUNT = 3
    }
}
