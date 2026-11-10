package dev.locus.core.agent

/**
 * Why a run ended on a context-overflow error, and what the user can do about it: the [model], whether
 * the earlier messages were already [summarised], and what the provider said. [english] is the run's
 * error message; the chat words the same in the user's language (feature/chat).
 */
data class ContextOverflowText(val model: String, val summarised: Boolean, val providerSaid: String) {
    val english: String get() {
        val what = if (summarised) {
            "This task no longer fits in $model's context window, even after summarising the earlier messages."
        } else {
            "This task no longer fits in $model's context window."
        }
        return "$what Start a new chat for the rest (the agent there can read this one with chat_history_read), or switch to a model " +
            "with a larger window.\n\nThe provider said: $providerSaid"
    }
}
