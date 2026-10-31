package dev.locus.core.data.script

import android.content.Context
import androidx.concurrent.futures.await
import androidx.javascriptengine.IsolateStartupParameters
import androidx.javascriptengine.JavaScriptSandbox
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.locus.core.agent.home.ScriptEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs connector scripts in androidx.javascriptengine: a V8 isolate in a separate sandboxed process
 * with no network, file or Android API access. Each evaluation gets a fresh isolate with a small
 * heap, so scripts can't keep state or affect each other.
 */
@Singleton
class SandboxScriptEngine @Inject constructor(@param:ApplicationContext private val context: Context) : ScriptEngine {
    private val lock = Mutex()
    private var sandbox: JavaScriptSandbox? = null

    override suspend fun eval(code: String): String {
        if (!JavaScriptSandbox.isSupported()) throw IllegalStateException("This phone's WebView doesn't support the script sandbox.")
        val box = lock.withLock { sandbox ?: JavaScriptSandbox.createConnectedInstanceAsync(context).await().also { sandbox = it } }
        val params = IsolateStartupParameters().apply {
            if (box.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE)) maxHeapSizeBytes = 16L * 1024 * 1024
        }
        return box.createIsolate(params).use { isolate ->
            withTimeout(5_000) { isolate.evaluateJavaScriptAsync(code).await() }
        }
    }
}
