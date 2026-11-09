package dev.locus.core.designsystem

import android.content.Context
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalResources

/**
 * Text made outside a composable (a view model's message, an error a use case reports) that a screen
 * shows later (docs/design/localization.md). It holds a resource id and its arguments rather than the
 * finished string, so the words are looked up in the language the screen shows when it shows them,
 * and follow a language change without the view model knowing.
 *
 * Arguments that are themselves [UiText] are resolved first, so a message can name another message.
 */
sealed interface UiText {

    /** Text that isn't translated: a name the user typed, a file path, an error from a server. */
    data class Plain(val value: String) : UiText

    /** A string resource, formatted with [args] (`%1$s`, `%2$d`). */
    data class Res(@param:StringRes val id: Int, val args: List<Any>) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /**
     * A plural resource, the form chosen by [count]. With no [args] the count is the only argument,
     * so `<item quantity="other">%1$d files</item>` needs nothing more.
     */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any>) : UiText {
        constructor(@PluralsRes id: Int, count: Int, vararg args: Any) : this(id, count, args.toList())
    }

    fun asString(context: Context): String = asString(context.resources)

    fun asString(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *resolve(args, resources))
        is Plural -> resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else resolve(args, resources)))
    }

    private companion object {
        fun resolve(args: List<Any>, resources: Resources): Array<Any> =
            Array(args.size) { i -> args[i].let { if (it is UiText) it.asString(resources) else it } }
    }
}

/** [UiText.asString] in the current composition's resources, so it changes with the language. */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String = asString(LocalResources.current)
