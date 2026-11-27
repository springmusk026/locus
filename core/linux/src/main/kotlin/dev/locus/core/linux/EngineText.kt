package dev.locus.core.linux

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text the engine modules (core/linux, core/data) make for the screen, in the app's language
 * (docs/design/localization.md, "Text from the engine"). Like core/designsystem's `UiText`, which these
 * modules can't use, it holds a resource id and its arguments rather than the finished string, so the
 * words are looked up when a screen shows them. Screens turn it into a `UiText` or resolve it with
 * [asString].
 *
 * Only for what the user reads: anything the model reads, or that is stored, stays the plain English
 * it always was, beside this.
 */
sealed interface EngineText {

    /** Text that isn't translated: a name, a path, an error from a program or a server. */
    data class Plain(val value: String) : EngineText

    /** A string resource, formatted with [args]; an argument that is itself [EngineText] is resolved first. */
    data class Res(@param:StringRes val id: Int, val args: List<Any>) : EngineText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    /** A plural resource chosen by [count]; with no [args] the count is the only argument. */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any>) : EngineText {
        constructor(@PluralsRes id: Int, count: Int, vararg args: Any) : this(id, count, args.toList())
    }

    fun asString(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *resolve(args, resources))
        is Plural -> resources.getQuantityString(id, count, *(if (args.isEmpty()) arrayOf<Any>(count) else resolve(args, resources)))
    }

    private companion object {
        fun resolve(args: List<Any>, resources: Resources): Array<Any> =
            Array(args.size) { i -> args[i].let { if (it is EngineText) it.asString(resources) else it } }
    }
}

/**
 * An error the engine throws for the user to read (docs/design/localization.md, "Text from the engine"):
 * [text] words it in the app's language; the exception's message stays the English, for logs and for
 * the model when the same error reaches it.
 */
interface EngineTextError {
    val text: EngineText
}

/** A refused input (a `require`): [text] for the screen, [message] the same in English. */
open class EngineInputException(override val text: EngineText, message: String) : IllegalArgumentException(message), EngineTextError

/** A step that couldn't be done (a `check`): [text] for the screen, [message] the same in English. */
open class EngineStateException(override val text: EngineText, message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause), EngineTextError

/** What a screen shows for this error: its [EngineTextError.text], else its message (or its name) as it is. */
fun Throwable.engineText(): EngineText = (this as? EngineTextError)?.text ?: EngineText.Plain(message ?: toString())
