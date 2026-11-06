package dev.locus.core.model

/**
 * The move from a fixed "high" effort default to the model's own (a blank [ProviderConfig.effort]).
 *
 * Settings are stored with every default written out, so a provider saved before the change has
 * `"effort": "high"` whether or not anyone chose it. Where the user could have chosen it (Anthropic and
 * Responses providers, which have always had the effort control) it stays: it may be a choice, and the
 * request those chats send doesn't change. Chat Completions providers never showed the control, so their
 * stored "high" was never a choice and was never sent; it becomes blank, so the effort Locus now sends on
 * that route ([Effort.CHAT_EFFORT_HOSTS]) starts from the model's default rather than a level nobody picked.
 *
 * Runs once ([AppSettings.effortDefaults]); saving a provider marks the settings as migrated, so a "high"
 * chosen afterwards is never undone.
 */
object EffortDefaults {
    const val CURRENT = 1

    /** Whether [settings] still need the migration: written before it, with a provider it changes. */
    fun needed(settings: AppSettings): Boolean = settings.effortDefaults < CURRENT && settings.providers.any(::neverChosen)

    fun migrate(settings: AppSettings): AppSettings = settings.copy(
        providers = settings.providers.map { if (neverChosen(it)) it.copy(effort = "") else it },
        effortDefaults = CURRENT,
    )

    /** [settings] as saved by the editor: from now on every stored effort is a choice. */
    fun current(settings: AppSettings): AppSettings = if (settings.effortDefaults >= CURRENT) settings else settings.copy(effortDefaults = CURRENT)

    private fun neverChosen(p: ProviderConfig) = p.format == ApiFormat.OPENAI_CHAT && p.effort == LEGACY_DEFAULT

    /** What [ProviderConfig.effort] defaulted to before. */
    const val LEGACY_DEFAULT = "high"
}
