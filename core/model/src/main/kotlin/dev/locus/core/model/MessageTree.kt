package dev.locus.core.model

/** A message's place in its chat's tree: [parentId] is the message it follows, null for the first. */
data class TreeNode(val id: String, val parentId: String?)

/** Where a message on the shown path sits among its alternatives (messages with the same parent). */
data class Branch(val index: Int, val siblings: List<String>) {
    val count: Int get() = siblings.size
    val previous: String? get() = siblings.getOrNull(index - 1)
    val next: String? get() = siblings.getOrNull(index + 1)
}

/**
 * The branch of a chat that is shown and continued: [messages] is a plain linear transcript (what the
 * model is sent), and [branches] says, for each message on it that has alternatives, which one it is.
 */
data class ChatThread(val messages: List<Message>, val branches: Map<String, Branch> = emptyMap())

/**
 * A chat's messages as a tree. Regenerating or editing adds a sibling instead of changing history,
 * so a chat is a tree and what's shown is one path from the first message to a leaf.
 *
 * [nodes] must be in the order they were written. A parent that isn't among them makes its child a
 * root, so a damaged row can never hide the rest of the chat.
 */
class MessageTree(nodes: List<TreeNode>) {
    private val order: Map<String, Int> = nodes.withIndex().associate { (i, n) -> n.id to i }
    private val ids: List<String> = nodes.map { it.id }
    private val parent: Map<String, String?> = nodes.associate { n -> n.id to n.parentId?.takeIf { it in order && it != n.id } }
    private val children: Map<String?, List<String>> = ids.groupBy { parent[it] }

    operator fun contains(id: String) = id in order

    fun parentOf(id: String): String? = parent[id]

    /** The shown path's last message: [preferred] when it's in this chat, else the newest message. */
    fun leaf(preferred: String?): String? = preferred?.takeIf { it in order } ?: ids.lastOrNull()

    /** Ids from the first message down to [leaf]; empty when [leaf] is null or unknown. */
    fun path(leaf: String?): List<String> {
        if (leaf == null || leaf !in order) return emptyList()
        val seen = LinkedHashSet<String>()
        var at: String? = leaf
        while (at != null && seen.add(at)) at = parent[at]
        return seen.toList().asReversed()
    }

    /** [id] and its alternatives (same parent), oldest first. */
    fun siblings(id: String): List<String> = if (id in order) children[parent[id]].orEmpty() else emptyList()

    /** For each message on [path] that has alternatives, which one it is. */
    fun branches(path: List<String>): Map<String, Branch> = path.mapNotNull { id ->
        val siblings = siblings(id)
        if (siblings.size < 2) null else id to Branch(siblings.indexOf(id), siblings)
    }.toMap()

    /**
     * Where switching to [id] lands: the newest message in its subtree (itself included), so going back
     * to a branch returns to where that branch was last continued.
     */
    fun newestUnder(id: String): String {
        var newest = id
        val queue = ArrayDeque(listOf(id))
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (!seen.add(at)) continue
            if ((order[at] ?: -1) > (order[newest] ?: -1)) newest = at
            queue.addAll(children[at].orEmpty())
        }
        return newest
    }

    companion object {
        /**
         * Copies of [path] up to [upTo] (included when [inclusive]) for a new chat [conversationId], with
         * new ids. Content, timestamps, usage and provider payloads stay as they were. Empty when [upTo]
         * isn't on the path.
         */
        fun forkCopies(path: List<Message>, upTo: String, inclusive: Boolean, conversationId: String, newId: () -> String): List<Message> {
            val at = path.indexOfFirst { it.id == upTo }
            if (at < 0) return emptyList()
            return path.take(if (inclusive) at + 1 else at).map { it.copy(id = newId(), conversationId = conversationId) }
        }
    }
}
