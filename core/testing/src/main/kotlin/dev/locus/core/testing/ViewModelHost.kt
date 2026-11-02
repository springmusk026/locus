package dev.locus.core.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlin.reflect.KClass

/**
 * Owns view models the way a screen does, so a test can clear them at its end. Clearing cancels each
 * viewModelScope and waits for it, so nothing a view model started (a flowOn producer finishing on a
 * real thread, a WhileSubscribed timeout) dispatches to Main after MainDispatcherRule has reset it
 * and fails the next test.
 */
class ViewModelHost {
    private val store = ViewModelStore()
    private val scopes = mutableListOf<Job>()

    fun <T : ViewModel> add(vm: T): T {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: KClass<V>, extras: CreationExtras): V = vm as V
        }
        ViewModelProvider.create(store, factory)["vm-${scopes.size}", vm::class]
        scopes += vm.viewModelScope.coroutineContext.job
        return vm
    }

    /** Clears every view model and waits until their scopes have finished. */
    suspend fun clear() {
        store.clear()
        scopes.forEach { it.join() }
        scopes.clear()
    }
}
