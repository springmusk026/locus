package dev.locus

import androidx.navigation3.runtime.NavKey
import dev.locus.core.model.SettingsDestination
import kotlinx.serialization.Serializable

@Serializable data object ConversationsRoute : NavKey

/** [draft] prefills the composer, e.g. with something shared from another app. */
/** [focusMessageId]: open the chat at this message (a search result) instead of at the end. */
/** [voice]: open voice mode over the chat (the Voice launcher shortcut). */
@Serializable data class ChatRoute(val conversationId: String, val draft: String? = null, val focusMessageId: String? = null, val voice: Boolean = false) : NavKey

/**
 * Settings, on [page] when a link names one ("Settings → Models"); null opens the hub. Back from a
 * page opened this way returns to the screen the link was on.
 */
@Serializable data class SettingsRoute(val page: SettingsDestination? = null) : NavKey

/** Settings → Models → On-device models: installed models, downloads and recommendations. */
@Serializable data object OnDeviceModelsRoute : NavKey

/** Search Hugging Face for GGUF models; [query] searches at once (a gated model's "Search for …"). */
@Serializable data class OnDeviceSearchRoute(val query: String? = null) : NavKey

/** One on-device model; [key] is a `ModelKey` encoded ("catalog:qwen3.5-2b", "repo:owner/name"). */
@Serializable data class OnDeviceModelRoute(val key: String) : NavKey

/** The Workspace; with [openPath] (relative to the workspace) it opens on that file, and back from it leaves. */
@Serializable data class WorkspaceRoute(val openPath: String? = null) : NavKey

/** Interactive terminals in the Linux workspace (docs/design/terminal.md). */
@Serializable data object TerminalRoute : NavKey

/** Linux health and inventory, from the Workspace's Linux card (docs/design/linux-health.md). */
@Serializable data object LinuxHealthRoute : NavKey

/** Installed Linux packages, from the health screen (docs/design/linux-health.md#packages). */
@Serializable data object LinuxPackagesRoute : NavKey

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
