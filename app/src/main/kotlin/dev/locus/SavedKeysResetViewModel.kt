package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Asks before resetting saved keys whose Android Keystore key looks unusable for good
 * (docs/design/diagnostics.md). Locus never resets them on its own; "Not now" leaves the vault closed
 * and unchanged for this session, and the question comes back at the next start.
 */
@HiltViewModel
class SavedKeysResetViewModel @Inject constructor(private val diagnostics: DiagnosticsRepository) : ViewModel() {
    private val dismissed = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val ask: StateFlow<Boolean> = combine(diagnostics.savedKeysNeedReset, dismissed) { needs, gone -> needs && !gone }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Opens the key early, so the question comes up at start rather than at the first request.
        viewModelScope.launch(Dispatchers.IO) { runCatching { diagnostics.openSavedKeys() } }
    }

    fun reset() {
        if (_busy.value) return
        _busy.value = true
        _error.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val opened = runCatching { diagnostics.resetSavedKeys() }.getOrDefault(false)
            if (!opened) _error.value = "The reset didn't open new keys. Details are in Settings → About & privacy → Diagnostics."
            _busy.value = false
        }
    }

    fun notNow() {
        dismissed.value = true
    }
}
