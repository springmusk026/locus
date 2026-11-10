package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.integrity.PolicyIntegrity
import dev.locus.core.linux.EngineText
import dev.locus.core.model.SettingsDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Tells the user when tool permissions or connector approvals failed their seal and were reset
 * (docs/design/linux-isolation.md, "Sealed permissions"): one paragraph per alert, and the Settings page
 * the first one names.
 */
@HiltViewModel
class IntegrityNoticeViewModel @Inject constructor(private val integrity: PolicyIntegrity) : ViewModel() {
    val messages: StateFlow<List<EngineText>> = integrity.messages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val destination: StateFlow<SettingsDestination?> = integrity.destination.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun dismiss() = integrity.dismiss()
}
