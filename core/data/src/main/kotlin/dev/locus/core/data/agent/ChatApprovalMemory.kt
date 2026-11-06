package dev.locus.core.data.agent

import dev.locus.core.agent.ApprovalMemory
import dev.locus.core.agent.ApprovalScope
import dev.locus.core.agent.ApprovalScopes
import dev.locus.core.data.repository.SettingsRepository
import dev.locus.core.model.RememberedApproval
import dev.locus.core.model.ToolPolicy
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * One chat's remembered approvals, kept in the sealed settings (docs/design/remembered-approvals.md):
 * its own "Allow for this chat" rules and the "Always allow" rules made in the same project (or, for
 * a chat outside projects, outside them). Read fresh for every call, so a revoke applies at once.
 *
 * A rule never widens a project's restriction: where the chat's project sets the tool to Ask or Deny
 * itself, no rule applies there.
 */
class ChatApprovalMemory(
    private val settings: SettingsRepository,
    private val conversationId: String,
    private val projectId: String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) : ApprovalMemory {
    override suspend fun match(toolName: String, args: JsonObject): RememberedApproval? {
        val s = settings.current()
        if (s.rememberedApprovals.isEmpty()) return null
        if (projectRestricts(s.projectPolicies[projectId]?.toolPolicies?.get(toolName))) return null
        return ApprovalScopes.applicable(s.rememberedApprovals, conversationId, projectId, clock())
            .firstOrNull { ApprovalScopes.covers(it, toolName, args) }
    }

    override suspend fun matchScope(scope: ApprovalScope): RememberedApproval? {
        val s = settings.current()
        if (s.rememberedApprovals.isEmpty()) return null
        if (projectRestricts(s.projectPolicies[projectId]?.toolPolicies?.get(scope.tool))) return null
        return ApprovalScopes.applicable(s.rememberedApprovals, conversationId, projectId, clock())
            .firstOrNull { it.tool == scope.tool && it.kind == scope.kind && it.value == scope.value }
    }

    override suspend fun remember(scope: ApprovalScope, forChat: Boolean) {
        val now = clock()
        settings.addRememberedApproval(
            RememberedApproval(
                id = ids(),
                tool = scope.tool,
                kind = scope.kind,
                value = scope.value,
                projectId = projectId,
                conversationId = if (forChat) conversationId else null,
                createdAt = now,
                expiresAt = if (forChat) null else now + RememberedApproval.DEFAULT_TTL_MS,
            ),
            now,
        )
    }

    override suspend fun offers(toolName: String): Boolean =
        !projectRestricts(settings.current().projectPolicies[projectId]?.toolPolicies?.get(toolName))

    private fun projectRestricts(own: ToolPolicy?) = own == ToolPolicy.ASK || own == ToolPolicy.DENY
}
