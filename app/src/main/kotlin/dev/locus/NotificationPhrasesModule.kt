package dev.locus

import android.content.res.Resources
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.locus.core.agent.BudgetReached
import dev.locus.core.data.agent.NotificationPhrases
import dev.locus.core.designsystem.localizedToolActivity
import dev.locus.core.designsystem.localizedToolLabel
import dev.locus.feature.chat.budgetReachedInWords

/**
 * The phrases core/data's notifications share with the chat, from the modules that hold their strings
 * (docs/design/localization.md, "Notifications"): the same words the chat shows, in the app's language.
 */
internal object AppNotificationPhrases : NotificationPhrases {
    override fun toolActivity(resources: Resources, tool: String): String = localizedToolActivity(resources, tool)

    override fun toolLabel(resources: Resources, tool: String, argumentsJson: String): String =
        localizedToolLabel(resources, tool, argumentsJson, done = false)

    override fun budgetReached(resources: Resources, reached: BudgetReached): String = budgetReachedInWords(resources, reached)
}

@Module
@InstallIn(SingletonComponent::class)
object NotificationPhrasesModule {
    @Provides
    fun notificationPhrases(): NotificationPhrases = AppNotificationPhrases
}
