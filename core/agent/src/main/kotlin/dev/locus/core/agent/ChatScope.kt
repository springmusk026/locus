package dev.locus.core.agent

import dev.locus.core.model.ChatScopeSpec
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The project a tool call runs for (docs/design/projects.md). [AgentLoop] runs every call of a
 * project chat with [element] in its coroutine context. While the call runs, the workspace resolves
 * paths inside the project's folder ([dev.locus.core.agent.tools.Workspace]), Linux binds that folder
 * at `/workspace`, and the memory tools use the project's memory.
 *
 * It's a thread-local carried by the coroutine (`ThreadLocal.asContextElement`), so code that isn't
 * suspending, such as `Workspace.resolve`, still sees it, on whichever thread the call resumes.
 * Outside a project chat it's null, and everything behaves as before.
 */
object ChatScope {
    private val local = ThreadLocal<ChatScopeSpec?>()

    /** The scope of the call running on this thread, or null outside a project chat. */
    fun current(): ChatScopeSpec? = local.get()

    /** The context element that sets [spec] for a coroutine; empty for null, so it costs nothing outside projects. */
    fun element(spec: ChatScopeSpec?): CoroutineContext = if (spec == null) EmptyCoroutineContext else local.asContextElement(spec)

    /** Runs [block] with [spec] as the scope on this thread (tests, and code outside coroutines). */
    fun <T> with(spec: ChatScopeSpec?, block: () -> T): T {
        val before = local.get()
        local.set(spec)
        try {
            return block()
        } finally {
            local.set(before)
        }
    }
}
