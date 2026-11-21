package dev.locus.core.data.chatimport

import dev.locus.core.model.Role

/**
 * A message of an export before it's placed: [parentId] is its parent in the export (which may be a
 * hidden message), and [shown] says whether Locus shows it at all.
 */
internal data class RawNode(
    val id: String,
    val parentId: String?,
    val shown: Boolean,
    val role: Role = Role.USER,
    val text: String = "",
    val createdAt: Long? = null,
    val notImported: List<String> = emptyList(),
    val imagesNotImported: Int = 0,
)

/**
 * Turns an export's messages (ChatGPT's mapping, or Claude's list) into the tree Locus stores:
 *
 * - hidden messages are skipped and their children hang off the nearest shown message above them;
 * - a reply split by hidden tool steps (assistant, tool, assistant) becomes one reply, when nothing
 *   branches in between;
 * - the result lists parents before children, siblings oldest first (the order Locus's [dev.locus.core.model.MessageTree]
 *   reads as "written");
 * - [activeLeaf] is where the export says the conversation ends, moved to the nearest shown message,
 *   or the newest leaf when it says nothing usable.
 *
 * Cycles and parents that aren't in the export never loop or drop messages: such a message becomes a root.
 * Iterative throughout, so a conversation thousands of messages deep can't overflow the stack.
 */
internal object ExportTree {
    data class Result(val messages: List<ImportedMessage>, val activeLeaf: String?)

    fun build(nodes: List<RawNode>, preferredLeaf: String?, fallbackTime: Long): Result {
        val byId = LinkedHashMap<String, RawNode>()
        nodes.forEach { if (it.id !in byId) byId[it.id] = it }
        val index = byId.keys.withIndex().associate { (i, id) -> id to i }

        // The nearest shown message above [id] (not [id] itself), walking the export's parents.
        val shownAbove = HashMap<String, String?>()
        fun nearestShownAbove(id: String): String? {
            shownAbove[id]?.let { return it }
            if (shownAbove.containsKey(id)) return null
            val seen = HashSet<String>().apply { add(id) }
            var at = byId[id]?.parentId
            while (at != null && seen.add(at)) {
                val node = byId[at] ?: break
                if (node.shown) break
                at = node.parentId
            }
            val found = at?.takeIf { byId[it]?.shown == true && it != id }
            shownAbove[id] = found
            return found
        }

        // A time for every message: its own, else the nearest ancestor's that has one, else the chat's.
        // Remembered per message, so a long chain without times stays linear.
        val times = HashMap<String, Long>()
        fun timeOf(id: String): Long {
            times[id]?.let { return it }
            val seen = LinkedHashSet<String>()
            var at: String? = id
            var found = fallbackTime
            while (at != null && seen.add(at)) {
                times[at]?.let { found = it; at = null } ?: run {
                    val node = byId[at!!]
                    if (node == null) at = null
                    else if (node.createdAt != null) { found = node.createdAt; at = null }
                    else at = node.parentId
                }
            }
            seen.forEach { times[it] = times[it] ?: found }
            return times.getValue(id)
        }

        val shown = byId.values.filter { it.shown }
        val parentOf = shown.associate { it.id to nearestShownAbove(it.id) }
        val time = shown.associate { it.id to timeOf(it.id) }
        val order = compareBy<RawNode>({ time.getValue(it.id) }, { index.getValue(it.id) })
        val children: Map<String?, List<RawNode>> = shown.groupBy { parentOf[it.id] }.mapValues { (_, v) -> v.sortedWith(order) }

        // Preorder walk from the roots. A message whose parents loop never reaches a root: picked up after.
        val visited = LinkedHashSet<String>()
        val walk = ArrayList<RawNode>()
        fun from(roots: List<RawNode>) {
            val stack = ArrayDeque(roots.asReversed())
            while (stack.isNotEmpty()) {
                val node = stack.removeLast()
                if (!visited.add(node.id)) continue
                walk += node
                children[node.id].orEmpty().asReversed().forEach { if (it.id !in visited) stack.addLast(it) }
            }
        }
        from(children[null].orEmpty())
        shown.sortedWith(order).filter { it.id !in visited }.forEach { from(listOf(it)) }

        // Merge an assistant message into its assistant parent when it's that parent's only reply.
        val into = HashMap<String, String>()
        fun target(id: String?): String? = id?.let { into[it] ?: it }
        val text = LinkedHashMap<String, StringBuilder>()
        val extraFiles = HashMap<String, MutableList<String>>()
        val extraImages = HashMap<String, Int>()
        val placedParent = LinkedHashMap<String, String?>()
        for (node in walk) {
            // A root that only looked like one because its parents looped keeps no parent.
            val parent = parentOf[node.id]?.takeIf { it in placedParent || it in into }
            val parentTarget = target(parent)
            val parentNode = parentTarget?.let(byId::get)
            val onlyChild = parent != null && children[parent].orEmpty().size == 1
            if (node.role == Role.ASSISTANT && parentNode?.role == Role.ASSISTANT && onlyChild) {
                into[node.id] = parentTarget
                if (node.text.isNotBlank()) {
                    val sb = text.getValue(parentTarget)
                    if (sb.isNotEmpty()) sb.append("\n\n")
                    sb.append(node.text)
                }
                if (node.notImported.isNotEmpty()) extraFiles.getOrPut(parentTarget) { mutableListOf() } += node.notImported
                if (node.imagesNotImported > 0) extraImages[parentTarget] = (extraImages[parentTarget] ?: 0) + node.imagesNotImported
                continue
            }
            placedParent[node.id] = parentTarget
            text[node.id] = StringBuilder(node.text)
        }

        val messages = placedParent.map { (id, parent) ->
            val node = byId.getValue(id)
            ImportedMessage(
                nodeId = id,
                parentNodeId = parent,
                role = node.role,
                text = text.getValue(id).toString(),
                createdAt = time.getValue(id),
                notImported = node.notImported + extraFiles[id].orEmpty(),
                imagesNotImported = node.imagesNotImported + (extraImages[id] ?: 0),
            )
        }

        val preferred = preferredLeaf?.let { p ->
            val start = if (byId[p]?.shown == true) p else nearestShownAbove(p)
            target(start)?.takeIf { it in placedParent }
        }
        val leaf = preferred ?: run {
            val parents = messages.mapNotNull { it.parentNodeId }.toSet()
            messages.withIndex().filter { it.value.nodeId !in parents }
                .maxWithOrNull(compareBy({ it.value.createdAt }, { it.index }))?.value?.nodeId
        }
        return Result(messages, leaf)
    }
}
