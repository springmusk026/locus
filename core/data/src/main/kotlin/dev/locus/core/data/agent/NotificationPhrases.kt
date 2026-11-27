package dev.locus.core.data.agent

import android.content.res.Resources
import dev.locus.core.agent.BudgetReached

/**
 * The phrases Locus's notifications share with the chat, in the app's language (docs/design/localization.md,
 * "Notifications"). Their strings live in the UI modules core/data doesn't depend on (core/designsystem's tool
 * phrases, feature/chat's budget sentence), so the app provides them; core/data's own notification words are
 * its `data_notif_*` strings. In English each says what [dev.locus.core.model.toolActivity],
 * [dev.locus.core.model.toolLabel] and [BudgetReached.english] say.
 */
interface NotificationPhrases {
    /** "Running a command": what [tool] is doing. */
    fun toolActivity(resources: Resources, tool: String): String

    /** "Run `ls`": what a call of [tool] would do, before it runs. */
    fun toolLabel(resources: Resources, tool: String, argumentsJson: String): String

    /** "Paused: reached the 200k-token budget for this task." */
    fun budgetReached(resources: Resources, reached: BudgetReached): String
}
