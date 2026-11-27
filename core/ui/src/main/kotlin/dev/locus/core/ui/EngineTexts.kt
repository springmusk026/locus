package dev.locus.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.UiText
import dev.locus.core.linux.EngineText
import dev.locus.core.linux.EngineTextError

/** [EngineText.asString] in the current composition's resources, so it follows the app's language. */
@Composable
@ReadOnlyComposable
fun EngineText.asString(): String = asString(LocalResources.current)

/** The same text as a [UiText], for a view model's state; nested arguments are converted too. */
fun EngineText.toUiText(): UiText = when (this) {
    is EngineText.Plain -> UiText.Plain(value)
    is EngineText.Res -> UiText.Res(id, args.map(::uiArg))
    is EngineText.Plural -> UiText.Plural(id, count, args.map(::uiArg))
}

private fun uiArg(arg: Any): Any = if (arg is EngineText) arg.toUiText() else arg

/**
 * What a screen shows for this error: the engine's words in the app's language when it has them
 * ([EngineTextError]), else its message as it is (a server's or a library's words), else [fallback].
 */
fun Throwable.userText(fallback: UiText): UiText =
    (this as? EngineTextError)?.text?.toUiText() ?: message?.takeIf { it.isNotBlank() }?.let(UiText::Plain) ?: fallback

/** [providerName] (a saved provider's name as screens show it) in the current composition's resources. */
@Composable
@ReadOnlyComposable
fun providerName(name: String): String = LocalResources.current.providerName(name)
