package dev.locus.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Decides once per app start whether to open onboarding: null while settings load, then fixed, so
 * finishing onboarding (or adding a provider) never yanks the user to another screen.
 */
@HiltViewModel
class OnboardingGateViewModel @Inject constructor(settings: SettingsRepository) : ViewModel() {
    val needsOnboarding: StateFlow<Boolean?> = flow { emit(settings.current().needsOnboarding) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
