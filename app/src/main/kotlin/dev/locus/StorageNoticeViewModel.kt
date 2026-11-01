package dev.locus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.locus.core.data.diagnostics.DiagnosticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Tells the user, once, that Locus had to reset stored data it couldn't read (a vault file that
 * doesn't decrypt, an unreadable settings file), or restored saved keys set aside at a reset
 * (docs/design/diagnostics.md). Kept until dismissed.
 */
@HiltViewModel
class StorageNoticeViewModel @Inject constructor(private val diagnostics: DiagnosticsRepository) : ViewModel() {
    private val _notices = MutableStateFlow<List<String>>(emptyList())
    val notices: StateFlow<List<String>> = _notices.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _notices.value = runCatching { diagnostics.notices() }.getOrDefault(emptyList()) }
    }

    fun dismiss() {
        _notices.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { runCatching { diagnostics.dismissNotices() } }
    }
}
