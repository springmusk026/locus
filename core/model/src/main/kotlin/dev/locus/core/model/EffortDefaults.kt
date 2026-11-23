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

    // ---- One-tap move to the model's default (docs/design/cost.md#effort) -------------------------

    /**
     * Whether [provider] gets the one-tap offer to move to the model's own effort: an Anthropic, Responses
     * or ChatGPT provider whose stored effort is the old default "high", which the user hasn't set, kept
     * (dismissed) or moved since this offer existed ([AppSettings.effortChosen]), on a model whose own
     * default is known and lower. Where the model's default is high anyway, the move would change nothing.
     * Chat Completions providers lost their unseen "high" in [migrate], so theirs is always a choice.
     *
     * A "high" saved from the editor between the default's change and this offer can't be told apart
     * from the old default (nothing recorded which providers were saved), so it's offered once too.
     */
    fun offersModelDefault(settings: AppSettings, provider: ProviderConfig): Boolean {
        if (provider.id in settings.effortChosen || provider.effort != LEGACY_DEFAULT) return false
        if (provider.format != ApiFormat.ANTHROPIC_MESSAGES && provider.format != ApiFormat.OPENAI_RESPONSES && provider.format != ApiFormat.CHATGPT) return false
        val own = Effort.modelDefault(provider.format, provider.baseUrl, provider.model) ?: return false
        return own < Effort.HIGH
    }

    /** The offer's one tap: [providerId] sends no effort from now on, and its setting counts as chosen. */
    fun useModelDefault(settings: AppSettings, providerId: String): AppSettings = settings.copy(
        providers = settings.providers.map { if (it.id == providerId) it.copy(effort = "") else it },
        effortChosen = settings.effortChosen + providerId,
    )

    /** The offer dismissed: [providerId] keeps its level, and the offer doesn't come back. */
    fun keep(settings: AppSettings, providerId: String): AppSettings = settings.copy(effortChosen = settings.effortChosen + providerId)

    /**
     * [settings] after the editor (or a draft's approval) saved [saved] over [previous]: as [current], and a
     * changed or new effort is the user's choice, so it's never offered for a move. An unchanged "high"
     * (the user saved for another reason, such as a new key) keeps the offer.
     */
    fun saved(settings: AppSettings, previous: ProviderConfig?, saved: ProviderConfig): AppSettings {
        val chosen = if (previous == null || previous.effort != saved.effort) settings.effortChosen + saved.id else settings.effortChosen
        return current(settings).copy(effortChosen = chosen)
    }
}
