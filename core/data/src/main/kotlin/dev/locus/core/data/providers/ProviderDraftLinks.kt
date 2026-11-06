package dev.locus.core.data.providers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The chat card's Review (docs/design/provider-drafts.md): which draft Settings → Models opens its review
 * sheet on. Only a tap in the app sets it, and it only opens the sheet; approving still takes the sheet's
 * own Approve.
 */
object ProviderDraftLinks {
    private val _requested = MutableStateFlow<String?>(null)
    val requested: StateFlow<String?> = _requested.asStateFlow()

    fun open(draftId: String) {
        _requested.value = draftId
    }

    fun clear() {
        _requested.value = null
    }
}
