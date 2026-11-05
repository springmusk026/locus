package dev.locus.feature.workspace

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import java.lang.reflect.Proxy
import kotlin.reflect.KClass

/**
 * Lets a screen whose children call `hiltViewModel()` (a default argument the screen doesn't pass on)
 * render with view models the test built over a TestGraph.
 *
 * `hiltViewModel()` always builds Hilt's factory from the activity, which Robolectric's host activity
 * isn't set up for; here the activity Hilt finds says it has no Hilt view models, so the factory only
 * delegates, and the store already holds [viewModels] under their default keys, so they are returned
 * as they are.
 */
@Composable
internal fun WithViewModels(vararg viewModels: ViewModel, content: @Composable () -> Unit) {
    val real = LocalContext.current
    val owner = remember { PrefilledOwner(viewModels.toList()) }
    val context = remember(real) { HiltlessActivity(real) }
    CompositionLocalProvider(LocalContext provides context, LocalViewModelStoreOwner provides owner, content = content)
}

private class PrefilledOwner(viewModels: List<ViewModel>) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    init {
        viewModels.forEach { vm ->
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = vm as T
            }
            ViewModelProvider.create(viewModelStore, factory)[vm::class]
        }
    }
}

/** An activity over the real one's context whose Hilt component knows no view models. */
private class HiltlessActivity(base: Context) : ComponentActivity(), GeneratedComponentManager<Any> {
    init {
        attachBaseContext(base)
    }

    override fun generatedComponent(): Any = component

    override fun getSystemService(name: String): Any? = baseContext.getSystemService(name)

    private companion object {
        val component: Any by lazy {
            val entryPoint = Class.forName("dagger.hilt.android.internal.lifecycle.HiltViewModelFactory\$ActivityCreatorEntryPoint")
            Proxy.newProxyInstance(entryPoint.classLoader, arrayOf(GeneratedComponent::class.java, entryPoint)) { proxy, method, args ->
                when (method.name) {
                    "getViewModelKeys" -> emptyMap<Class<*>, Boolean>()
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "HiltlessComponent"
                    else -> null
                }
            }
        }
    }
}

/**
 * Calls a private composable of [fileClass] (`dev.locus.feature.x.FooKt`) with [args], for sheets
 * only reachable through a screen's local state. Compose adds a composer and change/default masks.
 */
@Composable
internal fun CallPrivate(fileClass: String, name: String, vararg args: Any?) {
    val method = Class.forName(fileClass).declaredMethods.single { it.name == name }
    method.isAccessible = true
    val masks = List(method.parameterCount - args.size - 1) { 0 }
    method.invoke(null, *(args.toList() + currentComposer + masks).toTypedArray())
}

/**
 * Gives work a screen hands to Dispatchers.Default or IO (highlighting, decoding, parsing) time to
 * land before the capture: Robolectric waits for the main looper, not for those threads. A few frames,
 * each after a short pause on the main thread, cover a chain of two or three such steps.
 */
@Composable
internal fun Settled(content: @Composable () -> Unit) {
    content()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(4) {
            androidx.compose.runtime.withFrameNanos { }
            Thread.sleep(150)
        }
    }
}
