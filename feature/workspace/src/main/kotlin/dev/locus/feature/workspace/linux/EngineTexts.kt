package dev.locus.feature.workspace.linux

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources
import dev.locus.core.designsystem.UiText
import dev.locus.core.linux.EngineText

/** [EngineText.asString] in the current composition's resources, so it follows the app's language. */
@Composable
@ReadOnlyComposable
internal fun EngineText.asString(): String = asString(LocalResources.current)

/** The same text as a [UiText], for a view model's state; nested arguments are converted too. */
internal fun EngineText.toUiText(): UiText = when (this) {
    is EngineText.Plain -> UiText.Plain(value)
    is EngineText.Res -> UiText.Res(id, args.map(::uiArg))
    is EngineText.Plural -> UiText.Plural(id, count, args.map(::uiArg))
}

private fun uiArg(arg: Any): Any = if (arg is EngineText) arg.toUiText() else arg
