package dev.locus

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ConversationsRoute : NavKey

/** [draft] prefills the composer, e.g. with something shared from another app. */
@Serializable data class ChatRoute(val conversationId: String, val draft: String? = null) : NavKey

@Serializable data object SettingsRoute : NavKey

@Serializable data object WorkspaceRoute : NavKey

/** Everything, or one chat's activity when [conversationId] is set. */
@Serializable data class ActivityRoute(val conversationId: String? = null) : NavKey

@Serializable data object BrowserRoute : NavKey

/** [tab]: 0 memory, 1 skills, 2 connectors. */
@Serializable data class LibraryRoute(val tab: Int = 0) : NavKey

@Serializable data object TasksRoute : NavKey

/** First-run setup; also where Settings sends people to run it again. */
@Serializable data object OnboardingRoute : NavKey

/** Edit the agent's name, personality, user profile and house rules (persona files). */
@Serializable data object PersonaRoute : NavKey

/** Insights: tokens, reply times, tool outcomes and scheduled runs, counted from local data. */
@Serializable data object AnalyticsRoute : NavKey
