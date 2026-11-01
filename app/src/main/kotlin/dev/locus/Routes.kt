package dev.locus

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ConversationsRoute : NavKey

/** [draft] prefills the composer, e.g. with something shared from another app. */
/** [focusMessageId]: open the chat at this message (a search result) instead of at the end. */
@Serializable data class ChatRoute(val conversationId: String, val draft: String? = null, val focusMessageId: String? = null) : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data object WorkspaceRoute : NavKey

/**
 * Everything, or one chat's activity when [conversationId] is set. With [taskSince] set, one task's
 * entries from a task receipt: its chat and helpers between [taskSince] and [taskUntil], with
 * [taskDecisions] (ApprovalDecision names), shown as [taskLabel].
 */
@Serializable data class ActivityRoute(
    val conversationId: String? = null,
    val taskSince: Long? = null,
    val taskUntil: Long? = null,
    val taskDecisions: List<String> = emptyList(),
    val taskLabel: String? = null,
) : NavKey

@Serializable data object BrowserRoute : NavKey

/** [tab]: 0 memory, 1 skills, 2 connectors. [template]: an MCP template id to open on Connectors. */
@Serializable data class LibraryRoute(val tab: Int = 0, val template: String? = null) : NavKey

@Serializable data object TasksRoute : NavKey

/** First-run setup; also where Settings sends people to run it again. */
@Serializable data object OnboardingRoute : NavKey

/** Edit the agent's name, personality, user profile and house rules (persona files). */
@Serializable data object PersonaRoute : NavKey

/** Insights: tokens, reply times, tool outcomes and scheduled runs, counted from local data. */
@Serializable data object AnalyticsRoute : NavKey

/** Every project (docs/design/projects.md). */
@Serializable data object ProjectsRoute : NavKey

/** One project: its setup and chats. */
@Serializable data class ProjectRoute(val projectId: String) : NavKey

/** Create a project ([projectId] null) or edit one. */
@Serializable data class ProjectEditRoute(val projectId: String? = null) : NavKey
